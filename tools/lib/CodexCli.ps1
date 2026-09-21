<#
.SYNOPSIS
  codex CLI 的共用启动与终止逻辑。

.DESCRIPTION
  tools/agent.ps1（启动角色会话）与 tools/watchdog.ps1（判定会话是否卡住）
  都需要定位 codex 可执行文件、以及在必要时终止整个进程树。
  放在一处维护，避免两份路径探测逻辑各自漂移。
#>

# 返回 codex 可执行文件路径。
#
# Windows 上 npm 安装的 codex 是 node 包装脚本，直接终止它不会带走 node 子进程，
# 因此优先定位原生 codex.exe；只有在找不到时才回落到 PATH 上的 codex。
function Get-CodexExecutable {
    [CmdletBinding()]
    param()

    if ($env:CODEX_BIN -and (Test-Path -LiteralPath $env:CODEX_BIN)) {
        return $env:CODEX_BIN
    }

    $candidate = Join-Path $env:APPDATA `
        "npm\node_modules\@openai\codex\node_modules\@openai\codex-win32-x64\vendor\x86_64-pc-windows-msvc\bin\codex.exe"
    if (Test-Path -LiteralPath $candidate) {
        return $candidate
    }

    return "codex"
}

# 终止一个进程及其全部子进程。
#
# 用 taskkill /T 而不是 Stop-Process：后者只杀直接进程，
# 会话里正在跑的测试、长命令会成为孤儿进程继续占用端口与文件。
function Stop-ProcessTree {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]
        [int]$ProcessId
    )

    $previous = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        & taskkill /PID $ProcessId /T /F 2>&1 | Out-Null
        return ($LASTEXITCODE -eq 0)
    } finally {
        $ErrorActionPreference = $previous
    }
}