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
in `WireLimits` — node depth 24, tree-item depth 24, JSON depth 256, string
1 MiB, array 100 000, nodes 100 000 — and they are protocol numbers, not tuning
knobs. The first two carry the same figure and are declared separately on
purpose: a `Tree`'s whole hierarchy lives inside ONE node, so the node counter
cannot see it at all, and either axis could move without the other.

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
video's `sanitizedPoster`, each `TrackEntry.sanitizedSrc`, `Embed.sanitizedSrc`,
and `Action.sanitizedNavigateRoute`.

**`Embed.sanitizedSrc` is a stricter floor, not the same one on a different
slot.** An embed fetches a document and lets it EXECUTE, where everything else
here is fetch-and-display or navigate-on-a-click, so it accepts `https` and
nothing else — refusing `http` and refusing a schemeless reference, both of
which the ordinary floor accepts. On refusal the remedy is its own too: mount
the element with **no source at all**, never a substitute. A refused *track* or
*poster*, by contrast, is DROPPED rather than substituted, and a refused primary
`src` collapses to the refusal substitute; the three remedies differ because
what an element can do without the slot differs.

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

### The server-driven transport — bounded, survivable, typed

`fuaran-driver`'s reference `HttpUrlTransport` reads the NDJSON op stream under
explicit `OpStreamBounds`: 1 MiB per op line, 64 MiB per body, and an idle budget.
A server-driven client applies whatever the server sends, so this is the one place
it reads an unbounded amount of attacker-influenced input — and the previous
reader used `BufferedReader.readLine()`, which has no length limit at all, so one
line with no newline in it was an `OutOfMemoryError` with no diagnosis. A breach
is refused by name: a typed `TransportFailure.LINE_CAP_EXCEEDED` /
`BODY_CAP_EXCEEDED` whose message carries the limit.

**A quiet stream is the ordinary case, not a failure.** The socket read timeout is
now a POLL INTERVAL (`streamPollMillis`, 5 s) that the reader absorbs and retries;
the real limit on silence is `OpStreamBounds.idleBudgetMillis` (2 minutes), and
only exhausting it is fatal — as `IDLE_BUDGET_EXCEEDED`, which names what actually
ran out. Before this, a 30 s read timeout killed a perfectly healthy session
whenever the server had nothing to say for half a minute. The gate proves it
against a fixture that genuinely goes quiet for 35 s, which is why that leg takes
at least that long.

A non-`https` base URL is refused with a typed `INSECURE_SCHEME` unless
`allowInsecure = true` is passed — never a silent downgrade and never a silent
upgrade. Loopback (`localhost`, `127.0.0.1`, `::1`) is exempt without the flag,
because requiring a certificate for a development fixture server is how an opt-in
becomes a permanent default.

A transport failure reaching the loop is now `Fatal` rather than an exception
thrown out of `run`. The stream is opened lazily inside the sequence, so even a
failure to connect surfaced from inside the iteration and unwound whatever thread
the loop was on. It is terminal wherever it happened: a validator reject is
survivable because the next op is still coming, and a dead transport has no next
op.

`postEventApplyingReply` applies the server's REPLY OPS through the same
apply-then-project path a streamed op takes, surviving a reject with the last-good
tree. Without it an interaction was a one-way message: a request/response server
could decide a click had changed the tree and had no way to say so. `postEventOps`
defaults to the EMPTY sequence rather than to the response body — a server
answering `{"ok":true}` has not implemented a reply channel, and applying that
acknowledgement as a `TreeOp` would turn every successful event into a reject.

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

### `writeBack` runs OFF the main thread, and coalesces per key

`FuaranHost.writeBack` returns immediately: the session call, `projectResolved()`
and `decodeNode` run on a worker and the decoded tree is published back on the
main thread. It used to do all of it inline, on whatever thread the control's
`onValueChange` was called from — which on Compose is the main thread — so every
keystroke in a bound text field paid a full round trip plus a whole-tree re-decode
before the next frame could compose. On a live JNI session that is worse than it
sounds: those calls hop to the core's own confining executor and BLOCK waiting for
it, so the main thread waits on another thread by construction.

Writes to one key **coalesce, latest wins**: while a write is in flight, a further
edit to that key replaces the queued value rather than adding a round trip. A
superseded value is never sent, which is safe precisely because it was superseded
— no reader of that slot could have observed it. Coalescing is per key and never
across keys: two slots are two facts, and dropping one because the other was
edited later would lose an edit rather than an intermediate. There is no timer, so
a single considered edit is written at once and only a burst collapses.

`writesPending` is Compose state, so a host can show progress and a test can wait
on settlement rather than on a sleep. Both executors are constructor parameters —
`FuaranHost.DirectExecutor` restores the synchronous behaviour, which is what the
write-back tests use so their assertions stay definite rather than becoming
two-second timeouts.

The queue's decisions live in `WriteBackQueue.kt`, which carries no Compose and no
`android.os` import so they are asserted in the plain-JVM gate — the same split,
and the same reason, as the accessibility and trend-sentiment projections.
`dispatch` and `applyOp` remain synchronous: their contracts return values
(`dispatch` hands back the host-routed actions), and this phase changed only the
path a keystroke takes.

