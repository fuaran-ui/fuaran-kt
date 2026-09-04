# The Kotlin render projection

`fuaran-kt` is the **native Kotlin surface of the Fuaran UI wire format, over the
Rust reference core**. It decodes a canonical tree into `sealed` Kotlin types for
rendering, and drives a live session whose truth and mutation live in the
corpus-certified Rust core.

That sentence carries a boundary worth stating before anything else, because it
decides what this library does and does not owe you.

## What this is, and what it is not

**This is a render projection, not a conformant host.** The Rust reference core
owns the canonical codec, the tree-op apply engine, and mutation, exposed through
a small C-ABI. The Kotlin side holds a consumer-grade **decoder** into sealed
classes — and no canonical encoder at all.

So the bar this surface is held to is *"decodes every node fixture in the shared
conformance corpus"*, never the byte-parity bar. There is no encode leg here to
certify, and a byte-parity claim would be a claim about the core.

The one thing in the library that serialises is `JsonValue.encode()`, a compact
JSON writer used solely to marshal an interaction payload — a `SetState` value, a
form-field edit — back into the string the session's `setState` channel takes.
It never re-encodes a node, and it is not a canonical encoder.

**It is JVM-first.** The sealed model, the decoder, the session and the driver are
pure-JVM modules with no Android dependency and, in the case of `fuaran-ui`, no
runtime dependency at all. Android is where the Compose renderer and the packaged
native library live, not where the surface begins.

## The modules

| Module | What it is | Needs |
|---|---|---|
| `fuaran-ui` | The sealed tree model, the render-projection decoder, `FuaranSession`, the corpus coverage harness. Hand-rolled JSON reader; **zero runtime dependencies**. | JVM 17 bytecode, JDK 21 toolchain |
| `fuaran-core` | The JNI shim over the Rust core's C-ABI, plus native packaging. | a built native library |
| `fuaran-renderer` | The Jetpack Compose render floor, the Material tone bridge, and the interaction round trip. Its corpus coverage gate runs headlessly under Robolectric. | Android SDK |
| `fuaran-driver` | The server-driven driver: fetch a tree, apply streamed ops, post interaction events back. Pure JVM. | — |
| `samples` | An Android sample wiring a live session through the interactive renderer. | Android SDK |

**`FuaranSession` lives in `fuaran-ui`, not in `fuaran-core`.** That is the point
of the layering rather than an accident: `fuaran-ui` declares the
`FuaranNativeBridge` *interface* and owns the confinement; `fuaran-core` supplies
a concrete JNI *implementation* of it. A test double implementing that interface
drives the whole surface with no native library in sight. (The module table in
the repo README attributes the session to `fuaran-core`; the code is the
authority.)

## Decoding a tree

One public entry point:

```kotlin
import fuaran.ui.*

val root: Node = decodeNode(json)   // json: canonical tree JSON from a session
```

```kotlin
data class Node(
    val id: String,
    val kind: NodeKind,
    val style: SemanticStyle? = null,
    val state: StateBehaviour? = null,
    val accessibility: Accessibility? = null,
)
```

`NodeKind` is a `sealed interface`, so `when` over it is checked at compile time:

```kotlin
val box = root.kind as? Box ?: return          // box.role == BoxRole.Dashboard
for (child in box.children) {
    when (val k = child.kind) {
        is Heading -> println("${k.level} ${k.text}")   // 2, LiteralText("Channel performance")
        is Markdown -> println(k.text)
        else -> {}
    }
}
```

Every closed wire vocabulary is sealed the same way — `NodeKind`, `Binding`,
`Action`, `TextSource`, `BoxLayout`, `FormFieldKind`, `CellKind`, `Shape`,
`ValueFormat` and the rest. **When you write an exhaustive `when` over one, leave
off the `else`.** A new wire kind then arrives as a build error naming your file,
which is the guarantee this design exists to give; an `else` arm converts that
into a silent runtime fallback you find out about much later.

Two dispatch spines in the library are written that way deliberately and must
stay so: `NodeKind.category()` and `NodeKind.discriminator()` in
`NodeCategory.kt`, and the renderer's own `RenderNodeKind`.

Two Kotlin names differ from their wire spelling, because `List` and `Map` are
taken: the cases are `ListNode` and `MapNode`, and `discriminator()` maps them
back to `"List"` and `"Map"`.

### Failure is typed, never a fallback

