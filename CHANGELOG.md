# Changelog

## Unreleased

### Changed (Phase 2300, rule 1; Phase 1962)

- A field holding its declared default is omitted from the canonical form on every kind. Fifteen
  vocabulary rows moved to omit-at-default and the decoder restores each default when the member is
  absent: `Flex.wrap`, `Link.download`, `List.ordered`, `CodeBlock.lineNumbers`, `FormField.required`,
  `FileUpload.multiple` (`false`); `CodeBlock.copyable`, `Modal.dismissable` (`true`);
  `CodeBlock.highlightLines` (empty); `Heading.variant` (`Standard`), `Image.variant` (`Default`),
  `Badge.variant` (`Neutral`), `Button.variant` (`Secondary`), `SegmentedChoice.orientation`
  (`Horizontal`); `Stepper.activeStep` (`Static 0`, the `Tabs.activeIndex` shape, declared in the
  dialect). An explicit default on the wire is still read. The generated model, enums, union tags and
  categories were regenerated from the corpus IDL; `corpus-pin.json` moves to the corpus commit that
  carries the change.
- A multi-select `Select` carries `values` and no `value`: `Select.value` is nullable, a single-select
  document without it is refused (`MISSING_FIELD` at `$.kind.value`), and a multi-select document
  carrying one is read with the `value` dropped. `Select.multiple` is an ordinary optional member read
  as authored (`nodes/select-multiple-false.json`). A consumer that read `value` as non-null handles
  `null`.

### Added (Phase 2331)

The model now carries every wire data field the vocabulary declares. New properties, each appended
at the END of its type's constructor:

- `Chart`: `valueFormat: NumberFormat?`, `xTitle: TextSource?`, `yTitle: TextSource?`,
  `subtitle: TextSource?`, `legendPosition: ChartLegendPosition?`, `dataLabels: ChartDataLabels?`,
  `xScale: ChartXScale?` — with the new enums `ChartLegendPosition`, `ChartDataLabels`, `ChartXScale`.
- `DataGrid`: `reorderable: Boolean = false`, `windowStateKey: String?`, `rowTotal: Binding?`.
- `GridColumn`: `sortable: Boolean?`, `editable: Boolean?`.
- `DrawStyle`: `rotation: Double?`, `tip: TextSource?`.

Each new parameter has a default (`null`, or `false` for `reorderable`), so positional and named
calls keep compiling. The JVM constructor signature of each grown data class gains trailing
parameters, so a binary consumer must recompile. The Compose renderer does not yet show these
members; `docs/RENDER-PROJECTION.md` ("Carried but not rendered") lists why.

### Changed (Phase 2331)

- Union dispatch in the decoder reads every `$type` through a generated per-union case-tag set and
  an `else`-free `when`, so a union case added to the vocabulary is a compile error until decoded.
  A mechanism change only: the same documents decode, and the same ones are refused with the same
  code at the same path.
- `MountDirection.InOnly` is no longer accepted by the decoder: a document whose
  `channel.direction` is `"InOnly"` is refused with `UNKNOWN_DU_CASE` at `$.kind.channel.direction`,
  as for any direction the language does not have. The constant itself stays until Phase 2303's
  breaking cut.
