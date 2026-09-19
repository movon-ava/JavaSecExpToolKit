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
$sources = @(
    (Join-Path $root "src\Main.java"),
    (Join-Path $root "src\proxy\ProxyServer.java"),
    (Join-Path $root "src\shiro\ShiroEngine.java"),
    (Join-Path $root "python\fj_probe.py")
)

if (-not (Test-Path -LiteralPath $mvn)) { throw "Maven executable not found: $mvn" }
if (-not (Test-Path -LiteralPath (Join-Path $JavaHome "bin\java.exe"))) { throw "Java runtime not found: $JavaHome" }
if (-not (Test-Path -LiteralPath $pom)) { throw "POM not found: $pom" }

$env:JAVA_HOME = $JavaHome
Remove-Item -LiteralPath $outputJar -Force -ErrorAction SilentlyContinue
& $mvn -f $pom clean package
if ($LASTEXITCODE -ne 0) { throw "Maven build failed" }

$jarInfo = Get-Item -LiteralPath $outputJar
foreach ($source in $sources) {
    if (-not (Test-Path -LiteralPath $source)) { continue }
    $sourceInfo = Get-Item -LiteralPath $source
    if ($jarInfo.LastWriteTime -lt $sourceInfo.LastWriteTime) {
        throw "JAR build time is older than source: $($sourceInfo.Name) $($jarInfo.LastWriteTime) < $($sourceInfo.LastWriteTime)"
    }
}

Write-Host "Maven build complete: $outputJar"
Write-Host "JAR build time: $($jarInfo.LastWriteTime.ToString('yyyy-MM-dd HH:mm:ss'))"
Write-Host "JAR size: $($jarInfo.Length) bytes"
$bodyJar = Get-Item -LiteralPath (Join-Path $root "lib\java-chains-cli-2.0.0-beta4.jar") -ErrorAction SilentlyContinue
if ($bodyJar) {
    Write-Host "Runtime dependency: $($bodyJar.FullName) ($($bodyJar.Length) bytes)"
} else {
    Write-Host "Runtime dependency: missing lib\java-chains-cli-2.0.0-beta4.jar"
}