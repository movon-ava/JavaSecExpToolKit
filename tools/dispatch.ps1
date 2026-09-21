<#
.SYNOPSIS
  派发一个角色的 agent，并在其产出安全时自动合并、清理。

.DESCRIPTION
  把「派发 → 校验 → 合并 → 清理」四件事合成一条命令，使人工只需要
  「提出任务」与「事后测试」，不必手工执行 git merge 与 worktree 清理。

  自动合并的前提条件（任一不满足就停手，保留现场交人工处理）：
  - agent 正常退出，且没有超时、没有判定为卡死；
  - 改动全部落在该角色写入域内（越界会被 agent.ps1 拒绝提交）；
  - 分支上确实产生了新提交。

  合并失败（例如与主线冲突）时会执行 git merge --abort，保留分支与 worktree，
  并把处置办法打印出来；绝不留下半个合并状态。

.EXAMPLE
  # 角色由任务内容推断；成功后自动合并并清理
  .\tools\dispatch.ps1 -Task "补齐 1.2.73-1.2.80 的版本识别盲区"

.EXAMPLE
  # 角色与 slug 显式指定，且只看它会做什么、不真的执行
  .\tools\dispatch.ps1 -Role probe -Slug version-blindspot -Task "..." -DryRun

.EXAMPLE
  # 只跑不合并（想自己先看 diff）
  .\tools\dispatch.ps1 -Role exploit -Task "..." -NoMerge
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$Task,

    # 留空则按任务内容推断；推断不确定时直接报错，不做猜测
    [ValidateSet("", "orchestrator", "probe", "exploit", "traffic", "ui", "test", "supervisor")]
    [string]$Role = "",

    # 留空则按角色与时间戳生成
    [string]$Slug = "",

    # 0 表示使用该角色的默认超时预算
    [ValidateRange(0, 600)]
    [int]$TimeoutMinutes = 0,

    [ValidateRange(10, 600)]
    [int]$PollSeconds = 60,

    [ValidateRange(1, 20)]
    [int]$StallRounds = 2,

    # 只跑 agent 并校验，不自动合并、不清理
    [switch]$NoMerge,

    # 只打印将要做什么，不创建 worktree、不启动 agent
    [switch]$DryRun
)

$ErrorActionPreference = "Stop"
$OutputEncoding = New-Object System.Text.UTF8Encoding $false
try { [Console]::OutputEncoding = New-Object System.Text.UTF8Encoding $false } catch { }

$root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$agentScript = Join-Path $PSScriptRoot "agent.ps1"

if (-not (Test-Path -LiteralPath $agentScript)) {
    throw "找不到 $agentScript"
}

$previous = $ErrorActionPreference
$ErrorActionPreference = "Continue"

# 写入域矩阵与「跑 agent → 判能否合并 → 合并或保留现场 → 清理」的原语
# 都与 tools/orchestrate.ps1 共用。各留一份副本会出现两套不一致的安全策略：
# 单条派发允自动合并、批量编排拒绝合并，是很难解释的行为差异。
. (Join-Path $PSScriptRoot "lib\RoleMatrix.ps1")
. (Join-Path $PSScriptRoot "lib\AgentRun.ps1")

# 角色推断表：只认明确的功能关键词。
# 一个任务同时命中多个角色时不做猜测——写入域不同，猜错代价是白跑一轮或越界。
$roleKeywords = [ordered]@{
    supervisor   = @('审计', '复核', '监督', '审查', 'audit', '阻塞结论')
    orchestrator = @('规划件', 'change', 'propose', '方案设计', '归档', '拆分是否跨域')
    test         = @('测试', '自检', '断言', '回归', '用例', 'test', 'check')
    ui           = @('界面', '页面', '配置页', '按钮', '布局', '面板', 'ui', '界面自检')
    traffic      = @('代理', '抓包', '请求头', '转发', '拦截', 'proxy', 'capture')
    exploit      = @('shiro', 'payload', '利用链', '回显', '爆破', '密钥', '载荷', '链生成')
    probe        = @('fastjson', '探测', '版本识别', '识别盲区', '探针', 'fj_probe', 'probe')
}

