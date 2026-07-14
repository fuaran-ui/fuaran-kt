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

# Locate a Visual Studio `vcvars64.bat` (the MSVC host-toolchain env) — the fallback C compiler when a
# host `clang` is not on PATH. Tries `vswhere` first, then globs the standard install roots. The NDK's
# cross-`clang` is NOT a host compiler (no host libc headers — `stdio.h` not found), so on a box with
# only the NDK, MSVC `cl.exe` under this env is the correct desktop-shim compiler (rustc's default host
# on Windows is `x86_64-pc-windows-msvc`, so the fuaran-rs import lib is MSVC-ABI regardless).
function Find-Vcvars {
    $vswhere = Join-Path ${env:ProgramFiles(x86)} "Microsoft Visual Studio\Installer\vswhere.exe"
    if (Test-Path $vswhere) {
        $root = & $vswhere -latest -products * -property installationPath 2>$null | Select-Object -First 1
        if ($root) {
            $v = Join-Path $root "VC\Auxiliary\Build\vcvars64.bat"
            if (Test-Path $v) { return $v }
        }
    }
    foreach ($pf in @(${env:ProgramFiles(x86)}, $env:ProgramFiles)) {
        if (-not $pf) { continue }
        $hits = Get-ChildItem (Join-Path $pf "Microsoft Visual Studio") -Recurse -Filter "vcvars64.bat" -ErrorAction SilentlyContinue |
            Where-Object { $_.FullName -match 'VC\\Auxiliary\\Build\\vcvars64.bat$' } | Select-Object -First 1
        if ($hits) { return $hits.FullName }
    }
    return $null
}

$cargo = Find-Tool "cargo"
$cc = Find-Tool "clang"
$vcvars = if ($cc) { $null } else { Find-Vcvars }
$javaHome = [Environment]::GetEnvironmentVariable('JAVA_HOME', 'Machine')
if (-not $javaHome) { $javaHome = $env:JAVA_HOME }

if (-not $cargo) {
    Write-Host "SKIP (native): Rust toolchain (cargo) not found — the desktop JNI session leg requires it." -ForegroundColor Yellow
    return
}
if (-not $cc -and -not $vcvars) {
    Write-Host "SKIP (native): no C compiler (host clang / MSVC cl.exe) found — cannot build the JNI shim." -ForegroundColor Yellow
    return
}
if (-not $javaHome -or -not (Test-Path (Join-Path $javaHome "include\jni.h"))) {
    Write-Host "SKIP (native): JAVA_HOME has no include\jni.h — need a full JDK's headers (a headless JRE/JBR will not do)." -ForegroundColor Yellow
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

# 2. Compile + link the JNI shim — host clang if present, else MSVC cl.exe under vcvars64.
$nativeOut = Join-Path $Repo "build\native"
New-Item -ItemType Directory -Force $nativeOut | Out-Null
$shim = Join-Path $Repo "fuaran-core\src\main\jni\fuaran_jni.c"
$shimDir = Split-Path $shim -Parent
$jniShim = Join-Path $nativeOut "fuaran_jni.dll"
Remove-Item $jniShim -ErrorAction SilentlyContinue
$incJni = Join-Path $javaHome "include"
$incWin32 = Join-Path $javaHome "include\win32"

if ($cc) {
    $ccArgs = @(
        "-shared",
        "-o", $jniShim,
        $shim,
        $importLib,
        "-I", $shimDir,
        "-I", $incJni,
        "-I", $incWin32
    )
    Write-Host "clang :: JNI shim -> $jniShim"
    & $cc @ccArgs 2>&1 | ForEach-Object { Write-Verbose $_ }
} else {
    # MSVC path: run vcvars64 then cl in one cmd shell (cl builds a DLL with /LD; the JNI functions are
    # `JNIEXPORT` = `__declspec(dllexport)` on win32, so cl exports the Java_* symbols without a .def).
    $clLine = "call `"$vcvars`" && cd /d `"$nativeOut`" && cl /nologo /LD /Fe:fuaran_jni.dll `"$shim`" " +
        "/I `"$shimDir`" /I `"$incJni`" /I `"$incWin32`" `"$importLib`""
    Write-Host "cl.exe (MSVC) :: JNI shim -> $jniShim"
    cmd /c $clLine 2>&1 | ForEach-Object { Write-Verbose $_ }
}
if ($LASTEXITCODE -ne 0 -or -not (Test-Path $jniShim)) {
    Write-Host "SKIP (native): JNI shim compile failed." -ForegroundColor Yellow
    return
}

# 3. Stage the dependent core DLL beside the shim so it resolves at load.
Copy-Item $coreDll $nativeOut -Force

Write-Host "Native JNI shim ready: $jniShim" -ForegroundColor Green
# Emit the single pipeline value run.ps1 captures.
$jniShim
