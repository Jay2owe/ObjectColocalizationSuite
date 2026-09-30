# Changelog

All notable changes to Object Colocalization Suite are recorded here. The
format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and
the version numbers follow the rules in `VERSIONING.md`.

## [Unreleased]

## [0.1.1] - 2026-09-30

Faster, with every output unchanged byte for byte. No method, default,
column or file changed; only the embedded measurement cores were updated.

### Changed

- Embedded cores updated to volcoloc-core 0.2.0 (label sizes and pair
  overlaps counted in bulk instead of one hash-map lookup per voxel),
  oc3d-core 0.2.1 (centroid scan without boxing each voxel's label) and
  territories-core 0.2.2 (3D territories assigned tile by tile instead of one
  nearest-object search per voxel). cpc-core 0.2.0 and opa-core 0.4.0 are
  unchanged. Each core release keeps its predecessor as a reference in its
  own tests and compares every output as raw bits.

### Performance

Measured end to end with `ocs.bench.ReleaseBenchmark` on the same compiled
plugin, first on the 0.1.0 core versions and then on these (median of 3,
16 logical processors on a machine already fully loaded by other work, so
the one-processor runs are the steadier figure):

| Case | Setting | 0.1.0 | 0.1.1 | Factor |
|---|---|---|---|---|
| A: Quick look + 200-shuffle chance test, 256 x 256 x 8 | default | 455 ms | 280 ms | 1.6x |
| A | one processor | 3.4 s | 1.2 s | 2.8x |
| B: six object methods + 50-shuffle chance test, 4 channels, 512 x 512 x 13 | default | 22.0 s | 7.5 s | 2.9x |
| B | one processor | 49.9 s | 15.5 s | 3.2x |
| D: territory occupancy in 3D | default | 1.55 s | 0.98 s | 1.6x |
| D | one processor | 4.6 s | 1.3 s | 3.5x |

CPU time for case B falls from 52 s to 22 s. Case C (whole-image
intensity) does not use the updated cores and is unchanged.

**Evidence that nothing moved:** `GoldenOutputTest` passes against the
committed 0.1.0 goldens byte for byte, and each benchmark case's full output
tree has the same SHA-256 as on 0.1.0 (A `5a737a18...`, B `1c0edfd1...`,
C `cfafa57f...`, D `26957cdd...`).

What remains in case B is mostly this plugin's own distance-tolerance search
and the repeated per-method scans of the same label images; sharing one scan
between methods needs a cache across engines and is left for a later version.

## [0.1.0] - 2026-09-30

First public release.

Thirteen colocalization methods over the same 2 to 5 label images, reported
per object in one table: six object methods (centroid coincidence, volume
overlap, bounding-box overlap, containment, Jaccard/Dice, centroid distance
tolerance), two intensity methods (per-object Pearson and Manders; field-wide
Pearson, Manders and Costes with Costes randomisation), four cross-type
spatial statistics (G, K, L, pair correlation) and Voronoi territory
occupancy. The measurements come from the sibling plugins' cores, bundled and
relocated (cpc-core 0.2.0, volcoloc-core 0.1.0, opa-core 0.4.0,
territories-core 0.2.1, oc3d-core 0.2.0) and checked against the sibling
plugins' outputs.

On top of the methods:

- a chance test for every method from one shuffled field per permutation,
  with a fixed seed contract (permutation *i* uses `Random(seed + i)`, so the
  worker count never changes a p value);
- method-against-method agreement in four tiers, including Cohen's kappa on
  verdicts across a batch;
- a threshold sweep with each method's flip fraction;
- Discovery, which puts each method in one of five classes (Usable,
  Uninformative here, Fragile, Divergent, Not applicable) with its evidence
  and no composite score;
- a two-step dialog (inputs, then methods with presets and a cost estimate
  shown before Run), macro recording and replay, a Java API, headless use,
  and folder batches that save as they go.

Defaults set by the alpha validation on an unpublished set of real
four-channel confocal fields: 1,000 shuffles (from 1,000 to 10,000, 2 of 180
verdicts changed), whole-channel displacement as the null model (relocating
objects one by one found no room in any surveyed field), raw two-sided p at
alpha 0.05 with the measured 1.45-fold false-positive inflation stated in
every run record, and a threshold sweep of ±20% of each method's range.

The changes below are those made while preparing the release, after the
first complete build.

### Added

- The menu dialog asks for channels, intensity images, a region ROI and an
  output folder, in an Inputs step before the method chooser. With more than
  five images open the user picks the channels instead of the first five being
  taken silently. The chooser greys the chance test when no region was given,
  with the reason on the switch.
- The recorded macro line now carries the intensity images, the region, the
  output folder and `hide_display`. `region_roi=[ROI Manager]` and
  `region_roi=selection` read the region from the screen at replay; the Log
  says so when a recorded line depends on them.
- `run-record.json` and `README.txt` now state what the numbers must be read
  with: the chance test's null model and the hypothesis it tests, shuffles,
  seed and seed-contract version, the nominal p floor `min(1, 2/(P+1))`, the
  measured 1.45x cross-field inflation of the whole-channel null, that
  bounding-box gets no p under it, the jaccard-dice tie note, and, whenever a
  spatial method ran, that the spatial methods are planar (xy centroids only).
  The run record also gains `nullModelKind` and `sweepWidth`.
- Batch runs assemble tier-V verdict agreement: for each channel direction,
  Cohen's kappa between every pair of methods' verdicts over the fields,
  written to `batch/verdict-agreement.csv` and returned by
  `OCSBatchResult.verdictAgreement()`. It is how the spatial methods enter the
  comparison. Discovery still classifies each field on its own evidence.
- The batch macro accepts every single-run analysis option (`sweep_width=`,
  `null_model`, `permutations=`, `seed=`, `threshold_<id>=`, ...) alongside
  its folder options, and the batch dialog has an "Other options" field for
  them. A batch run from the dialog now records a line the batch command can
  replay (before, it recorded the dialog's labels as keys).
- Batch output gains `run-records/<field>.json`, one run record per field. The
  top-level `run-record.json` is rewritten by each field and describes the
  last one.
- `OCSBatchResult.fieldNames()`: the fields that ran, in order.
- The batch dialog's folders and region file have Browse buttons (folder and
  file fields rather than plain text).

### Fixed

- Batch runs dropped the template's threshold-sweep width and null-model kind,
  so `sweep_width=` and a per-object chance test ran at their defaults on every
  field. A test now fails if `OCSParameters.Builder` gains a setting the batch
  does not carry.
- A batch field whose tables could not be saved was counted as run. It is now a
  failure, and a save folder that cannot exist (inside a file, on a missing
  drive, not writable) is refused before the first field instead of failing
  once per field. The single-run command checks the output folder before
  running too.
- Escape now stops the batch field in flight, not only between fields.
- Unwritable output folders and unreadable files show a sentence instead of a
  stack trace; running out of memory says how to give Fiji more. Stack traces
  are kept for genuine bugs.
- Macro values may contain backslashes, so Windows paths typed into a macro
  (`region_roi=C:\\data\\region.zip` in the macro string) are accepted, and
  the recorder doubles backslashes so recorded lines replay. A title holding a
  comma is kept whole when each channel has its own `channels=` key (the form
  the recorder writes); a single `channels=a,b` is still a list.
- Macro lines refuse more than five channels and seeds of magnitude above 2^53
  up front, as the dialog does.
- The batch macro no longer reads `folder=` out of `intensity_folder=`, and
  `subfolders` is a flag rather than any occurrence of the word, including one
  inside a path.
- RGB, multi-channel and time-series label images are refused for every method
  with a sentence saying what to do; before, only the methods that happened to
  check refused them, and the rest read interleaved planes as one volume.
- A region ROI covering no pixel of the image is refused for the chance test
  (usually a ROI set from another image); the whole-channel null would
  otherwise have run unconstrained by it.
- The whole-channel null no longer fails on 32-bit label images holding values
  near 2^31 (it sized a lookup table by the largest label).
- A method that cannot measure one of the chance test's shuffled copies (seen
  with territory occupancy on objects laid out on a perfectly regular grid,
  where the tessellation cannot resolve co-circular points) no longer stops the
  whole run: its chance test is reported as `FAILED_ON_A_SHUFFLE`, its observed
  values stand, and the other methods' tests are unaffected.
