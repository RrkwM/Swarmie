[CmdletBinding()]
param(
    [switch]$ApproveNetwork,
    [switch]$SkipClient,
    [switch]$RunServer
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$JdkHome = Join-Path $ProjectRoot 'tools\jdk'
$JdkJava = Join-Path $JdkHome 'bin\java.exe'
$JdkJavac = Join-Path $JdkHome 'bin\javac.exe'
$JdkJar = Join-Path $JdkHome 'bin\jar.exe'
$WrapperBat = Join-Path $ProjectRoot 'gradlew.bat'
$WrapperProperties = Join-Path $ProjectRoot 'gradle\wrapper\gradle-wrapper.properties'
$GradleArchive = Join-Path $ProjectRoot 'tools\dist\gradle-9.8.2-bin.zip'
$GradleHome = Join-Path $ProjectRoot 'tools\gradle-home'
$BootstrapInit = Join-Path $ProjectRoot 'tools\bootstrap\online-repos.init.gradle.kts'
$ContextProbeSource = Join-Path $ProjectRoot 'tools\verification\ContextProbe.java'
$LogDir = Join-Path $ProjectRoot 'tools\logs'
$Stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$LogPath = Join-Path $LogDir "verify-runtime-$Stamp.log"
$TempRoot = Join-Path $ProjectRoot "tools\provision-tmp\verify-runtime-$Stamp"

New-Item -ItemType Directory -Force -Path $LogDir, $TempRoot | Out-Null

function Write-Log {
    param([string]$Text)
    $line = "$(Get-Date -Format 'HH:mm:ss') $Text"
    Write-Host $line
    Add-Content -LiteralPath $LogPath -Value $line -Encoding UTF8
}

function Fail {
    param([string]$Message, [int]$Code = 1)
    Write-Log "FAIL: $Message"
    Write-Log "LOG: $LogPath"
    exit $Code
}

function Write-NativeOutput {
    param([object[]]$Lines)
    foreach ($line in $Lines) {
        Write-Log ([string]$line)
    }
}

Write-Log 'Swarmie runtime verification'
Write-Log "PROJECT=$ProjectRoot"
Write-Log "APPROVE_NETWORK=$([bool]$ApproveNetwork)"
if ($env:DSH_SESSION_ID -or $env:DSH_HOME) {
    Write-Log "DSH_SESSION_ID=$env:DSH_SESSION_ID"
    Write-Log "DSH_HOME=$env:DSH_HOME"
    Fail 'This shell is still a DSH/Harness process (DSH_* environment present). Open a standalone Windows Terminal from the Start menu, outside DSH, and run the script there.' 5
}

# ---------------------------------------------------------------------------
# 1. Execution context: require a normal, non-elevated standard-user process.
# ---------------------------------------------------------------------------
$groups = & whoami.exe /groups 2>&1 | Out-String
if ($groups -match 'S-1-16-4096') {
    Fail 'This process is Low Integrity. Run this script from a normal, non-elevated standard-user PowerShell session; see docs/runtime-verification.md.' 4
}
if ($groups -notmatch 'S-1-16-8192') {
    Fail 'This process is not at Medium Integrity (standard user, non-elevated). Run from a normal PowerShell session.' 4
}
Write-Log 'OK execution context appears to be Medium Integrity'

$probeRoot = Join-Path $TempRoot 'context'
$probeClasses = Join-Path $probeRoot 'classes'
New-Item -ItemType Directory -Force -Path $probeClasses | Out-Null

$compileOut = & $JdkJavac -d $probeClasses $ContextProbeSource 2>&1
$compileExit = $LASTEXITCODE
Write-NativeOutput $compileOut
if ($compileExit -ne 0) {
    Fail "Could not compile ContextProbe.java (exit $compileExit)." 20
}

$probeOut = & $JdkJava -cp $probeClasses ContextProbe $probeRoot 2>&1
$probeExit = $LASTEXITCODE
Write-NativeOutput $probeOut
$probeText = ($probeOut | Out-String)
if ($probeExit -ne 0 -or $probeText -notmatch 'CONTEXT_OK') {
    Fail 'Filesystem/ZipFS context probe failed. Run this script from a normal PowerShell launched from the desktop, outside DSH/AppContainer; Loom cannot process Minecraft jars in this context.' 3
}

# ---------------------------------------------------------------------------
# 2. Toolchain and wrapper checks.
# ---------------------------------------------------------------------------
foreach ($path in @($JdkJava, $JdkJavac, $JdkJar, $WrapperBat, $WrapperProperties, $GradleArchive, $BootstrapInit)) {
    if (-not (Test-Path -LiteralPath $path)) {
        Fail "Required file is missing: $path" 21
    }
}

$javaVersion = (& $JdkJava -version 2>&1 | Out-String)
Write-NativeOutput ($javaVersion -split "`r?`n")
if ($javaVersion -notmatch '25\.0\.4\.1') {
    Fail 'Project-local JDK is not 25.0.4.1.' 21
}

$manifestPath = Join-Path $ProjectRoot 'tools\manifest\toolchain.lock.json'
if (-not (Test-Path -LiteralPath $manifestPath)) {
    Fail 'tools/manifest/toolchain.lock.json is missing.' 21
}
$manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
foreach ($artifact in $manifest.artifacts) {
    $archive = Join-Path $ProjectRoot $artifact.archivePath
    if (-not (Test-Path -LiteralPath $archive)) {
        Fail "Toolchain archive is missing: $($artifact.archivePath)" 21
    }
    $hash = (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($hash -ne $artifact.sha256Calculated) {
        Fail "Toolchain checksum mismatch: $($artifact.archivePath)" 21
    }
    if ((Get-Item -LiteralPath $archive).Length -ne [int64]$artifact.sizeBytes) {
        Fail "Toolchain size mismatch: $($artifact.archivePath)" 21
    }
}
Write-Log 'OK JDK/Gradle archives match toolchain manifest'

$wrapperText = Get-Content -LiteralPath $WrapperProperties -Raw
if ($wrapperText -notmatch 'distributionUrl=\.\./\.\./tools/dist/gradle-9\.8\.2-bin\.zip') {
    Fail 'Gradle wrapper does not use the project-local 9.8.2 archive.' 21
}
if ($wrapperText -notmatch 'distributionSha256Sum=21bd5da2de49b642904077f38c0efdc7778e92b3ed0889392dca61b97e389607') {
    Fail 'Gradle wrapper checksum is not the expected 9.8.2 checksum.' 21
}
if ($wrapperText -match 'distributionUrl=https?://') {
    Fail 'Gradle wrapper distributionUrl must not be remote.' 21
}

$env:JAVA_HOME = $JdkHome
$env:PATH = "$JdkHome\bin;$env:PATH"
$env:GRADLE_USER_HOME = $GradleHome
$env:TEMP = $TempRoot
$env:TMP = $TempRoot
$env:JAVA_OPTS = (($env:JAVA_OPTS + ' -XX:-UsePerfData').Trim())

$versionOut = & $WrapperBat --version --no-daemon 2>&1
$versionExit = $LASTEXITCODE
Write-NativeOutput $versionOut
if ($versionExit -ne 0) {
    Fail "Gradle could not initialize (exit $versionExit). See log." 21
}
$versionText = $versionOut | Out-String
if ($versionText -notmatch 'Gradle 9\.8\.2') {
    Fail 'Gradle wrapper did not report Gradle 9.8.2.' 21
}
Write-Log 'OK Gradle wrapper starts with 9.8.2'

# ---------------------------------------------------------------------------
# 3. Build. Network is used only when explicitly approved.
# ---------------------------------------------------------------------------
$gradleCommon = @(
    '--no-daemon',
    '--console=plain',
    '--stacktrace',
    "-Dorg.gradle.java.home=$JdkHome",
    "-Dorg.gradle.java.installations.paths=$JdkHome"
)

if ($ApproveNetwork) {
    Write-Log 'Building with the existing online bootstrap (-ApproveNetwork). Only official documented repositories are used.'
    $buildArgs = @('-I', $BootstrapInit) + $gradleCommon + @('build')
} else {
    Write-Log 'Building offline (no network). If dependencies/assets are missing, re-run with -ApproveNetwork.'
    $buildArgs = @('--offline') + $gradleCommon + @('build')
}

$buildOut = & $WrapperBat @buildArgs 2>&1
$buildExit = $LASTEXITCODE
Write-NativeOutput $buildOut
if ($buildExit -ne 0) {
    Fail "Gradle build failed (exit $buildExit)." 22
}
Write-Log 'OK Gradle build completed'

# ---------------------------------------------------------------------------
# 4. Artifact checks.
# ---------------------------------------------------------------------------
$jars = @(Get-ChildItem (Join-Path $ProjectRoot 'build\libs') -File -Filter '*.jar' -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -notmatch '-sources' } |
    Sort-Object LastWriteTime -Descending)
if ($jars.Count -eq 0) {
    Fail 'No non-sources JAR was produced in build/libs.' 23
}
$jar = $jars[0]
if ($jar.Length -lt 10000) {
    Fail "Produced JAR is implausibly small ($($jar.Length) bytes); refusing it as a valid Mod JAR." 23
}

$entriesOut = & $JdkJar tf $jar.FullName 2>&1
$entriesExit = $LASTEXITCODE
Write-NativeOutput ($entriesOut | Select-Object -First 80)
if ($entriesExit -ne 0) {
    Fail 'The produced JAR failed jar listing / ZIP integrity.' 23
}
$entries = @($entriesOut)

$requiredEntries = @(
    'fabric.mod.json',
    'dev/swarmie/SwarmieMod.class',
    'dev/swarmie/SwarmieEntity.class',
    'dev/swarmie/SwarmieFlightController.class',
    'dev/swarmie/client/SwarmieClient.class',
    'dev/swarmie/client/SwarmieEntityRenderer.class',
    'dev/swarmie/client/SwarmieEntityModel.class',
    'dev/swarmie/client/SwarmieRenderState.class',
    'dev/swarmie/command/SwarmieCommands.class',
    'dev/swarmie/runtime/SwarmieRuntime.class',
    'dev/swarmie/inventory/SwarmieInventory.class',
    'dev/swarmie/interaction/InteractionActor.class',
    'dev/swarmie/query/DefaultWorldQuery.class',
    'dev/swarmie/sensor/DefaultLocalSensor.class',
    'assets/swarmie/textures/entity/swarmie.png',
    'assets/swarmie/lang/en_us.json',
    'assets/swarmie/lang/zh_cn.json'
)
foreach ($required in $requiredEntries) {
    if ($entries -notcontains $required) {
        Fail "Mod JAR is missing required entry: $required" 23
    }
}
Write-Log 'OK required classes and resources are present'

Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead($jar.FullName)
try {
    $modEntry = $zip.GetEntry('fabric.mod.json')
    if ($null -eq $modEntry) {
        Fail 'fabric.mod.json is missing from the JAR.' 23
    }
    $reader = New-Object System.IO.StreamReader($modEntry.Open())
    try {
        $modJson = $reader.ReadToEnd()
    }
    finally {
        $reader.Dispose()
    }

    foreach ($entry in $zip.Entries) {
        if ([string]::IsNullOrEmpty($entry.Name)) { continue }
        $stream = $entry.Open()
        try {
            $stream.CopyTo([System.IO.Stream]::Null)
        }
        finally {
            $stream.Dispose()
        }
    }
}
finally {
    $zip.Dispose()
}

$mod = $modJson | ConvertFrom-Json
if ($mod.id -ne 'swarmie') {
    Fail "fabric.mod.json id is '$($mod.id)', expected 'swarmie'." 23
}
if ([string]$mod.version -match '\$\{') {
    Fail "fabric.mod.json still contains unresolved placeholder: $($mod.version)" 23
}
$mainEntries = @($mod.entrypoints.main)
$clientEntries = @($mod.entrypoints.client)
if ($mainEntries -notcontains 'dev.swarmie.SwarmieMod') {
    Fail 'fabric.mod.json main entrypoint does not match the actual class.' 23
}
if ($clientEntries -notcontains 'dev.swarmie.client.SwarmieClient') {
    Fail 'fabric.mod.json client entrypoint does not match the actual class.' 23
}

$sha = (Get-FileHash -LiteralPath $jar.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
$report = [ordered]@{
    schemaVersion = 1
    generatedAt = (Get-Date).ToString('o')
    jar = $jar.FullName.Substring($ProjectRoot.Length).TrimStart('\')
    sizeBytes = $jar.Length
    sha256 = $sha
    modId = $mod.id
    modVersion = $mod.version
    entrypointMain = $mainEntries
    entrypointClient = $clientEntries
    entryCount = $entries.Count
    requiredEntriesPresent = $true
    zipIntegrity = 'ok'
}
$reportPath = Join-Path $LogDir "artifact-$Stamp.json"
$utf8 = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllText($reportPath, ($report | ConvertTo-Json -Depth 6), $utf8)
Write-Log "OK JAR=$($jar.FullName)"
Write-Log "OK SIZE=$($jar.Length) SHA256=$sha"
Write-Log "OK ARTIFACT_REPORT=$reportPath"

# ---------------------------------------------------------------------------
# 5. Client launch (optional but default). Do not claim visual success.
# ---------------------------------------------------------------------------
if (-not $SkipClient) {
    Write-Log 'Launching development client (runClient). The script cannot observe the Minecraft window.'
    if ($ApproveNetwork) {
        $clientArgs = @('-I', $BootstrapInit) + $gradleCommon + @('runClient')
    } else {
        $clientArgs = @('--offline') + $gradleCommon + @('runClient')
    }
    $clientOut = & $WrapperBat @clientArgs 2>&1
    $clientExit = $LASTEXITCODE
    Write-NativeOutput ($clientOut | Select-Object -Last 150)
    if ($clientExit -ne 0) {
        Fail "Client launch failed (exit $clientExit)." 31
    }
    Write-Log 'Client process exited normally; main-menu and in-game behavior still require manual observation.'
}
else {
    Write-Log 'Skipped client launch (-SkipClient).'
}

# ---------------------------------------------------------------------------
# 6. Optional dedicated server; never edit/accept the EULA.
# ---------------------------------------------------------------------------
if ($RunServer) {
    $eulaCandidates = @(
        (Join-Path $ProjectRoot 'run\server\eula.txt'),
        (Join-Path $ProjectRoot 'run\eula.txt')
    )
    $eulaReady = $false
    $eulaPath = $null
    foreach ($candidate in $eulaCandidates) {
        if (-not (Test-Path -LiteralPath $candidate)) { continue }
        $eulaText = Get-Content -LiteralPath $candidate -Raw
        if ($eulaText -match '(?m)^\s*eula\s*=\s*true\s*$') {
            $eulaReady = $true
            $eulaPath = $candidate
            break
        }
    }
    if (-not $eulaReady) {
        Fail "Dedicated server not started: no run/eula.txt or run/server/eula.txt contains eula=true. The script will not accept the EULA for you." 40
    }
    Write-Log "Using existing EULA acceptance at $eulaPath"

    if ($ApproveNetwork) {
        $serverArgs = @('-I', $BootstrapInit) + $gradleCommon + @('runServer')
    } else {
        $serverArgs = @('--offline') + $gradleCommon + @('runServer')
    }
    $serverOut = & $WrapperBat @serverArgs 2>&1
    $serverExit = $LASTEXITCODE
    Write-NativeOutput ($serverOut | Select-Object -Last 150)
    if ($serverExit -ne 0) {
        Fail "Dedicated server exited with code $serverExit." 41
    }
    Write-Log 'Dedicated server exited normally.'
}

Write-Log 'VERIFICATION SCRIPT COMPLETED; inspect log and record manual client results.'
exit 0
