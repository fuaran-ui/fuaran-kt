# Contributing to fuaran-kt

This repo is licensed **Apache-2.0** (see [`LICENSE`](LICENSE)). Contributions are welcome under
the same licence. The conventions below keep the tree green and the wire format stable.

## Contribution licensing — Developer Certificate of Origin

Every commit must be signed off under the [Developer Certificate of Origin 1.1](https://developercertificate.org/)
to certify you have the right to contribute the code under Apache-2.0. Add a `Signed-off-by:`
trailer to each commit:

```
git commit -s -m "feat: your change"
```

A pull request without DCO sign-off on every commit will not be merged.

## Per-commit hard requirements

1. **`pwsh ./run.ps1` is green** — the one-command gate: build, then every verification leg in
   one pass. Since Phase 1654 the legs COLLECT their failures rather than aborting at the first,
   so a red run names every failing leg at the end; read that summary rather than the first
   error.
2. **Formatting** — match the existing code style by hand: 4-space indent, trailing commas,
   Kotlin official style. **`run.ps1` runs NO format check**, and this line used to say it did.
   ktlint is the intended gate and has not landed, so formatting here is a review obligation
   rather than a mechanical one — which is the whole reason it is worth saying plainly. Sending
   a contributor to a gate that does not exist means unformatted code reaches review believing
   it was checked.
3. **Conformance** — the decoder must keep decoding every corpus node fixture (the decode-only bar — this surface never canonically encodes, so there is no byte-parity leg).

## Pull request flow

1. Branch from `main` with a descriptive name (`feat/<short-name>`, `fix/<short-name>`,
   `docs/<short-name>`).
2. Make focused, DCO-signed commits. Group related changes; do not bundle unrelated cleanups.
3. Run the per-commit hard requirements above.
4. Open a PR describing the change and its wire-format impact.
5. A maintainer reviews and merges.
