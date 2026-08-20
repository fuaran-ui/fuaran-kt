# CLAUDE.md — fuaran-kt (native Kotlin surface)

This repo is the **native Kotlin surface of the Fuaran UI wire format**, over the Rust
reference core — the JVM / Android twin of the native Swift surface. It sits alongside `fuaran`, `fuaran-ts`, `fuaran-py`, `fuaran-go`, and `fuaran-rs`.
Cross-repo development conventions (port allocation, formatting, language-baseline pinning) live at the maintainers' workspace level and are not shipped here.

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
emulator. The **Android leg** (`run.ps1 -Package` → `dev-scripts/build-android-aar.ps1`)
cross-builds, for `arm64-v8a` / `armeabi-v7a` / `x86_64`: the Rust core
(`libfuaran_rs.so`) via **cargo-ndk**, plus the JNI shim (`libfuaran_jni.so`) via the NDK
per-ABI clang, into `fuaran-core/src/main/jniLibs/<abi>/`, and assembles a
`build/fuaran-core.aar` (`AndroidManifest.xml` + `classes.jar` + `R.txt` +
`jni/<abi>/*.so`). It verifies **per-ABI page alignment — 16KB (`0x4000`) on the 64-bit
ABIs (arm64-v8a, x86_64) where Google requires it; 4KB (`0x1000`) on 32-bit armeabi-v7a**,
and that each shim exports the `Java_fuaran_core_FuaranNative_*` symbols. Requires
`ANDROID_NDK_HOME` + cargo-ndk + the Rust android targets; it **skips cleanly** with a
named message when absent (mirrors fuaran-rs `run.ps1 -CrossTargets`). The `.so`s and the
`.aar` are build outputs (gitignored). Two gotchas baked into the script: invoke
cargo-ndk as the cargo subcommand `cargo ndk` (calling `cargo-ndk.exe` bare mis-parses —
cargo supplies the `ndk` first-arg); and 16KB alignment is a 64-bit-only concern, so
`-Wl,-z,max-page-size=16384` is applied to the 64-bit ABIs only. Runtime behaviour is
covered by the desktop JNI leg (the Android `.so`s can't run without a device/emulator, so
the Android leg's bar is build + correct symbols + alignment).

## Corpus legs — what `CorpusDecodeTest` actually runs

Three families, all hard-failing, plus the URL-floor checks:

- **node-round-trip** — every fixture decodes with zero fallback-arm hits (the original bar).
- **lenient-accept** — every 16 / 3.6 shorthand, field alias, enum alias and shape coercion. The
  spec says of this family that a host skipping it "can pass certification while diverging, which is
  precisely what this family exists to prevent". Being *stricter* than the language is not a safe
  default: it is an availability defect that presents to the user as the surface rejecting a tree
  the language accepts, and a model's first guess is exactly the spelling these fixtures pin.
- **reject** — the negative half of the decode contract. Each malformed fixture must fail with the
  canonical code and a `$`-rooted path carrying the expected **prefix** (a discriminator refusal
  legitimately reports at `<path>.$type` where the corpus records `<path>`; the reference host
  matches by prefix for the same reason). A decode that SUCCEEDS is the hard failure.

The runner also fails when any family enumerates ZERO fixtures — a leg that quietly found nothing
is a gate that checked nothing, one level below the CI workflow's `SKIP:` guard.

**Two documented exclusions, neither a filter over a family:** `decoder == "op"` fixtures (there is
no `TreeOp` decoder here at all — the core owns apply, and a render projection never sees an op),
and the `envelope-reject` family, which asserts `FOREIGN_PROFILE` — versioning-envelope negotiation,
a codec-host obligation this decode-only surface does not carry.

**Forward-coupling rule.** A host-opaque payload slot (`SetState.value`-shaped: held raw, never
interpreted) reads through `payload()` / `payloadMap()`, which refuse an explicit `null`. The wire
spells absence by omitting a key, so a `null` in a payload slot is malformed; reading it raw hands
the embedding app a slot that claims to carry a value and does not.

**Field aliases take an alias SET, not a single alias.** `reqAliased(key, path, vararg aliases)` /
`getAliased(key, vararg aliases)`. A one-alias helper structurally cannot express `route ← href |
url | to` or a grid column's `label ← header | title`, and the decoder grew inline `?:` chains to
work around it. Enum-value aliases go through **one reader per vocabulary**
(`toneVariantOf` / `badgeVariantOf` / `buttonVariantOf` / `headingVariantOf` / `orientationOf` /
`decodeEmphasisEnum` / `decodeEmphasisFlag`) for the reason the tone reader already stated: a second
reader at a second position is how one position comes to accept a spelling the other refuses.

## The URL safety floor (`fuaran-ui/.../UrlPolicy.kt`)

`FuaranUrlPolicy` plus the `sanitizedHref` / `sanitizedSrc` / `sanitizedNavigateRoute` accessors are
this surface's answer to "who checks the destination". **Chosen posture: a public accessor, not a
decode-time filter** — `href` / `src` are `Binding`s whose value may not exist until the core
resolves a `State` / `Query` / `Format` slot, so a decode-time allowlist would be checking a
placeholder, and filtering during decode would also stop the projection being a faithful view of the
wire. The consumer obligations live in the README's "Safety floor" section; keep them there, since
that is what a consumer reads.

A renderer or interaction arm that ever *does* route a URL onward — a real image loader, a tappable
link, an `Intent` — must go through `FuaranUrlPolicy.sanitize` in the same change that adds it. Same
shape as the write-back rule below, and for the same reason: the compiler forces the arm to exist,
it cannot force the arm to check.

## Control write-back — a forward-coupling rule (Phase 667)

A value-carrying control arm in `FuaranRenderer.kt` must, **in the same change that adds it**,
either commit its edit through `stateKeyOf(kind.value)` + `sink?.writeBack(...)`, or be
`readOnly`/inert with a `NON-WRITABLE BY CONSTRUCTION` comment saying why.

**Why this needs a written rule.** The renderer dispatches over a sealed tree, so the compiler
forces a new arm to EXIST but cannot force it to WRITE. An arm that renders a live control and
forgets the write-back is invisible: the user types, the local Compose buffer updates, the UI
looks correct, and the value never reaches the session store. Phase 667 found five such arms —
`TextArea`, `Date`, `DateRange`, `RangedNumber`, and `SegmentedChoice` (whose radio group had no
`onClick` at all) — where the 2026-07-25 survey had named two. A test per arm in
`WriteBackGapTest` is the only real guard; add one with the arm.

## Accessibility projection — the mapping, and what is dropped

A node's `Accessibility` trait carries six slots. The HTML render tiers project them into `aria-*`
attributes; a Compose surface has no attribute bag, so the projection is a mapping onto **semantics
properties** — and the two vocabularies do not correspond one-for-one. The mapping lives in
`fuaran-renderer/.../Accessibility.kt`; the decision is here.

| slot | Compose `semantics {}` |
|---|---|
| `label` | `contentDescription`, resolved through the binding; an empty resolved label is dropped |
| `labelledBy` | **no mapping** — dropped, reported |
| `describedBy` | **no mapping** — dropped, reported |
| `role` | `button` → `Role.Button`, `tab` → `Role.Tab`, `heading` → `heading()`; every other token **dropped, reported** |
| `liveRegion` | `polite` / `assertive` → `LiveRegionMode.Polite` / `.Assertive` — an **exact** mapping; `off` → nothing (`off` is the platform default) |
| `hidden` | `hideFromAccessibility()` when the binding resolves true |

**An unmappable slot is DROPPED, never refused — and never silently.** Two halves, both
load-bearing:

*Never refused.* A render surface does not reject a tree the wire declares valid. Refusing would
fork the vocabulary by platform — the same tree would render on one surface and fail on another —
and it would make an author's `aria-describedby` a portability hazard rather than a hint. This
module's own model already says so ("carried best-effort for the render projection").

*Never silently.* Silence is the defect this closed: the trait decoded into the model and was
dropped on the floor, with nothing recording that a question had been asked. So the projection
**returns its drop set** (`AccessibilityProjection.unmapped`, in wire-slot order), the tests assert
it slot by slot, and this table enumerates it. A slot that becomes mappable moves from one list to
the other and the assertion goes red until both are updated — which is what makes the drop set a
decision rather than an omission.

**Placement is by construction, not by convention.** The reference host decides which element
carries the projection (`../fuaran-dotnet/docs/DECISIONS.md`, D4: the node's semantic element, not
its wrapper `<div>`). A Compose surface has no wrapper — `RenderNodeKind` emits exactly what the
kind arm renders — so the projection is applied at the single dispatch site in `FuaranNode` and
nowhere else. Compose cannot attach semantics to a composable that does not take a `Modifier`, so
the site uses a semantics-bearing `Box` that adds no visual chrome; it is reached **only** when the
node carries a projectable trait, so every other node reaches exactly the composable it did before.
`mergeDescendants` is set only when a label is projected — that is what makes `contentDescription`
rename the node rather than add a second announced element beside its content.

**One approximation was declined**, and is worth stating so it is not re-proposed as an
improvement: `role: "link"` as `Role.Button`. Compose has no link role, and announcing a link as a
button is a mis-statement rather than a partial one. Note the sibling Swift surface maps `link`
genuinely (`.isLink`) and cannot map `tab`, which this one can — the two native surfaces have
**different drop sets** by design. Neither is the other's parity target; both answer to the
reference `aria-*` projection.

**Forward-coupling.** A new slot on the wire trait, or a new ARIA role token, updates the mapping
table above, `roleSemanticsOf`, and the drop-set assertions in `AccessibilityProjectionTest` in the
same change — the same shape as the write-back rule above, and for the same reason: nothing in the
compiler can tell that a slot went unread.

## Public vocabulary discipline

fuaran-kt is OSS-public (Apache 2.0). Per the workspace OSS publication boundary,
**shipped artefacts** (source, README, package metadata) reference only "the Fuaran UI
wire format" generically — never a private sibling / package / product / command name.
This `CLAUDE.md` observes the same boundary.
