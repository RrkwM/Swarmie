[CmdletBinding()]
param(
    [switch]$Offline
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path

# Use the project-local verified toolchain when present. A fresh clone should
# set JAVA_HOME (JDK 25) and use the standard Gradle wrapper download.
$JdkHome = Join-Path $ProjectRoot 'tools\jdk'
if (Test-Path -LiteralPath (Join-Path $JdkHome 'bin\java.exe')) {
    $env:JAVA_HOME = $JdkHome
    $env:PATH = "$JdkHome\bin;$env:PATH"
}

$LocalGradleHome = Join-Path $ProjectRoot 'tools\gradle-home'
if (Test-Path -LiteralPath $LocalGradleHome) {
    $env:GRADLE_USER_HOME = $LocalGradleHome
}

$arguments = @('build')
if ($Offline) {
    $arguments += '--offline'
}

Push-Location $ProjectRoot
try {
    $localGradle = Join-Path $ProjectRoot 'tools\gradle-9.8.2\bin\gradle.bat'
    if (Test-Path -LiteralPath $localGradle) {
        & $localGradle @arguments
    }
    else {
        & (Join-Path $ProjectRoot 'gradlew.bat') @arguments
    }
    exit $LASTEXITCODE
}
finally {
    Pop-Location
}