```kotlin
class FuaranDecodeException(
    val code: String,     // INVALID_JSON | MISSING_FIELD | WRONG_TYPE | UNKNOWN_DU_CASE
                          // | WRONG_NODE_KIND | EMPTY_NODE_ID | LIMIT_EXCEEDED
    val path: String,     // "$"-rooted, e.g. "$.kind.text"
    val detail: String,
) : Exception("$code at $path: $detail")
```

These are the canonical codes every conformant host reports at the same paths, so
a malformed tree reasons the same way here as anywhere else. An unrecognised
discriminator raises `UNKNOWN_DU_CASE` — there is no catch-all arm producing a
generic node.

`LIMIT_EXCEEDED` is deliberately distinct from `INVALID_JSON`: it means the
document is well formed and merely too large to walk. The reader raises two
exception types rather than one with a flag, precisely so a `catch` site cannot
lose the distinction by forgetting to read the flag. The limits themselves live
in `WireLimits` — node depth 24, JSON depth 256, string 1 MiB, array 100 000,
nodes 100 000 — and they are protocol numbers, not tuning knobs.

### The decoder is lenient in specific, enumerated ways

Being *stricter* than the language is an availability defect rather than a safe
default, so the decoder accepts what a model's first guess actually looks like:

- **A `Static` envelope wrapped around a plain scalar unwraps** before every
  scalar read — applied in one place rather than site by site. An object that is
  not a well-formed `Static` envelope passes through and fails normally. Never
  applied to array or object slots, where the envelope has a second reading.
- **Enum aliases go through one reader per vocabulary** — `"Positive"` reads as
  `Success`, `"Danger"` / `"Negative"` as `Critical`, and so on.
- **Field aliases** are taken as a set: `heading` also accepts `title`.

What it does *not* do is guess. `ImageAspect` admits no arbitrary ratio, so
`"16/9"` is `UNKNOWN_DU_CASE` rather than a second spelling of the enum case. And
`TrendPolarity.Neutral` is reserved by the specification and deliberately absent,
so `"Neutral"` is refused — the absence is why the slot is an enum rather than an
`inverted: Boolean`.

### What the projection deliberately drops

A closure cannot ride the wire, so slots that carry one are modelled as presence
only: `ComputedBinding`, `NowBinding`, `DispatchAction`, `CustomValueFormat`,
`EditableCell` and `CheckboxCell` are objects; `StateBehaviour.hasOnError` is a
`Boolean` where the wire had an unobservable handler; a grid column's `value`
closure is dropped. Host-opaque payloads stay raw `JsonValue`
(`StaticBinding.value`, a `TransformBinding`'s source and pipeline,
`Custom.props`).

There is also **no `TreeOp` decoder at all**, and that is the boundary again: the
core owns apply, so a render projection never sees an op.

## The safety floor

A decoded tree is **untrusted input**. It usually arrives from a model, and a
model will happily emit a `Link` whose `href` is `javascript:…`.

```kotlin
when (val dest = link.sanitizedHref) {              // NOT link.href
    is SanitizedUrl.Allowed  -> open(dest.url)
    is SanitizedUrl.Rejected -> log("refused destination: ${dest.reason}")
    SanitizedUrl.Dynamic     -> {                   // a State / Query / Format binding
        FuaranUrlPolicy.sanitize(resolvedHref)?.let { open(it) }
    }
}
```

Accessors exist for every slot the wire hands you verbatim: `Link.sanitizedHref`,
`Image.sanitizedSrc`, each `SrcSetEntry.sanitizedSrc`, `Media.sanitizedSrc`, a
video's `sanitizedPoster`, and `Action.sanitizedNavigateRoute`.

`Dynamic` is a case rather than a null on purpose: "refused" and "not knowable
yet" call for different handling, and a binding's value may not exist until the
core resolves it. That is also why the floor is a public accessor and not a
decode-time filter — a check at decode time would be examining a placeholder, and
the projection stays a faithful view of the wire.

The full policy, the per-slot remedies, and the reason this surface carries no
script-injection sink at all are in the repository README. Read them before
shipping; the media and `srcSet` slots deserve particular attention because they
are fetched with no user act at all.

## Driving a live session

```kotlin
NativeBridge.loadLibrary("fuaran_jni")   // or NativeBridge.load("/abs/path/fuaran_jni.dll")

FuaranSession.create(NativeBridge, seedTreeJson).use { session ->
    val before = decodeNode(session.treeJson())
    session.applyOp(editOpJson)
    val after = decodeNode(session.treeJson())
}
```

