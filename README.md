# Object Colocalization Suite

[![Build](https://github.com/Jay2owe/ObjectColocalizationSuite/actions/workflows/build.yml/badge.svg)](https://github.com/Jay2owe/ObjectColocalizationSuite/actions)
[![License: BSD-3-Clause](https://img.shields.io/badge/License-BSD_3--Clause-blue.svg)](LICENSE)

**Which colocalization method fits this dataset?**

A Fiji/ImageJ plugin that runs thirteen colocalization methods over the same
2 to 5 label images and reports every one of them **per object**, side by
side: object overlap, centroid coincidence and distance, intensity
correlation, spatial point-pattern statistics and territory occupancy. Each
method can be tested against chance by shuffling the objects, the methods can
be compared with each other, and a Discovery mode sorts them into those that
are informative on your data and those that are not.

It is not *Colocalization Object Counter*, which counts objects and assigns
each a colocalization category. This plugin runs many methods on every
object and reports which of them to trust.

It reads label images from any segmentation (StarDist, Cellpose,
thresholding, MorphoLibJ, manual), in 2D or 3D.

## Installation

### Update site

1. Start Fiji and open `Help > Update...`.
2. Press **Manage update sites**, then **Add Unlisted Site**:
   - Name: `Object-Colocalization-Suite`
   - URL: `https://sites.imagej.net/Object-Colocalization-Suite/`
3. Close the list, **Apply changes** and restart Fiji.

The update site goes live with the first upload of 0.1.1; until then use the
jar below.

### Manual

Download `Object_Colocalization_Suite-0.1.1.jar` from
[Releases](https://github.com/Jay2owe/ObjectColocalizationSuite/releases),
put it in Fiji's `plugins/` folder and restart. It needs no other update
site: the measurement cores it shares with its sibling plugins are bundled
under their own package names, so both can be installed side by side.

## Quick start

Open two or more label images (one object per label value), then
`Plugins > Object Colocalization Suite > Object Colocalization Suite`.

1. **Inputs.** Pick a label image per channel (channels 3 to 5 are optional),
   the matching raw intensity images if you want the intensity methods, a
   region ROI (a `.roi` or `.zip` file, the ROI Manager or the active
   selection) if you want the chance test, and an output folder if the
   tables should be saved.
2. **Methods.** Choose a preset, or tick methods by hand. Methods your inputs
   cannot feed are greyed with the reason. The line under the list estimates
   the cost before you press Run.
3. **Extra checks.** Test each method against chance, compare the methods
   with each other, and check how much each setting matters.

The results open as `OCS <table>` windows and, with an output folder, are
saved with a run record that repeats the run.
`Plugins > Object Colocalization Suite > Batch (folder)` runs the same analysis over a folder of fields.

| Preset | Methods | Extra checks |
|---|---|---|
| Quick look (default) | `cpc`, `volume-overlap` | none |
| Object colocalization | the six object methods | all three |
| Intensity colocalization | `per-object-intensity`, `whole-image-intensity` | all three |
| Spatial arrangement | the four spatial methods, `territory-occupancy` | chance, agreement |
| Discovery — everything | all thirteen | all three, plus Discovery |

In a macro the preset is named by its first word, for example
`methods=[preset:Discovery]`.

## Methods

Method ids are stable public API: they appear in macros, column headers and
run records.

| Id | Family | What it measures | Same measure as |
|---|---|---|---|
| `cpc` | object | whether each object's centroid falls inside an object of the other channel | [CPC](https://github.com/Jay2owe/CPC) |
| `volume-overlap` | object | percentage of each object's volume overlapped by its best partner | [Volumetric Colocalization](https://github.com/Jay2owe/VolumetricColocalization) |
| `bounding-box` | object | overlap of the objects' bounding boxes | Volumetric Colocalization |
| `containment` | object | whether one object lies inside the other, both ways round | — |
| `jaccard-dice` | object | Jaccard index and Dice coefficient with the best partner | — |
| `distance-tolerance` | object | distance to the nearest centroid of the other channel, within a tolerance | — |
| `per-object-intensity` | intensity | Pearson and Manders inside each object | — |
| `whole-image-intensity` | intensity | field-wide Pearson, Manders and Costes thresholds with Costes randomisation | — |
| `cross-g` | spatial | cross-type nearest-neighbour G function | [Object Proximity Analysis](https://github.com/Jay2owe/ObjectProximityAnalysis) |
| `cross-k` | spatial | cross-type Ripley's K | Object Proximity Analysis |
| `cross-l` | spatial | cross-type Ripley's L | Object Proximity Analysis |
| `cross-pair-correlation` | spatial | cross-type pair correlation g(r) | Object Proximity Analysis |
| `territory-occupancy` | territory | whether each object sits inside a Voronoi territory of the other channel | [Object Territories](https://github.com/Jay2owe/ObjectTerritories) |

Every method runs in both directions for every pair of channels (A to B and
B to A) unless `one_direction` is given.

For a single focused question, the sibling plugin in the last column is
usually the better tool: it has the full options for that one measure. This
plugin is for comparing methods on the same objects.

## The chance test

The chance test shuffles the objects and re-runs every selected method on
each shuffled copy, so each method's count of coincident objects gets a
permutation *p*. Defaults and what they mean:

- **1,000 shuffles.** The nominal smallest two-sided *p* is
  `min(1, 2/(shuffles+1))`, about 0.002 at 1,000. Ties can raise it; the
  dialog shows the floor beside the shuffle count.
- **Whole-channel displacement.** Each channel is slid by a random in-plane
  offset, wrapping at the edges. It keeps every object's shape and the
  channel's own arrangement, and it runs on crowded tissue where relocating
  objects one by one cannot find room. Per-object relocation inside the
  region is available from the Java API (`nullModelKind(...)`).
- **Raw two-sided *p*, alpha 0.05 per test.** No multiple-comparison
  correction is applied, because the plugin cannot know whether your family
  is one field, one method across fields or the whole study.
- **Calibration caveat.** On independent real channels the whole-channel
  null gave *p* < 0.05 at 0.029 against an exact expectation of 0.020: a
  measured 1.45-fold inflation. Carry that into any inference.
- **`bounding-box` has no *p* under whole-channel displacement**, because an
  object that wraps across the edge gets a bounding box the size of the
  frame. Its observed values stand.
- **`jaccard-dice` ties.** Its coincident count ties almost everywhere on
  typical data, which makes it uninformative for the chance test; that is
  not a calibration failure.
- **The spatial family is planar.** Its statistics are 2D, so a 3D object
  contributes its xy centroid only. The object, intensity and territory
  families are fully 3D.
- **Envelope level.** The spatial curves carry their own simulation
  envelopes rather than the shared chance test; the run record says which.

The chance test needs a region ROI saying where objects may be. It will not
default to the whole frame, because scattering objects over areas that are
not tissue makes every result look more significant than it is.

## Discovery

With Discovery on, every method is placed in one class, with the evidence
that put it there and no composite score:

| Class | Meaning |
|---|---|
| Usable | beats chance, stable under its setting, consistent with the others |
| Uninformative here | cannot beat chance on this data (for example a tied count) |
| Fragile | the verdict flips for more than 10% of objects when its setting moves |
| Divergent | disagrees with the other methods; worth a look, not marked down |
| Not applicable | the inputs cannot feed it, or there are fewer than 30 objects to judge |

Methods agreeing is not proof: Jaccard, Dice and volume overlap restate one
another, so three of them agreeing is one idea counted three times. On data
where objects abut rather than overlap, a distance-based method *should*
disagree with the overlap methods.

## Output

With an output folder, one run writes:

```
<output>/Object Colocalization Suite/
  README.txt              how to read the tables, with this run's caveats
  run-record.json         every setting, the seed and the seed-contract version
  per-object/<image>.csv  one row per object per direction, every method's columns
  summary/<image>.csv     one row per method per direction
  whole-direction/        field-wide values (whole-image intensity)
  curves/                 spatial curves and their envelopes
  null-model/             observed and expected counts, enrichment, p
  agreement/              method-against-method agreement
  threshold-sensitivity/  flip fraction per method
  discovery/              one class per method, with its evidence
  skipped/                methods asked for that could not run, and why
```

Empty tables are not written. A batch adds `batch/` (all fields in one
table per kind, plus `verdict-agreement.csv`) and `run-records/<field>.json`,
one run record per field.

## Macro

```javascript
run("Object Colocalization Suite",
    "channels=[C1.tif] channels=[C2.tif] intensity=[raw1.tif] intensity=[raw2.tif] " +
    "region_roi=[C:/data/region.zip] methods=[preset:Object colocalization] " +
    "permutations=1000 seed=42 output=[C:/data/results] hide_display");
```

Channels are open window titles or file paths. Give one `channels=` key per
channel (or one key with commas between titles), and either no `intensity=`
or one per channel. Run the dialog once with the Macro Recorder open to get
the line for your own settings.

| Key | Default | Meaning |
|---|---|---|
| `channels=` | required | label images, 2 to 5, by title or path |
| `intensity=` | none | raw intensity images, one per channel |
| `region_roi=` | none | the region: a `.roi`/`.zip` path, `[ROI Manager]` or `selection` |
| `methods=` | `preset:Quick look` | method ids separated by commas, `preset:<name>` or `all` |
| `threshold_<id>=` | method default | a method's own setting, e.g. `threshold_volume-overlap=45` |
| `bidirectional` | on | both directions for every pair |
| `one_direction` | off | first channel to the others only |
| `null_model` | off | test each method against chance (presets may switch it on) |
| `permutations=` | 1000 | shuffles for the chance test |
| `seed=` | 20260812 | shuffle seed, recorded in the run record |
| `agreement` | off | compare the methods with each other |
| `threshold_sweep` | off | check how much each method's setting matters |
| `sweep_width=` | 0.2 | the sweep's half-width, as a fraction of each method's range; 1 sweeps the whole range |
| `discovery` | off | classify the methods (switches on what it needs) |
| `alpha=` | 0.05 | significance level used by Discovery |
| `flip_threshold=` | 0.10 | flip fraction above which a method is Fragile |
| `min_compare_n=` | 30 | fewest shared objects before two methods are compared |
| `output=` | none | folder to save the tables in |
| `hide_display` | off | open no windows (for headless and batch use) |

## Batch

`Plugins > Object Colocalization Suite > Batch (folder)` groups a folder's files into fields by a regular
expression, shows what will run, then analyses each field and saves as it
goes. Escape stops it between or within fields; what finished is kept.

```javascript
run("Batch (folder)",
    "folder=[C:/data/labels] pattern=(.*)_C(\\d)\\.tif channel_group=2 " +
    "output=[C:/data/results] methods=[preset:Quick look] sweep_width=0.3");
```

| Key | Default | Meaning |
|---|---|---|
| `folder=` | required | folder of label images |
| `pattern=` | `(.*)_C(\d)\..*` | regular expression over file names |
| `channel_group=` | 2 | which bracketed group differs between channels of one field |
| `subfolders` | off | search subfolders too |
| `intensity_folder=` | none | folder of intensity images |
| `intensity_pattern=` | same as `pattern=` | pattern for the intensity images |
| `output=` | beside the labels | folder to save in |

Every single-run key except `channels=`, `intensity=` and `hide_display`
also applies, to every field. `region_roi=` gives one region for all fields.

## Java API

The API opens no dialogs, shows no windows and writes no files.

```java
OCSResult result = OCS.run(OCSParameters.builder(labelImages)
        .intensityImages(intensityImages)
        .domain(regionRois)
        .preset("Object colocalization")
        .permutations(1000).seed(42L)
        .build());

ResultsTable perObject = OCSTables.perObject(result);
OCSOutputWriter.write(result, new File("results"));
```

`OCSBatchRunner.run(OCSBatchParameters)` is the batch equivalent.
`VERSIONING.md` lists what counts as public API.

## How long a run takes

Measured on a 16-thread desktop with the default settings (synthetic
fields; wall time, range over repeated runs):

| Run | Time |
|---|---|
| Quick look + chance test, 200 shuffles, 2 channels 256 x 256 x 8, 60 objects each | 0.5-0.9 s |
| Object colocalization + chance test, 50 shuffles, 4 channels 512 x 512 x 13, 500 objects each | 19-27 s |
| Whole-image intensity (Costes randomisation), 2 channels 512 x 512 x 13 | 3.0-3.4 s |
| Territory occupancy in 3D, 2 channels 512 x 512 x 13 | 1.7-2.0 s |
| Real four-channel tissue field, 1024 x 1024 x 13, 1,000 shuffles, four workers | 5-22 min (median 11.4) |

The chance test multiplies the run by the number of shuffles, and the cost
line in the dialog tracks the count you type.

Territory occupancy on many small 3D fields is faster with its core's own
threading off, because the chance test already runs shuffles in parallel.
Add the Java option `-Dterritories.parallelism=1` to the options Fiji starts
Java with. The plugin does not set it itself, because the setting is shared
with Object Territories.

## Validation

Before release the defaults were checked on an unpublished set of 330
four-channel confocal tissue fields (1024 x 1024 x 13 voxels, 29 to 2,309
objects per field):

- **Threshold sweep.** Sweeping each method's whole range called 83% of
  results Fragile; sweeping ±20% of the range around the chosen setting
  called 36%, the ones that genuinely move. ±20% (`sweep_width=0.2`) is the
  default.
- **Shuffles.** From 100 to 1,000 shuffles 21 of 720 verdicts changed; from
  1,000 to 10,000, 2 of 180. 1,000 is the default.
- **Null model.** Relocating objects one by one refused all 27 surveyed
  fields; whole-channel displacement ran on all of them.
- **Calibration.** Across 24 fields and 1,440 tests, the whole-channel null's
  false-positive rate on independent channels was 1.45 times nominal (see
  above).
- **Time.** At 1,000 shuffles a four-channel field of this size took 5 to 22
  minutes, median 11.4, with four workers.

The engines themselves are checked against the sibling plugins' outputs and a
hand-computed reference dataset in the test suite.

## Citing

Please cite this plugin and the sibling plugin whose measure you report
(see `CITATION.cff`):

> Malcolm, J. (2026). Object Colocalization Suite (v0.1.1) [Software].
> https://github.com/Jay2owe/ObjectColocalizationSuite

Methods-section form:

> Colocalization was quantified per object with Object Colocalization Suite
> (v0.1.1) for Fiji, using volumetric overlap as in Volumetric Colocalization
> (v0.1.0); each method was tested against chance with 1,000 whole-channel
> displacements within the region of interest (seed 20260812), reporting raw
> two-sided permutation p values.

## Acknowledgements

Developed by Jamie Malcolm in the [Brancaccio Lab](https://www.ukdri.ac.uk/labs/brancaccio-lab)
at the [UK Dementia Research Institute](https://ukdri.ac.uk/centres/imperial),
Imperial College London.

This work was supported by the UK Dementia Research Institute,
which receives its core funding from the UK Medical Research Council,
the Alzheimer's Society, and Alzheimer's Research UK.

Built on the [Fiji](https://fiji.sc/) / [ImageJ](https://imagej.net/)
ecosystem; we thank the SciJava community for the platform.

## License

BSD 3-Clause. See [LICENSE](LICENSE) and, for the bundled libraries,
[NOTICE](NOTICE).