### Number formatting is locale-invariant

`formatCellValue` and `formatDuration` pass `Locale.ROOT` at every site. Without
it `String.format` follows the JVM's DEFAULT locale, so on a decimal-comma device
`GBP 1234.50` rendered as `GBP 1234,50` and `12.5%` as `12,5%`. That is not a
presentation preference: a formatted datum crosses the wire as text and is
compared, keyed and re-parsed downstream — a grid column's tone map is keyed on
the author's raw value — so a decimal comma is a different string that silently
stops matching, on the reader's device and nowhere near the author. The goldens
assert under `Locale.GERMANY` rather than under whatever the gate box is set to,
because a POSIX-locale gate cannot see the defect at all. Localising a displayed
number belongs to the wire's own `Format` binding with its declared locale.

`BindingContext.resolveFloat` is deprecated in favour of `resolveDouble`: a value
resolved through `Float` does not come back as the number the author wrote (`3.7`
becomes `3.700000047683716` in the slot every other reader then sees), and the
renderer narrows at the Compose boundary instead, where the loss costs a pixel.

## What is pending — stated plainly

- **That vocabulary residue is CLOSED (Phase 1499).** It read: form-field kinds
  `Color`, `Rating` and `Tokens` unmodelled; `WriteToClipboard` still taking a
  bare `String` where the corpus had moved to a text source; `FileUpload`
  carrying neither the capture nor the destination slot; `Modal` carrying no
  `modality`. All of it is adopted, along with the rest of the vocabulary the
  corpus had grown around it — `Action.Print` / `Confirm` / `Focus`,
  `Navigate`'s text-source route and closed target, `Binding.Expr`,
  `Binding.Local`'s codec and commit target, `Binding.Now`'s grain,
  `NumberFormat.Since` and `.Duration`, the `Switch` predicate cases and timed
  advance, node-level `visible`, `SemanticStyle.direction`, `DataGrid`'s export
  and transfer keys, the print-break controls, and the chart annotation family.
  **Every node fixture decodes and every reject vector refuses with the pinned
  code at the pinned path.**

  Two of the closures were places this decoder silently ACCEPTED a malformed
  document rather than failing to model a slot, which is the worse half of the
  two: a bare string was lifted into a one-element token list, and a raw C0
  control character was admitted inside a string. Both are refused now.

  **`Modal.anchor` is modelled now too, and it was the last named slot in the
  vocabulary this surface decoded and then dropped.** It is a NodeId — the node a
  `Popover` belongs to (3.6.11) — and the drop was structurally invisible: two
  corpus vectors carry it, both decode, and this surface has no canonical encoder
  to compare bytes against, so a discarded member failed nothing. It surfaced only
  as a host unable to say which node its popover belonged to. `Modal.anchor` is
  carried on the model, refuses a non-string as `WRONG_TYPE` at `.anchor`, and is
  kept even on a blocking modal, where 3.6.11 calls it meaningless — dropping a
  member because this decoder judged it pointless would silently rewrite the
  author's document, the argument the inert-`trendPolarity` clause already makes.
  The corpus-decode harness carries its own `overlayAnchor` leg for the value, the
  absence and the refusal.

  **What is NOT adopted is PLACEMENT, and that is 3.6.11's own division rather than
  a gap.** Nothing in the wire names a pixel: no placement token, no offset, no flip
  strategy. Where a popover is put is the renderer's, and rule 7 states the floor a
  surface that cannot measure its anchor owes — the surface IN FLOW at the node's own
  document position, with no positioning of any kind. That is what the Compose arm
  renders today, so the anchor reaches no placement here. A Compose surface that
  wanted the real thing needs the host's window metrics and a popup surface, which is
  an arm rather than a slot; it would read the anchor the decoder now carries.
  Neither standing render obligation is about this slot.

  **The RENDER half of that adoption landed separately, and the gap is worth
  recording.** Phase 1499 widened the model and the decoder in `:fuaran-ui` and
  did not carry the widening into `:fuaran-renderer`, which needs the Android SDK
  and so is built only in CI — so the paragraph above was true of the decoder and
  silently false of the renderer, and the only thing that said so was a red
  `:fuaran-renderer:testDebugUnitTest` compile. The three form-field arms and the
  widened `Switch` case selection (a literal `match` against a RESOLVED selector,
  a `when` predicate taken on a resolved `true` only) are in the floor now. The
  lesson generalises: a vocabulary claim made from the decoder's side is not a
  claim about this host until the module that cannot be built on the authoring
  machine has been run.

  The three new arms are **inert by construction**, and each says so at its arm
  rather than only here: `Tokens` renders its chips in AUTHORED order with the
  `allowFreeText` polarity shown and not enforced (§3.6.9 obligation 4), `Rating`
  fills pips against the resolved score and prints the resolved LEXEME beside
  them — deliberately not `allowHalf`, which governs entry and not display — and
  `Color` renders the swatch its `#rrggbb` names beside the hex as authored.
  None of them wires write-back, because none of them is editable: there is no
  chip entry, no pressable pip and no picker to return a value from, so a
  `writeBack` call here would be dead code that merely looked like a fix. Making
  any of them live is renderer feature work, and it is the same line the
  `ChoiceField` and `RangeField` arms already stand on.
