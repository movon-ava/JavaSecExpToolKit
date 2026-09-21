<#
.SYNOPSIS
  角色会话的执行与集成原语。

.DESCRIPTION
  tools/dispatch.ps1（单条派发）与 tools/orchestrate.ps1（多任务编排）需要同一套
  「跑 agent → 读结果 → 判断能否合并 → 合并或保留现场 → 清理」逻辑。
  复制两份必然漂移，因此抽到这里共用。

  所有函数都显式接收仓库路径，不依赖调用方的脚本作用域，避免操作到别的仓库。
#>

# 调 git，选项与操作数分开传。
#
# 不能把它们混在一个参数数组里：以 - 开头的实参会被 PowerShell 当成新的具名参数
# 而丢弃，命令会退化成另一个语义。实测：`git branch -D <name>` 的 -D 被丢后
# 退化为列出分支，调用处却报 “a branch named ... already exists”，
# 看起来像删除失败，实际是根本没执行删除。
function Invoke-GitOn {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][string]$Repository,
        [Parameter(Mandatory = $true)][string[]]$Options,
        # 允许空数组：`git status`、`git worktree prune` 这类命令只有选项。
        # 写成 Mandatory 会撞上 PowerShell 的空数组校验而在调用处报错。
        [AllowEmptyCollection()]
        [string[]]$Operands = @()
    )
    $output = & git -C $Repository --no-pager @Options @Operands 2>&1
    return @{ Code = $LASTEXITCODE; Output = @($output) }
}

function Get-AgentBranchName {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][string]$Role,
        [Parameter(Mandatory = $true)][string]$Slug
    )
    return "agent/$Role/$Slug"
}

# worktree 放在仓库同级的兄弟目录，避免污染主仓库工作区
function Get-AgentWorktreePath {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][string]$Repository,
        [Parameter(Mandatory = $true)][string]$Role,
        [Parameter(Mandatory = $true)][string]$Slug
    )
    return (Join-Path (Split-Path $Repository -Parent) "jset-agents\$Role-$Slug")
}

# 自动合并的前提：主仓库干净、在 main 上、不在合并过程中。
# 任一不满足就返回原因，由调用方停手——不能在别人的现场上做合并。
function Test-RepoReadyForMerge {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][string]$Repository,
        # 已知的未跟踪目录，不计入「脏工作区」（它们由其它工具管理）
        [string[]]$IgnoredUntracked = @(".pi/")
    )

    $status = Invoke-GitOn -Repository $Repository -Options @("status", "--porcelain")
    $dirty = @()
    foreach ($line in $status.Output) {
        if (-not $line) { continue }
        $skip = $false
        foreach ($pattern in $IgnoredUntracked) {
            if ($line -match ("^\?\? " + [regex]::Escape($pattern))) { $skip = $true; break }
        }
        if (-not $skip) { $dirty += $line }
    }
    if ($dirty.Count -gt 0) {
        return @{ Ready = $false; Reason = "主仓库有未提交改动：$($dirty -join '; ')"; Detail = $dirty }
    }

    $head = Invoke-GitOn -Repository $Repository -Options @("rev-parse", "--abbrev-ref") -Operands @("HEAD")
    if ($head.Output[0] -ne "main") {
        return @{ Ready = $false; Reason = "当前不在 main 分支（实际：$($head.Output[0])）"; Detail = @() }
    }

    $mergeHead = Invoke-GitOn -Repository $Repository -Options @("rev-parse", "-q", "--verify") -Operands @("MERGE_HEAD")
    if ($mergeHead.Code -eq 0) {
        return @{ Ready = $false; Reason = "主仓库正处于合并过程中"; Detail = @() }
    }

    return @{ Ready = $true; Reason = ""; Detail = @() }
}

# 读取 tools/agent.ps1 写出的结果文件。缺失时返回空表，调用方须按最保守方式处理。
function Get-AgentRunResult {
    [CmdletBinding()]
    param([Parameter(Mandatory = $true)][string]$ResultFile)

    $result = @{}
    if (-not (Test-Path -LiteralPath $ResultFile)) { return $result }
    foreach ($line in (Get-Content -LiteralPath $ResultFile -Encoding UTF8)) {
        if ($line -match '^([^=]+)=(.*)$') { $result[$Matches[1]] = $Matches[2] }
    }
    return $result
}

