<#
.SYNOPSIS
  tools/ 下 agent 工具链的机械自检。

.DESCRIPTION
  把实际踩到过的缺陷固化成可重复执行的断言，避免回归：
  参数与计数器同名导致参数校验拒赋值、开关名写错导致分支成为死代码、
  含中文脚本丢失 BOM、.gitignore 未锚定根目录而误伤工具链源码、
  看护脚本解析不到结论时的保守行为。

  判定质量本身（LLM 是否判对）不在此校验范围内：那需要真跑一次会话，
  不确定性太高。这里只固化「判定链路的机械行为」。

.EXAMPLE
  powershell -NoProfile -ExecutionPolicy Bypass -File tools\check_agent_tools.ps1
#>
[CmdletBinding()]
param(
    [switch]$Quiet
)

$ErrorActionPreference = "Stop"
$OutputEncoding = New-Object System.Text.UTF8Encoding $false
try { [Console]::OutputEncoding = New-Object System.Text.UTF8Encoding $false } catch { }

$root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$agentPath = Join-Path $PSScriptRoot "agent.ps1"
$watchdogPath = Join-Path $PSScriptRoot "watchdog.ps1"
$dispatchPath = Join-Path $PSScriptRoot "dispatch.ps1"
$orchestratePath = Join-Path $PSScriptRoot "orchestrate.ps1"
$sharedPath = Join-Path $PSScriptRoot "lib\CodexCli.ps1"
$roleMatrixPath = Join-Path $PSScriptRoot "lib\RoleMatrix.ps1"
$agentRunPath = Join-Path $PSScriptRoot "lib\AgentRun.ps1"
$sandbox = Join-Path $env:TEMP "jset-tools-check"

$script:checks = 0
$script:failures = @()

function Assert-That {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][bool]$Condition,
        [string]$Detail = ""
    )
    $script:checks++
    if ($Condition) {
        if (-not $Quiet) { Write-Host "  [ok] $Name" }
    } else {
        Write-Host "  [FAIL] $Name"
        if ($Detail) { Write-Host "         $Detail" }
        $script:failures += $Name
    }
}

function Read-Text {
    param([Parameter(Mandatory = $true)][string]$Path)
    return [System.IO.File]::ReadAllText($Path, [System.Text.Encoding]::UTF8)
}

function Get-Ast {
    param([Parameter(Mandatory = $true)][string]$Path)
    $tokens = $null
    $errors = $null
    $ast = [System.Management.Automation.Language.Parser]::ParseFile($Path, [ref]$tokens, [ref]$errors)
    return @{ Ast = $ast; Errors = $errors }
}

# 模拟 codex：把收到的参数存档，再把预先备好的回答复制到 -o 指定的路径。
# 用它替换真实 LLM，判定链路才能被确定性地重复检查。
# 回答走文件复制而不是 echo，是为了让多行文本原样落盘（cmd 的 echo 无法可靠地写多行）。
function Initialize-FakeCodex {
    param([Parameter(Mandatory = $true)][string]$Name, [Parameter(Mandatory = $true)][string]$Answer)

    $answerPath = Join-Path $sandbox "answer-$Name.txt"
    [System.IO.File]::WriteAllText($answerPath, $Answer, (New-Object System.Text.UTF8Encoding $false))

    $path = Join-Path $sandbox "$Name.cmd"
    $body = @"
@echo off
setlocal enabledelayedexpansion
set "OUT="
:parse
if "%~1"=="" goto parsed
if /i "%~1"=="-o" set "OUT=%~2"
shift
goto parse
:parsed
>"$sandbox\args-$Name.txt" echo %*
if defined OUT copy /y "$answerPath" "%OUT%" >nul
exit /b 0
"@
    [System.IO.File]::WriteAllText($path, $body, (New-Object System.Text.ASCIIEncoding))
    return $path
}

# 读取一个脚本 param 块里带数值范围校验的参数及其区间。
function Get-RangedParameters {
    param([Parameter(Mandatory = $true)][string]$Path)

    $parsed = Get-Ast -Path $Path
    $result = @{}
    if (-not $parsed.Ast.ParamBlock) { return $result }
    foreach ($parameter in $parsed.Ast.ParamBlock.Parameters) {
        $name = $parameter.Name.VariablePath.UserPath
        foreach ($attribute in $parameter.Attributes) {
            if ($attribute.TypeName.Name -eq "ValidateRange") {
                $arguments = @($attribute.PositionalArguments)
                if ($arguments.Count -ge 2) {
                    $result[$name] = @([int]$arguments[0].Value, [int]$arguments[1].Value)
                }
            }
        }
    }
    return $result
}