- Preset names are matched ignoring case, spaces and punctuation, and the part
  before a dash is enough (`preset:discovery`). On Windows a UTF-8 macro file
  is read in the platform encoding, which garbled the dash in
  "Discovery — everything" so that preset could not be named from a macro file.
- The "chance test needs a region" message names `region_roi=` and the dialog's
  Region field, not only the Java `domain(...)` call; presets that switch the
  chance test on reach it too.
- The "Discovery — everything" preset now classifies the methods. It switched
  on the three checks Discovery reads but not Discovery itself, so no
  Discovery table could be produced from the dialog at all, and
  `methods=[preset:Discovery]` needed a separate `discovery` flag. In the
  chooser, Discovery stays on while the selection is that preset's and all
  three checks are on.
- The Inputs step no longer pre-fills channels 3 to 5 with every other open
  image. With two label images and their two intensity images open, the
  usual setup for the intensity methods, the intensity images landed in
  channels 3 and 4 and the first OK failed. Channels 1 and 2 take the first
  two open images; the rest start at none unless remembered.
- A batch given an intensity folder but no intensity pattern ran without the
  intensity images and said nothing. The intensity images now default to the
  label pattern.
- Long preset descriptions wrap instead of widening the chooser behind a
  sideways scroll bar.
- Escape during the chance test stops the permutation already running. The
  permuted methods were handed a progress that could never be cancelled, so
  on a large field Stop waited out whole permutations (8 s measured).

