# Stability record — fuaran-kt

This file records each change to what this surface ACCEPTS or REFUSES on the wire, so a consumer
reading a version can tell what adopting it costs. fuaran-kt is a native Kotlin surface over the Rust
reference core: a decode-only render projection, never a conformant host, so each entry is a change
to the projection's accept set, stated against the corpus revision it adopts.

## Unreleased

### The corpus pin moves to `8b71a3ea0c678159aabeceb516fb8b6a8c7daa07` (Phase 2411)

`corpus-pin.json` moves past the corpus commits of Phase 2286 and Phase 2206. Both answers below land
with it, because at the earlier pin the corpus still pinned the old ones.

- **Narrower number acceptance (Phase 2286).** The JSON reader refuses, as `INVALID_JSON`, an
  overflowing exponent (`1e999`), which used to read as an infinity, and an integer token past 2^53
  that is not the canonical float layout of the double it reads as (`9007199254740993`), which used to
  read as the nearest double. `1e-999` still reads as zero. Class: narrowing; a document carrying
  either token was already malformed under WIRE_FORMAT.md section 20.2 row 7 and section 2 rule 5 as
  amended.
- **The timestamp unit (Phase 2206).** An embedded `Transform` source stays held raw, but the
  projection now checks its declared column types and its timestamp cells (WIRE_FORMAT.md section
  3.6.25): a column type outside `int`, `float`, `bool`, `string`, `date`, `timestamp`,
  `timestamp_ms`, `timestamp_us`, `timestamp_ns` is refused; a present timestamp cell must be canonical
  instant text with a fraction no finer than its unit, or a whole epoch number below 9e15 in magnitude
  read in the column's unit, naming an instant in the years 0000-9999. Every such refusal is
  `WRONG_TYPE` at the Transform's `.source` path. Class: additive for the three sub-second tags (they
  were held raw, never refused), and narrowing for malformed timestamp cells, which were held raw
  unchecked.
