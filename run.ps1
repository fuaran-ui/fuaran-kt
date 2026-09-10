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

# --- Failure COLLECTION across the verification legs -------------------------------- #
#
# Every leg below used to `throw` on its first non-zero exit, and leg ORDER was carefully
# argued from that: put the cheap platform-neutral harnesses ahead of the decode harness so a
# standing decode red cannot hide a mapping regression. The argument was sound and the shape
# it rests on is not, because ordering can only ever decide WHICH leg does the hiding. It was
# observed pointing the other way on 2026-09-03: with the render-obligation leg failing, the
# 576-check corpus decode harness BELOW it did not execute at all, and getting a number out of
# the decoder meant invoking `fuaran.ui.CorpusDecodeTestKt` by hand.
#
# **A note on that instance, because the record should not overstate it.** The obligation leg
# is GREEN today: its bar is "every declared obligation is asserted or declared exempt with a
# reason", and the two obligations this repo still owes (`FileUpload/picker-always-present`,
# `Modal/aria-modal-only-when-blocking`) report UNCHECKED without failing it. So the specific
# masking that prompted this is not currently happening. The shape that allowed it is
# unchanged, and it is not hypothetical: on the first run of this rewrite, in a worktree where
# the corpus was not resolvable, the corpus leg failed — and under the old shape that single
# failure would have taken the fuzz leg, the JNI round trip, the driver gate and the Gradle
# legs with it, none of them reported.
#
# Collecting is what stops any leg hiding another: each runs, each reports, and the run fails
# ONCE at the end naming every red. The careful order above is kept — it now decides reading
# order rather than reachability.
#
# `Invoke-Leg` is therefore the shape for a leg that ESTABLISHES NOTHING later legs need.
# Compilation is not one of those and still throws: with no jar there is nothing to run.
$script:FailedLegs = [System.Collections.Generic.List[string]]::new()

# The last leg's verdict. A SCRIPT VARIABLE rather than the function's return value, and that
# is load-bearing rather than a style choice: `& $Action` captures the leg's native stdout into
# the function's OUTPUT STREAM, so a `return $true/$false` comes back as an array of every line
# java printed with the boolean on the end — which is truthy either way. The first draft of this
# read the verdict that way and the conditional fuzz leg below ran on a red corpus leg regardless,
# looking exactly like a working conditional. Do not "simplify" this back to a return value.
$script:LastLegGreen = $true

function Invoke-Leg {
    <#
      .SYNOPSIS
        Run one verification leg, record a failure, and CONTINUE.
      .DESCRIPTION
        Sets $script:LastLegGreen; returns nothing usable. Read the verdict from that variable
        immediately after the call — see the note above it.
      .PARAMETER Name
        What appears in the end-of-run summary. Make it the leg a reader would grep for.
      .PARAMETER Action
        The leg. It must end in a native command, so that $LASTEXITCODE is its exit code.
    #>
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)] [string] $Name,
        [Parameter(Mandatory)] [scriptblock] $Action
    )
    # Seeded for the reason the desktop JNI leg below records: PowerShell does not reset
    # $LASTEXITCODE between commands, so a leg that runs no native command would otherwise be
    # graded on whatever the last unrelated one set.
    $global:LASTEXITCODE = 0
    & $Action
    if ($LASTEXITCODE -ne 0) {
        $script:FailedLegs.Add("$Name (exit $LASTEXITCODE)")
        Write-Host "FAIL: $Name — exit $LASTEXITCODE. Continuing; the run fails at the end." -ForegroundColor Red
        $script:LastLegGreen = $false
        return
    }
    $script:LastLegGreen = $true
}

function Assert-AllLegsGreen {
    <#
      .SYNOPSIS
        The single point of failure for every collected leg. Call it LAST.
    #>
    if ($script:FailedLegs.Count -eq 0) { return }
    Write-Host "`n=======================================================================" -ForegroundColor Red
    Write-Host "$($script:FailedLegs.Count) leg(s) FAILED — every leg ran, so this list is complete:" -ForegroundColor Red
    foreach ($leg in $script:FailedLegs) { Write-Host "  - $leg" -ForegroundColor Red }
    Write-Host "=======================================================================" -ForegroundColor Red
    exit 1
}

