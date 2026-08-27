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
  Build the Android per-ABI native libraries (Rust core via cargo-ndk + the JNI shim via
  the NDK per-ABI clang) into the `fuaran-core` `jniLibs/` layout and assemble a
  `fuaran-core.aar`, verifying per-ABI page alignment (16KB on the 64-bit ABIs). Requires
  the Android NDK (`ANDROID_NDK_HOME`) + cargo-ndk + the Rust android targets; the leg
  skips cleanly with a named message when they are absent (mirrors fuaran-rs
  `run.ps1 -CrossTargets`). See dev-scripts/build-android-aar.ps1.

.PARAMETER SkipRenderer
  Skip the Phase 544 Jetpack Compose render-coverage leg (the Gradle `:fuaran-renderer`
  Robolectric gate). That leg needs an Android SDK + the Gradle wrapper; it is
  auto-skipped when the SDK is absent, so this switch is only for forcing it off when the
  toolchain IS present.

  It does NOT skip the accessibility projection's mapping decisions or its corpus-driven
  drop-set leg: those assert a platform-neutral result type and run in the plain-JVM
  harnesses above, precisely so they are re-checked on whatever machine the next change is
  made from. What the Robolectric leg alone can answer — that the emitted semantics
  genuinely reach the Compose tree — stays behind this switch.
#>
[CmdletBinding()]
param(
    [switch] $SkipBuild,
    [switch] $SkipTests,
    [switch] $Package,
    [switch] $SkipRenderer
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

# The COMPOSE-FREE half of `:fuaran-renderer`, compiled into this plain-JVM build on purpose.
#
# The rest of that module cannot be: it is an Android library and its composables need the Compose
# compiler plugin plus an android.jar, which is exactly why the Robolectric gate below auto-skips on
# a machine with no Android SDK. But the projection's DECISIONS are not Compose — the binding
# resolver and the accessibility projection are ordinary logic over the decoded model — and a
# decision that can only be tested on one platform is a decision nobody re-checks. So the two files
# that carry no `androidx` import are listed here BY NAME and their assertions run in the ordinary
# gate; the thin Compose application half stays behind the Gradle leg.
#
# Adding an `androidx` import to either of these breaks this build LOUDLY, which is the intended
# guard: the split is only worth anything while the neutral half stays neutral.
#
# Kept OUT of `$MainKt` on purpose: that list is also what the `-Package` leg compiles into the
# `fuaran-core` AAR's classes.jar, and shipping two renderer classes inside the JNI-binding artefact
# would be a packaging accident rather than a decision. This set is for the test jar only.
$RendererNeutralKt = @(
    (Join-Path $Repo "fuaran-renderer\src\main\kotlin\fuaran\renderer\Binding.kt"),
    (Join-Path $Repo "fuaran-renderer\src\main\kotlin\fuaran\renderer\AccessibilityProjection.kt"),
    (Join-Path $Repo "fuaran-renderer\src\main\kotlin\fuaran\renderer\TrendSentiment.kt")
) | Where-Object { Test-Path $_ }
# The direct kotlinc build runs the two `main()`-driven harnesses (`CorpusDecodeTest`, `SessionTest`)
# via `java`; it deliberately compiles ONLY those, not every test file. The Gradle-only JUnit gates
# (e.g. `:fuaran-core` `InteractionRoundTripTest`, which pulls JUnit + `:fuaran-driver`) are built and
# run by Gradle — they are absent from this no-Gradle-binary classpath by design.
$TestKt = @(
    Get-ChildItem -Recurse -Path (Join-Path $Repo "fuaran-ui\src\test\kotlin") -Filter "CorpusDecodeTest.kt" -ErrorAction SilentlyContinue |
        ForEach-Object FullName
    Get-ChildItem -Recurse -Path (Join-Path $Repo "fuaran-core\src\test\kotlin") -Filter "SessionTest.kt" -ErrorAction SilentlyContinue |
        ForEach-Object FullName
    # The accessibility projection's two JUnit-free harnesses — the mapping decisions and the
    # corpus-driven leg. They assert the platform-neutral result type, so they belong here rather
    # than behind the Android-SDK gate; the Robolectric leg runs the same mapping set through a
    # one-line delegating test and keeps the reachability assertions only it can answer.
    Get-ChildItem -Recurse -Path (Join-Path $Repo "fuaran-renderer\src\test\kotlin") -Filter "AccessibilityProjectionHarness.kt" -ErrorAction SilentlyContinue |
        ForEach-Object FullName
    Get-ChildItem -Recurse -Path (Join-Path $Repo "fuaran-renderer\src\test\kotlin") -Filter "AccessibilityCorpusHarness.kt" -ErrorAction SilentlyContinue |
        ForEach-Object FullName
    # The trend-sentiment projection's decisions (WIRE_FORMAT 3.6.1 — sign x polarity, the glyph
    # vocabulary, the drop case). Same split and same reason as the two above: `TrendSentiment.kt`
    # carries no Compose import precisely so its decisions are re-checkable on the machine they are
    # changed from, rather than only on the box holding the Android SDK.
    Get-ChildItem -Recurse -Path (Join-Path $Repo "fuaran-renderer\src\test\kotlin") -Filter "TrendSentimentHarness.kt" -ErrorAction SilentlyContinue |
        ForEach-Object FullName
)
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
    $ktArgs += $RendererNeutralKt
    $ktArgs += $TestKt
    if ($JavaSrc) { $ktArgs += @("-classpath", $ClassesDir) }
    $ktArgs += @("-include-runtime", "-d", $Jar)
    Write-Host "kotlinc :: $($MainKt.Count) main + $($RendererNeutralKt.Count) neutral-renderer + $($TestKt.Count) test file(s)"
    & $Kotlinc @ktArgs
    if ($LASTEXITCODE -ne 0) { throw "kotlinc failed" }
    Write-Host "Built $Jar" -ForegroundColor Green
}