function Resolve-Role {
    param([Parameter(Mandatory = $true)][string]$Text)

    $lower = $Text.ToLowerInvariant()
    $hits = @()
    foreach ($candidate in $roleKeywords.Keys) {
        foreach ($keyword in $roleKeywords[$candidate]) {
            if ($lower.Contains($keyword.ToLowerInvariant())) {
                $hits += $candidate
                break
            }
        }
    }
    # 必须用普通数组返回，调用方再包一层 @(...)：
    # 写成 `return ,@(...)` 时整个数组会被当成单个元素，
    # 于是 $hits.Count 恒为 1 —— 「无命中」与「多角色命中」两条防呆分支双双失效，
    # 实测表现是无关键词的任务静默拿到空角色继续执行。
    return @($hits | Select-Object -Unique)
}

Write-Host "== 派发 =="
Write-Host "任务    : $Task"

if (-not $Role) {
    $hits = @(Resolve-Role -Text $Task)
    if ($hits.Count -eq 0) {
        throw "无法从任务内容推断角色（没有任何已知功能关键词）。`n请显式指定 -Role，可选：$($roleKeywords.Keys -join ' / ')"
    }
    if ($hits.Count -gt 1) {
        throw "任务同时命中多个角色（$($hits -join '、')），写入域不同，不猜测。`n请显式指定 -Role 之一。"
    }
    $Role = $hits[0]
    Write-Host "角色    : $Role（按任务内容推断）"
} else {
    Write-Host "角色    : $Role（显式指定）"
}

if (-not $Slug) {
    $Slug = "$Role-" + (Get-Date -Format 'MMdd-HHmm')
    $Slug = $Slug.ToLowerInvariant()
    Write-Host "slug    : $Slug（自动生成）"
} else {
    Write-Host "slug    : $Slug"
}

# 只读角色不产出可合并内容，走单独分支没有意义
if ($Role -eq "supervisor") {
    Write-Host "`n监督角色是只读审计，不产生提交；直接调用 tools\agent.ps1 查看其报告即可。"
    if ($DryRun) { return }
}

$branch = Get-AgentBranchName -Role $Role -Slug $Slug
$worktree = Get-AgentWorktreePath -Repository $root -Role $Role -Slug $Slug
$resultFile = Join-Path $env:TEMP "jset-dispatch-result-$Role-$Slug.txt"

# 合并前置检查与批量编排共用同一份判定：主仓库必须干净、在 main 上、不在合并中，
# 否则自动合并会踩到别人的现场。
$ready = Test-RepoReadyForMerge -Repository $root
if (-not $ready.Ready) { throw "$($ready.Reason)`n请先处理，再重新派发。" }
if (Test-Path -LiteralPath $worktree) {
    throw "工作区已存在：$worktree`n请先清理（git worktree remove 与 git branch -D），或换个 -Slug。"
}