- **The declared upload ceilings are adopted (Phase 1548).** `FileUpload` carries
  `maxBytes` and `maxFiles` — both optional, both positive, both §7.1's signed
  32-bit slot — and the positivity floor is applied at DECODE, because the format
  has no refined-integer type: `0` and below are `WRONG_TYPE` at the member's own
  path, on the same line as an `SrcSetEntry` whose `width` is not positive. A
  `maxFiles` beside `"multiple":false` is carried and **not** refused: §3.6.23
  makes it inert rather than malformed, and a host refusing it would reject
  documents every other host accepts. Each of the corpus's four refusals is
  asserted beside a CORRECTED TWIN, on the same argument the platform-baseline
  wave's twin leg records — a reject vector alone cannot tell a decoder that
  refuses the malformed value from one that refuses the member outright.

  **The RENDER half withholds the values, deliberately.** This floor opens no
  file picker, so it meets no selection: §3.6.23's obligations 1–3 have nothing
  to act on here, and obligation 5 is vacuous. What it does instead is follow
  obligation 4's reasoning — a tier that cannot act on a ceiling records only
  THAT one was declared, never its number — so `uploadCeilingMarkers` projects two
  value-free booleans and the arm renders a marker line built from those alone. An
  upload declaring neither ceiling renders exactly as it did before the revision:
  the marker line is ABSENT, not empty. Both halves are pinned in the
  platform-neutral `UploadCeilingHarness`, including the one assertion that can
  actually fail — that no digit reaches the marker — with the Robolectric leg
  re-checking it against the composition, where an arm that ignored the projection
  and interpolated the number into its own caption would still be caught.
- **Two declared render obligations remain owed and unanswered**
  (`FileUpload/picker-always-present`, `Modal/aria-modal-only-when-blocking`).
  Their STATUS is unchanged and their REASON is not: the slots they are about
  are modelled now, so what remains is that nobody has written the checker. They
  stay on `conformance-residue.txt` rather than being exempted, because silence
  is not a conformant answer.

  **The artefact's twentieth claim arrived with Phase 1548 and is an EXEMPTION,
  not a third residue line.** `FileUpload/ceiling-recorded-never-enforced` is
  about a marker *attribute* on the static no-script tier, and this floor emits no
  attribute bag and no document — the structural test the `Embed` and `Image`
  exemptions already meet. Recording it as residue instead would have been the
  wrong answer for the wrong reason: the slots are modelled, so it is not
  unadopted work.
- **Three specification adoption bars are open**: contract cards, timed advance
  on a `Switch`, and streamed upload. A host that has not adopted is not thereby
  exempt — it owes the obligation and has simply not made its answer visible.
  Note the DECODE half of the last two now lands here; what is unadopted is the
  RENDER answer, which is the half those bars are about.
- **Render obligations: several asserted, several declared exempt with reasons,
  two owed and unanswered.** The gate prints all three groups by name on every
  run; the repository's `CLAUDE.md` carries the current table and the rule that
  decides which group a claim lands in. The exemptions are real and specific:
  this floor carries **no playback engine, no network image loader and no
  browsing context**, so a `Media` node renders as a labelled transport tile,
  an `Image` as a placeholder box, and an `Embed` as a labelled frame tile.
  Pulling any of those in is not a decision a decoding surface makes on your
  behalf.
- **`Chart`, `MapNode`, `Mount` and `FragmentRef` render as informational
  stubs**, and `Sparkline` renders without data. Each has a real dispatch arm —
  none falls through — but none paints the thing itself yet. **The sparkline is
  a decision rather than a backlog item**: the cross-host lowering phase's
  contract is byte-equality against SVG goldens, and this surface emits no
  markup to compare, so it declines and pins the placeholder instead — the arm
  receives no `Sparkline` at all, so the series is unreachable from it by the
  type system, and a test asserts exactly that. Lowering means passing the
  kind, and the test goes red on that line.
- **A `Tree` renders FULLY EXPANDED whatever `expandedStateKey` names**, because
  this floor holds no state store to read the open-row set from. For a tree
  naming no key that is the specified rendering; for one that does, it is a
  degradation, stated here rather than papered over with a toggle that writes
  nothing. Rows state their own accessible names; there is no `tree`/`treeitem`
  role, no `aria-level` / `setsize` / `posinset`, no roving tabindex, no key
  bindings and no selection.
- **The `tooltip` trait decodes and reaches `Node.tooltip`, and the render floor
  reports it DROPPED.** §3.1 says the hint is a description and must never be
  projected as a name; Compose has exactly one announcement channel for a node's
  own text, and writing to it makes the string the name. So the one available
  projection is the one the specification forbids, and the honest answer is the
  accessibility projection's existing drop-set discipline — dropped, never
  refused, and never silently. An embedding app with a description channel of
  its own can project the slot itself.
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