Loading the native library is a **caller act**: the library itself never calls
`System.loadLibrary`, so which library, from where, and when are all yours to
decide. `loadLibrary(name)` resolves through `java.library.path` (packaged and
Android); `load(absolutePath)` is the desktop and test route.

`FuaranSession` implements `TreeSession`:

| Method | |
|---|---|
| `treeJson()` | the current tree as canonical wire JSON — `decodeNode` it |
| `projectResolved()` | the same tree with scalar `Transform` bindings folded to their values |
| `applyOp(opJson)` | apply a canonical `TreeOp`; throws `FuaranException` on refusal |
| `setState(key, valueJson)` | write a reactive `$state.<key>` slot |
| `setFilter(name, valueJson)` | write a `$filters.<name>` slot |
| `setQuery(name, valueJson)` | seed a `$queries.<name>` result slot |
| `resolvedRows(nodeId)` | the resolved rows of one row-bearing node |
| `render()` | the core's HTML render (a `FuaranSession` extra, not on the seam) |
| `close()` | free the handle |

`projectResolved()` is what lets a decode-only surface show computed values
without carrying an evaluator: the core folds the scalar `Transform` slots and
hands back a tree that is byte-identical to `treeJson()` everywhere else.

`resolvedRows` answers in **three** cases, and the middle one is why:

```kotlin
sealed interface ResolvedRows {
    data class Rows(val rows: List<JsonValue>) : ResolvedRows  // possibly zero — an EMPTY state
    data object NotResolved : ResolvedRows                      // render LOADING, never an empty table
    data object NoRowSource : ResolvedRows                      // a caller mistake, not a data condition
}
```

Collapsing `NotResolved` into an empty list shows "no data" for "not yet" — the
quiet-wrong-looking-right failure this whole tier is built to avoid.

### Single-owner confinement, and how it is enforced

The C-ABI session is **single-owner**: it, and every call taking it, must stay on
one thread for its whole lifetime. `FuaranSession` enforces that *by
construction* rather than by documentation. It owns a private single-threaded
executor and routes every native call through it, so the session cannot be
touched concurrently even if the wrapper is shared across threads.

Three consequences worth knowing:

- **Calls are synchronous and serialised.** Each method submits to the
  confinement thread and blocks on the result. Eight threads hammering
  `treeJson()` get eight serialised reads, not a race.
- **`fuaran_last_error` is per-thread**, so a failing `create` and the read of its
  error envelope both run on that same executor thread. That is why construction
  goes through the executor too.
- **Use after close throws `IllegalStateException`**, checked before submission.

### Teardown is leak-safe on both routes

```kotlin
FuaranSession.create(NativeBridge, json).use { session -> /* … */ }   // AutoCloseable
```

`close()` is idempotent and frees the handle exactly once. A `Cleaner` backstop
reclaims a session dropped without a close, and **both routes run the free on the
confinement executor** — single-owner is honoured even at reclamation. The
cleaner's action holds only the handle, the bridge and the executor, never the
session itself; holding the session would make it uncollectable and the backstop
would never fire.

Prefer `use { }`. The `Cleaner` is a backstop, not a plan.

### The JNI seam

`fuaran-ui` declares `FuaranNativeBridge`, an eleven-method interface where all
text crosses as UTF-8 `ByteArray` and the session handle is an opaque `Long`:
`sessionNew`, `lastError`, `sessionFree`, `sessionRender`, `sessionTreeJson`,
`sessionProjectResolved`, `sessionResolvedRows`, `sessionApplyOp`,
`sessionSetState`, `sessionSetFilter`, `sessionSetQuery`.

`fuaran-core` implements it. The `native` declarations are in a standalone
**Java** class rather than Kotlin `external` functions, for a practical reason:
`javac -h` generates the JNI header directly (JDK 21 ships no `javah`), and that
class deliberately depends on nothing else so header generation needs only
`jni.h`. The hand-written C shim implements the
`Java_fuaran_core_FuaranNative_*` symbols and forwards to the C-ABI.

The shim owns the buffer protocol so you never see it: every text-returning
C-ABI function returns a Rust-owned `(ptr, len)` pair with **no trailing NUL**;
the shim copies exactly `len` bytes into a fresh `byte[]` and frees the buffer,
so every array that reaches the JVM is JVM-owned.

## Rendering with Compose

The Compose render floor is **shipped**, not a later phase.

```kotlin
@Composable
fun FuaranNode(node: Node, ctx: BindingContext = BindingContext.Empty)
```

