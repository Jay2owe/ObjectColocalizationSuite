// Coexistence: with the sibling plugins installed in the same Fiji, run one
// command of each on the smoke fixtures. Argument: "<work-dir>|<sibling>",
// sibling one of cpc, volcoloc, territories, proximity. Each ships its own
// relocated copy of a core OCS also ships; a clash would fail here or change
// OCS's own output digests.
argument = split(getArgument(), "|");
work = argument[0];
sibling = argument[1];
if (!endsWith(work, "/")) work = work + "/";
setBatchMode(true);
open(work + "single/A.tif");
open(work + "single/B.tif");

if (sibling == "cpc") {
    run("Centre-Particle Coincidence", "image1=[A.tif] image2=[B.tif]");
} else if (sibling == "volcoloc") {
    run("Volumetric Colocalization", "mode=labels image1=[A.tif] image2=[B.tif]"
        + " threshold1=30 threshold2=30 bidirectional auto_save save_dir=["
        + work + "sibling-volcoloc] hide_display");
} else if (sibling == "territories") {
    run("Object Territories", "mode=both label1=[A.tif] label2=[B.tif] regions=["
        + work + "region.roi] region_mode=independent edge_cells=include_flagged"
        + " density_weighting=both boundary=corrected bandwidth=auto permutations=19"
        + " seed=12345 output=[" + work + "sibling-territories] hide_results");
} else if (sibling == "proximity") {
    run("Object Proximity Analysis", "input_mode=[Open label images] channel_count=2"
        + " label_image_1=A.tif label_image_2=B.tif run_distances"
        + " centre_centre k_nearest_neighbours=1 contact_distance=2 hide_display");
} else {
    exit("unknown sibling " + sibling);
}
print("SMOKE PASS sibling-" + sibling);
