# Golden outputs

Byte-for-byte copies of what the suite currently writes for a few seeded
synthetic inputs. `ocs.golden.GoldenOutputTest` recomputes them on every build
and fails, naming the case, the file and the first differing line, if any of
them moves.

| Folder | Input (`GoldenFixtures`) | Run |
|---|---|---|
| `discovery-3d/` | three channels, 40 x 40 x 5, paired intensity images, oval region | all 13 methods, agreement, sweep, Discovery, whole-channel chance test, 49 shuffles |
| `discovery-2d/` | two channels, one slice, paired intensity images, oval region | the same |
| `empty-and-single/` | a channel with no objects, one with a single object, one ordinary | the same |
| `anisotropic-per-object/` | two channels calibrated 0.284 x 0.284 x 1.0 um | Object colocalization preset, Discovery, per-object chance test |
| `workers-whole-channel/` | as `discovery-3d` | the object methods' chance test, every shuffle's statistic, identical at 1 and 4 workers |
| `workers-per-object/` | as `anisotropic-per-object` | the same under the per-object null |

In each case folder, `table-<name>.csv` is one table from `OCSTables.all` with
every number written by `Double.toString` (full precision), and `tree/` is
exactly what `OCSOutputWriter.write` put on disk. A case whose run is refused
holds `error.txt` with the exception instead.

Normalised before comparison, and nothing else: CRLF line endings, the
`Version` line of `README.txt` and the `"version"` field of `run-record.json`.

## Updating

Only for an intended change to the numbers or the files:

```bash
MAVEN_OPTS=-Xmx1g sh ./mvnw -B -q test -Dtest=GoldenOutputTest -Docs.golden.update=true
```

Then name every changed file and column, and why, in the CHANGELOG and the
commit message. Never regenerate to make an unexplained difference go away.
