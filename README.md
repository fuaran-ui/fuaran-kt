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

## Build + test

```powershell
pwsh ./run.ps1              # compile + corpus harness (+ desktop JNI session leg when the Rust toolchain is present)
pwsh ./run.ps1 -SkipTests   # compile only
pwsh ./run.ps1 -SkipBuild   # re-run the harnesses against the existing jar
```

The reference dev box has no Gradle binary, so `run.ps1` compiles directly with
`kotlinc` / `javac` / `java`. The `build.gradle.kts` files are a forward-looking
scaffold for a future Gradle wrapper. Every leg skips cleanly when a prerequisite (JDK,
corpus, Rust toolchain, C compiler) is absent.

## Corpus

The render-coverage harness certifies against the shared `../wire-format-fixtures/`
corpus (the `manifest.json` enumeration is authoritative): every node round-trip fixture
decodes into the sealed model with zero fallback-arm hits. When the corpus is absent the
harness skips cleanly, so the repo is standalone-testable.
