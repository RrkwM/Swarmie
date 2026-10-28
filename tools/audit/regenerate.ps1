# Regenerate docs/auditing/completeness.generated.json from locally resolved jars.
#
# This script intentionally does not download anything. It uses the project JDK
# and the Loom/Gradle caches already present in the workspace. Point the explicit
# parameters at equivalent 26.3 artifacts on another machine if the local cache
# layout differs.
[CmdletBinding()]
param(
    [string]$ServerJar,
    [string]$ClientJar,
    [string]$FabricNetJar,
    [string]$FabricTransferJar,
    [string]$FabricLookupJar,
    [string]$Out
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$cache = Join-Path $root 'tools\gradle-home\caches\modules-2\files-2.1\net.fabricmc.fabric-api'

function Find-CachedJar {
    param([string]$Module, [string]$Pattern)
    $dir = Join-Path $cache $Module
    if (-not (Test-Path -LiteralPath $dir)) {
        throw "Missing Fabric module cache: $dir"
    }
    $jar = Get-ChildItem -LiteralPath $dir -Recurse -Filter $Pattern |
        Sort-Object FullName |
        Select-Object -Last 1
    if (-not $jar) {
        throw "Could not find $Pattern under $dir"
    }
    return $jar.FullName
}

if (-not $ServerJar) {
    $ServerJar = Join-Path $root 'tools\mc\loom-cache\26.3\minecraft-extracted_server.jar'
}
if (-not $ClientJar) {
    $ClientJar = Join-Path $root 'tools\mc\loom-cache\26.3\minecraft-client.jar'
}
if (-not $FabricNetJar) {
    $FabricNetJar = Find-CachedJar 'fabric-networking-api-v1' 'fabric-networking-api-v1-*.jar'
}
if (-not $FabricTransferJar) {
    $FabricTransferJar = Find-CachedJar 'fabric-transfer-api-v1' 'fabric-transfer-api-v1-*.jar'
}
if (-not $FabricLookupJar) {
    $FabricLookupJar = Find-CachedJar 'fabric-api-lookup-api-v1' 'fabric-api-lookup-api-v1-*.jar'
}
if (-not $Out) {
    $Out = Join-Path $root 'docs\auditing\completeness.generated.json'
}

foreach ($item in @($ServerJar, $ClientJar, $FabricNetJar, $FabricTransferJar, $FabricLookupJar)) {
    if (-not (Test-Path -LiteralPath $item)) {
        throw "Missing input jar: $item"
    }
}

$localJava = Join-Path $root 'tools\jdk\bin\java.exe'
$java = if (Test-Path -LiteralPath $localJava) { $localJava } else { 'java' }
$extractor = Join-Path $PSScriptRoot 'ExtractAudit.java'

& $java --source 25 $extractor `
    --server $ServerJar `
    --client $ClientJar `
    --fabric-net $FabricNetJar `
    --fabric-transfer $FabricTransferJar `
    --fabric-lookup $FabricLookupJar `
    --out $Out

exit $LASTEXITCODE
