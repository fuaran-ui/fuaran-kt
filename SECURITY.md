# Security Policy

## Supported versions

The `fuaran-kt` library is pre-1.0. Security fixes are applied to the latest released `0.x` version on the
`main` branch. Older pre-releases are not maintained.

## Reporting a vulnerability

Please report suspected vulnerabilities privately — do **not** open a public issue.

- **Preferred:** GitHub's private vulnerability reporting (the repository's **Security** tab →
  **Report a vulnerability**).
- **Or email:** security@diametrical.co.uk — include a description, the affected version, and steps
  to reproduce.

We aim to acknowledge a report within five business days and to agree a disclosure timeline with
you. Please allow a reasonable window to ship a fix before any public disclosure.

## Scope

This repo is a render-projection surface over the Rust reference core: it decodes the
session's tree JSON into native sealed types for rendering (it never canonically encodes).

- **Decoder robustness:** malformed or adversarial tree JSON must produce a typed failure,
  never a crash or an out-of-bounds read.
- **JNI boundary:** the hand-written JNI binding over `fuaran-rs`'s C-ABI — lifetime or
  ownership defects (double-free, use-after-free, races past the single-threaded-dispatcher
  confinement, `Cleaner`-vs-explicit-close hazards) are in scope.
