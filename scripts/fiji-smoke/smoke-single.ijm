// Single runs from a macro line, headless: every shipped preset, a 3D pair,
// intensity images and the chance test with a region. Each run must write the
// suite's output tree with its run record and README.
work = getArgument();
if (!endsWith(work, "/")) work = work + "/";
setBatchMode(true);

A = work + "single/A.tif";
B = work + "single/B.tif";
IA = work + "single/A-intensity.tif";
IB = work + "single/B-intensity.tif";
REGION = work + "region.roi";

// Checks one run's output folder; `kinds` is the fewest table folders expected.
function check(label, out, kinds) {
    root = out + "Object Colocalization Suite/";
    problems = "";
    if (!File.exists(root + "run-record.json")) problems = problems + " no run-record.json;";
    if (!File.exists(root + "README.txt")) problems = problems + " no README.txt;";
    folders = 0;
    list = getFileList(root);
    for (i = 0; i < list.length; i++) {
        if (endsWith(list[i], "/")) {
            files = getFileList(root + list[i]);
            if (files.length > 0) folders++;
        }
    }
    if (folders < kinds) problems = problems + " " + folders + " table folder(s), expected " + kinds + ";";
    if (problems == "") print("SMOKE PASS " + label);
    else print("SMOKE FAIL " + label + ":" + problems);
}

function runOcs(label, options, kinds) {
    out = work + "out-" + label + "/";
    run("Object Colocalization Suite", options + " hide_display output=[" + out + "]");
    check(label, out, kinds);
}

both = "channels=[" + A + "] channels=[" + B + "]";
withIntensity = both + " intensity=[" + IA + "] intensity=[" + IB + "]";
withRegion = " region_roi=[" + REGION + "] permutations=19";

runOcs("quick-look", both + " methods=[preset:Quick look]", 2);
runOcs("object", withIntensity + withRegion + " methods=[preset:Object colocalization]", 3);
runOcs("intensity", withIntensity + withRegion + " methods=[preset:Intensity colocalization]", 2);
runOcs("spatial", both + withRegion + " methods=[preset:Spatial arrangement]", 2);
// The preset name with its em dash, as documented; on Windows the file is read
// in the platform encoding, which the preset lookup tolerates.
runOcs("discovery", withIntensity + withRegion + " methods=[preset:Discovery — everything]", 5);
runOcs("all", withIntensity + withRegion + " methods=all discovery", 5);
runOcs("3d", "channels=[" + work + "single/A3d.tif] channels=[" + work + "single/B3d.tif]"
    + withRegion + " methods=[preset:Object colocalization]", 3);

// Titles instead of paths: the images are open, as a recorded line expects.
open(A);
open(B);
runOcs("titles", "channels=A.tif channels=B.tif methods=[volume-overlap,cpc]", 2);
close("*");