### Changed

- The pre-run cost estimate no longer halves Jaccard/Dice and whole-image
  intensity. Both compute both directions in full, so the dialog showed them
  at half their cost.
- `block_size=`, `psf_xy=`, `psf_z=` and `min_object_voxels=` are refused with
  a message naming 0.2.0 instead of as unknown options.
- **Java API:** with auto-save on, `OCSBatchResult.results()` is empty: each
  field's full result is written and released instead of held until the batch
  ends, which exhausted the heap on large batches. Use `fieldNames()` and
  `fieldsRun()`; with auto-save off `results()` still returns every field.
- **Java API:** `OCSBatchParameters.workers()` and its builder setter are
  removed. Nothing read them; fields always ran one at a time.
- `channels=[a,b] channels=c` now means two channels, `a,b` and `c`; it used to
  mean three.
- **opa-core 0.2.0 -> 0.4.0.** The spatial methods' pointwise Monte Carlo
  envelope is now a rank envelope (opa-core 0.3.0 fix: the old interpolated
  band escaped 6.9% of the time at 99 simulations while labelled 95%). Its
  `Lower`/`Upper` series therefore differ; they are not written to any table,
  so no output file changes. `Observed`, `Expected`, `Global p` and
  `Max Deviation` are unchanged, as are all golden tables. The alpha
  validation covered the object methods only, so none of its numbers move.
- The curves table gains `Envelope Level` (the escape probability the
  envelope actually delivers, 0.04 at the default 99 simulations, a 96% band),
  `Saturation Radius` and `Saturated Radii` (cross-G radii past which the band
  collapses to a point and carries no information; NaN and 0 for the K-based
  curves).
- **territories-core 0.1.0 -> 0.2.1.** Territory results are bit-identical;
  Escape now stops a 3D territory-occupancy run inside the territory
  assignment instead of only between channel pairs.

### Performance

- Spatial methods about 10x faster through opa-core 0.4.0's single-pass K
  (`SuiteBenchmarkTest`, 256 x 256 x 8, 60 objects a channel, 99
  simulations): cross-K 397-414 ms to 42-49 ms, cross-L 445-863 ms to
  43-45 ms, pair correlation 555-809 ms to 43-44 ms; cross-G unchanged
  (21-37 ms).
