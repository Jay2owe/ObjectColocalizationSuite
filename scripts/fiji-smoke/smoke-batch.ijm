// The batch command from a macro line: two fields found by file name, analysis
// options passed through to every field, one run record per field.
work = getArgument();
if (!endsWith(work, "/")) work = work + "/";
setBatchMode(true);

out = work + "out-batch/";
run("Batch (folder)", "folder=[" + work + "batch] pattern=(.*)_C(\\d)\\.tif"
    + " output=[" + out + "] methods=[volume-overlap,cpc] threshold_sweep sweep_width=0.3");

root = out + "Object Colocalization Suite/";
problems = "";
if (!File.exists(root + "batch/summary.csv")) problems = problems + " no batch/summary.csv;";
records = getFileList(root + "run-records/");
if (records.length != 2) problems = problems + " " + records.length + " run records, expected 2;";
for (i = 0; i < records.length; i++) {
    json = File.openAsString(root + "run-records/" + records[i]);
    if (indexOf(json, "\"sweepWidth\": 0.3") < 0)
        problems = problems + " " + records[i] + " lacks sweepWidth 0.3;";
}
if (problems == "") print("SMOKE PASS batch");
else print("SMOKE FAIL batch:" + problems);
