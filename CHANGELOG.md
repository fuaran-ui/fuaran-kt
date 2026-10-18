# Changelog

## Unreleased

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
