[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$ManifestPath = Join-Path $ProjectRoot 'tools\manifest\toolchain.lock.json'

function Fail {
    param([string]$Message)
    [Console]::Error.WriteLine("FAIL: $Message")
    exit 1
}

if (-not (Test-Path -LiteralPath $ManifestPath -PathType Leaf)) {
    Fail "toolchain manifest not found: $ManifestPath"
}

$manifest = Get-Content -LiteralPath $ManifestPath -Raw | ConvertFrom-Json
if ($null -eq $manifest.artifacts -or $manifest.artifacts.Count -lt 2) {
    Fail 'toolchain manifest does not contain the expected two artifacts'
}

foreach ($artifact in $manifest.artifacts) {
    $archive = Join-Path $ProjectRoot $artifact.archivePath
    if (-not (Test-Path -LiteralPath $archive -PathType Leaf)) {
        Fail "missing archive: $($artifact.archivePath)"
    }
    $actualHash = (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actualHash -ne $artifact.sha256Calculated) {
        Fail "SHA-256 mismatch for $($artifact.archivePath)"
    }
    $actualSize = (Get-Item -LiteralPath $archive).Length
    if ($actualSize -ne [int64]$artifact.sizeBytes) {
        Fail "size mismatch for $($artifact.archivePath)"
    }
    Write-Host "OK manifest $($artifact.product) $($artifact.version)"
}

$JdkHome = Join-Path $ProjectRoot 'tools\jdk'
$GradleBat = Join-Path $ProjectRoot 'tools\gradle-9.8.2\bin\gradle.bat'
$GradleUserHome = Join-Path $ProjectRoot 'tools\gradle-home'
$TempDir = Join-Path $GradleUserHome 'tmp'

foreach ($path in @(
    (Join-Path $JdkHome 'bin\java.exe'),
    (Join-Path $JdkHome 'bin\javac.exe'),
    $GradleBat
)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        Fail "missing executable: $path"
    }
}

New-Item -ItemType Directory -Force -Path $GradleUserHome, $TempDir | Out-Null

$env:JAVA_HOME = $JdkHome
$env:PATH = "$JdkHome\bin;$env:PATH"
$env:GRADLE_USER_HOME = $GradleUserHome
$env:TEMP = $TempDir
$env:TMP = $TempDir
$env:JAVA_TOOL_OPTIONS = '-XX:-UsePerfData'

Push-Location $ProjectRoot
try {
    $javaOutput = (& (Join-Path $JdkHome 'bin\java.exe') -version 2>&1 | Out-String)
    if ($LASTEXITCODE -ne 0) { Fail 'java -version failed' }
    if ($javaOutput -notmatch 'Temurin-25\.0\.4\.1\+1') {
        Fail "unexpected java runtime: $javaOutput"
    }
    Write-Host 'OK java 25.0.4.1+1 (Temurin)'

    $javacOutput = (& (Join-Path $JdkHome 'bin\javac.exe') -version 2>&1 | Out-String)
    if ($LASTEXITCODE -ne 0) { Fail 'javac -version failed' }
    if ($javacOutput -notmatch 'javac 25\.0\.4\.1') {
        Fail "unexpected javac version: $javacOutput"
    }
    Write-Host 'OK javac 25.0.4.1'

    $gradleOutput = (& $GradleBat --version 2>&1 | Out-String)
    if ($LASTEXITCODE -ne 0) { Fail 'gradle --version failed' }
    if ($gradleOutput -notmatch 'Gradle 9\.8\.2') {
        Fail "unexpected Gradle version: $gradleOutput"
    }
    if ($gradleOutput -notmatch 'Launcher JVM:\s+25\.0\.4\.1') {
        Fail "Gradle did not launch with the project-local JDK: $gradleOutput"
    }
    Write-Host 'OK Gradle 9.8.2 with project-local Temurin 25.0.4.1+1'
}
finally {
    Pop-Location
}

if (Test-Path -LiteralPath (Join-Path $env:USERPROFILE '.gradle')) {
    Fail 'unexpected global Gradle user home was created'
}

Write-Host 'TOOLCHAIN OK'
exit 0
