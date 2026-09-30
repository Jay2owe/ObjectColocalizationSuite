Object Colocalization Suite
Version <normalised>
Image: golden A

Every setting that could change these numbers is in run-record.json, including the seed.

Folders here:
  per-object/             one row per object per channel pair, one column per method  (58 rows)
  summary/                one row per channel pair  (6 rows)
  whole-direction/        methods that measure the field rather than the objects in it  (6 rows)
  curves/                 the spatial curves' status and global p  (24 rows)
  null-model/             each method against chance, with the seed that produced it  (78 rows)
  agreement/              how far the methods agree with each other, object by object  (554 rows)
  threshold-sensitivity/  how much each answer depends on where its cut-off was drawn  (43 rows)
  discovery/              each method classified, with the evidence behind it  (78 rows)

Read these numbers with:
  - Chance test: whole-channel null (the whole channel slid by one offset, wrapping at the frame edges). It tests whether the two channels meet no more and no less often than they would if each kept its own internal arrangement but its position relative to the other were random. 49 shuffles, seed 20260812, seed contract version 1.
  - The smallest two-sided p this run can report is min(1, 2/(49+1)) = 0.04; ties can raise it. p values are raw and two-sided; alpha (0.05) is per test, with no correction for multiple comparisons.
  - Under the whole-channel null, independent real channels gave p < 0.05 about 1.45 times as often as expected (0.029 against 0.020, alpha validation 2026-08-30), so read p values near alpha with that inflation in mind.
  - bounding-box has no p under the whole-channel null: wrapping at the frame edge can turn an object's box into the whole frame. Its measured values are still reported.
  - jaccard-dice: its coincident count ties almost everywhere on real data, so its p rarely falls below 0.05 even when there is an effect (about 0.0007 by chance in validation). A non-significant jaccard-dice result is weak evidence of no effect.
  - The spatial methods (cross-G, cross-K, cross-L, pair correlation) are planar: each object contributes only its xy centroid, so two objects at the same xy on different slices count as coincident.

Columns for a method are named by its method id, which is stable across
versions. Blank means the method produced no value there; it never means zero.
