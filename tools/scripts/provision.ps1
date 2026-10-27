[CmdletBinding()]
param(
    [switch]$ApproveNetwork,
    [string]$Task = 'build'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path

function Fail {
    param([string]$Message, [int]$Code = 1)
    [Console]::Error.WriteLine("FAIL: $Message")
    exit $Code
}

if (-not $ApproveNetwork) {
    [Console]::Error.WriteLine('REFUSED: provisioning performs network access. Re-run with -ApproveNetwork.')
    exit 2
}

$JdkHome = Join-Path $ProjectRoot 'tools\jdk'
$GradleHome = Join-Path $ProjectRoot 'tools\gradle-home'
$WrapperBat = Join-Path $ProjectRoot 'gradlew.bat'
$WrapperProperties = Join-Path $ProjectRoot 'gradle\wrapper\gradle-wrapper.properties'
$ToolchainManifest = Join-Path $ProjectRoot 'tools\manifest\toolchain.lock.json'
$VersionCatalog = Join-Path $ProjectRoot 'gradle\libs.versions.toml'
$BootstrapInit = Join-Path $ProjectRoot 'tools\bootstrap\online-repos.init.gradle.kts'
$LocalRepo = Join-Path $ProjectRoot 'tools\repo'
$McRoot = Join-Path $ProjectRoot 'tools\mc'
$LogDir = Join-Path $ProjectRoot 'tools\logs'
$TempDir = Join-Path $ProjectRoot 'tools\provision-tmp'

foreach ($path in @(
    (Join-Path $JdkHome 'bin\java.exe'),
    $WrapperBat,
    $WrapperProperties,
    $ToolchainManifest,
    $VersionCatalog,
    $BootstrapInit
)) {
    if (-not (Test-Path -LiteralPath $path)) {
        Fail "required provisioning input is missing: $path"
    }
}

# --- Toolchain verification (no network) ---------------------------------
$manifest = Get-Content -LiteralPath $ToolchainManifest -Raw | ConvertFrom-Json
foreach ($artifact in $manifest.artifacts) {
    $archive = Join-Path $ProjectRoot $artifact.archivePath
    if (-not (Test-Path -LiteralPath $archive)) {
        Fail "toolchain archive missing: $($artifact.archivePath)"
    }
    $hash = (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($hash -ne $artifact.sha256Calculated) {
        Fail "toolchain checksum mismatch: $($artifact.archivePath)"
    }
    if ((Get-Item -LiteralPath $archive).Length -ne [int64]$artifact.sizeBytes) {
        Fail "toolchain size mismatch: $($artifact.archivePath)"
    }
}
Write-Host 'OK toolchain archives match manifest'

# --- Version confirmation ------------------------------------------------
$catalogText = Get-Content -LiteralPath $VersionCatalog -Raw
$versions = @{}
foreach ($line in ($catalogText -split "`n")) {
    if ($line -match '^\s*([A-Za-z][A-Za-z0-9]*)\s*=\s*"([^"]+)"\s*$') {
        $versions[$Matches[1]] = $Matches[2]
    }
}

$expected = [ordered]@{
    minecraft = '26.3'
    loader    = '0.20.1'
    fabricApi = '0.165.0+26.3'
    loom      = '1.19.3'
    java      = '25'
}
foreach ($key in $expected.Keys) {
    if (-not $versions.ContainsKey($key)) {
        Fail "version catalog is missing '$key'"
    }
    if ($versions[$key] -ne $expected[$key]) {
        Fail "version '$key' is '$($versions[$key])' but provisioning expects '$($expected[$key])'"
    }
}
Write-Host "OK versions: MC $($versions.minecraft), Loader $($versions.loader), API $($versions.fabricApi), Loom $($versions.loom), Java $($versions.java)"

$wrapperText = Get-Content -LiteralPath $WrapperProperties -Raw
if ($wrapperText -notmatch 'distributionUrl=\.\./\.\./tools/dist/gradle-9\.8\.2-bin\.zip') {
    Fail 'wrapper does not use the expected project-relative Gradle archive'
}
if ($wrapperText -notmatch 'distributionSha256Sum=21bd5da2') {
    Fail 'wrapper checksum does not match the provisioned Gradle 9.8.2 archive'
}

# --- Process-local environment ------------------------------------------
New-Item -ItemType Directory -Force -Path $GradleHome, $TempDir, $LogDir | Out-Null
$env:JAVA_HOME = $JdkHome
$env:PATH = "$JdkHome\bin;$env:PATH"
$env:GRADLE_USER_HOME = $GradleHome
$env:TEMP = $TempDir
$env:TMP = $TempDir
$env:JAVA_OPTS = (($env:JAVA_OPTS + ' -XX:-UsePerfData').Trim())

# Use the explicit local proxy if one is present in the process environment.
$proxyArgs = @()
$proxyRaw = $env:HTTPS_PROXY
if (-not $proxyRaw) { $proxyRaw = $env:https_proxy }
if (-not $proxyRaw) { $proxyRaw = $env:HTTP_PROXY }
if ($proxyRaw) {
    try {
        $proxyUri = [Uri]$proxyRaw
        if ($proxyUri.Host) {
            $proxyPort = if ($proxyUri.Port -gt 0) { $proxyUri.Port } else { 80 }
            $proxyArgs = @(
                "-Dhttps.proxyHost=$($proxyUri.Host)",
                "-Dhttps.proxyPort=$proxyPort",
                "-Dhttp.proxyHost=$($proxyUri.Host)",
                "-Dhttp.proxyPort=$proxyPort"
            )
            Write-Host "OK using local proxy $($proxyUri.Host):$proxyPort (credentials, if any, are never logged)"
        }
    }
    catch {
        Fail 'could not parse the configured proxy URL'
    }
}

$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$logPath = Join-Path $LogDir "provision-$stamp.log"

$gradleArgs = @(
    '-I', $BootstrapInit,
    '--no-daemon',
    '--console=plain',
    '--info',
    '--stacktrace',
    "-Dorg.gradle.java.home=$JdkHome",
    "-Dorg.gradle.java.installations.paths=$JdkHome"
) + $proxyArgs + @($Task)

Write-Host "RUN gradlew $($gradleArgs -join ' ')"
Push-Location $ProjectRoot
try {
    & $WrapperBat @gradleArgs 2>&1 | Tee-Object -FilePath $logPath
    $buildExit = $LASTEXITCODE
}
finally {
    Pop-Location
}

if ($buildExit -ne 0) {
    [Console]::Error.WriteLine("BUILD FAILED (exit $buildExit). Log: $logPath")
    exit $buildExit
}
Write-Host "OK build task '$Task' completed. Log: $logPath"

# --- Capture Maven dependency closure -----------------------------------
$modulesCache = Join-Path $GradleHome 'caches\modules-2\files-2.1'
if (-not (Test-Path -LiteralPath $modulesCache)) {
    Fail "Gradle module cache not found: $modulesCache"
}

New-Item -ItemType Directory -Force -Path $LocalRepo | Out-Null
$copied = 0
$conflicts = 0
Get-ChildItem -LiteralPath $modulesCache -Recurse -File | ForEach-Object {
    $relative = $_.FullName.Substring($modulesCache.Length).TrimStart('\')
    $parts = $relative -split '\\'
    if ($parts.Count -lt 5) { return }

    $group = $parts[0]
    $artifact = $parts[1]
    $version = $parts[2]
    $destDir = Join-Path $LocalRepo ($group -replace '\.', '\')
    $destDir = Join-Path $destDir $artifact
    $destDir = Join-Path $destDir $version
    $destFile = Join-Path $destDir $_.Name

    New-Item -ItemType Directory -Force -Path $destDir | Out-Null
    if (Test-Path -LiteralPath $destFile) {
        $oldHash = (Get-FileHash -LiteralPath $destFile -Algorithm SHA256).Hash
        $newHash = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash
        if ($oldHash -ne $newHash) {
            Write-Warning "duplicate artifact with different content: $destFile"
            $conflicts++
        }
    }
    else {
        Copy-Item -LiteralPath $_.FullName -Destination $destFile
        $copied++
    }
}
Write-Host "OK captured $copied Maven files into tools/repo ($conflicts conflicts)"

# --- Capture Loom / Minecraft cache -------------------------------------
$loomCache = Join-Path $GradleHome 'caches\fabric-loom'
if (-not (Test-Path -LiteralPath $loomCache)) {
    Fail "Loom cache not found after build: $loomCache"
}
$loomDest = Join-Path $McRoot 'loom-cache'
if (Test-Path -LiteralPath $loomDest) {
    Remove-Item -LiteralPath $loomDest -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $loomDest | Out-Null
Copy-Item -Path (Join-Path $loomCache '*') -Destination $loomDest -Recurse -Force
Write-Host 'OK copied Loom cache into tools/mc/loom-cache'

# --- Manifests -----------------------------------------------------------
$now = (Get-Date).ToString('yyyy-MM-ddTHH:mm:sszzz')
$utf8 = New-Object Text.UTF8Encoding($false)

$repoFiles = Get-ChildItem -LiteralPath $LocalRepo -Recurse -File
$provArtifacts = foreach ($f in $repoFiles) {
    $relative = $f.FullName.Substring($LocalRepo.Length).TrimStart('\')
    $parts = $relative -split '\\'
    if ($parts.Count -lt 4) { continue }
    $file = $parts[-1]
    $version = $parts[-2]
    $artifact = $parts[-3]
    $group = ($parts[0..($parts.Count - 4)] -join '.')
    $isPlugin = ($group -eq 'net.fabricmc.fabric-loom') -or ($artifact -eq 'fabric-loom')
    [ordered]@{
        group = $group
        artifact = $artifact
        version = $version
        file = $file
        path = "tools/repo/$($relative -replace '\\', '/')"
        sizeBytes = $f.Length
        sha256 = (Get-FileHash -LiteralPath $f.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
        type = if ($isPlugin) { 'gradle-plugin' } else { 'maven' }
        sourceRepository = if ($group.StartsWith('net.fabricmc')) { 'https://maven.fabricmc.net/' } else { 'https://repo.maven.apache.org/maven2/' }
        publisherChecksum = $null
        verification = 'sha256-recorded; publisher checksum not retrieved in this phase'
        capturedFrom = "tools/gradle-home/caches/modules-2/files-2.1/$($relative -replace '\\', '/')"
    }
}

$provenance = [ordered]@{
    schemaVersion = 1
    task = 'P1-T1c'
    capturedAt = $now
    projectRoot = '.'
    source = 'tools/gradle-home/caches/modules-2/files-2.1'
    artifactCount = @($provArtifacts).Count
    artifacts = @($provArtifacts)
    notes = @(
        'Captured from the Gradle module cache after the first successful build.',
        'Publisher checksums were not fetched separately; SHA-256 values are computed from local files.',
        'C2 closure completeness is not yet proven; offline verification is a later phase.'
    )
}
[IO.File]::WriteAllText((Join-Path $ProjectRoot 'tools\manifest\dependency-provenance.json'), ($provenance | ConvertTo-Json -Depth 8), $utf8)

$mcFiles = Get-ChildItem -LiteralPath $loomDest -Recurse -File
$mcArtifacts = foreach ($f in $mcFiles) {
    $relative = $f.FullName.Substring($loomDest.Length).TrimStart('\')
    [ordered]@{
        path = "tools/mc/loom-cache/$($relative -replace '\\', '/')"
        sizeBytes = $f.Length
        sha256 = (Get-FileHash -LiteralPath $f.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
        category = ($relative -split '\\')[0]
    }
}
$mcManifest = [ordered]@{
    schemaVersion = 1
    task = 'P1-T1c'
    capturedAt = $now
    projectRoot = '.'
    sourceCache = 'tools/gradle-home/caches/fabric-loom'
    copyRoot = 'tools/mc/loom-cache'
    fileCount = @($mcArtifacts).Count
    files = @($mcArtifacts)
    notes = @(
        'Copied verbatim from the Loom cache after the first build.',
        'Path sensitivity and portability are not yet verified.'
    )
}
[IO.File]::WriteAllText((Join-Path $ProjectRoot 'tools\manifest\mc-artifacts.json'), ($mcManifest | ConvertTo-Json -Depth 8), $utf8)

Write-Host "OK provenance: tools/manifest/dependency-provenance.json ($(@($provArtifacts).Count) entries)"
Write-Host "OK mc artifacts: tools/manifest/mc-artifacts.json ($(@($mcArtifacts).Count) files)"
Write-Host 'PROVISIONING OK'
exit 0
