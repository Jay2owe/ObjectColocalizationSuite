/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Developed at the Brancaccio Lab, UK Dementia Research Institute,
 * Imperial College London.
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package ocs;

import ij.IJ;
import ij.ImagePlus;
import ij.WindowManager;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns what a macro line says into images.
 *
 * <p>An entry is a window title or a file path, and which one it is is decided
 * by looking, not by guessing from the spelling: a window may legitimately be
 * titled {@code C1.tif}, and a file may legitimately have no extension.
 *
 * <p>Open windows win. Somebody who has just segmented a channel and is looking
 * at it means <i>that</i>, not the older copy on disk under the same name — and
 * silently analysing the file would produce numbers that do not match the
 * picture on screen.
 */
public final class OCSLabelImages {

    private OCSLabelImages() {
    }

    /**
     * @param entries window titles or file paths
     * @return the images, in the order given
     * @throws IllegalArgumentException naming the entry that could not be found,
     *         because "one of your five images is missing" is not actionable
     */
    public static List<ImagePlus> resolve(List<String> entries) {
        List<ImagePlus> images = new ArrayList<ImagePlus>();
        for (int i = 0; i < entries.size(); i++) {
            images.add(resolveOne(entries.get(i)));
        }
        return images;
    }

    /** @return the resolved image, never null */
    public static ImagePlus resolveOne(String entry) {
        if (entry == null || entry.trim().isEmpty()) {
            throw new IllegalArgumentException("an empty image name was given");
        }
        String name = entry.trim();
        ImagePlus open = byTitle(name);
        if (open != null) {
            return open;
        }
        File file = new File(name);
        if (file.isFile()) {
            ImagePlus loaded = IJ.openImage(file.getAbsolutePath());
            if (loaded == null) {
                throw new IllegalArgumentException(
                        "could not read '" + name + "' as an image");
            }
            return loaded;
        }
        throw new IllegalArgumentException("no open window and no file named '"
                + name + "'. Give a window title exactly as it appears in the"
                + " title bar, or a full path.");
    }

    /** Exact title first, then case-insensitively, which is what people type. */
    private static ImagePlus byTitle(String title) {
        int[] ids = WindowManager.getIDList();
        if (ids == null) {
            return null;
        }
        for (int i = 0; i < ids.length; i++) {
            ImagePlus image = WindowManager.getImage(ids[i]);
            if (image != null && title.equals(image.getTitle())) {
                return image;
            }
        }
        for (int i = 0; i < ids.length; i++) {
            ImagePlus image = WindowManager.getImage(ids[i]);
            if (image != null && title.equalsIgnoreCase(image.getTitle())) {
                return image;
            }
        }
        return null;
    }

    /** Every open image, for the dialog's channel chooser. */
    public static List<ImagePlus> openImages() {
        List<ImagePlus> images = new ArrayList<ImagePlus>();
        int[] ids = WindowManager.getIDList();
        if (ids == null) {
            return images;
        }
        for (int i = 0; i < ids.length; i++) {
            ImagePlus image = WindowManager.getImage(ids[i]);
            if (image != null) {
                images.add(image);
            }
        }
        return images;
    }

    public static List<String> titlesOf(List<ImagePlus> images) {
        List<String> titles = new ArrayList<String>();
        for (int i = 0; i < images.size(); i++) {
            titles.add(images.get(i).getTitle());
        }
        return titles;
    }
}