if ($DryRun) {
    Write-Host "`n[DryRun] 将执行："
    Write-Host "  tools\agent.ps1 -Role $Role -Slug $Slug -Task `"$Task`""
    Write-Host "  成功后：git merge --no-ff $branch；随后移除 worktree 与分支"
    return
}

$agentArguments = @(
    "-NoProfile", "-ExecutionPolicy", "Bypass",
    "-File", $agentScript,
    "-Role", $Role,
    "-Slug", $Slug,
    "-Task", $Task,
    "-PollSeconds", $PollSeconds,
    "-StallRounds", $StallRounds,
    "-ResultFile", $resultFile
)
if ($TimeoutMinutes -gt 0) { $agentArguments += @("-TimeoutMinutes", $TimeoutMinutes) }
if (Test-Path -LiteralPath $resultFile) { [System.IO.File]::Delete($resultFile) }

$ErrorActionPreference = "Continue"

$baseline = (Invoke-GitOn -Repository $root -Options @("rev-parse") -Operands @("HEAD")).Output[0]

Write-Host "`n启动 agent（退出后自动处理）..."
& powershell @agentArguments | Out-Host
$agentExit = $LASTEXITCODE

Write-Host "`n== 集成 =="
Write-Host "agent 退出码：$agentExit"

# 结果文件是判断依据；缺失时按最保守方式处理（不合并）
$result = Get-AgentRunResult -ResultFile $resultFile

function Stop-WithManual {
    param([Parameter(Mandatory = $true)][string]$Reason)
    Write-Host ""
    Write-Host "[不自动合并] $Reason"
    Write-Host "现场保留如下，供人工判断："
    Write-Host "  分支    : $branch"
    Write-Host "  工作区  : $worktree"
    Write-Host "  查看改动: git log --oneline main..$branch; git diff main..$branch"
    Write-Host "  确认可用: git merge --no-ff $branch"
    Write-Host "  放弃现场: git worktree remove `"$worktree`" --force; git branch -D $branch; git worktree prune"
    return
}

if ($NoMerge) {
    Write-Host "-NoMerge：跳过自动合并。分支 $branch 已就绪。"
    return
}
# 该不该合并由共享原语判定：超时 / 卡死 / 越界 / 未提交，任一命中都不自动合并。
$decision = Get-MergeDecision -Repository $root -Result $result -Branch $branch
switch ($decision.Action) {
    "manual" { Stop-WithManual -Reason $decision.Reason; return }
    "skip" {
        Write-Host "分支上没有新提交：$($decision.Reason)"
        $cleanup = Remove-AgentWorkspace -Repository $root -Role $Role -Slug $Slug
        $cleanup.Messages | ForEach-Object { Write-Host "  $_" }
        Write-Host "已清理。"
        return
    }
}

$commits = Invoke-GitOn -Repository $root -Options @("log", "--oneline") -Operands @("main..$branch")
Write-Host "待合并提交："
$commits.Output | ForEach-Object { Write-Host "  $_" }

$merge = Invoke-GitOn -Repository $root -Options @("merge", "--no-ff", "--no-edit") -Operands @($branch)
if ($merge.Code -ne 0) {
    # 冲突时不留半个合并状态
    Invoke-GitOn -Repository $root -Options @("merge", "--abort") -Operands @() | Out-Null
    Stop-WithManual -Reason "合并失败（很可能是与主线冲突）：$($merge.Output -join ' ')"
    return
}
Write-Host "已合并到 main。"

$cleanup = Remove-AgentWorkspace -Repository $root -Role $Role -Slug $Slug
$cleanup.Messages | ForEach-Object { Write-Host "  $_" }

$newHead = (Invoke-GitOn -Repository $root -Options @("rev-parse") -Operands @("HEAD")).Output[0]

# 收尾汇报：明确回答「调用了哪个角色、做了什么、改到哪些文件」。
# 取 <合并提交>^1..<合并提交> 而不是 main(Baseline)..新 HEAD：
# 若期间主线已前进，后者会把别人的改动算到本次名下。
$filesResult = Invoke-GitOn -Repository $root -Options @("diff", "--name-only") -Operands @("$newHead^1..$newHead")
$changedFiles = @($filesResult.Output | Where-Object { $_ })
$summary = ($Task -replace '\s+', ' ').Trim()

Write-Host ""
Write-Host "== 完成 =="
Write-Host "  主 agent 本次调用角色：$Role"
Write-Host "  该角色主要工作       ：$summary"
Write-Host "  产出文件（$($changedFiles.Count) 个）："
if ($changedFiles.Count -eq 0) {
    Write-Host "    （本次合并提交没有引入文件改动）"
} else {
    $changedFiles | ForEach-Object { Write-Host "    $_" }
}
Write-Host "  main                 : $baseline -> $newHead"
Write-Host "  已清理               : $worktree、分支 $branch"
Write-Host "  下一步               : 你来做测试与验收，再提修改建议"

$ErrorActionPreference = $previous