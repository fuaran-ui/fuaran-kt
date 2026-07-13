# CLAUDE.md — fuaran-kt (native Kotlin surface)

This repo is the **native Kotlin surface of the Fuaran UI wire format**, over the Rust
reference core — the JVM / Android twin of the native Swift surface. It is a sibling
under the Fuaran-UI sub-estate at `../`, alongside `fuaran`, `fuaran-ts`, `fuaran-py`,
`fuaran-go`, and `fuaran-rs`. Cross-repo conventions (Sync All, the formatting mandate,
the language-baseline pinning, the OSS publication boundary, port allocation) live in the
workspace `CLAUDE.md` (`../../../CLAUDE.md`) and the Fuaran-UI sub-estate `CLAUDE.md`
(`../CLAUDE.md`). Read those first.

## Framing — load-bearing, do not regress

**Not a conformant host — a render projection over the Rust reference core.** The Rust
core (`fuaran-rs`) owns the canonical codec, the tree-op apply engine, and mutation,
exposed through a small target-neutral C-ABI. The Kotlin side is a **consumer-grade
decoder** into `sealed` types, held to a "decodes every corpus node fixture" bar — never
the wire-spec byte-parity bar (there is no canonical Kotlin encoder). Public docs say
"native Kotlin surface … over the Rust reference core", never "conformant host".

## Posture

- **Apache 2.0 from day one** — same posture as the OSS-public tiers. Every source file
  carries the SPDX `Apache-2.0` header + `Copyright Diametrical Ltd.`
- **Dependency-light.** The JSON reader is hand-rolled (the Kotlin stdlib has no JSON,
  and the render-projection path never re-encodes, so byte-exact canonical output is a
  non-goal). `kotlinx.serialization` was considered and declined: it adds a classpath
  dependency for no gain and would break the "buildable with a bare `kotlinc`, no
  artefact resolution" property. The JNI binding (Phase 543) is hand-written — no
  JNA/JNR.
- **Exhaustive by construction.** Closed wire DUs are `sealed` hierarchies; the dispatch
  spines (`NodeKind.category()`, `NodeKind.discriminator()`) are `else`-free exhaustive
  `when`s, so a new wire kind is a build error until its arm lands — the Kotlin analogue
  of the Rust host's `match` with no `_ =>`.

## Language baseline

Kotlin 2.x (kotlinc 2.4.0 on the reference box), JVM toolchain **21** (Microsoft JDK 21).
The `fuaran-ui` module is a plain-JVM library — Android tooling is **not** required to
build or test it; Android enters only for the Compose renderer (a later phase) and the
cargo-ndk `.so` packaging leg (Phase 543, Android-only).

## Layout

```
fuaran-kt/
├── fuaran-ui/                       # pure-JVM: sealed model + render-projection decoder (Phase 542)
│   └── src/main/kotlin/fuaran/ui/
│       ├── Json.kt                  # hand-rolled JSON reader (JsonValue tree)
│       ├── Errors.kt                # FuaranDecodeException + the six canonical codes
│       ├── Enums.kt                 # bare-string wire enums + enumOf()
│       ├── Model.kt                 # the sealed tree: Node / NodeKind / Binding / Action / Shape / ...
│       ├── NodeCategory.kt          # the exhaustive (else-free) dispatch spines
│       ├── Decode.kt                # the render-projection decoder
│       └── Session.kt               # (Phase 543) the confined FuaranSession wrapper
│   └── src/test/kotlin/fuaran/ui/
│       └── CorpusDecodeTest.kt      # corpus render-coverage harness (main-driven runner)
├── fuaran-core/                     # (Phase 543) C-ABI JNI binding
│   ├── src/main/java/fuaran/core/   # FuaranNative — the JNI `native` bridge (javac -h generates the header)
│   ├── src/main/jni/                # the hand-written JNI C shim + generated/ header
│   └── src/test/kotlin/fuaran/core/ # SessionTest — the live-session round-trip leg
├── run.ps1                          # Stage-0 entry point (kotlinc/javac/java directly; no Gradle binary)
├── *.gradle.kts                     # forward-looking Gradle scaffold (no wrapper yet — see below)
├── LICENSE / README.md / CLAUDE.md
```

## Build / verify pipeline

```powershell
pwsh ./run.ps1              # compile fuaran-ui + fuaran-core, run the corpus harness (+ desktop JNI leg when Rust is present)
pwsh ./run.ps1 -SkipTests   # compile only
pwsh ./run.ps1 -SkipBuild   # re-run the harnesses against the existing jar
pwsh ./run.ps1 -Package     # (skips — Android NDK + cargo-ndk absent on this box)
```

**No Gradle binary on the reference dev box**, so `run.ps1` is the real Stage-0 build:
it compiles directly with `kotlinc` / `javac` / `java`. The `build.gradle.kts` /
`settings.gradle.kts` files declare the intended module graph (Kotlin 2.x, JVM 21) for a
future Gradle wrapper — **the wrapper is deferred** (it cannot be generated without a
Gradle binary; `gradle-wrapper.jar` is a binary artefact a `gradle wrapper` run
produces). Do not treat the Gradle files as the live build until a wrapper lands.

## Formatting mandate

The workspace formatting mandate (Fantomas for F#, rustfmt for Rust, …) maps here to
**ktlint**. ktlint is **not installed on the reference box**, so the format gate is
**deferred**: code is hand-formatted to the Kotlin official style (4-space indent,
trailing commas). When ktlint lands, wire a `run.ps1` format gate (`ktlint --format`)
ahead of the build, matching the sibling pattern.

## Native C-ABI binding (Phase 543)

The `fuaran-core` module binds the fuaran-rs C-ABI (`../fuaran-rs/include/fuaran.h`):
`fuaran_session_new` / `_free` / `_tree_json` / `_apply_op` / `_set_state` / …, plus
`fuaran_alloc` / `_dealloc` and `fuaran_last_error`. Load-bearing ABI facts the shim
honours:

- **Buffer return is a by-value `FuaranBuf { uint8_t* ptr; size_t len }` on native**
  (the packed-`u64` form is `wasm32`-only). The shim reads `len` bytes at `ptr` (no
  trailing NUL) then frees with `fuaran_dealloc(ptr, len)`.
- **Sessions are single-owner.** `FuaranSession` confines every call — construction,
  ops, `tree_json`, free — to a **single-threaded executor**, per the header's threading
  contract. `fuaran_last_error` is per-thread and is read on that same executor.
- **Deterministic free.** The wrapper is `AutoCloseable` (idempotent `close`) with a
  `java.lang.ref.Cleaner` backstop; the handle is freed exactly once via
  `fuaran_session_free`.

The desktop test leg builds a host-native `.dll` (Rust cdylib + a clang-compiled JNI
shim) and `System.load`s it, so `run.ps1` exercises a live Rust session without an
emulator. The **Android leg** (per-ABI `.so`s via cargo-ndk into an AAR's `jniLibs/`,
16KB-page-aligned) requires the Android NDK + cargo-ndk and is Android-only — it skips
cleanly on this box with a named message (mirrors fuaran-rs `run.ps1 -CrossTargets`).

## Public vocabulary discipline

fuaran-kt is OSS-public (Apache 2.0). Per the workspace OSS publication boundary,
**shipped artefacts** (source, README, package metadata) reference only "the Fuaran UI
wire format" generically — never a private sibling / package / product / command name.
This `CLAUDE.md` observes the same boundary.
