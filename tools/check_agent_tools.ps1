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
$sharedPath = Join-Path $PSScriptRoot "lib\CodexCli.ps1"
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
$scripts = @($agentPath, $watchdogPath, $sharedPath)
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

$rangeViolations = @(Get-RangeViolations -Path $agentPath) + @(Get-RangeViolations -Path $watchdogPath) + @(Get-RangeViolations -Path $sharedPath)
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
if ($script:failures.Count -eq 0) {
    Write-Host "agent 工具链自检通过（$($script:checks) 项）"
    exit 0
}
Write-Host "agent 工具链自检失败（$($script:failures.Count)/$($script:checks) 项）："
$script:failures | ForEach-Object { Write-Host "  - $_" }
exit 1