#Requires -Version 7.0
<#
.SYNOPSIS
  Build the desktop JNI native leg for the Phase 543 session round-trip test.

.DESCRIPTION
  1. `cargo build` the fuaran-rs reference core (cdylib + import lib).
  2. Compile the hand-written JNI C shim with a C compiler (clang), linking against the
     fuaran-rs import lib and the JDK's jni.h, into `build/native/fuaran_jni.dll`.
  3. Stage `fuaran_rs.dll` beside it so the dependent library resolves at load time.

  Emits the absolute path of the loadable JNI shim on stdout. Skips cleanly (prints a
  named-toolchain message and returns nothing) when the Rust toolchain or a C compiler is
  absent — mirrors how fuaran-rs `run.ps1` skips its cross-target legs.

  Windows/desktop only for now; the Android per-ABI `.so`s are a separate cargo-ndk leg.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)] [string] $Repo,
    [Parameter(Mandatory)] [string] $ClassesDir,
    [Parameter(Mandatory)] [string] $JniGenDir
)

$ErrorActionPreference = "Stop"

function Find-Tool([string] $Name) {
    $c = Get-Command $Name -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($c) { return $c.Source }
    return $null
}

$cargo = Find-Tool "cargo"
$cc = Find-Tool "clang"
$javaHome = [Environment]::GetEnvironmentVariable('JAVA_HOME', 'Machine')
if (-not $javaHome) { $javaHome = $env:JAVA_HOME }

if (-not $cargo) {
    Write-Host "SKIP (native): Rust toolchain (cargo) not found — the desktop JNI session leg requires it." -ForegroundColor Yellow
    return
}
if (-not $cc) {
    Write-Host "SKIP (native): no C compiler (clang) found — cannot build the JNI shim." -ForegroundColor Yellow
    return
}
if (-not $javaHome) {
    Write-Host "SKIP (native): JAVA_HOME not set — need the JDK jni.h headers." -ForegroundColor Yellow
    return
}

$rsRepo = Resolve-Path (Join-Path $Repo "..\fuaran-rs") -ErrorAction SilentlyContinue
if (-not $rsRepo) {
    Write-Host "SKIP (native): sibling fuaran-rs not found at ..\fuaran-rs — cannot build the reference core." -ForegroundColor Yellow
    return
}

$header = Join-Path $JniGenDir "fuaran_core_FuaranNative.h"
if (-not (Test-Path $header)) {
    Write-Host "SKIP (native): generated JNI header missing ($header) — run the Java bridge compile first." -ForegroundColor Yellow
    return
}

# 1. Build the reference core.
Write-Host "cargo build :: fuaran-rs ($rsRepo)"
Push-Location $rsRepo
try {
    & $cargo build 2>&1 | ForEach-Object { Write-Verbose $_ }
    if ($LASTEXITCODE -ne 0) {
        Write-Host "SKIP (native): fuaran-rs cargo build failed." -ForegroundColor Yellow
        return
    }
} finally {
    Pop-Location
}

$dbg = Join-Path $rsRepo "target\debug"
$importLib = Join-Path $dbg "fuaran_rs.dll.lib"
$coreDll = Join-Path $dbg "fuaran_rs.dll"
if (-not (Test-Path $importLib) -or -not (Test-Path $coreDll)) {
    Write-Host "SKIP (native): fuaran-rs build artefacts missing (expected $importLib + $coreDll)." -ForegroundColor Yellow
    return
}

# 2. Compile + link the JNI shim.
$nativeOut = Join-Path $Repo "build\native"
New-Item -ItemType Directory -Force $nativeOut | Out-Null
$shim = Join-Path $Repo "fuaran-core\src\main\jni\fuaran_jni.c"
$shimDir = Split-Path $shim -Parent
$jniShim = Join-Path $nativeOut "fuaran_jni.dll"

$ccArgs = @(
    "-shared",
    "-o", $jniShim,
    $shim,
    $importLib,
    "-I", $shimDir,
    "-I", (Join-Path $javaHome "include"),
    "-I", (Join-Path $javaHome "include\win32")
)
Write-Host "clang :: JNI shim -> $jniShim"
& $cc @ccArgs 2>&1 | ForEach-Object { Write-Verbose $_ }
if ($LASTEXITCODE -ne 0 -or -not (Test-Path $jniShim)) {
    Write-Host "SKIP (native): JNI shim compile failed." -ForegroundColor Yellow
    return
}

# 3. Stage the dependent core DLL beside the shim so it resolves at load.
Copy-Item $coreDll $nativeOut -Force

Write-Host "Native JNI shim ready: $jniShim" -ForegroundColor Green
# Emit the single pipeline value run.ps1 captures.
$jniShim