if ($SkipTests) { Write-Host "Tests skipped."; exit 0 }

$Classpath = if ($JavaSrc) { "$Jar;$ClassesDir" } else { $Jar }

if ($Corpus) { $env:FUARAN_CORPUS = $Corpus.Path }

# --- The accessibility projection's platform-neutral half --------------------------- #
# The mapping decisions and the drop set, asserted where the ordinary gate runs rather than only on
# a machine carrying the Android SDK.
#
# AHEAD of the decode harness deliberately. Each leg below aborts the run on its first failure, so
# leg order decides what a standing failure can MASK: with these last, any red in the decode
# harness — including a reject vector this surface is known not to refuse yet — would stop a
# mapping regression from ever being reported, and the run would blame the wrong thing. These two
# depend on nothing the decode leg establishes (each decodes the fixtures it asserts on) and take
# under a second, so putting them first costs nothing and buys an independent answer.
Write-Host "`n== accessibility projection (platform-neutral) ==" -ForegroundColor Cyan
& $Java -cp $Classpath "fuaran.renderer.AccessibilityProjectionHarnessKt"
if ($LASTEXITCODE -ne 0) { throw "accessibility mapping harness failed" }
& $Java -cp $Classpath "fuaran.renderer.AccessibilityCorpusHarnessKt"
if ($LASTEXITCODE -ne 0) { throw "accessibility corpus projection harness failed" }

# --- The trend-sentiment projection's platform-neutral half -------------------------- #
# Same placement argument as the two legs above: it establishes nothing the decode harness needs and
# depends on nothing the decode harness establishes, so running it ahead keeps a standing red there
# from masking a regression in the composition rule.
Write-Host "`n== trend sentiment projection (platform-neutral) ==" -ForegroundColor Cyan
& $Java -cp $Classpath "fuaran.renderer.TrendSentimentHarnessKt"
if ($LASTEXITCODE -ne 0) { throw "trend sentiment projection harness failed" }

# --- Phase 542: corpus render-coverage harness -------------------------------------- #
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

# --- Phase 544: Jetpack Compose render-coverage leg (Gradle + Robolectric) ---------- #
# The render floor is an Android library (Compose), so its coverage gate runs through the
# Gradle wrapper under Robolectric (headless JVM, no emulator). Auto-skips when the Android
# SDK is absent so the repo stays green on a plain JDK-only box.
function Find-AndroidSdk {
    foreach ($env in @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT)) {
        if ($env -and (Test-Path (Join-Path $env "platforms"))) { return $env }
    }
    $lp = Join-Path $Repo "local.properties"
    if (Test-Path $lp) {
        $line = Get-Content $lp | Where-Object { $_ -match '^\s*sdk\.dir\s*=' } | Select-Object -First 1
        if ($line) {
            $dir = (($line -replace '^\s*sdk\.dir\s*=', '').Trim() -replace '\\\\', '\' -replace '\\:', ':')
            if (Test-Path (Join-Path $dir "platforms")) { return $dir }
        }
    }
    return $null
}

