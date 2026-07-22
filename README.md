# fuaran-kt

The **native Kotlin surface of the Fuaran UI wire format**, over the Rust reference
core. A JVM / Android render-and-authoring surface: it decodes the canonical wire tree
into native, compile-time-exhaustive Kotlin `sealed` types for rendering, and (via a
thin JNI binding) drives a live session whose truth and mutation live in the
corpus-certified Rust core.

Apache-2.0 from day one.

## What this is — and is not

- **A render projection, not a seventh conformant host.** The Rust reference core owns
  the canonical codec, the tree-op apply engine, and mutation, exposed through a small
  C-ABI. The Kotlin side holds a consumer-grade **decoder** into `sealed` classes, held
  to a "decodes every corpus node fixture" bar — never the byte-parity bar (it has no
  canonical encoder).
- **Exhaustive by construction.** Every closed wire DU (`NodeKind`, `Binding`, `Action`,
  `Shape`, …) is a `sealed` hierarchy, so a per-kind `when` is checked at compile time:
  a new wire kind that lands without its dispatch arm is a build error, not a silent
  runtime fallback.

## Modules

| Module | Role |
|---|---|
| `fuaran-ui` | The pure-JVM sealed tree model + render-projection decoder + corpus coverage harness. Dependency-light: the JSON reader is hand-rolled, no runtime dependency. Testable on any JVM. |
| `fuaran-core` | The C-ABI JNI binding (Phase 543): the hand-written JNI shim over the Rust session surface + the `FuaranSession` confined wrapper + native packaging. |
| `fuaran-renderer` | The Jetpack Compose render floor (Phase 544) + Material tone bridge and interaction round-trip (Phase 545). The corpus render-coverage gate runs headlessly under Robolectric. |
| `fuaran-driver` | The server-driven (SDUI) driver: fetch a tree, apply streamed ops against a session, post interaction events back. Pure JVM. |
| `samples` | An Android sample app wiring a live session through the interactive renderer. |

## Build + test

```powershell
pwsh ./run.ps1              # compile + corpus harness (+ desktop JNI session leg when the Rust toolchain is present)
pwsh ./run.ps1 -SkipTests   # compile only
pwsh ./run.ps1 -SkipBuild   # re-run the harnesses against the existing jar
```

`run.ps1` compiles the pure-JVM legs directly with `kotlinc` / `javac` / `java`, then
runs the Gradle-wrapper legs (the Compose render-coverage gate under Robolectric, the
driver gate, the live JNI interaction round-trip, and the sample `assembleDebug`) when
the Android SDK is present. The renderer's Robolectric unit tests run on the debug
variant only (the Compose test activity merges into the debug manifest, not the release
AAR). Every leg skips cleanly when a prerequisite (JDK, corpus, Android SDK, Rust
toolchain, C compiler) is absent.

## Corpus

The render-coverage harness certifies against the shared `../wire-format-fixtures/`
corpus (the `manifest.json` enumeration is authoritative): every node round-trip fixture
decodes into the sealed model **and composes through the Compose render floor** with zero
fallback-arm hits — every node kind the corpus carries has a real dispatch arm. When the
corpus is absent the harness skips cleanly, so the repo is standalone-testable.

The decoder tracks the wire format's **0.2.x canonical wave**, mirroring the Rust
reference core's decode semantics:

- **Bare-string `Literal`** is the canonical text form (`"label": "Revenue"`); the
  `{"$type":"Literal"}` envelope stays decode-accepted.
- **The `value` rename law** — a scalar displayed value is named `value` (`Metric`,
  `LabelValueRow`; `data` decodes as an alias, the retired `source` spelling does not).
- **`Fact`** — the labeled text-fact kind, `Metric`'s complementary type.
- **Filters unification** — a filter chip's control is an ordinary `FormFieldKind`
  (the retired `FilterKind` discriminators hard-reject), including the dual-thumb
  `Range` control with its bare `{min, max}` Static pair; a control with no `value`
  key auto-binds (`Filter(name)` on a chip, `State(field id)` with a typed placeholder
  on a form field).
- **Omit-when-default fields** are absent from canonical bytes and restored on decode:
  `Metric`/`LabelValueRow` style fields, `Progress.indeterminate`, `DataGrid.editable`,
  `Callout.dismissable`, `Toast.dismissable` (the one omit-when-**true**),
  `Tabs.orientation`, `SegmentedChoice.orientation`, grid-column `format`/`width`.
- **`Binding.Filter.defaultValue`**, **`Binding.Selection.defaultValue`** and
  **`Selection.field`** (the declarative row-field projection).
- **`DrawStyle.markId`** — keyed mark identity on data-bearing `Drawing` shapes
  (object constancy under reorder/refresh).
