#Requires -Version 7.0
<#
.SYNOPSIS
  Build the Phase 543 Android native packaging: per-ABI `.so`s (Rust core + JNI shim) into
  the fuaran-core `jniLibs/` layout, assembled into a `fuaran-core.aar`.

.DESCRIPTION
  For each Android ABI (arm64-v8a, armeabi-v7a, x86_64):
    1. `cargo ndk` cross-builds the fuaran-rs reference core -> libfuaran_rs.so, staged into
       fuaran-core/src/main/jniLibs/<abi>/ (16KB-page-aligned by the NDK linker).
    2. The hand-written JNI C shim (fuaran-core/src/main/jni/fuaran_jni.c) is cross-compiled
       with the NDK per-ABI clang -> libfuaran_jni.so, linking the per-ABI libfuaran_rs.so,
       with explicit 16KB page alignment.
  Then a minimal AAR (AndroidManifest.xml + classes.jar + R.txt + jni/<abi>/*.so) is zipped,
  and every packaged .so is verified 16KB-page-aligned via the NDK llvm-readelf.

  Skips cleanly (named message, returns nothing) when the Android NDK / cargo-ndk / Rust
  toolchain are absent, so the workspace stays green on a box with no Android toolchain —
  mirrors dev-scripts/build-native-desktop.ps1 and fuaran-rs run.ps1 -CrossTargets.

  Emits the absolute path of the assembled .aar on stdout on success.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)] [string] $Repo,
    [Parameter(Mandatory)] [string] $ClassesDir,   # runtime-free main .class tree (jarred as the AAR classes.jar)
    [int] $ApiLevel = 21
)

$ErrorActionPreference = "Stop"

function Skip([string] $Message) {
    Write-Host "SKIP (android): $Message" -ForegroundColor Yellow
}

function Find-Tool([string] $Name) {
    $c = Get-Command $Name -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($c) { return $c.Source }
    return $null
}

# --- Resolve the toolchain; skip cleanly if any piece is missing --------------------- #
$ndk = $env:ANDROID_NDK_HOME
if (-not $ndk) { $ndk = [Environment]::GetEnvironmentVariable('ANDROID_NDK_HOME', 'User') }
if (-not $ndk) { $ndk = $env:ANDROID_NDK_ROOT }
if (-not $ndk -or -not (Test-Path $ndk)) {
    Skip "ANDROID_NDK_HOME not set / not found — install the Android NDK and set ANDROID_NDK_HOME (mirror fuaran-rs run.ps1 -CrossTargets)."
    return
}
$cargo = Find-Tool "cargo"
$cargoNdk = Find-Tool "cargo-ndk"
if (-not $cargo -or -not $cargoNdk) {
    Skip "cargo / cargo-ndk not found — 'cargo install cargo-ndk' and add the Rust android targets to enable."
    return
}
$rsRepo = Resolve-Path (Join-Path $Repo "..\fuaran-rs") -ErrorAction SilentlyContinue
if (-not $rsRepo) { Skip "sibling fuaran-rs not found at ..\fuaran-rs."; return }

$ndkBin = Join-Path $ndk "toolchains\llvm\prebuilt\windows-x86_64\bin"
$readelf = Join-Path $ndkBin "llvm-readelf.exe"
if (-not (Test-Path $ndkBin)) { Skip "NDK llvm prebuilt toolchain missing under $ndkBin."; return }

$JavaHome = [Environment]::GetEnvironmentVariable('JAVA_HOME', 'Machine')
if (-not $JavaHome) { $JavaHome = $env:JAVA_HOME }
$jarTool = if ($JavaHome) { Join-Path $JavaHome "bin\jar.exe" } else { Find-Tool "jar" }
if (-not $jarTool -or -not (Test-Path $jarTool)) { Skip "JDK 'jar' tool not found (need JAVA_HOME) — cannot assemble classes.jar."; return }

# ABI -> (rust triple, NDK clang wrapper stem, expected LOAD alignment). Google's 16KB-page
# requirement is a 64-bit concern (arm64-v8a, x86_64); 32-bit armeabi-v7a uses 4KB pages.
$abis = @(
    @{ Abi = "arm64-v8a";   Triple = "aarch64-linux-android";   Clang = "aarch64-linux-android$ApiLevel-clang.cmd";   Align = "0x4000" }
    @{ Abi = "armeabi-v7a"; Triple = "armv7-linux-androideabi"; Clang = "armv7a-linux-androideabi$ApiLevel-clang.cmd"; Align = "0x1000" }
    @{ Abi = "x86_64";      Triple = "x86_64-linux-android";    Clang = "x86_64-linux-android$ApiLevel-clang.cmd";    Align = "0x4000" }
)

$jniLibs = Join-Path $Repo "fuaran-core\src\main\jniLibs"
Remove-Item -Recurse -Force $jniLibs -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $jniLibs | Out-Null

$shim = Join-Path $Repo "fuaran-core\src\main\jni\fuaran_jni.c"
$shimGenDir = Join-Path $Repo "fuaran-core\src\main\jni"   # generated/ header is #included relative to this
$pageAlign = "-Wl,-z,max-page-size=16384"                  # 16KB page alignment (belt-and-braces; NDK r28+ defaults to it)

# --- 1. Cross-build the Rust core for every ABI, staged into jniLibs/<abi>/ ----------- #
Write-Host "cargo ndk :: fuaran-rs -> libfuaran_rs.so (arm64-v8a, armeabi-v7a, x86_64)" -ForegroundColor Cyan
$env:ANDROID_NDK_HOME = $ndk
Push-Location $rsRepo
try {
    # Invoke as the cargo subcommand `cargo ndk` — cargo-ndk.exe run directly expects `ndk`
    # as its first arg (cargo supplies it), so calling the exe bare mis-parses the options.
    & $cargo ndk -o $jniLibs -t arm64-v8a -t armeabi-v7a -t x86_64 build --release
    if ($LASTEXITCODE -ne 0) { throw "cargo ndk build failed." }
} finally {
    Pop-Location
}

# --- 2. Cross-compile the JNI shim per ABI, beside the core .so ----------------------- #
foreach ($a in $abis) {
    $abiDir = Join-Path $jniLibs $a.Abi
    $coreSo = Join-Path $abiDir "libfuaran_rs.so"
    if (-not (Test-Path $coreSo)) { throw "expected $coreSo from cargo ndk — missing." }
    $clang = Join-Path $ndkBin $a.Clang
    if (-not (Test-Path $clang)) { throw "NDK clang wrapper not found: $clang (API $ApiLevel)." }
    $jniSo = Join-Path $abiDir "libfuaran_jni.so"
    # Match the core .so's page size: 16KB for the 64-bit ABIs, the linker default (4KB) for armv7.
    $linkArgs = @("-shared", "-fPIC", "-O2", "-o", $jniSo, $shim, "-I$shimGenDir", "-L$abiDir", "-lfuaran_rs")
    if ($a.Align -eq "0x4000") { $linkArgs += $pageAlign }
    Write-Host "clang ($($a.Abi)) :: JNI shim -> libfuaran_jni.so" -ForegroundColor Cyan
    & $clang @linkArgs
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path $jniSo)) { throw "JNI shim cross-compile failed for $($a.Abi)." }
}

# --- 3. Verify each packaged .so's page alignment (16KB on 64-bit, 4KB on armv7) + JNI syms - #
Write-Host "verify :: per-ABI page alignment + JNI symbols" -ForegroundColor Cyan
foreach ($a in $abis) {
    $abiDir = Join-Path $jniLibs $a.Abi
    foreach ($so in (Get-ChildItem $abiDir -Filter *.so)) {
        $aligns = (& $readelf -l $so.FullName | Select-String "LOAD").Line | ForEach-Object { ($_ -split '\s+')[-1] } | Sort-Object -Unique
        $bad = $aligns | Where-Object { $_ -ne $a.Align }
        if ($bad) { throw "$($so.Name) [$($a.Abi)] expected LOAD alignment $($a.Align) but found: $($aligns -join ', ')" }
    }
    $syms = & $readelf --dyn-syms (Join-Path $abiDir "libfuaran_jni.so")
    if (-not ($syms | Select-String "Java_fuaran_core_FuaranNative_sessionNew")) {
        throw "libfuaran_jni.so [$($a.Abi)] is missing the JNI export Java_fuaran_core_FuaranNative_sessionNew."
    }
    Write-Host "  $($a.Abi): libfuaran_rs.so + libfuaran_jni.so aligned $($a.Align), JNI exports present." -ForegroundColor Green
}

# --- 4. Assemble a minimal AAR (manifest + classes.jar + R.txt + jni/<abi>/*.so) ------ #
$aarStage = Join-Path $Repo "build\aar-stage"
Remove-Item -Recurse -Force $aarStage -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $aarStage | Out-Null

# classes.jar: runtime-free main classes (AAR consumers bring kotlin-stdlib as a Gradle dep).
$classesJar = Join-Path $aarStage "classes.jar"
if (-not (Test-Path $ClassesDir)) { throw "main classes dir not found: $ClassesDir (compile the main sources first)." }
& $jarTool --create --file $classesJar -C $ClassesDir .
if ($LASTEXITCODE -ne 0) { throw "classes.jar assembly failed." }

@"
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="fuaran.core">
    <uses-sdk android:minSdkVersion="$ApiLevel" />
</manifest>
"@ | Set-Content -Path (Join-Path $aarStage "AndroidManifest.xml") -Encoding utf8
New-Item -ItemType File -Force (Join-Path $aarStage "R.txt") | Out-Null

$aarJni = Join-Path $aarStage "jni"
foreach ($a in $abis) {
    $dst = Join-Path $aarJni $a.Abi
    New-Item -ItemType Directory -Force $dst | Out-Null
    Copy-Item (Join-Path $jniLibs "$($a.Abi)\*.so") $dst -Force
}

$aar = Join-Path $Repo "build\fuaran-core.aar"
Remove-Item $aar -Force -ErrorAction SilentlyContinue
Compress-Archive -Path (Join-Path $aarStage "*") -DestinationPath "$aar.zip" -Force
Move-Item "$aar.zip" $aar -Force

Write-Host "Android AAR ready: $aar" -ForegroundColor Green
Write-Host ("  contents: AndroidManifest.xml, classes.jar, R.txt, jni/{arm64-v8a,armeabi-v7a,x86_64}/{libfuaran_rs.so,libfuaran_jni.so}") -ForegroundColor Green
$aar
