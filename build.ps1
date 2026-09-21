[CmdletBinding()]
param(
    [string]$JavaHome = "E:\java\jdk17",
    [string]$MavenHome = "E:\java\maven\apache-maven-3.9.4"
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$mvn = Join-Path $MavenHome "bin\mvn.cmd"
$pom = Join-Path $root "src\pom.xml"
$outputJar = Join-Path $root "JavaSecExpToolKit.jar"
# 递归枚举全部源文件，而不是硬编码白名单：
# 白名单会漏掉新增文件，使下面那段时间守卫形同虚设（曾有 31 个源文件只校验了 4 个）。
$sources = @()
foreach ($folder in @("src", "python", "tests")) {
    $candidate = Join-Path $root $folder
    if (-not (Test-Path -LiteralPath $candidate)) { continue }
    $sources += Get-ChildItem -LiteralPath $candidate -Recurse -File |
        Where-Object { $_.Extension -in @(".java", ".py") -and $_.FullName -notmatch "__pycache__" } |
        ForEach-Object { $_.FullName }
}

if (-not (Test-Path -LiteralPath $mvn)) { throw "Maven executable not found: $mvn" }
if (-not (Test-Path -LiteralPath (Join-Path $JavaHome "bin\java.exe"))) { throw "Java runtime not found: $JavaHome" }
if (-not (Test-Path -LiteralPath $pom)) { throw "POM not found: $pom" }

$env:JAVA_HOME = $JavaHome
Remove-Item -LiteralPath $outputJar -Force -ErrorAction SilentlyContinue
& $mvn -f $pom clean package
if ($LASTEXITCODE -ne 0) { throw "Maven build failed" }

$jarInfo = Get-Item -LiteralPath $outputJar
$newer = @()
foreach ($source in $sources) {
    $sourceInfo = Get-Item -LiteralPath $source
    if ($jarInfo.LastWriteTime -lt $sourceInfo.LastWriteTime) {
        $newer += "$($sourceInfo.FullName) ($($sourceInfo.LastWriteTime.ToString('yyyy-MM-dd HH:mm:ss')))"
    }
}
if ($newer.Count -gt 0) {
    throw "JAR build time is older than these $($newer.Count) source file(s):`n  " + ($newer -join "`n  ")
}

Write-Host "Maven build complete: $outputJar"
Write-Host "JAR build time: $($jarInfo.LastWriteTime.ToString('yyyy-MM-dd HH:mm:ss'))"
Write-Host "JAR size: $($jarInfo.Length) bytes"
Write-Host "Source freshness checked: $($sources.Count) file(s) under src / python / tests"
$bodyJar = Get-Item -LiteralPath (Join-Path $root "lib\java-chains-cli-2.0.0-beta4.jar") -ErrorAction SilentlyContinue
if ($bodyJar) {
    Write-Host "Runtime dependency: $($bodyJar.FullName) ($($bodyJar.Length) bytes)"
} else {
    Write-Host "Runtime dependency: missing lib\java-chains-cli-2.0.0-beta4.jar"
}