function Resolve-Tool([string] $Name, [string[]] $Fallbacks) {
    $cmd = Get-Command $Name -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($cmd) { return $cmd.Source }
    foreach ($f in $Fallbacks) { if (Test-Path $f) { return $f } }
    return $null
}

# Tool resolution is PLATFORM-NEUTRAL, not Windows-shaped.
#
# This script is a `pwsh` script and PowerShell 7 runs on macOS and Linux, where
# the JDK's launchers are `bin/java` (no extension), the Kotlin compiler is
# `kotlinc` (no `.bat`), and the Gradle wrapper is `./gradlew`. Hard-coding the
# Windows spellings did not make those platforms fail — it made them SKIP, with
# "no JDK ... nothing to build", on a box that had a perfectly good JDK. A gate
# that reports "nothing to build" on a machine that can build everything is the
# same lie as one that reports green having run nothing.
$IsWin = $IsWindows -or ($null -eq $IsWindows)  # $IsWindows is undefined on Windows PowerShell 5.1
$ExeSuffix = if ($IsWin) { '.exe' } else { '' }

function Resolve-JavaTool([string] $JavaHome, [string] $Name) {
    # Prefer JAVA_HOME, fall back to PATH — a JDK installed by a package manager
    # is often on PATH with no JAVA_HOME set at all.
    if ($JavaHome) {
        $candidate = Join-Path (Join-Path $JavaHome 'bin') ($Name + $ExeSuffix)
        if (Test-Path $candidate) { return $candidate }
    }
    return (Resolve-Tool $Name @())
}

$JavaHome = if ($IsWin) { [Environment]::GetEnvironmentVariable('JAVA_HOME', 'Machine') } else { $null }
if (-not $JavaHome) { $JavaHome = $env:JAVA_HOME }
$Java = Resolve-JavaTool $JavaHome 'java'
$Javac = Resolve-JavaTool $JavaHome 'javac'
$Kotlinc = Resolve-Tool "kotlinc" @(
    "C:\Program Files\kotlinc\bin\kotlinc.bat",
    "/usr/local/bin/kotlinc",
    "/opt/homebrew/bin/kotlinc"
)

# --- C-ABI header drift (the reference-stylesheet shape) ---------------------------- #
# `fuaran-core/src/main/jni/include/fuaran.h` is a COPY. The reference lives in the Rust core, the
# JNI shim `#include`s the copy rather than re-declaring the ABI, and this leg is what keeps the
# copy honest: byte-compare it against the reference when the sibling checkout is present.
#
# AHEAD of the toolchain skip below, deliberately. This is a text comparison — it needs no JDK, no
# Kotlin and no Rust — so a box with none of them still answers the one question it CAN answer,
# rather than exiting 0 having checked nothing at all.
#
# NOT CHECKED rather than a silent pass when the sibling is absent, and that distinction is why
# this prints anything: a single-repo CI checkout has no sibling, and a green line saying nothing
# about the header would read to every future log reader as "the copy is current". It is
# deliberately not a hard failure either — a contributor with only this repo cloned is not doing
# anything wrong.
$HeaderCopy = Join-Path $Repo "fuaran-core\src\main\jni\include\fuaran.h"
$HeaderRef = Join-Path $Repo "..\fuaran-rs\include\fuaran.h"
Write-Host "`n== C-ABI header (fuaran.h) drift ==" -ForegroundColor Cyan
if (-not (Test-Path $HeaderCopy)) {
    throw "fuaran.h copy missing at $HeaderCopy — the JNI shim includes it and cannot compile without it."
}
elseif (-not (Test-Path $HeaderRef)) {
    Write-Host "NOT CHECKED: no sibling core checkout at $HeaderRef — the copy was not compared." -ForegroundColor Yellow
}
elseif ((Get-FileHash -Algorithm SHA256 $HeaderCopy).Hash -ne (Get-FileHash -Algorithm SHA256 $HeaderRef).Hash) {
    throw @"
fuaran.h has drifted from the reference.

  copy:      $HeaderCopy
  reference: $HeaderRef

The copy is GENERATED — regenerate it rather than hand-editing either side:
  Copy-Item '$HeaderRef' '$HeaderCopy'

Then read the diff before committing. A change to the C-ABI is a change to what the JNI shim
compiles against, and the whole point of the copy is that you meet it here rather than at run time
on a device.
"@
}
else {
    Write-Host "fuaran.h byte-identical to the reference." -ForegroundColor Green
}