It is a pure projection of the sealed model — no wire JSON is parsed here, decode
ran first. Its `when` over `NodeKind` is exhaustive with no `else`, so a new wire
kind is a compile error until its arm lands. The corpus render-coverage gate
proves it: every node fixture composes headlessly under Robolectric with **zero
fallback-arm hits**.

Beside it ship the Material 3 tone bridge (`FuaranTheme`, the tone palettes), the
accessibility projection, the trend-sentiment projection, and `BindingContext`
for resolving bindings and formatting cell values.

For interaction, `FuaranHost` wraps a live `TreeSession` and exposes the
re-projected tree as Compose state:

```
control interaction → FuaranHost.dispatch / writeBack → TreeSession.applyOp / setState
  → session re-encodes tree_json → decodeNode re-projects → tree state changes
  → Compose recomposes
```

No wire-JSON handling happens outside the session boundary: the host hands raw op
or value JSON to the session and decodes what comes back. `ActionDispatch` sorts
a decoded `Action` into what the session can perform (a `SetState` with a literal
value) and what it hands back for you to route (`Navigate`, `Call`, `Notify`,
`AiTool`, clipboard, file read). A `Navigate` is **returned to you rather than
acted on**, precisely so the destination decision stays yours.

`InteractiveFuaranTree(host, ctx)` renders the host's current tree; a rejected op
leaves the last good tree in place and surfaces the failure on `lastError`.

## What is pending — stated plainly

- **The most recent platform-baseline wave has not been adopted here.** The
  shared corpus carries node kinds `Embed` and `Tree`, and form-field kinds
  `Color`, `Combobox`, `Rating` and `Tokens`, which this surface does not yet
  model; `WriteToClipboard` still takes a bare `String` where the corpus has
  moved to a text source; `FileUpload` carries neither the capture nor the
  destination slot. A fixture using any of them raises `UNKNOWN_DU_CASE` —
  loudly, which is the design, but it is a gap rather than a refusal.
- **Three specification adoption bars are open**: contract cards, timed advance
  on a `Switch`, and streamed upload. A host that has not adopted is not thereby
  exempt — it owes the obligation and has simply not made its answer visible.
- **Render obligations: three asserted, seven declared exempt with reasons.** The
  exemptions are real and specific: this floor carries **no playback engine and
  no network image loader**, so a `Media` node renders as a labelled transport
  tile and an `Image` as a placeholder box. Pulling either in is not a decision a
  decoding surface makes on your behalf.
- **`Chart`, `MapNode`, `Mount` and `FragmentRef` render as informational
  stubs**, and `Sparkline` renders without data. Each has a real dispatch arm —
  none falls through — but none paints the thing itself yet.
- **Nothing publishes from this repository.** The `io.fuaran:fuaran-ui:0.1.0`
  coordinate is declared, but there is no publishing configuration and no
  publish workflow. Consume it from a local build for now.
- **There is no format gate.** `run.ps1` runs no formatter; match the surrounding
  style by hand.

## Verifying

```powershell
pwsh ./run.ps1              # compile + corpus harness (+ the desktop JNI leg when available)
pwsh ./run.ps1 -SkipTests   # compile only
pwsh ./run.ps1 -SkipBuild   # re-run the harnesses against the existing jar
```

or drive Gradle directly for the two gates CI runs:

```
./gradlew :fuaran-ui:corpusCheck --console=plain
./gradlew :fuaran-renderer:testDebugUnitTest --console=plain
```

`corpusCheck` is a custom task because the corpus harness is `main()`-driven and
the ordinary `test` task would never run it. The renderer gate runs on the
**debug** variant only — the Compose test activity merges into the debug
manifest, not the release AAR, so the release unit-test variant has no activity
to compose into.

Every leg skips cleanly when a prerequisite is absent, which is exactly how a
gate ends up passing while checking nothing — so CI asserts the corpus is
present **before** running either gate.

### The Android native leg

`pwsh ./run.ps1 -Package` cross-builds the Rust core per ABI (`arm64-v8a`,
`armeabi-v7a`, `x86_64`) with `cargo ndk`, cross-compiles the JNI shim against
each, verifies 16 KB page alignment on the 64-bit ABIs and that the expected
symbol is exported, then assembles a minimal AAR.

It is opt-in and skips with a named message when the NDK, `cargo-ndk`, the Rust
toolchain or a C compiler is absent. Two limits worth knowing: the packaged
artefacts are gitignored build output, and this path is **not exercised in CI** —
its bar is build, correct symbols, and alignment, since the Android libraries
cannot run without a device. Runtime behaviour is covered by the desktop JNI leg
instead.