if (-not $SkipTests -and -not $SkipRenderer) {
    Write-Host "`n== Phase 544 :: Jetpack Compose render-coverage (Gradle + Robolectric) ==" -ForegroundColor Cyan
    $Gradlew = Join-Path $Repo "gradlew.bat"
    $Sdk = Find-AndroidSdk
    if (-not (Test-Path $Gradlew)) {
        Write-Host "SKIP: no Gradle wrapper (gradlew.bat) — renderer leg needs the Gradle build." -ForegroundColor Yellow
    }
    elseif (-not $Sdk) {
        Write-Host "SKIP: no Android SDK (set ANDROID_HOME or local.properties sdk.dir) — renderer leg needs it." -ForegroundColor Yellow
    }
    else {
        Write-Host "Android SDK :: $Sdk"
        & $Gradlew ":fuaran-renderer:testDebugUnitTest" "--console=plain"
        if ($LASTEXITCODE -ne 0) { throw "Phase 544 render-coverage gate failed" }
        Write-Host "Phase 544 render-coverage gate green." -ForegroundColor Green

        # --- Phase 545: interaction round-trip + server-driven driver + Material tone bridge ------ #
        # The renderer gate above already covers the Phase 545 Theme + Interaction Robolectric tests
        # (they are in the `:fuaran-renderer` unit-test source set). Here we add the pure-JVM driver
        # gate and the live-native interaction round-trip. The interaction leg reuses the desktop shim
        # `$nativeDll` the Phase 543 leg built; when it is absent the Gradle test cleanly skips.
        Write-Host "`n== Phase 545 :: server-driven driver gate (Gradle) ==" -ForegroundColor Cyan
        & $Gradlew ":fuaran-driver:test" "--console=plain"
        if ($LASTEXITCODE -ne 0) { throw "Phase 545 driver gate failed" }

        Write-Host "`n== Phase 545 :: live interaction round-trip (Gradle + JNI) ==" -ForegroundColor Cyan
        if ($nativeDll) {
            & $Gradlew ":fuaran-core:test" "-Pfuaran.lib=$nativeDll" "--console=plain"
            if ($LASTEXITCODE -ne 0) { throw "Phase 545 live interaction round-trip failed" }
            Write-Host "Phase 545 live interaction round-trip green." -ForegroundColor Green
        }
        else {
            & $Gradlew ":fuaran-core:test" "--console=plain"
            if ($LASTEXITCODE -ne 0) { throw "Phase 545 interaction gate failed" }
            Write-Host "Phase 545 interaction round-trip skipped cleanly (desktop native shim absent)." -ForegroundColor Yellow
        }

        Write-Host "`n== Phase 545 :: sample app build (assembleDebug) ==" -ForegroundColor Cyan
        & $Gradlew ":samples:assembleDebug" "--console=plain"
        if ($LASTEXITCODE -ne 0) { throw "Phase 545 sample app build failed" }
        Write-Host "Phase 545 sample app assembled." -ForegroundColor Green
    }
}

# --- Android packaging leg (opt-in) ------------------------------------------------- #
if ($Package) {
    Write-Host "`n== Phase 543 :: Android per-ABI .so + AAR packaging ==" -ForegroundColor Cyan
    # A runtime-free main-classes tree for the AAR's classes.jar (no -include-runtime, no tests).
    $AarClasses = Join-Path $BuildDir "aar-classes"
    Remove-Item -Recurse -Force $AarClasses -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force $AarClasses | Out-Null
    if ($JavaSrc) {
        & $Javac -h $JniGenDir -d $AarClasses @JavaSrc
        if ($LASTEXITCODE -ne 0) { throw "javac (aar classes) failed" }
    }
    $mainCp = if ($JavaSrc) { @("-classpath", $AarClasses) } else { @() }
    & $Kotlinc @MainKt @mainCp "-d" $AarClasses
    if ($LASTEXITCODE -ne 0) { throw "kotlinc (aar classes) failed" }

    $aar = & (Join-Path $Repo "dev-scripts\build-android-aar.ps1") -Repo $Repo -ClassesDir $AarClasses
    if ($LASTEXITCODE -ne 0) { throw "Android AAR packaging failed" }
    if (-not $aar) {
        Write-Host "Android packaging skipped (NDK/cargo-ndk absent) — see message above." -ForegroundColor Yellow
    }
}

Write-Host "`nAll available legs green." -ForegroundColor Green