# 依据结果文件判断该不该自动合并。返回 Action：merge / skip / manual。
#
# 设计原则：只要有任何不确定，就 manual（保留现场交人工），绝不猜。
function Get-MergeDecision {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][string]$Repository,
        [Parameter(Mandatory = $true)][hashtable]$Result,
        [Parameter(Mandatory = $true)][string]$Branch
    )

    if ($Result.Count -eq 0) {
        return @{ Action = "manual"; Reason = "未找到结果文件，无法确认 agent 的完成情况" }
    }
    if ($Result['stalled'] -eq 'True') {
        return @{ Action = "manual"; Reason = "看护判定该会话卡死，产出不完整" }
    }
    if ($Result['timedOut'] -eq 'True') {
        return @{ Action = "manual"; Reason = "会话超时，产出可能不完整" }
    }
    $outOfScope = 0
    if ($Result.ContainsKey('outOfScope')) { $outOfScope = [int]$Result['outOfScope'] }
    if ($outOfScope -gt 0) {
        return @{ Action = "manual"; Reason = "有 $outOfScope 个文件越出写入域，已被拒绝提交" }
    }

    if ($Result['committed'] -ne 'True') {
        $count = Invoke-GitOn -Repository $Repository -Options @("rev-list", "--count") -Operands @("main..$Branch")
        if ($count.Code -eq 0 -and [int]$count.Output[0] -eq 0) {
            return @{ Action = "skip"; Reason = "分支上没有新提交（本次无需改动）" }
        }
        return @{ Action = "manual"; Reason = "脚本未提交任何改动（agent 可能未完成实现）" }
    }

    return @{ Action = "merge"; Reason = "" }
}

# 清理一个角色的 worktree 与分支（合并后、或空分支时使用）
function Remove-AgentWorkspace {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][string]$Repository,
        [Parameter(Mandatory = $true)][string]$Role,
        [Parameter(Mandatory = $true)][string]$Slug
    )

    $worktree = Get-AgentWorktreePath -Repository $Repository -Role $Role -Slug $Slug
    $branch = Get-AgentBranchName -Role $Role -Slug $Slug
    $messages = @()

    $remove = Invoke-GitOn -Repository $Repository -Options @("worktree", "remove", "--force") -Operands @($worktree)
    if ($remove.Code -ne 0) { $messages += "worktree 移除失败：$($remove.Output -join ' ')" }

    $delete = Invoke-GitOn -Repository $Repository -Options @("branch", "-D") -Operands @($branch)
    if ($delete.Code -ne 0) { $messages += "分支删除失败：$($delete.Output -join ' ')" }

    Invoke-GitOn -Repository $Repository -Options @("worktree", "prune") | Out-Null

    return @{ Worktree = $worktree; Branch = $branch; Messages = @($messages) }
}

# 提前把 worktree 与分支建好。
#
# 编排器会并行启动多个 agent；`git worktree add` 会写 .git/worktrees 与 refs，
# 并发执行有竞争风险。串行预建可消除这个不确定性，之后 agent 用 -ReuseWorktree 进入。
function New-AgentWorktree {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][string]$Repository,
        [Parameter(Mandatory = $true)][string]$Role,
        [Parameter(Mandatory = $true)][string]$Slug
    )

    $worktree = Get-AgentWorktreePath -Repository $Repository -Role $Role -Slug $Slug
    $branch = Get-AgentBranchName -Role $Role -Slug $Slug

    if (Test-Path -LiteralPath $worktree) {
        return @{ Created = $false; Worktree = $worktree; Branch = $branch; Reason = "工作区已存在" }
    }

    $add = Invoke-GitOn -Repository $Repository -Options @("worktree", "add", $worktree, "-b", $branch)
    if ($add.Code -ne 0) {
        return @{ Created = $false; Worktree = $worktree; Branch = $branch; Reason = ($add.Output -join ' ') }
    }
    return @{ Created = $true; Worktree = $worktree; Branch = $branch; Reason = "" }
}