[CmdletBinding()]
param(
    [string]$JavaHome = "E:\java\jdk17",
    [string]$MavenHome = "E:\java\maven\apache-maven-3.9.4"
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$java = Join-Path $JavaHome "bin\java.exe"
$jar = Join-Path $root "JavaSecExpToolKit.jar"

if (-not (Test-Path -LiteralPath $java)) { throw "Java runtime not found: $java" }

if (-not (Test-Path -LiteralPath $jar)) {
    & (Join-Path $root "build.ps1") -JavaHome $JavaHome -MavenHome $MavenHome
    if ($LASTEXITCODE -ne 0) { throw "Maven build failed" }
}

# java-chains 的字节码 gadget 需要访问 JDK 内部 xalan 实现，Java 17 下必须显式开放
& $java `
    --add-opens java.xml/com.sun.org.apache.xalan.internal.xsltc.trax=ALL-UNNAMED `
    --add-opens java.xml/com.sun.org.apache.xalan.internal.xsltc.runtime=ALL-UNNAMED `
    -jar $jar