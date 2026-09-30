// A bad macro option must end in one message naming it, not a stack trace.
work = getArgument();
if (!endsWith(work, "/")) work = work + "/";
setBatchMode(true);
run("Object Colocalization Suite", "channels=[" + work + "single/A.tif] channels=["
    + work + "single/B.tif] permutations=0 hide_display");
print("SMOKE AFTER option");
