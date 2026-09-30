// Builds every synthetic input the smoke macros need, under the work
// directory passed as the macro argument. No image data is stored in the
// repository.
work = getArgument();
if (work == "") exit("usage: make-fixtures.ijm <work-dir>");
if (!endsWith(work, "/")) work = work + "/";
setBatchMode(true);

function calibrate() {
    run("Properties...", "channels=1 slices=" + nSlices + " frames=1 unit=um pixel_width=0.5 pixel_height=0.5 voxel_depth=1.5");
}

// A label image: a 4 x 4 grid of square objects, shifted by `offset` pixels.
function labels(path, offset, slices) {
    newImage("labels", "16-bit black", 128, 128, slices);
    label = 1;
    for (z = 1; z <= slices; z++) {
        setSlice(z);
        label = 1;
        for (row = 0; row < 4; row++) {
            for (column = 0; column < 4; column++) {
                setColor(label);
                fillRect(8 + column * 30 + offset, 8 + row * 30 + offset, 9, 9);
                label++;
            }
        }
    }
    calibrate();
    saveAs("Tiff", path);
    close();
}

// An intensity image: a smooth ramp with a bright patch over every object.
function intensity(path, offset, slices) {
    newImage("intensity", "16-bit ramp", 128, 128, slices);
    run("Multiply...", "value=200 stack");
    for (z = 1; z <= slices; z++) {
        setSlice(z);
        for (row = 0; row < 4; row++) {
            for (column = 0; column < 4; column++) {
                setColor(3000 + row * 400 + column * 100);
                fillRect(8 + column * 30 + offset, 8 + row * 30 + offset, 9, 9);
            }
        }
    }
    calibrate();
    saveAs("Tiff", path);
    close();
}

File.makeDirectory(work + "single");
labels(work + "single/A.tif", 0, 1);
labels(work + "single/B.tif", 4, 1);
intensity(work + "single/A-intensity.tif", 0, 1);
intensity(work + "single/B-intensity.tif", 4, 1);
labels(work + "single/A3d.tif", 0, 4);
labels(work + "single/B3d.tif", 4, 4);

// The region: one rectangle covering most of the frame, as a .roi file. (The
// ROI Manager, which writes .zip sets, needs a display; .zip reading is
// covered by the unit tests.)
newImage("roi-canvas", "8-bit black", 128, 128, 1);
makeRectangle(2, 2, 124, 124);
saveAs("Selection", work + "region.roi");
close();

File.makeDirectory(work + "batch");
labels(work + "batch/f1_C1.tif", 0, 1);
labels(work + "batch/f1_C2.tif", 3, 1);
labels(work + "batch/f2_C1.tif", 1, 1);
labels(work + "batch/f2_C2.tif", 6, 1);

expected = newArray("single/A.tif", "single/B3d.tif", "single/B-intensity.tif",
    "region.roi", "batch/f2_C2.tif");
missing = "";
for (i = 0; i < expected.length; i++) {
    if (!File.exists(work + expected[i])) missing = missing + " " + expected[i];
}
if (missing == "") print("SMOKE PASS fixtures");
else print("SMOKE FAIL fixtures: not written:" + missing);
