<#
.SYNOPSIS
  启动一个带角色约束的 Codex agent 会话，工作区与主仓库隔离。

.DESCRIPTION
  为每个 agent 创建独立的 Git worktree（独立分支 agent/<role>/<slug>），
  使各 agent 的文件改动互不可见、互不覆盖；协作通过 Git 分支完成。

  角色清单与写入域见 docs/AGENT-ROLES.md，运行手册见 docs/AGENT-RUNBOOK.md。

.EXAMPLE
  .\tools\agent.ps1 -Role probe -Slug version-blindspot -Task "补齐 1.2.73-1.2.80 的版本识别盲区"

.EXAMPLE
  .\tools\agent.ps1 -Role supervisor -Slug audit-001 -Task "审计本轮改动是否越界" -Sandbox read-only
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet("orchestrator", "probe", "exploit", "traffic", "ui", "test", "supervisor")]
    [string]$Role,

    [Parameter(Mandatory = $true)]
    [string]$Slug,

    [Parameter(Mandatory = $true)]
    [string]$Task,

    [ValidateSet("read-only", "workspace-write", "danger-full-access")]
    [string]$Sandbox = "",

    [string]$Model = "",

    [switch]$DryRun,

    [switch]$ReuseWorktree,

    # 超时上限（分钟）。没有它时，一次跑偏的会话会无限期占用终端
    # 且拿不到任何产出（本仓库已实际发生过）。
    [ValidateRange(1, 600)]
    [int]$TimeoutMinutes = 30,

    # 轮询间隔（秒）：判断会话是否还在推进的检查周期。
    [ValidateRange(10, 600)]
    [int]$PollSeconds = 60,

    # 连续多少轮日志无新增才唤起看护判定。
    # 用它控制调用频率，避免频繁判定造成额外开销。
    [ValidateRange(1, 20)]
    [int]$StallRounds = 2,

    # 关闭看护判定，回到“只有总超时”的行为。
    [switch]$NoWatchdog,

    # 把本次运行的机器可读结果写到这个文件，供 tools/dispatch.ps1 程序化读取。
    # 只靠退出码区分不了“提交了什么”与“为何没提交”，自动合并需要后者。
    [string]$ResultFile = ""
)

$ErrorActionPreference = "Stop"

# Windows PowerShell 5.1 把管道传给原生程序时默认用 ASCII 编码，
# 会把提示词里的中文变成问号。必须显式指定 UTF-8。
$OutputEncoding = New-Object System.Text.UTF8Encoding $false
try { [Console]::OutputEncoding = New-Object System.Text.UTF8Encoding $false } catch { }

$root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
. (Join-Path $PSScriptRoot "lib\CodexCli.ps1")
# 角色矩阵（写入域 / 超时预算 / 只读属性）与 tools/orchestrate.ps1 共用一份，
# 避免两处各自维护后漂移。
. (Join-Path $PSScriptRoot "lib\RoleMatrix.ps1")

if (-not $PSBoundParameters.ContainsKey("TimeoutMinutes")) {
    $TimeoutMinutes = Get-RoleDefaultTimeout -Role $Role
}

# 角色的默认沙箱：只读角色不给写权限，这是硬约束而非提醒
$readOnlyRoles = Get-ReadOnlyRoles
if (-not $Sandbox) {
    $Sandbox = if ($readOnlyRoles -contains $Role) { "read-only" } else { "workspace-write" }
}
if (($readOnlyRoles -contains $Role) -and $Sandbox -ne "read-only") {
    throw "角色 $Role 是只读角色，不允许使用沙箱 $Sandbox。"
}

if ($Slug -notmatch '^[a-z0-9][a-z0-9-]{0,40}$') {
    throw "-Slug 只允许小写字母、数字与连字符，且不超过 41 字符：$Slug"
}

$branch = "agent/$Role/$Slug"

# worktree 放在仓库同级的兄弟目录，避免污染主仓库工作区
$parent = Split-Path $root -Parent
$worktreeRoot = Join-Path $parent "jset-agents"

# 监督角色直接在主仓库运行：它的审计对象是当前工作区的未提交改动，
# 而 worktree 是从 HEAD 建的，看不到这些改动。只读沙箱保证它不会修改任何文件。
$readOnlyAudit = $Role -eq "supervisor"
$worktree = if ($readOnlyAudit) { $root } else { Join-Path $worktreeRoot "$Role-$Slug" }

