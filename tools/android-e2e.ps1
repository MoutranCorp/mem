param(
    [string]$AdbPath = "C:\src\androidsdk\platform-tools\adb.exe",
    [string]$BuildPython = $env:CHAQUOPY_BUILD_PYTHON,
    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"

$repo = Split-Path -Parent $PSScriptRoot
$artifacts = Join-Path $repo "artifacts\android-e2e"
$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$runDir = Join-Path $artifacts $timestamp
New-Item -ItemType Directory -Force -Path $runDir | Out-Null

if (-not (Test-Path $AdbPath)) {
    throw "adb not found at $AdbPath. Pass -AdbPath or install Android platform-tools."
}

if (-not $BuildPython) {
    $candidate = Join-Path $repo ".tools\python311-nuget\pkg\tools\python.exe"
    if (Test-Path $candidate) {
        $BuildPython = $candidate
    }
}

if ($BuildPython) {
    $env:CHAQUOPY_BUILD_PYTHON = $BuildPython
}

Push-Location $repo
try {
    & $AdbPath devices | Tee-Object -FilePath (Join-Path $runDir "adb-devices.txt")
    & $AdbPath logcat -c

    $gradleArgs = @("-Pkotlin.compiler.execution.strategy=in-process")

    if (-not $SkipBuild) {
        & .\gradlew.bat @gradleArgs :app:assembleDebug :app:assembleDebugAndroidTest
    }

    & .\gradlew.bat @gradleArgs :app:connectedDebugAndroidTest `
        "-Pandroid.testInstrumentationRunnerArguments.class=com.moutrancorp.memspike.DownloadedVideoSearchRegressionTest"

    & $AdbPath logcat -d > (Join-Path $runDir "logcat.txt")
    & $AdbPath exec-out run-as com.moutrancorp.memspike cat files/last_crash.txt > (Join-Path $runDir "last_crash.txt") 2>$null
    Write-Host "Android E2E artifacts: $runDir"
} finally {
    Pop-Location
}
