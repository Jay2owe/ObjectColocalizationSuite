# Versioning

This project uses semantic versioning:

- patch releases fix behaviour without intentionally changing outputs;
- minor releases add backward-compatible methods, measurements or options;
- major releases may change defaults, column meaning, file layout or APIs.

Before 1.0.0 a minor release may also carry a breaking change, and when it
does the CHANGELOG says so under its own heading.

## What counts as public API

A change to any of these is a change to the public API:

- **Method ids** (`cpc`, `volume-overlap`, `bounding-box`, `containment`,
  `jaccard-dice`, `distance-tolerance`, `per-object-intensity`,
  `whole-image-intensity`, `cross-g`, `cross-k`, `cross-l`,
  `cross-pair-correlation`, `territory-occupancy`). They appear in macros,
  in column headers and in run records, so an id is never renamed or reused.
- **Macro keys** of `Object Colocalization Suite` and `Batch (folder)`, their
  meaning and their defaults.
- **Table and column names** in the saved output tree and in the result
  windows, and the layout of that tree (`per-object/`, `summary/`,
  `run-records/`, ...).
- **The seed contract**: the same inputs, settings and seed give the same
  shuffles and the same p values. The run record carries the contract
  version; a change to how shuffles are drawn raises it.
- **The Java API** in the `ocs` package: `OCS`, `OCSParameters`, `OCSResult`,
  `OCSBatchRunner`, `OCSBatchParameters`, `OCSBatchResult` and the types they
  return. Classes under `ocs.internal` are relocated libraries, not API.

## Releases

During development Maven builds use `-SNAPSHOT`. A release removes that
suffix, dates the CHANGELOG entry, updates `CITATION.cff` and tags the
matching `vMAJOR.MINOR.PATCH`.

Scientific output changes are called out explicitly, including threshold
semantics, column definitions, chance-test defaults and any change that can
alter which objects are called coincident.
