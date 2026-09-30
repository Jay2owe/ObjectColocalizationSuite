#@ String work
// The Java API from a headless Fiji script: no dialog, no window, one call.
import ij.IJ
import ocs.OCS
import ocs.OCSParameters

def a = IJ.openImage(work + "/single/A.tif")
def b = IJ.openImage(work + "/single/B.tif")
def result = OCS.run(OCSParameters.builder(a, b)
        .methods("cpc", "volume-overlap", "jaccard-dice")
        .build())
def ids = result.engineResults().collect { it.engineId() }
if (ids == ["cpc", "volume-overlap", "jaccard-dice"] && result.skipped().isEmpty()) {
    println("SMOKE PASS api")
} else {
    println("SMOKE FAIL api: ran " + ids + ", skipped " + result.skipped())
}