if (-not $Java -or -not $Kotlinc) {
    $missing = @()
    if (-not $Java) { $missing += 'a JDK (java; JAVA_HOME or PATH)' }
    if (-not $Kotlinc) { $missing += 'kotlinc' }
    Write-Host ("SKIP: {0} not found — nothing to build. (A JDK 21 + Kotlin 2.x are required.)" -f ($missing -join ' and ')) -ForegroundColor Yellow
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
    (Join-Path $Repo "fuaran-renderer\src\main\kotlin\fuaran\renderer\TrendSentiment.kt"),
    # Phase 1541 — the interaction host's coalescing write queue. Same split and the same reason: what
    # coalesces, what does not and what "settled" means are ordinary logic over a queue, and typed in
    # Compose vocabulary they would be provable only on a box carrying the Android SDK. The host's thin
    # wiring (executors, publishing Compose state) stays behind the Robolectric leg.
    (Join-Path $Repo "fuaran-renderer\src\main\kotlin\fuaran\renderer\WriteBackQueue.kt"),
    # Phase 1548 — the upload-ceiling projection. Same split and the same reason: whether a declared
    # ceiling is recorded, and that its VALUE is withheld from a tier that cannot enforce it, is
    # ordinary logic over the decoded model; in Compose vocabulary it would be provable only on a box
    # carrying the Android SDK.
    (Join-Path $Repo "fuaran-renderer\src\main\kotlin\fuaran\renderer\UploadCeilings.kt")
) | Where-Object { Test-Path $_ }
# The direct kotlinc build runs the two `main()`-driven harnesses (`CorpusDecodeTest`, `SessionTest`)
# via `java`; it deliberately compiles ONLY those, not every test file. The Gradle-only JUnit gates
# (e.g. `:fuaran-core` `InteractionRoundTripTest`, which pulls JUnit + `:fuaran-driver`) are built and
# run by Gradle — they are absent from this no-Gradle-binary classpath by design.
$TestKt = @(
    Get-ChildItem -Recurse -Path (Join-Path $Repo "fuaran-ui\src\test\kotlin") -Filter "CorpusDecodeTest.kt" -ErrorAction SilentlyContinue |
        ForEach-Object FullName
    # The decoder-robustness fuzz leg (Phase 1023's family, extended here by Phase 1540 H-30). Named
    # explicitly rather than picked up by a recursive glob, because this list deliberately compiles
    # only the `main()`-driven harnesses and not every file under `src/test/kotlin` — see the note
    # above. A new harness that is not listed here compiles nowhere and runs nowhere.
    Get-ChildItem -Recurse -Path (Join-Path $Repo "fuaran-ui\src\test\kotlin") -Filter "DecoderFuzzTest.kt" -ErrorAction SilentlyContinue |
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
    # The number-formatting goldens (Phase 1541). Same split and the same reason as the three above:
    # `formatCellValue` and `resolveDouble` are ordinary logic over the decoded model, and the defect
    # they close is INVISIBLE under a POSIX default locale — `String.format` with no locale follows the
    # JVM default, so a decimal-comma machine rendered `GBP 1234,50`. The harness sets
    # `Locale.GERMANY` for its own run, so the assertion is meaningful on this box rather than only on
    # a European one.
    Get-ChildItem -Recurse -Path (Join-Path $Repo "fuaran-renderer\src\test\kotlin") -Filter "NumberFormatHarness.kt" -ErrorAction SilentlyContinue |
        ForEach-Object FullName
    # The write-back queue's coalescing + settlement decisions (Phase 1541).
    Get-ChildItem -Recurse -Path (Join-Path $Repo "fuaran-renderer\src\test\kotlin") -Filter "WriteBackQueueHarness.kt" -ErrorAction SilentlyContinue |
        ForEach-Object FullName
    # The render-obligation gate (WIRE_FORMAT.md 13) — the reader, the reporting surface, the
    # checker/exemption registries and every gate check over them. Same split and same reason as the
    # three above: the classification half is ordinary logic over a generated artefact, so it runs on
    # whatever machine the next change is made from, while the checkers that must observe a
    # composition stay in the Robolectric leg (`RenderObligationTest`) and are DECLARED here by key.
    Get-ChildItem -Recurse -Path (Join-Path $Repo "fuaran-renderer\src\test\kotlin") -Filter "RenderObligations.kt" -ErrorAction SilentlyContinue |
        ForEach-Object FullName
    Get-ChildItem -Recurse -Path (Join-Path $Repo "fuaran-renderer\src\test\kotlin") -Filter "RenderObligationHarness.kt" -ErrorAction SilentlyContinue |
        ForEach-Object FullName
    # Phase 1548 — the upload ceilings (WIRE_FORMAT.md 3.6.23): the decode floor's positivity rule
    # with a corrected twin beside each corpus refusal, and the value-free read-markers that are the
    # supporting evidence for this surface's declared exemption from
    # `FileUpload/ceiling-recorded-never-enforced`. Both halves are ordinary logic over the decoded
    # model, so both run here rather than only on the box holding the Android SDK.
    Get-ChildItem -Recurse -Path (Join-Path $Repo "fuaran-renderer\src\test\kotlin") -Filter "UploadCeilingHarness.kt" -ErrorAction SilentlyContinue |
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

# `;` is the classpath separator on Windows and `:` everywhere else. Hard-coding
# `;` did not fail loudly off Windows — the JVM read the whole string as ONE path
# entry, so the JNI bridge classes silently were not on the classpath.
$Classpath = if ($JavaSrc) { $Jar + [IO.Path]::PathSeparator + $ClassesDir } else { $Jar }

if ($Corpus) { $env:FUARAN_CORPUS = $Corpus.Path }

# --- The accessibility projection's platform-neutral half --------------------------- #
# The mapping decisions and the drop set, asserted where the ordinary gate runs rather than only on
# a machine carrying the Android SDK.
#
# AHEAD of the decode harness deliberately, and the ORDER is now about READING rather than
# reachability: since Phase 1654 each leg runs and reports through `Invoke-Leg`, and the run
# fails once at the end naming every red. Leg order used to decide what a standing failure could
# MASK, which meant it could only ever choose the direction of the masking — and it chose wrong
# in the end, because the obligation leg below is red by design while two obligations are owed.
# These legs still come first because they are cheap, independent (each decodes the fixtures it
# asserts on) and answer a different question from the decoder's.
Invoke-Leg "accessibility mapping harness" {
    Write-Host "`n== accessibility projection (platform-neutral) ==" -ForegroundColor Cyan
    & $Java -cp $Classpath "fuaran.renderer.AccessibilityProjectionHarnessKt"
}
Invoke-Leg "accessibility corpus projection harness" {
    & $Java -cp $Classpath "fuaran.renderer.AccessibilityCorpusHarnessKt"
}

# --- The trend-sentiment projection's platform-neutral half -------------------------- #
# It establishes nothing the decode harness needs and depends on nothing the decode harness
# establishes, so it answers independently whatever the decoder is doing.
Invoke-Leg "trend sentiment projection harness" {
    Write-Host "`n== trend sentiment projection (platform-neutral) ==" -ForegroundColor Cyan
    & $Java -cp $Classpath "fuaran.renderer.TrendSentimentHarnessKt"
}

# --- Phase 1541: number formatting under a non-POSIX locale -------------------------- #
# The harness sets `Locale.GERMANY` itself and restores the default afterwards, so the goldens are
# asserted against the locale that USED to break them rather than against whatever this box happens
# to be configured for — a gate that only ever runs under a decimal-point locale cannot see the
# defect this closes.
Invoke-Leg "number-format locale-invariance harness" {
    Write-Host "`n== number formatting :: locale invariance (platform-neutral) ==" -ForegroundColor Cyan
    & $Java -cp $Classpath "fuaran.renderer.NumberFormatHarnessKt"
}

# --- Phase 1541: the interaction host's write-back queue ----------------------------- #
# Coalescing per state key, latest-wins, and what "settled" means. Platform-neutral for the reason the
# legs above record; the Robolectric leg keeps only the claim that needs a host — that the round trip
# leaves the caller's thread.
Invoke-Leg "write-back queue harness" {
    Write-Host "`n== write-back queue :: coalescing + settlement (platform-neutral) ==" -ForegroundColor Cyan
    & $Java -cp $Classpath "fuaran.renderer.WriteBackQueueHarnessKt"
}

# --- Render-obligation conformance (WIRE_FORMAT.md 13) ------------------------------ #
# The checkable remainder of the render contract, enumerated from the corpus's generated
# `render-fidelity.json` rather than from a list beside the checkers — so a newly declared
# obligation arrives here as a claim with no checker and turns this leg red.
#
# THIS is the leg whose failure prompted the collection above. It PASSES today — its bar is
# "asserted or declared exempt with a reason", and the two obligations this repo still owes
# (`FileUpload/picker-always-present`, `Modal/aria-modal-only-when-blocking`) report UNCHECKED
# without failing it. Under the old abort-on-first-failure shape, a red here made every leg
# below unreachable, the 576-check corpus decode harness included.
Invoke-Leg "render-obligation conformance gate" {
    Write-Host "`n== render obligations (WIRE_FORMAT.md 13) ==" -ForegroundColor Cyan
    & $Java -cp $Classpath "fuaran.renderer.RenderObligationHarnessKt"
}

# --- Phase 1548: the upload ceilings (WIRE_FORMAT.md 3.6.23) ------------------------ #
# The positivity floor at both members — asserted beside a CORRECTED TWIN for each corpus refusal,
# because a reject vector on its own cannot tell a decoder that refuses the malformed value from one
# that refuses the member outright — and the value-free read-markers this floor may show for a
# ceiling it cannot enforce.
Invoke-Leg "upload-ceiling gate" {
    Write-Host "`n== upload ceilings (WIRE_FORMAT.md 3.6.23) ==" -ForegroundColor Cyan
    & $Java -cp $Classpath "fuaran.renderer.UploadCeilingHarnessKt"
}

# --- Phase 542: corpus render-coverage harness -------------------------------------- #
Invoke-Leg "Phase 542 corpus harness" {
    Write-Host "`n== Phase 542 :: corpus render-coverage ==" -ForegroundColor Cyan
    & $Java -cp $Classpath "fuaran.ui.CorpusDecodeTestKt"
}
$corpusLegGreen = $script:LastLegGreen

# --- Phase 1540 (H-30): decoder robustness fuzz ------------------------------------- #
# The corpus leg above asserts the malformed inputs somebody thought of; this one asserts the
# PROPERTY they are evidence for - that no input escapes as anything but the typed error.
#
# It is the ONE leg that is conditional, and the condition is its own recorded argument rather
# than the abort-on-first-failure shape everything else here has shed: a fuzz counterexample is
# far harder to read when a named corpus vector is already failing for a reason the fuzz will
# rediscover as noise. So a red corpus leg SKIPS this one, by name and with the reason printed —
# which is not the same as being hidden by it. The run is already failing on the corpus leg, and
# the summary says so.
if ($corpusLegGreen) {
    Invoke-Leg "Phase 1540 decoder fuzz" {
        Write-Host "`n== Phase 1540 :: decoder robustness fuzz ==" -ForegroundColor Cyan
        & $Java -cp $Classpath "fuaran.ui.DecoderFuzzTestKt"
    }
}
else {
    Write-Host "`n== Phase 1540 :: decoder robustness fuzz ==" -ForegroundColor Cyan
    Write-Host ("SKIP: the corpus leg above is RED. A fuzz counterexample read against a failing named " +
        "vector is noise — fix the corpus leg and re-run. This leg is skipped, not passed.") -ForegroundColor Yellow
}

# --- Phase 543: desktop JNI live-session round-trip --------------------------------- #
$SessionTestClass = "fuaran.core.SessionTestKt"
$HasSessionTest = Get-ChildItem -Recurse -Path (Join-Path $Repo "fuaran-core\src\test\kotlin") -Filter "SessionTest.kt" -ErrorAction SilentlyContinue
if ($HasSessionTest) {
    Write-Host "`n== Phase 543 :: desktop JNI session round-trip ==" -ForegroundColor Cyan
    # SEED $LASTEXITCODE before the child script. PowerShell does not reset it
    # between commands: a `.ps1` that runs no native command leaves whatever the
    # LAST native command set, so this test read a stale code from an unrelated
    # step. On the first run of a fresh session it is $null, and `$null -ne 0` is
    # TRUE — so a perfectly good native build was discarded as a failure.
    $LASTEXITCODE = 0
    $nativeDll = & (Join-Path $Repo "dev-scripts/build-native-desktop.ps1") -Repo $Repo -ClassesDir $ClassesDir -JniGenDir $JniGenDir
    if ($LASTEXITCODE -ne 0 -or -not $nativeDll) {
        Write-Host "SKIP: desktop JNI leg — Rust toolchain / C compiler unavailable (see message above)." -ForegroundColor Yellow
    } else {
        $libDir = Split-Path $nativeDll -Parent
        $env:Path = "$libDir;$env:Path"
        & $Java "-Dfuaran.lib=$nativeDll" -cp $Classpath $SessionTestClass
        if ($LASTEXITCODE -ne 0) { $script:FailedLegs.Add("Phase 543 session round-trip (exit $LASTEXITCODE)") }
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

# `gradlew.bat` on Windows, `./gradlew` everywhere else. With only the `.bat`
# spelling every non-Windows box reported "no Gradle wrapper" and skipped the
# renderer gate — the one leg that exists because it cannot be run locally on
# the reference Windows box.
$GradlewName = if ($IsWin) { "gradlew.bat" } else { "gradlew" }
$Gradlew = Join-Path $Repo $GradlewName

# --- Phase 545/1541: server-driven driver gate (Gradle, PURE JVM) -------------------- #
# HOISTED OUT of the Android-SDK branch below (Phase 1541). `:fuaran-driver` is a plain-JVM module —
# `HttpURLConnection`, an in-JVM fixture server, a fake session, no Compose and no android.jar — and
# it configures and runs perfectly on a box with no Android SDK. Nested inside the SDK branch it did
# not run on the reference dev box at all, so the driver's transport bounds, its idle policy and its
# reply channel were provable only in CI while the file sat open in front of whoever was changing it.
# The Android SDK is a prerequisite of the COMPOSE leg; making it a prerequisite of this one made a
# runnable gate unrunnable.
#
# It stays behind $SkipRenderer's sibling switch NOWHERE: this is not the renderer leg. It skips only
# when the Gradle wrapper is absent, which is the one thing it genuinely needs.
if (-not $SkipTests) {
    Write-Host "`n== Phase 545/1541 :: server-driven driver gate (Gradle, pure JVM) ==" -ForegroundColor Cyan
    if (-not (Test-Path $Gradlew)) {
        Write-Host "SKIP: no Gradle wrapper ($GradlewName) — the driver gate needs the Gradle build." -ForegroundColor Yellow
    }
    else {
        # One leg here takes ~35 s on purpose: it proves an idle op stream survives past the socket
        # read timeout, which only a genuinely quiet socket can show.
        & $Gradlew ":fuaran-driver:test" "--console=plain"
        if ($LASTEXITCODE -ne 0) { $script:FailedLegs.Add("Phase 545/1541 driver gate (exit $LASTEXITCODE)") }
        Write-Host "Driver gate green." -ForegroundColor Green
    }
}

if (-not $SkipTests -and -not $SkipRenderer) {
    Write-Host "`n== Phase 544 :: Jetpack Compose render-coverage (Gradle + Robolectric) ==" -ForegroundColor Cyan
    $Sdk = Find-AndroidSdk
    if (-not (Test-Path $Gradlew)) {
        Write-Host "SKIP: no Gradle wrapper (gradlew.bat) — renderer leg needs the Gradle build." -ForegroundColor Yellow
    }
    elseif (-not $Sdk) {
        Write-Host "SKIP: no Android SDK (set ANDROID_HOME or local.properties sdk.dir) — renderer leg needs it." -ForegroundColor Yellow
    }
    else {
        Write-Host "Android SDK :: $Sdk"
        # Forward the corpus EXPLICITLY, as the plain-JVM legs above get it via
        # $env:FUARAN_CORPUS. A Gradle test worker is a separate JVM and the
        # renderer harnesses' own fallback is a relative-path walk from whatever
        # directory that worker happens to run in — which is how the render
        # gate came to `return` green on a machine that plainly had the corpus.
        $corpusArgs = if ($Corpus) { @("-Dfuaran.corpus=$($Corpus.Path)") } else { @() }
        & $Gradlew ":fuaran-renderer:testDebugUnitTest" "--console=plain" @corpusArgs
        if ($LASTEXITCODE -ne 0) { $script:FailedLegs.Add("Phase 544 render-coverage gate (exit $LASTEXITCODE)") }
        Write-Host "Phase 544 render-coverage gate green." -ForegroundColor Green

        # --- Phase 545: interaction round-trip + Material tone bridge ---------------------------- #
        # The renderer gate above already covers the Phase 545 Theme + Interaction Robolectric tests
        # (they are in the `:fuaran-renderer` unit-test source set). Here we add the live-native
        # interaction round-trip. It reuses the desktop shim `$nativeDll` the Phase 543 leg built;
        # when it is absent the Gradle test cleanly skips. (The driver gate ran ABOVE, outside this
        # branch — it needs no Android SDK.)
        Write-Host "`n== Phase 545 :: live interaction round-trip (Gradle + JNI) ==" -ForegroundColor Cyan
        if ($nativeDll) {
            & $Gradlew ":fuaran-core:test" "-Pfuaran.lib=$nativeDll" "--console=plain"
            if ($LASTEXITCODE -ne 0) { $script:FailedLegs.Add("Phase 545 live interaction round-trip (exit $LASTEXITCODE)") }
            Write-Host "Phase 545 live interaction round-trip green." -ForegroundColor Green
        }
        else {
            & $Gradlew ":fuaran-core:test" "--console=plain"
            if ($LASTEXITCODE -ne 0) { $script:FailedLegs.Add("Phase 545 interaction gate (exit $LASTEXITCODE)") }
            Write-Host "Phase 545 interaction round-trip skipped cleanly (desktop native shim absent)." -ForegroundColor Yellow
        }

        Write-Host "`n== Phase 545 :: sample app build (assembleDebug) ==" -ForegroundColor Cyan
        & $Gradlew ":samples:assembleDebug" "--console=plain"
        if ($LASTEXITCODE -ne 0) { $script:FailedLegs.Add("Phase 545 sample app build (exit $LASTEXITCODE)") }
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

    # Seeded for the same reason as the desktop leg above.
    $LASTEXITCODE = 0
    $aar = & (Join-Path $Repo "dev-scripts/build-android-aar.ps1") -Repo $Repo -ClassesDir $AarClasses
    if ($LASTEXITCODE -ne 0) { throw "Android AAR packaging failed" }
    if (-not $aar) {
        Write-Host "Android packaging skipped (NDK/cargo-ndk absent) — see message above." -ForegroundColor Yellow
    }
}

Assert-AllLegsGreen

Write-Host "`nAll available legs green." -ForegroundColor Green