- territories-core 0.2.1 parallelises the 3D territory assignment itself,
  which is slower on small stacks and nests inside the chance test's own
  workers. Measured: territory occupancy at 256 x 256 x 8 went from 877-928 ms
  to 3,758-3,821 ms with the core's default parallelism, and 806 ms with
  `-Dterritories.parallelism=1`; a 24-shuffle territory chance test at 4
  workers (256 x 256 x 16) took 278-306 s at the default against 277 s at 1,
  with about 20 MB (4%) more peak heap. The plugin does not set the property,
  because it is JVM-wide and shared with Object Territories; add
  `-Dterritories.parallelism=1` to Fiji's Java options for many small 3D
  fields.

### Deferred to 0.2.0

- Clustering and phenotyping (k-means, DBSCAN): needs the profile columns to be
  trusted first.
- Surface-contact fraction and dilated-mask colocalization: new code with no
  source to lift; the object family already has six methods.
- Coordinate-based colocalization (CBC) and SODA: new implementations that need
  their own validation.
- Spearman, Kendall, Li's ICQ, Manders k1/k2 and Van Steensel: not needed to
  make the suite honest.
- Cross-F: `opa-core` has cross-G/K/L but not cross-F.
- Cluster-coloured maps and pooled batch model fitting: follow clustering.
- Coincidence maps (`maps/<image>-<method>.tif` in the auto-save tree): the
  contract fixes the path but not what a map shows, so there is nothing to
  build against yet.
- `block_size=`, `psf_xy=`, `psf_z=`, `min_object_voxels=`: they configure how
  the intensity methods are built, which the parameter bundle cannot reach yet.
  0.1.0 uses a 5 x 5 voxel Costes block (recorded in the Block Size column) and
  per-object Pearson for objects of 3 or more voxels.
- A minimum object size for per-object intensity metrics: the 20-voxel figure
  was never validated; closing it needs a per-object Pearson error sweep by
  object size.
- Raising `RANDOMIZATION_VOXEL_LIMIT` above 50 million voxels: needs a benchmark
  on a real 2048 x 2048 x 40 stack.
- Per-region summary statistics and a region axis on results: territory-plugin
  outputs rather than colocalization outputs.
- A `ColumnSpec` per-object flag: `EngineResult.isWholeDirection()` covers the
  need today.
- Shared executor plumbing for the two parallel intensity loops: two copies of
  one cancellation contract, working and tested.
- A torus-aware bounding box, so bounding-box could have a p under the
  whole-channel null.
- A per-field region ROI in batch: one shared region file serves every field.
- Tier-V verdict agreement feeding back into each field's Discovery class.
- Guided "recommend a method from data properties" (0.3.0): a separate,
  unvalidated claim.

### Build

- Added the Maven wrapper and a GitHub Actions build that installs the five
  embedded cores at their pinned tags, runs the full test suite and checks the
  shaded jar (no unrelocated `sc/fiji/`, `org/locationtech/` or `ij/`
  classes; both plugin entry classes and `plugins.config` present).
- The test for cancelling queued Costes permutations no longer depends on
  machine load: it drives a gated executor and counts exactly, instead of
  timing a race against a bound. The three completion-order tests no longer
  rely on sleeps to reverse the order; they hold the queued tasks and run them
  last-first, so they pass under any machine load.
- Added `ReleaseBenchmark` (test sources, a `main`): four fixed synthetic
  cases timed by wall and CPU time, each checked for identical saved output
  across runs by a SHA-256 over the whole tree. Profiling it placed the
  remaining cost inside the embedded cores (overlap scans, centroid scans,
  territory search) and the Costes block shuffle; no bit-identical change in
  this plugin measured faster, so none was made.
- Added a golden output gate (`GoldenOutputTest`): every table and every
  auto-saved file for four seeded synthetic cases is compared byte for byte
  with a committed copy, and the chance test is checked identical at 1 and 4
  workers.
