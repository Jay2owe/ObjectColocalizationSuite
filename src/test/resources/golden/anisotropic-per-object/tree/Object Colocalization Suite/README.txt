Object Colocalization Suite
Version <normalised>
Image: aniso A

Every setting that could change these numbers is in run-record.json, including the seed.

Folders here:
  per-object/             one row per object per channel pair, one column per method  (16 rows)
  summary/                one row per channel pair  (2 rows)
  null-model/             each method against chance, with the seed that produced it  (12 rows)
  agreement/              how far the methods agree with each other, object by object  (90 rows)
  threshold-sensitivity/  how much each answer depends on where its cut-off was drawn  (11 rows)
  discovery/              each method classified, with the evidence behind it  (12 rows)

Read these numbers with:
  - Chance test: per-object null (each object relocated independently inside the region). It tests whether the objects meet no more and no less often than they would if each were placed independently inside the region, keeping its shape and size. 49 shuffles, seed 20260812, seed contract version 1.
  - The smallest two-sided p this run can report is min(1, 2/(49+1)) = 0.04; ties can raise it. p values are raw and two-sided; alpha (0.05) is per test, with no correction for multiple comparisons.
  - jaccard-dice: its coincident count ties almost everywhere on real data, so its p rarely falls below 0.05 even when there is an effect (about 0.0007 by chance in validation). A non-significant jaccard-dice result is weak evidence of no effect.

Columns for a method are named by its method id, which is stable across
versions. Blank means the method produced no value there; it never means zero.
