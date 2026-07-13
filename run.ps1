#Requires -Version 7.0
<#
.SYNOPSIS
  fuaran-kt Stage-0 entry point — build + test the native Kotlin surface over the
  Rust reference core of the Fuaran UI wire format.

.DESCRIPTION
  The happy path compiles the pure-JVM `fuaran-ui` module (sealed tree model +
  render-projection decoder) and runs the corpus render-coverage harness, then — when
  the Rust toolchain + a C compiler are present — builds the desktop JNI native leg and
  runs the live-session round-trip test.

  There is no Gradle binary on the reference dev box, so this driver compiles directly
  with `kotlinc` / `javac` / `java`; the committed `build.gradle.kts` files are a
  forward-looking scaffold for a future Gradle build. The harnesses skip cleanly when a
  prerequisite (JDK, corpus, Rust toolchain, C compiler) is absent, so the repo stays
  green on any machine.

.PARAMETER SkipBuild
  Reuse the existing compiled jar; only run the test harnesses.

.PARAMETER SkipTests
  Compile only; do not run the harnesses.

.PARAMETER Package
  Assemble the Android per-ABI `.so` / AAR `jniLibs` packaging. Requires the Android
  NDK + cargo-ndk, which are absent on the reference box — this leg skips with a named
  message (mirrors fuaran-rs `run.ps1 -Package`).
#>
[CmdletBinding()]
param(
    [switch] $SkipBuild,
    [switch] $SkipTests,
    [switch] $Package
)

$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

$Repo = $PSScriptRoot
$BuildDir = Join-Path $Repo "build"
$ClassesDir = Join-Path $BuildDir "classes"
$JniGenDir = Join-Path $Repo "fuaran-core\src\main\jni\generated"
$Jar = Join-Path $BuildDir "fuaran-kt.jar"
$Corpus = Resolve-Path (Join-Path $Repo "..\wire-format-fixtures") -ErrorAction SilentlyContinue

function Resolve-Tool([string] $Name, [string[]] $Fallbacks) {
    $cmd = Get-Command $Name -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($cmd) { return $cmd.Source }
    foreach ($f in $Fallbacks) { if (Test-Path $f) { return $f } }
    return $null
}

$JavaHome = [Environment]::GetEnvironmentVariable('JAVA_HOME', 'Machine')
if (-not $JavaHome) { $JavaHome = $env:JAVA_HOME }
$Java = if ($JavaHome) { Join-Path $JavaHome "bin\java.exe" } else { $null }
$Javac = if ($JavaHome) { Join-Path $JavaHome "bin\javac.exe" } else { $null }
$Kotlinc = Resolve-Tool "kotlinc" @("C:\Program Files\kotlinc\bin\kotlinc.bat")

if (-not ($Java -and (Test-Path $Java)) -or -not $Kotlinc) {
    Write-Host "SKIP: no JDK ($Java) or kotlinc found — nothing to build. (A JDK 21 + Kotlin 2.x are required.)" -ForegroundColor Yellow
    exit 0
}

Write-Host "fuaran-kt :: JDK $JavaHome" -ForegroundColor Cyan

# --- Gather sources ------------------------------------------------------------------
$MainKt = @(Get-ChildItem -Recurse -Path (Join-Path $Repo "fuaran-ui\src\main\kotlin"),
    (Join-Path $Repo "fuaran-core\src\main\kotlin") -Filter *.kt -ErrorAction SilentlyContinue |
    ForEach-Object FullName)
$TestKt = @(Get-ChildItem -Recurse -Path (Join-Path $Repo "fuaran-ui\src\test\kotlin"),
    (Join-Path $Repo "fuaran-core\src\test\kotlin") -Filter *.kt -ErrorAction SilentlyContinue |
    ForEach-Object FullName)
$JavaSrc = @(Get-ChildItem -Recurse -Path (Join-Path $Repo "fuaran-core\src\main\java") -Filter *.java -ErrorAction SilentlyContinue |
    ForEach-Object FullName)

# --- Build -------------------------------------------------------------------------- #
if (-not $SkipBuild) {
    Remove-Item -Recurse -Force $BuildDir -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force $ClassesDir | Out-Null

    if ($JavaSrc) {
        New-Item -ItemType Directory -Force $JniGenDir | Out-Null
        Write-Host "javac -h (JNI header) :: $($JavaSrc.Count) Java bridge file(s)"
        & $Javac -h $JniGenDir -d $ClassesDir @JavaSrc
        if ($LASTEXITCODE -ne 0) { throw "javac failed" }
    }

    $ktArgs = @()
    $ktArgs += $MainKt
    $ktArgs += $TestKt
    if ($JavaSrc) { $ktArgs += @("-classpath", $ClassesDir) }
    $ktArgs += @("-include-runtime", "-d", $Jar)
    Write-Host "kotlinc :: $($MainKt.Count) main + $($TestKt.Count) test file(s)"
    & $Kotlinc @ktArgs
    if ($LASTEXITCODE -ne 0) { throw "kotlinc failed" }
    Write-Host "Built $Jar" -ForegroundColor Green
}

if ($SkipTests) { Write-Host "Tests skipped."; exit 0 }

$Classpath = if ($JavaSrc) { "$Jar;$ClassesDir" } else { $Jar }

# --- Phase 542: corpus render-coverage harness -------------------------------------- #
if ($Corpus) { $env:FUARAN_CORPUS = $Corpus.Path }
Write-Host "`n== Phase 542 :: corpus render-coverage ==" -ForegroundColor Cyan
& $Java -cp $Classpath "fuaran.ui.CorpusDecodeTestKt"
if ($LASTEXITCODE -ne 0) { throw "Phase 542 corpus harness failed" }

# --- Phase 543: desktop JNI live-session round-trip --------------------------------- #
$SessionTestClass = "fuaran.core.SessionTestKt"
$HasSessionTest = Get-ChildItem -Recurse -Path (Join-Path $Repo "fuaran-core\src\test\kotlin") -Filter "SessionTest.kt" -ErrorAction SilentlyContinue
if ($HasSessionTest) {
    Write-Host "`n== Phase 543 :: desktop JNI session round-trip ==" -ForegroundColor Cyan
    $nativeDll = & (Join-Path $Repo "dev-scripts\build-native-desktop.ps1") -Repo $Repo -ClassesDir $ClassesDir -JniGenDir $JniGenDir
    if ($LASTEXITCODE -ne 0 -or -not $nativeDll) {
        Write-Host "SKIP: desktop JNI leg — Rust toolchain / C compiler unavailable (see message above)." -ForegroundColor Yellow
    } else {
        $libDir = Split-Path $nativeDll -Parent
        $env:Path = "$libDir;$env:Path"
        & $Java "-Dfuaran.lib=$nativeDll" -cp $Classpath $SessionTestClass
        if ($LASTEXITCODE -ne 0) { throw "Phase 543 session round-trip failed" }
    }
}

# --- Android packaging leg (opt-in) ------------------------------------------------- #
if ($Package) {
    Write-Host "`nSKIP: Android jniLibs/AAR packaging requires the Android SDK/NDK + cargo-ndk, which are absent on this machine. Build the per-ABI .so legs on a machine with the Android NDK (mirror fuaran-rs run.ps1 -CrossTargets/-Package)." -ForegroundColor Yellow
}

Write-Host "`nAll available legs green." -ForegroundColor Green
