// A save folder that cannot exist (inside a file) must be refused with a
// sentence before the run, not a stack trace after it.
work = getArgument();
if (!endsWith(work, "/")) work = work + "/";
setBatchMode(true);
run("Object Colocalization Suite", "channels=[" + work + "single/A.tif] channels=["
    + work + "single/B.tif] hide_display output=[" + work + "single/A.tif/results]");
print("SMOKE AFTER output");