# 找出「把整数字面量赋给带 ValidateRange 的同名参数」的地方。
# 这正是实际踩过的坑：计数器与参数同名时，赋 0 会被参数校验直接拒绝，
# 且报错发生在脚本主体，看起来像是看护功能本身就坏的。
function Get-RangeViolations {
    param([Parameter(Mandatory = $true)][string]$Path)

    $parsed = Get-Ast -Path $Path
    $ranges = Get-RangedParameters -Path $Path

    $violations = @()
    $assignments = $parsed.Ast.FindAll({ param($node) $node -is [System.Management.Automation.Language.AssignmentStatementAst] }, $true)
    foreach ($assignment in $assignments) {
        $target = $assignment.Left
        if ($target -isnot [System.Management.Automation.Language.VariableExpressionAst]) { continue }
        $name = $target.VariablePath.UserPath
        if (-not $ranges.ContainsKey($name)) { continue }

        # 带类型约束的赋值（如 `[int]$X = 0` 或变量名已带约束时的 `$X = 0`）
        # 会被解析成 CommandExpressionAst，常量包在内层；不解开会漏判。
        $right = $assignment.Right
        if ($right -is [System.Management.Automation.Language.CommandExpressionAst]) { $right = $right.Expression }
        if ($right -isnot [System.Management.Automation.Language.ConstantExpressionAst]) { continue }
        $value = $right.Value
        if (($value -isnot [int]) -and ($value -isnot [long])) { continue }

        $low = $ranges[$name][0]
        $high = $ranges[$name][1]
        $number = [int]$value
        if (($number -lt $low) -or ($number -gt $high)) {
            $violations += ("行 {0}：{1} = {2}，越出参数声明的 [{3}, {4}]" -f `
                $assignment.Extent.StartLineNumber, $name, $number, $low, $high)
        }
    }
    return $violations
}

# 在隔离的临时目录里跑一次看护判定，返回结论文本。
function Invoke-Verdict {
    param(
        [Parameter(Mandatory = $true)][string]$LogPath,
        [Parameter(Mandatory = $true)][string]$Name,
        [string]$CodexPath = ""
    )

    $verdictPath = Join-Path $sandbox "verdict-$Name.txt"
    if (Test-Path -LiteralPath $verdictPath) { [System.IO.File]::Delete($verdictPath) }

    $previous = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        if ($CodexPath) { $env:CODEX_BIN = $CodexPath } else { Remove-Item Env:\CODEX_BIN -ErrorAction SilentlyContinue }
        & powershell -NoProfile -ExecutionPolicy Bypass -File $watchdogPath `
            -LogPath $LogPath -VerdictFile $verdictPath -Quiet | Out-Null
        $script:lastCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previous
        Remove-Item Env:\CODEX_BIN -ErrorAction SilentlyContinue
    }

    if (Test-Path -LiteralPath $verdictPath) { return (Read-Text -Path $verdictPath).Trim() }
    return ""
}

if (-not (Test-Path -LiteralPath $sandbox)) {
    New-Item -ItemType Directory -Path $sandbox -Force | Out-Null
}

Write-Host "== agent 工具链自检 =="

Write-Host ""
Write-Host "[1] 文件与语法"
$scripts = @($agentPath, $watchdogPath, $dispatchPath, $orchestratePath, $sharedPath, $roleMatrixPath, $agentRunPath)
foreach ($script in $scripts) {
    Assert-That -Name "存在：$(Split-Path $script -Leaf)" -Condition (Test-Path -LiteralPath $script)
}
foreach ($script in $scripts) {
    $parsed = Get-Ast -Path $script
    Assert-That -Name "语法可解析：$(Split-Path $script -Leaf)" -Condition ($parsed.Errors.Count -eq 0) `
        -Detail (($parsed.Errors | Select-Object -First 3 | ForEach-Object { $_.Message }) -join "；")
}

# 含中文的脚本必须带 UTF-8 BOM：PowerShell 5.1 无 BOM 时按本地代码页解析，直接报错。
foreach ($script in $scripts) {
    $bytes = [System.IO.File]::ReadAllBytes($script)
    $hasBom = (($bytes.Length -ge 3) -and ($bytes[0] -eq 0xEF) -and ($bytes[1] -eq 0xBB) -and ($bytes[2] -eq 0xBF))
    Assert-That -Name "UTF-8 BOM：$(Split-Path $script -Leaf)" -Condition $hasBom `
        -Detail "缺少 BOM 时中文脚本会解析失败"
}

Write-Host ""
Write-Host "[2] 编码卫生"
foreach ($script in $scripts) {
    $text = Read-Text -Path $script
    $literalEscapes = [regex]::Matches($text, '(?<!\\)\\u[0-9a-fA-F]{4}').Count
    Assert-That -Name "无字面量转义残留：$(Split-Path $script -Leaf)" -Condition ($literalEscapes -eq 0) `
        -Detail "发现 $literalEscapes 处；这类转义不会被 PowerShell 解码，会原样打印给人看"
}

Write-Host ""
Write-Host "[3] 参数与变量"
$agentText = Read-Text -Path $agentPath
Assert-That -Name "开关判断用的是 NoWatchdog" -Condition ($agentText -match 'if\s*\(\s*\$NoWatchdog\s*\)') `
    -Detail "写成 Watchdog 时该变量从未赋值、恒为空，判断分支会变成死代码"
# 用 -cmatch：-match 大小写不敏感，会把 \$watchdogFile 也当成命中。
Assert-That -Name "不再出现未赋值的 Watchdog 变量" -Condition (-not ($agentText -cmatch '\$Watchdog\b')) `
    -Detail "参数名是 -NoWatchdog，不存在名为 Watchdog 的变量"
Assert-That -Name "看护计数器与 -StallRounds 参数不同名" -Condition ($agentText -cmatch '\$stallStreak') `
    -Detail "同名赋值会触发参数的 ValidateRange 校验并中断整个脚本"

$rangeViolations = @(Get-RangeViolations -Path $agentPath) + @(Get-RangeViolations -Path $watchdogPath) + `
    @(Get-RangeViolations -Path $dispatchPath) + @(Get-RangeViolations -Path $orchestratePath) + `
    @(Get-RangeViolations -Path $sharedPath) + @(Get-RangeViolations -Path $roleMatrixPath) + @(Get-RangeViolations -Path $agentRunPath)
Assert-That -Name "无「整数赋值越出同名参数区间」" -Condition ($rangeViolations.Count -eq 0) `
    -Detail ($rangeViolations -join "；")

Write-Host ""
Write-Host "[4] 看护脚本的权限边界"
$watchdogText = Read-Text -Path $watchdogPath
$parsed = Get-Ast -Path $watchdogPath
$watchdogParams = @()
if ($parsed.Ast.ParamBlock) {
    $watchdogParams = @($parsed.Ast.ParamBlock.Parameters | ForEach-Object { $_.Name.VariablePath.UserPath })
}
Assert-That -Name "看护脚本不接受任何进程标识参数" `
    -Condition (@($watchdogParams | Where-Object { $_ -match '^(ProcessId|Pid)$' }).Count -eq 0) `
    -Detail "没有进程标识参数，结构上就杀不掉被判定的会话；终止只由持有句柄的 agent.ps1 执行"
Assert-That -Name "看护脚本不直接调用 taskkill" `
    -Condition (-not ($watchdogText -match 'taskkill')) `
    -Detail "终止动作封在共用库里，看护脚本本身不提及系统终止命令"

# 看护会终止自己的判定子进程（超时时），这是合法的；
# 不合法的是去终止被判定的会话。判定依据：
# 该调用的参数必须频自本脚本起的子进程句柄，而本脚本又不接受任何进程标识参数。
$ownKills = [regex]::Matches($watchdogText, 'Stop-ProcessTree\s+-ProcessId\s+(\S+)')
$foreignKills = @($ownKills | Where-Object { $_.Groups[1].Value -ne '$proc.Id' })
Assert-That -Name "看护只终止自己的判定子进程，不终止被判定会话" `
    -Condition (($ownKills.Count -ge 1) -and ($foreignKills.Count -eq 0)) `
    -Detail "实际命中：$(($ownKills | ForEach-Object { $_.Value }) -join ', ')"
Assert-That -Name "共用库按 PID 终止整个进程树" -Condition ((Read-Text -Path $sharedPath) -match 'taskkill /PID') `
    -Detail "缺少 /T 会留下 node 子进程成为孤儿"
Assert-That -Name "agent.ps1 只在 STUCK 时终止会话" `
    -Condition (([regex]::Matches($agentText, '\$verdict -eq "STUCK"')).Count -eq 1) `
    -Detail "WAITING / PROGRESSING / UNKNOWN 都必须继续等待，由总超时兜底"

Write-Host ""
Write-Host "[5] .gitignore 锚定"
$previous = $ErrorActionPreference
$ErrorActionPreference = "Continue"
try {
    & git -C $root check-ignore -q -- "tools/lib/CodexCli.ps1" 2>&1 | Out-Null
    $sharedIgnored = ($LASTEXITCODE -eq 0)
    & git -C $root check-ignore -q -- "lib/placeholder.jar" 2>&1 | Out-Null
    $buildLibIgnored = ($LASTEXITCODE -eq 0)
} finally {
    $ErrorActionPreference = $previous
}
Assert-That -Name "工具链源码 tools/lib/ 不被忽略" -Condition (-not $sharedIgnored) `
    -Detail "写成 lib/ 会连带忽略 tools/lib/，共用库无法入库"
Assert-That -Name "构建产物 lib/ 仍被忽略" -Condition $buildLibIgnored `
    -Detail "锚定后必须仍能忽略仓库根的 lib/"

Write-Host ""
Write-Host "[6] 看护判定的解析与保守行为"
$sampleLog = Join-Path $sandbox "sample.log"
[System.IO.File]::WriteAllText($sampleLog, "[step 2/4] retrying the same command`nFAILED (errors=3)`n", (New-Object System.Text.UTF8Encoding $false))

$missingLog = Join-Path $sandbox "does-not-exist.log"
$missingText = Invoke-Verdict -LogPath $missingLog -Name "missing"
$missingCode = $script:lastCode
Assert-That -Name "日志不存在时返回 UNKNOWN" -Condition ($missingText -eq "UNKNOWN") -Detail "实际：$missingText"
Assert-That -Name "日志不存在时退出码为 2" -Condition ($missingCode -eq 2) -Detail "实际：$missingCode"

$noVerdictExe = Initialize-FakeCodex -Name "noverdict" -Answer "I could not decide from this log window."
$noVerdictText = Invoke-Verdict -LogPath $sampleLog -Name "noverdict" -CodexPath $noVerdictExe
Assert-That -Name "解析不到 VERDICT 时返回 UNKNOWN" -Condition ($noVerdictText -eq "UNKNOWN") `
    -Detail "实际：$noVerdictText；缺结论时必须保守，不能误判为 STUCK 去杀会话"

$multiAnswer = "This is not STUCK, it is WAITING.`nVERDICT: STUCK"
$multiExe = Initialize-FakeCodex -Name "multi" -Answer $multiAnswer
$multiText = Invoke-Verdict -LogPath $sampleLog -Name "multi" -CodexPath $multiExe
Assert-That -Name "多个状态名时取最后一个 VERDICT" -Condition ($multiText -eq "STUCK") `
    -Detail "实际：$multiText；只看最后一行才能避免前文提及的状态名干扰"

$waitingAnswer = "The log states an explicit wait target.`nVERDICT: WAITING"
$waitingExe = Initialize-FakeCodex -Name "waiting" -Answer $waitingAnswer
$waitingText = Invoke-Verdict -LogPath $sampleLog -Name "waiting" -CodexPath $waitingExe
Assert-That -Name "WAITING 结论被正确透传" -Condition ($waitingText -eq "WAITING") -Detail "实际：$waitingText"

$captureExe = Initialize-FakeCodex -Name "capture" -Answer "VERDICT: PROGRESSING"
$null = Invoke-Verdict -LogPath $sampleLog -Name "capture" -CodexPath $captureExe
$capturedArgs = ""
$argsPath = Join-Path $sandbox "args-capture.txt"
if (Test-Path -LiteralPath $argsPath) { $capturedArgs = Read-Text -Path $argsPath }
Assert-That -Name "看护会话以 read-only 沙箱启动" -Condition ($capturedArgs -match 'read-only') `
    -Detail "实际参数：$($capturedArgs.Trim())"
Assert-That -Name "看护会话的工作目录是本仓库" -Condition ($capturedArgs -match [regex]::Escape($root)) `
    -Detail "实际参数：$($capturedArgs.Trim())"

Write-Host ""
Write-Host "[7] 提示词口径（直接查源码，避免中文经 stdin 中转后失真）"
Assert-That -Name "提示词写明三种状态的判定口径" `
    -Condition (($watchdogText -match 'PROGRESSING') -and ($watchdogText -match 'WAITING') -and ($watchdogText -match 'STUCK')) `
    -Detail "三种状态口径缺失时判定会不稳定"
Assert-That -Name "提示词要求把长等待排除在卡死之外" -Condition ($watchdogText -match '不得判为 STUCK') `
    -Detail "这正是「静默数分钟但属于正常等待」不被误杀的依据"
Assert-That -Name "提示词要求把重试与重复错误作为卡死依据" -Condition ($watchdogText -match '反复重试同一条命令') `
    -Detail "卡死的判据必须来自日志内容模式，而不是静默时长"
Assert-That -Name "提示词声明判定方只读且不执行命令" -Condition ($watchdogText -match '只读，不修改任何文件，不执行任何命令') `
    -Detail "判定方越权会破坏「判定与终止分离」"

Write-Host ""
Write-Host "[8] 派发权与隔离边界"

# agent 沙箱只能拿到本次 worktree、.git\worktrees\<name> 与 .git\lfs。
# 若把整个 .git 放行，agent 就能自行 git worktree add 无限分裂工作区，
# 写入域与并发度就不再由脚本掌控。这里把它固化为断言。
$grantBlock = [regex]::Match($agentText, '\$grantDirs\s*=\s*@\((?<body>.*?)\n\s*\)', 'Singleline')
Assert-That -Name "能解析出授权目录清单" -Condition $grantBlock.Success `
    -Detail "未找到 \$grantDirs 定义"
$grantText = $grantBlock.Groups['body'].Value
Assert-That -Name "授权包含本次 worktree 的 Git 元数据目录" `
    -Condition ($grantText -match 'worktrees') `
    -Detail "缺它时 git add / commit 会因 index.lock 被拒"
Assert-That -Name "授权包含 .git\lfs" -Condition ($grantText -match 'lfs') `
    -Detail "缺它时 git-lfs filter-process 会失败"
Assert-That -Name "授权未放行整个 .git 目录" `
    -Condition (-not ($grantText -match '\$gitDir\s*\)\s*$|Join-Path\s+\$gitDir\s+"\"\s*\)')) `
    -Detail "放行整个 .git 会让 agent 自行建分支与 worktree，破坏派发权集中在脚本"

# 监督角色必须在主仓库以只读运行，否则看不到待审的未提交改动。
Assert-That -Name "监督角色在主仓库运行且为只读" `
    -Condition (($agentText -match '\$readOnlyAudit\s*=\s*\$Role\s*-eq\s*"supervisor"') -and `
                ($agentText -match '\$worktree\s*=\s*if\s*\(\$readOnlyAudit\)\s*\{\s*\$root\s*\}')) `
    -Detail "监督必须直接审主仓库当前工作区（worktree 是从 HEAD 建的，看不到未提交改动）"

Write-Host ""
Write-Host "[9] 汇报必须写明角色与产出"

# 用户要求：每次任务结束后，汇报里要能看出「调用了哪些 agent 角色、各自做了什么」。
# 光有逐步表格不够——并行批次里步骤顺序不等于角色分工，得按角色聚合。
$orchCode = @((Read-Text -Path $orchestratePath) -split "`n" | Where-Object { $_ -notmatch '^\s*#' }) -join "`n"
# 必须匹配「调用」而不是函数定义：只删调用点、留着定义时，
# 按函数名匹配会假通过（已用变体验证过一次）。
Assert-That -Name "编排汇报按角色聚合调用情况" `
    -Condition ($orchCode -match '\$breakdown\s*=\s*Get-RoleBreakdown') `
    -Detail "缺少按角色聚合时，人只能从逐步表格反推「谁做了什么」"
Assert-That -Name "编排汇报列出本次调用的角色" `
    -Condition ($orchCode -match '本次调用的角色') `
    -Detail "缺少该节时，汇报无法一眼回答「这次用了哪些角色」"
Assert-That -Name "编排汇报逐角色列出主要工作" `
    -Condition ($orchCode -match '各角色主要工作') `
    -Detail "缺少该节时，只能看到步骤状态，看不到各角色的职责分工"
Assert-That -Name "编排汇报记录每步的产出文件" `
    -Condition (($orchCode -match 'Get-StepFiles') -and ($orchCode -match '产出文件')) `
    -Detail "产出文件是判断「这一步到底做了什么」的直接证据"
Assert-That -Name "每步都带上交给角色做的事" `
    -Condition (([regex]::Matches($orchCode, '-Task \$task')).Count -ge 1 -and `
                ([regex]::Matches($orchCode, '-Task \$handle\.Task')).Count -ge 1) `
    -Detail "并行批次若不传任务描述，汇报里那一步的「主要工作」会是空的"

# 产出文件的取值区间必须只覆盖这一次合并带来的改动。
# 实测踩过：并行批次共用批次基线，先合并的步骤会串进后一步的产出清单
# （traffic 的清单里出现 probe 的 python/fj_probe.py）。
Assert-That -Name "产出文件按合并提交的父提交对比" `
    -Condition (([regex]::Matches($orchCode, 'newHead\^1\.\.\$newHead')).Count -ge 2) `
    -Detail "写成基线..新 HEAD 时，并行批次里后合并的步骤会把他人的改动算进自己的产出"
Assert-That -Name "派发命令的收尾汇报也写明角色与产出" `
    -Condition (((Read-Text -Path $dispatchPath) -match '主 agent 本次调用角色') -and `
                ((Read-Text -Path $dispatchPath) -match '该角色主要工作')) `
    -Detail "单条派发是更常用的入口，同样要能回答「调了谁、做了什么」"

# 汇报里的角色名必须是中文：直接打印 probe / traffic 这种标识，
# 读的人还要对照文档才知道是谁。但角色标识仍然是分支名、写入域与 -Role
# 参数的取值，因此两者不能互相替换。
$displayCode = @((Read-Text -Path $roleMatrixPath) -split "`n" | Where-Object { $_ -notmatch '^\s*#' }) -join "`n"
Assert-That -Name "角色矩阵提供中文显示名" `
    -Condition ($displayCode -match 'function\s+Get-RoleDisplayName' ) `
    -Detail "缺少显示名映射时，汇报只能打印角色标识"
. $roleMatrixPath
$displayNames = Get-RoleDisplayNames
$allRoles = @(Get-AllRoles)
$unmapped = @($allRoles | Where-Object { -not $displayNames.ContainsKey($_) })
Assert-That -Name "全部角色都有中文显示名（$($allRoles.Count) 个）" `
    -Condition ($unmapped.Count -eq 0) `
    -Detail "未登记的角色会在汇报里回退成标识：$($unmapped -join '、')"
# 显示名必须含中文：全 ASCII（如 "test" / "traffic agent"）就是没翻过去。
$notChinese = @($allRoles | Where-Object { $displayNames[$_] -notmatch '[\u4e00-\u9fa5]' })
Assert-That -Name "显示名均含中文" `
    -Condition ($notChinese.Count -eq 0) `
    -Detail "这些显示名里没有中文：$($notChinese -join '、')"
# 显示名不得直接等于角色标识，否则等于没改。
$identityNames = @($allRoles | Where-Object { $displayNames[$_] -eq $_ })
Assert-That -Name "显示名不等于角色标识" `
    -Condition ($identityNames.Count -eq 0) `
    -Detail "这些名字还是原样的标识：$($identityNames -join '、')"
Assert-That -Name "未登记角色回退为标识而不是空" `
    -Condition ((Get-RoleDisplayName -Role "nosuchrole") -eq "nosuchrole") `
    -Detail "回退成空串会让汇报里那一行变成空白"
# 直接读文件而不用 $dispatchCode / $orchCode：后两者在本节之后才赋值，
# 在这里引用会拿到 $null，断言恒为假（实测踩过）。
$dispatchRaw = @((Read-Text -Path $dispatchPath) -split "`n" | Where-Object { $_ -notmatch '^\s*#' }) -join "`n"
$orchestrateRaw = @((Read-Text -Path $orchestratePath) -split "`n" | Where-Object { $_ -notmatch '^\s*#' }) -join "`n"
Assert-That -Name "派发汇报用显示名" `
    -Condition (([regex]::Matches($dispatchRaw, 'Get-RoleDisplayName')).Count -ge 2) `
    -Detail "开头的角色行与收尾汇报都要用显示名"
Assert-That -Name "编排汇报用显示名" `
    -Condition (([regex]::Matches($orchestrateRaw, 'Get-RoleDisplayName')).Count -ge 1) `
    -Detail "编排汇报的角色列与标题都要用显示名"

Write-Host ""
Write-Host "[10] 角色矩阵完整性"

# 矩阵是「哪两个角色能并行」与「提交是否越界」的唯一依据，
# 因此它自身必须自洽：调用方不能用 ,@() / 裸 return 把数组打散或退化。
# 注释里出现这个写法是合法的（正是解释为什么不能用），因此先剥掉整行注释再判断。
$roleMatrixCode = @((Read-Text -Path $roleMatrixPath) -split "`n" | Where-Object { $_ -notmatch '^\s*#' }) -join "`n"
Assert-That -Name "RoleMatrix 不再用 ,@() 包装返回数组" `
    -Condition (-not ($roleMatrixCode -match 'return\s+,@\(')) `
    -Detail "return ,@(...) 会让 foreach 把整个数组当成一个元素，-Role 取值直接报参数转换错误"
# 单条派发也要守同一条规矩：`return ,@(...)` 会让 $hits.Count 恒为 1，
# 于是「无命中」与「多角色命中」两条防呆分支双双失效，任务会静默拿到空角色继续跑。
$dispatchCode = @((Read-Text -Path $dispatchPath) -split "`n" | Where-Object { $_ -notmatch '^\s*#' }) -join "`n"
Assert-That -Name "dispatch.ps1 的角色推断不用 ,@() 包装" `
    -Condition (-not ($dispatchCode -match 'return\s+,@\(')) `
    -Detail "逗号返回会让多角色/无命中两条防呆分支变成死代码"
Assert-That -Name "dispatch.ps1 的角色推断仍做多命中拦截" `
    -Condition ($dispatchCode -match '-not \$Role[\s\S]{0,600}\$hits\.Count\s*-gt\s*1') `
    -Detail "缺少拦截时，写入域不同却按首个命中角色派发"
Assert-That -Name "orchestrate.ps1 复用共享矩阵而非自带副本" `
    -Condition (((Read-Text -Path $orchestratePath) -match 'lib\\RoleMatrix\.ps1') -and `
                (-not ((Read-Text -Path $orchestratePath) -match '\$writeScopes\s*=\s*@\{'))) `
    -Detail "自带副本会与 agent.ps1 的提交校验漂移"
Assert-That -Name "dispatch.ps1 复用共享执行原语" `
    -Condition (((Read-Text -Path $dispatchPath) -match 'lib\\AgentRun\.ps1') -and `
                (-not ((Read-Text -Path $dispatchPath) -match 'function\s+Get-MergeDecision'))) `
    -Detail "重复实现合并判定会出现两套不一致的安全策略"

# agent.ps1 的退出码是自动化的判定输入，必须覆盖全部失败分支。
$agentText2 = Read-Text -Path $agentPath
# 退出码是自动化的判定输入。agent.ps1 先把集成阶段的失败码收进 $integrateCode，
# 会话超时则直接落在 $code 上，因此按语义匹配而不是匹配字面量 "exit N"。
foreach ($pair in @(
    @("越界拒绝提交", '\$integrateCode\s*=\s*3'),
    @("提交失败", '\$integrateCode\s*=\s*4'),
    @("暂存失败", '\$integrateCode\s*=\s*5'),
    @("会话超时", '\$code\s*=\s*124'),
    @("失败码统一外抛", 'exit\s+\$integrateCode')
)) {
    Assert-That -Name "退出码覆盖：$($pair[0])" -Condition ($agentText2 -match $pair[1]) `
        -Detail "缺 $($pair[1]) 时编排器无法区分「成功」与「没做完」，会把不完整产出当成可合并"
}

# 每个受管源文件都必须有且仅有一个角色可写。
# 实测踩过：src/config/** 与 src/util/** 曾落在所有角色写入域之外，
# 于是「共享内核需主 agent 单独开 change」这条规则在机械校验层面根本走不通。
. $roleMatrixPath
$scopes = Get-RoleWriteScopes
$managed = @()
foreach ($directory in @("src", "python", "tests")) {
    $full = Join-Path $root $directory
    if (-not (Test-Path -LiteralPath $full)) { continue }
    foreach ($file in (Get-ChildItem -LiteralPath $full -Recurse -File)) {
        $relative = $file.FullName.Substring($root.Length + 1).Replace('\', '/')
        if ($relative -match '__pycache__|\.pyc$') { continue }
        $managed += $relative
    }
}
$orphans = @()
foreach ($target in $managed) {
    $owners = @()
    foreach ($role in @($scopes.Keys)) {
        foreach ($pattern in $scopes[$role]) {
            if ($target -like $pattern) { $owners += $role; break }
        }
    }
    if ($owners.Count -eq 0) { $orphans += $target }
}
Assert-That -Name "受管源文件都有角色可写（$($managed.Count) 个）" -Condition ($orphans.Count -eq 0) `
    -Detail "无角色可写的路径会永远提交不了：$($orphans -join '、')"

# 共享内核必须落在主 agent 手里，否则「单独开 change」的规定无人执行。
foreach ($core in @("src/config/AppConfig.java", "src/util/Codec.java", "src/pom.xml")) {
    Assert-That -Name "共享内核归主 agent：$core" `
        -Condition (@($scopes['orchestrator'] | Where-Object { $core -like $_ }).Count -eq 1) `
        -Detail "实际写入域：$($scopes['orchestrator'] -join '、')"
}

Assert-That -Name "只读角色写入域为空" `
    -Condition (@((Get-ReadOnlyRoles) | Where-Object { @($scopes[$_]).Count -ne 0 }).Count -eq 0) `
    -Detail "只读角色一旦有写入域，审计独立性就不再是结构保证"
Assert-That -Name "全部角色的写入域非空且无空项" `
    -Condition (@($scopes.Keys | Where-Object { @($scopes[$_]) -contains "" }).Count -eq 0) `
    -Detail "空 glob 会匹配到任意路径，等于放开越界校验"

# 文档总览表必须与矩阵逐项一致：两处说法不一，读文档的人就会照错的做。
$rolesText = Read-Text -Path (Join-Path $root "docs\AGENT-ROLES.md")
$documented = @{}
foreach ($line in ($rolesText -split "`n")) {
    if ($line -notmatch '^\|\s*(?<role>[^|]+?)\s*\|(?<scope>[^|]*)\|') { continue }
    $documented[$Matches['role']] = $Matches['scope']
}
foreach ($pair in @(
    @("主 agent", "orchestrator"),
    @("功能开发 probe", "probe"),
    @("功能开发 exploit", "exploit"),
    @("功能开发 traffic", "traffic"),
    @("UI", "ui"),
    @("测试", "test")
)) {
    $label = $pair[0]
    $role = $pair[1]
    if (-not $documented.ContainsKey($label)) {
        Assert-That -Name "角色总览表含：$label" -Condition $false -Detail "文档里找不到这一行"
        continue
    }
    $cell = $documented[$label]
    $missing = @()
    foreach ($pattern in $scopes[$role]) {
        if ($cell -notmatch [regex]::Escape($pattern)) { $missing += $pattern }
    }
    Assert-That -Name "总览表与矩阵一致：$label" -Condition ($missing.Count -eq 0) `
        -Detail "文档缺这些写入域：$($missing -join '、')"
}

Write-Host ""
Write-Host ""
if ($script:failures.Count -eq 0) {
    Write-Host "agent 工具链自检通过（$($script:checks) 项）"
    exit 0
}
Write-Host "agent 工具链自检失败（$($script:failures.Count)/$($script:checks) 项）："
$script:failures | ForEach-Object { Write-Host "  - $_" }
exit 1