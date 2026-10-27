[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$ContractDir = Join-Path $ProjectRoot 'contract\src\main\java\dev\swarmie\contract'
$ContractBuild = Join-Path $ProjectRoot 'contract\build.gradle.kts'

if (-not (Test-Path -LiteralPath $ContractDir)) {
    [Console]::Error.WriteLine("FAIL: contract source package is missing: $ContractDir")
    exit 1
}
if (-not (Test-Path -LiteralPath $ContractBuild)) {
    [Console]::Error.WriteLine("FAIL: contract subproject build file is missing: $ContractBuild")
    exit 1
}

$files = @(Get-ChildItem -LiteralPath $ContractDir -Filter '*.java' -File)
if ($files.Count -eq 0) {
    [Console]::Error.WriteLine('FAIL: contract package contains no Java sources')
    exit 1
}

$violations = @()
foreach ($file in $files) {
    $matches = Select-String -LiteralPath $file.FullName -Pattern '^\s*import\s+(net\.minecraft|net\.fabricmc)\.' -CaseSensitive
    foreach ($match in $matches) {
        $violations += "$($file.Name):$($match.LineNumber): $($match.Line.Trim())"
    }
    $body = Get-Content -LiteralPath $file.FullName -Raw
    if ($body -match '\bnet\.(minecraft|fabricmc)\.') {
        $violations += "$($file.Name): contract source mentions a Minecraft/Fabric type"
    }
}

$buildText = Get-Content -LiteralPath $ContractBuild -Raw
if ($buildText -match 'net\.(minecraft|fabricmc)|fabric-loom') {
    $violations += 'contract/build.gradle.kts declares a Minecraft/Fabric dependency'
}

if ($violations.Count -gt 0) {
    [Console]::Error.WriteLine('FAIL: contract is not independent:')
    $violations | ForEach-Object { [Console]::Error.WriteLine("  $_") }
    exit 1
}

Write-Host "OK contract boundary: $($files.Count) files; independent :contract source and build"
exit 0
