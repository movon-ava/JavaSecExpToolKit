<#
.SYNOPSIS
  读会话日志，判定该会话处于「推进中」「正常长等待」还是「卡死」。

.DESCRIPTION
  只负责判定，不终止任何进程。终止由持有进程句柄的调用方
  （tools/agent.ps1）依据本脚本的结论执行。

  判定输出三态，而不是「活着 / 卡死」二值：
  二值无法表达「在等一条已知的长命令」这一最常见情形，
  而这类等待在日志上表现为静默数分钟且毫无新增。

  本脚本可独立运行，用于事后分析任意一次会话的日志。

.EXAMPLE
  .\tools\watchdog.ps1 -LogPath "$env:TEMP\jset-agent-log-probe-demo.err.txt"

.EXAMPLE
  # 只看结论，不打印判定理由
  .\tools\watchdog.ps1 -LogPath <日志> -Quiet
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$LogPath,

    # 交给判定方的日志窗口大小（行）
    [ValidateRange(20, 2000)]
    [int]$TailLines = 120,

    # 判定超时上限（秒）：判定本身也可能卡住，必须自带上限
    [ValidateRange(30, 900)]
    [int]$TimeoutSeconds = 180,

    [string]$Model = "",

    # 只输出结论，便于人看
    [switch]$Quiet,

    # 把结论写到这个文件，供调用方程序化读取（比解析控制台输出可靠）
    [string]$VerdictFile = ""
)

$ErrorActionPreference = "Stop"
$OutputEncoding = New-Object System.Text.UTF8Encoding $false
try { [Console]::OutputEncoding = New-Object System.Text.UTF8Encoding $false } catch { }

$root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
. (Join-Path $PSScriptRoot "lib\CodexCli.ps1")

# UNKNOWN 表示「没能判定」，调用方必须按保守方式处理（继续等待，不终止）。
$validVerdicts = @("PROGRESSING", "WAITING", "STUCK")

if (-not (Test-Path -LiteralPath $LogPath)) {
    Write-Host "日志不存在：$LogPath"
    if ($VerdictFile) {
        [System.IO.File]::WriteAllText($VerdictFile, "UNKNOWN", (New-Object System.Text.UTF8Encoding $false))
    }
    Write-Host "VERDICT: UNKNOWN"
    exit 2
}

$info = Get-Item -LiteralPath $LogPath
$tail = @(Get-Content -LiteralPath $LogPath -Encoding UTF8 -Tail $TailLines -ErrorAction SilentlyContinue)
$idleSeconds = [int]((Get-Date) - $info.LastWriteTime).TotalSeconds

$windowText = ($tail -join "`n")
if ($windowText.Length -gt 60000) {
    $windowText = $windowText.Substring($windowText.Length - 60000)
}

$prompt = @"
你是会话健康判定的看护 agent。只读，不修改任何文件，不执行任何命令。

## 待判定的会话日志

日志文件：$LogPath
日志大小：$($info.Length) 字节
最后写入距今：$idleSeconds 秒
以下是最末 $($tail.Count) 行：

``````
$windowText
``````

## 判定任务

请判断这个会话当前处于哪种状态：

- PROGRESSING：有推进痕迹。例如出现了新的工具调用、新的输出块、新的结论文字。
- WAITING：在等待一个明确的、有理由的耗时操作。例如正在执行一条耗时命令
  （长 ping、大范围搜索、完整测试套件、长时间构建），日志里能看到该等待对象。
  这类情况日志静默数分钟是正常的，**不得判为 STUCK**。
- STUCK：反复重试同一条命令、反复出现同一个错误、或长时间零输出且
  日志里看不到任何等待对象与推进痕迹。

## 输出要求

先给一两句理由，指出你依据的是日志里的哪些内容。
最后一行必须是且仅是以下三者之一：

VERDICT: PROGRESSING
VERDICT: WAITING
VERDICT: STUCK
"@

$stdinFile = Join-Path $env:TEMP "jset-watchdog-stdin.txt"
$outFile = Join-Path $env:TEMP "jset-watchdog-answer.txt"
$errFile = Join-Path $env:TEMP "jset-watchdog-err.txt"
$logFile = Join-Path $env:TEMP "jset-watchdog-log.txt"
[System.IO.File]::WriteAllText($stdinFile, $prompt, (New-Object System.Text.UTF8Encoding $false))
foreach ($stale in @($outFile, $errFile, $logFile)) {
    if (Test-Path -LiteralPath $stale) { [System.IO.File]::Delete($stale) }
}

$codexArgs = @("exec", "-C", $root, "-s", "read-only", "-o", "`"$outFile`"", "-")
if ($Model) { $codexArgs += @("-m", $Model) }

$exe = Get-CodexExecutable
if (-not $Quiet) { Write-Host "看护判定中（上限 $TimeoutSeconds 秒，日志静默 $idleSeconds 秒）..." }

$previous = $ErrorActionPreference
$ErrorActionPreference = "Continue"
$verdict = "UNKNOWN"
try {
    $proc = Start-Process -FilePath $exe -ArgumentList $codexArgs -NoNewWindow -PassThru `
        -RedirectStandardInput $stdinFile `
        -RedirectStandardOutput $logFile -RedirectStandardError $errFile
    $proc | Wait-Process -Timeout $TimeoutSeconds -ErrorAction SilentlyContinue
    if ($proc.HasExited) {
        if (Test-Path -LiteralPath $outFile) {
            $answer = Get-Content -Raw -Encoding UTF8 $outFile -ErrorAction SilentlyContinue
            # 只认「最后一行」的结论：模型可能在前文提到其它状态名。
            $match = [regex]::Matches([string]$answer, 'VERDICT:\s*(PROGRESSING|WAITING|STUCK)')
            if ($match.Count -gt 0) {
                $verdict = $match[$match.Count - 1].Groups[1].Value.ToUpperInvariant()
                if (-not $Quiet) {
                    Write-Host "`n===== 看护判定理由 ====="
                    Write-Host $answer.Trim()
                }
            } elseif (-not $Quiet) {
                Write-Host "判定输出里没有 VERDICT 行，按无法判定处理。"
            }
        }
    } else {
        # 判定自身超时：不终止目标会话，交给调用方继续按总超时等待。
        Stop-ProcessTree -ProcessId $proc.Id | Out-Null
        if (-not $Quiet) { Write-Host "看护判定自身超时（$TimeoutSeconds 秒），按无法判定处理。" }
    }
} catch {
    if (-not $Quiet) { Write-Host "看护判定失败：$($_.Exception.Message)" }
} finally {
    $ErrorActionPreference = $previous
}

if ($validVerdicts -notcontains $verdict) { $verdict = "UNKNOWN" }
if ($VerdictFile) {
    # 只写纯结论，不带额外文字，方便调用方直接比较
    [System.IO.File]::WriteAllText($VerdictFile, $verdict, (New-Object System.Text.UTF8Encoding $false))
}
Write-Host "VERDICT: $verdict"
exit 0