# 角色卡：只写边界，细节指向 docs/AGENT-ROLES.md，避免两处维护
$cards = @{
    orchestrator = @"
你是主 agent（orchestrator）。职责：用 OpenSpec 的 propose 产出 change 规划件、
判定改动是否跨写入域、集成各角色产出、归档 change、执行 Git 操作。
禁止：不写功能代码（唯一可直接编辑的代码文件是 AGENTS.md、构建脚本，以及共享内核 src/config/**、src/util/**）。
共享内核约束：src/config/** 与 src/util/** 只由主 agent 单独开 change 修改，改前必须列出全部调用方。
"@
    probe = @"
你是功能开发 agent（probe）。写入域仅限 python/fj_probe.py 与 src/probe/Probe*.java。
禁止：不改 src/ui/**、tests/**、src/util/**、src/config/**、src/pom.xml；不改配置项键名。
完成标志：python -m unittest discover -s tests 全绿。
"@
    exploit = @"
你是功能开发 agent（exploit）。写入域仅限 src/shiro/** 与 src/payload/**。
禁止：不改 src/ui/**、tests/**、src/util/**、src/config/**、src/pom.xml；不改配置项键名。
完成标志：ShiroCheck 自检通过。
"@
    traffic = @"
你是功能开发 agent（traffic）。写入域仅限 src/proxy/** 与 src/probe/CaptureBridge.java。
禁止：不改 src/ui/**、tests/**、src/util/**、src/config/**、src/pom.xml；不改配置项键名。
完成标志：ProxyServerCheck 自检通过。
"@
    ui = @"
你是 UI agent。写入域仅限 src/ui/** 与 src/Main.java。
禁止：不改任何引擎行为（只接线，不改引擎逻辑）；不改 tests/**。
硬性要求：新增的可长期保存配置要落进 src/ui/ConfigPage.java 的分组；
完成标志：UiNavigationCheck、UiShiroCheck、UiSwitchEndToEndCheck 全绿。
"@
    test = @"
你是测试 agent。写入域仅限 tests/**。
禁止：不为让测试通过而放宽断言；不实现功能代码（发现问题交回对应角色）。
硬性要求：断言数量只增不减，删减须给出理由。
"@
    supervisor = @"
你是监督 agent，只读审计者。不修改任何文件。
必须独立复算，不得复用执行者的结论：JAR 时间要自己重新枚举源文件比较，
依赖边界要自己重建依赖图。
输出固定三段：通过项、发现问题（含文件路径与行号）、阻断结论（可归档 / 阻断归档）。
审计清单见 docs/AGENT-ROLES.md 第六节。
"@
}

# 写入域矩阵，与 docs/AGENT-ROLES.md 一致。
# 用于提交前机械校验，防止越界改动被默默提交进来；
# 这里只管提交范围，不代替审计。
$writeScope = Get-RoleWriteScope -Role $Role

$roleCard = $cards[$Role]
if (-not $roleCard) { throw "未知角色：$Role" }

$workspaceNote = if ($readOnlyAudit) {
    "本次工作区是主仓库 $root（只读沙箱），审计对象就是当前工作区的未提交改动；你不会、也不能修改任何文件。"
} else {
    "本次工作区是独立 Git worktree $worktree，分支 $branch，不会影响其它 agent。"
}

$roleConstraints = if ($readOnlyAudit) {
    @(
        "- 你是只读审计者：不修改文件、不提交、不跑构建。",
        "- 必须独立复算，不得复用执行者的结论：JAR 时间自己重新枚举源文件比较，依赖边界自己重建依赖图。",
        "- 逐条对照 docs/AGENT-ROLES.md 第六节的十项审计清单，每个结论都要给出文件路径与行号，禁止只写「已检查，无问题」。",
        "- 输出固定三段：通过项、发现问题、阻断结论（可归档 / 阻断归档）。"
    ) -join "`n"
} else {
    @(
        "- 只改你的写入域内的文件；发现需要牵连其它文件时，先说明方案再动手。",
        "- Bug 修复前先给出根因（Root Cause）分析，禁止试错式修改。",
        "- 只改代码，**不要自己执行 git add / git commit**：沙箱对共享的 .git/objects 只有部分写权限，",
        "  你提交会失败并可能在对象库里留下不可达对象；提交由启动你的脚本在你退出后统一完成。",
        "- 不要把工作区内的辅助文件当成产物；需要看改动用 `git status --short` 或 `git diff`。",
        "- 收尾三项（备份 / 写报告 / 构建校验）由主 agent 在合并后统一执行，你不要执行，",
        "  以免与其它 agent 争抢 .backups/、AI_REPORT.md、PROGRESS.md 与构建产物。"
    ) -join "`n"
}
$commonConstraints = @(
    "- 先阅读仓库根目录的 AGENTS.md 与 docs/AGENT-ROLES.md，遵守其中全部约束。",
    "- $workspaceNote"
) -join "`n"

$prompt = @"
$roleCard

## 本次任务

$Task

## 约束

$commonConstraints
$roleConstraints
"@
$promptFile = Join-Path $env:TEMP "jset-agent-prompt-$Role-$Slug.md"
[System.IO.File]::WriteAllText($promptFile, $prompt, (New-Object System.Text.UTF8Encoding $false))

Write-Host "角色    : $Role"
Write-Host "分支    : $branch"
Write-Host "工作区  : $worktree"
Write-Host "沙箱    : $Sandbox"
Write-Host "提示词  : $promptFile"

if ($DryRun) {
    Write-Host "`n[DryRun] 不会创建 worktree，也不会启动 agent。提示词内容如下：`n"
    Get-Content -Raw -Encoding UTF8 $promptFile | Write-Host
    return
}

if ($readOnlyAudit) {
    Write-Host "监督角色：在主仓库以只读沙箱审计，不建 worktree、不提交。"
} elseif (-not (Test-Path $worktreeRoot)) {
    New-Item -ItemType Directory -Path $worktreeRoot -Force | Out-Null
}

if ((-not $readOnlyAudit) -and (Test-Path $worktree)) {
    if (-not $ReuseWorktree) {
        throw "工作区已存在：$worktree`n如需继续使用请加 -ReuseWorktree，或换个 -Slug。"
    }
    Write-Host "复用已存在的工作区。"
} elseif (-not $readOnlyAudit) {
    Push-Location $root
    # git 会把 "Preparing worktree" 之类的进度写到 stderr；
    # 在 $ErrorActionPreference = "Stop" 下会被当成终止错误，因此这里单独放宽，
    # 只依据退出码判断成败。
    $previous = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        & git worktree add $worktree -b $branch 2>&1 | ForEach-Object { Write-Verbose ([string]$_) }
        $gitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previous
        Pop-Location
    }
    if ($gitCode -ne 0) {
        throw "git worktree add 失败（退出码 $gitCode，分支 $branch 可能已存在）"
    }
}

$codexArgs = @("exec", "-C", $worktree, "-s", $Sandbox)

# worktree 的索引、对象库与 Git LFS 临时目录都在主仓库的 .git 下，
# 属于工作区之外；不放行的话 git add / git commit 会因 index.lock
# 或 LFS 过滤器被拒（实测）。只放行本分支与本次 worktree，
# 使 agent 无法触碰其它 agent 的分支与工作区。
if ($Sandbox -ne "read-only") {
    $gitDir = Join-Path $root ".git"
    $grantDirs = @(
        (Join-Path $gitDir "worktrees\$Role-$Slug"),
        (Join-Path $gitDir "lfs")
    )
    foreach ($grant in $grantDirs) {
        if (-not (Test-Path -LiteralPath $grant)) {
            New-Item -ItemType Directory -Path $grant -Force | Out-Null
        }
        $codexArgs += @("--add-dir", $grant)
    }
}
if ($Model) { $codexArgs += @("-m", $Model) }
$outFile = Join-Path $env:TEMP "jset-agent-last-$Role-$Slug.md"
$logFile = Join-Path $env:TEMP "jset-agent-log-$Role-$Slug.txt"
$errFile = Join-Path $env:TEMP "jset-agent-log-$Role-$Slug.err.txt"
$stdinFile = Join-Path $env:TEMP "jset-agent-stdin-$Role-$Slug.md"

# 提示词走 stdin 文件，而不是命令行参数：命令行在 Windows 上对长文本与特殊字符不稳定。
# 实测该方式下中文不乱码。
[System.IO.File]::WriteAllText($stdinFile, $prompt, (New-Object System.Text.UTF8Encoding $false))
$codexArgs += @("-o", "`"$outFile`"", "-")

# 超时上限：没有它时，一次跑偏的会话会无限期占用终端且拿不到任何产出（已发生过）。
# Windows 上 npm 安装的 codex 是 node 包装脚本，直接终止它不会带走 node 子进程，
# 因此优先定位原生可执行文件。
$codexExe = Get-CodexExecutable

Write-Host "`n启动 agent（超时上限 $TimeoutMinutes 分钟）..."
Write-Host "  实时日志: $errFile"
Write-Host "  （codex 的会话输出走 stderr，该文件就是实时进度；超时或中断时靠它判断做到了哪一步）"

# 等待循环：一次性阻塞只能回答「进程有没有退出」，回答不了「它是在干活还是卡住了」。
# 因此改为轮询日志，出现连续静默时唤起只读看护 agent 读日志判定，再决定是否终止。
$previous = $ErrorActionPreference
$ErrorActionPreference = "Continue"
$timedOut = $false
$stalled = $false
# 集成结果：供调用方判断能不能合并。
# 0 = 无需提交或已提交；非 0 表示必须人工介入，切不可自动合并。
$integrateCode = 0
$committed = $false
$outOfScopeCount = 0
$stallStreak = 0
$lastLength = -1
$lastWrite = Get-Date
$deadline = (Get-Date).AddMinutes($TimeoutMinutes)
$watchdogVerdicts = @()
$watchdogFile = Join-Path $env:TEMP "jset-agent-verdict-$Role-$Slug.txt"
$code = 1

try {
    $proc = Start-Process -FilePath $codexExe -ArgumentList $codexArgs -NoNewWindow -PassThru `
        -RedirectStandardInput $stdinFile `
        -RedirectStandardOutput $logFile -RedirectStandardError $errFile

    while (-not $proc.HasExited) {
        $proc | Wait-Process -Timeout $PollSeconds -ErrorAction SilentlyContinue
        if ($proc.HasExited) { break }

        # 总超时作为兜底始终生效
        if ((Get-Date) -ge $deadline) {
            $timedOut = $true
            break
        }
        if ($NoWatchdog) { continue }

        $currentLength = 0
        $currentWrite = $lastWrite
        if (Test-Path -LiteralPath $errFile) {
            $info = Get-Item -LiteralPath $errFile
            $currentLength = $info.Length
            $currentWrite = $info.LastWriteTime
        }
        if (($currentLength -eq $lastLength) -and ($currentWrite -eq $lastWrite)) {
            $stallStreak++
        } else {
            $stallStreak = 0
            $lastLength = $currentLength
            $lastWrite = $currentWrite
            continue
        }

        if ($stallStreak -lt $StallRounds) { continue }

        # 达到静默轮数：唤起只读看护判定。
        # 判定本身也带超时上限，避免看护自己卡住。
        if (Test-Path -LiteralPath $watchdogFile) { [System.IO.File]::Delete($watchdogFile) }
        Write-Host "`n[看护] 日志已静默 $StallRounds 轮，开始判定会话状态..."
        $watchdogArgs = @(
            "-NoProfile", "-ExecutionPolicy", "Bypass",
            "-File", (Join-Path $PSScriptRoot "watchdog.ps1"),
            "-LogPath", $errFile,
            "-VerdictFile", $watchdogFile,
            "-Quiet"
        )
        & powershell @watchdogArgs | Out-Null

        $verdict = "UNKNOWN"
        if (Test-Path -LiteralPath $watchdogFile) {
            $verdict = (Get-Content -Raw -Encoding UTF8 $watchdogFile).Trim()
        }
        Write-Host "[看护] 判定结论：$verdict"
        $watchdogVerdicts += $verdict
        $stallStreak = 0

        if ($verdict -eq "STUCK") {
            $stalled = $true
            break
        }
        # WAITING / PROGRESSING / UNKNOWN 一律继续等待：宁可多等，不可误杀。
    }

    if ($proc.HasExited) {
        $code = $proc.ExitCode
    } else {
        $code = 124
    }
} catch {
    Write-Host "启动 agent 失败：$($_.Exception.Message)"
    $code = 1
} finally {
    $ErrorActionPreference = $previous
}

if ($timedOut) {
    Write-Host "`n[超时] 已运行满 $TimeoutMinutes 分钟，正在终止 agent 及其子进程..."
    Stop-ProcessTree -ProcessId $proc.Id | Out-Null
    Write-Host "[超时] 现场保留在 $worktree（未提交）。处置建议："
    Write-Host "  - 结果可能不完整，不要直接合并；先看下面打印的日志尾部判断进度；"
    Write-Host "  - 需续做时加 -ReuseWorktree 并缩小任务范围；"
    Write-Host "  - 任务确实需要更久，就显式调大 -TimeoutMinutes。"
} elseif ($stalled) {
    Write-Host "`n[卡死] 看护判定会话已停滞，正在终止 agent 及其子进程..."
    Stop-ProcessTree -ProcessId $proc.Id | Out-Null
    Write-Host "[卡死] 现场保留在 $worktree（未提交）。判定历史：$($watchdogVerdicts -join ' -> ')"
    Write-Host "  - 日志尾部见下，据此判断卡在哪一步；"
    Write-Host "  - 建议缩小任务范围后重新派发。"
}
Write-Host "`nagent 退出码: $code"
if (Test-Path $outFile) {
    Write-Host "`n===== agent 最终回复 ====="
    Get-Content -Raw -Encoding UTF8 $outFile | Write-Host
}
if (Test-Path $logFile) {
    Write-Host "`n===== agent 日志尾部 ====="
    Get-Content -Encoding UTF8 $logFile -Tail 15 | Write-Host
}
if ((Test-Path $errFile) -and (Get-Item $errFile).Length -gt 0) {
    Write-Host "`n===== agent 错误输出尾部 ====="
    Get-Content -Encoding UTF8 $errFile -Tail 10 | Write-Host
}
# 写入域校验 + 统一提交。
# agent 只改文件、不自己提交：沙箱对共享的 .git/objects 只有部分写权限，
# 它自行提交会失败并可能在对象库留下不可达对象（均为实测）。
# 因此改由脚本在沙箱外提交，并在提交前核对改动是否都落在写入域内。
if ($Sandbox -ne "read-only") {
    $previous = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        & git -C $worktree add -A | Out-Null
        if ($LASTEXITCODE -ne 0) {
            Write-Host "`n[收尾] git add 失败，未提交，请手工检查 $worktree"
            $integrateCode = 5
        } else {
            $changed = @(& git -C $worktree diff --cached --name-only)
            if ($changed.Count -eq 0) {
                Write-Host "`n[收尾] 本次没有文件改动，无需提交。"
            } else {
                $outside = @()
                foreach ($file in $changed) {
                    $normalized = $file -replace '\\', '/'
                    $matched = $false
                    foreach ($pattern in $writeScope) {
                        if ($normalized -like $pattern) { $matched = $true; break }
                    }
                    if (-not $matched) { $outside += $normalized }
                }
                Write-Host "`n[收尾] agent 改动的文件："
                $changed | ForEach-Object { Write-Host "  $_" }
                if ($outside.Count -gt 0) {
                    Write-Host "[收尾] 以下文件不在写入域内，已回退暂存、不予提交："
                    $outside | ForEach-Object { Write-Host "  $_" }
                    & git -C $worktree reset -q | Out-Null
                    Write-Host "[收尾] 如需保留，请先确认方案再手工提交。"
                    # 越界不是“没事发生”：调用方必须能从退出码看出来，
                    # 否则自动合并会把“被拒提交”当成“已完成”。
                    $integrateCode = 3
                    $outOfScopeCount = $outside.Count
                } else {
                    $summary = ($Task -replace '\s+', ' ').Trim()
                    if ($summary.Length -gt 72) { $summary = $summary.Substring(0, 72) }
                    & git -C $worktree commit -q -m "agent($Role/$Slug): $summary" | Out-Null
                    if ($LASTEXITCODE -eq 0) {
                        Write-Host "[收尾] 已提交到分支 $branch"
                        $committed = $true
                    } else {
                        Write-Host "[收尾] 提交失败，请手工处理 $worktree"
                        $integrateCode = 4
                    }
                }
            }
        }
    } finally {
        $ErrorActionPreference = $previous
    }
}

if ($readOnlyAudit) {
    Write-Host "`n本次为只读审计，未创建 worktree与分支，无需合并与清理。"
    Write-Host "审计报告请由主 agent 落盘到 AI_REPORT.md，并标注「监督 agent 提供，主 agent 落盘」。"
} else {
    Write-Host "`n合并方式（在主仓库执行）："
    Write-Host "  git log --oneline main..$branch"
    Write-Host "  git merge --no-ff $branch"
    Write-Host "清理工作区："
    Write-Host "  git worktree remove `"$worktree`" --force; git worktree prune"
}
# 机器可读结果：供 dispatch 判断能不能自动合并。
if ($ResultFile) {
    $result = @(
        "role=$Role",
        "slug=$Slug",
        "branch=$branch",
        "worktree=$worktree",
        "agentExit=$code",
        "timedOut=$timedOut",
        "stalled=$stalled",
        "committed=$committed",
        "outOfScope=$outOfScopeCount"
    ) -join "`n"
    [System.IO.File]::WriteAllText($ResultFile, $result, (New-Object System.Text.UTF8Encoding $false))
}

# 退出码语义：0 成功；3 越界拒提交；4 提交失败；5 git add 失败；
# 124 超时；其余为 agent 自身的退出码。
if ($integrateCode -ne 0) { exit $integrateCode }
exit $code