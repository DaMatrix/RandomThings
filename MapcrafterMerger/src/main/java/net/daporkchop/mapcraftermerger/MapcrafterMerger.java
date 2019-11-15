/*
 * Adapted from the Wizardry License
 *
 * Copyright (c) 2019-2019 DaPorkchop_ and contributors
 *
 * Permission is hereby granted to any persons and/or organizations using this software to copy, modify, merge, publish, and distribute it. Said persons and/or organizations are not allowed to use the software or any derivatives of the work for commercial use or any other means to generate income, nor are they allowed to claim this software as their own.
 *
 * The persons and/or organizations are also disallowed from sub-licensing and/or trademarking this software without explicit permission from DaPorkchop_.
 *
 * Any persons and/or organizations using this software must disclose their source code and have it publicly available, include this license, provide sufficient credit to the original authors of the project (IE: DaPorkchop_), as well as provide a link to the original project.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NON INFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 *
 */

package net.daporkchop.mapcraftermerger;

import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.experimental.Accessors;
import net.daporkchop.lib.common.function.io.IOBiConsumer;
import net.daporkchop.lib.common.function.io.IOConsumer;
import net.daporkchop.lib.common.function.io.IOFunction;
import net.daporkchop.lib.common.misc.file.PFiles;
import net.daporkchop.lib.logging.LogAmount;
import net.daporkchop.lib.logging.Logger;
import net.daporkchop.lib.logging.Logging;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Stack;
import java.util.StringJoiner;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * @author DaPorkchop_
 */
public class MapcrafterMerger implements Logging {
    public static final File     ROOT  = new File("/home/daporkchop/192.168.1.119/Minecraft/2b2t/tiles");
    public static final File     DST   = new File("/home/daporkchop/192.168.1.119/Minecraft/2b2t/map_final");
    public static final String[] TYPES = {
            "topdown/tl"
    };

    public static final IOBiConsumer<File, String> RM     = (path, flags) -> {
        try {
            int exitCode = new ProcessBuilder(Stream.of(
                    "/bin/rm",
                    "-" + flags,
                    path.getAbsoluteFile().getAbsolutePath()
            ).filter(s -> !s.equals("-")).toArray(String[]::new))
                    .inheritIO()
                    .start()
                    .waitFor();
            if (exitCode != 0) {
                throw new IllegalStateException(String.format("Illegal exit code %d!", exitCode));
            }
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    };
    public static final IOConsumer<File>           RM_RFV = path -> RM.accept(path, "rfv");
    public static final IOConsumer<File>           RM_RF  = path -> RM.accept(path, "rf");

    public static void main(String... args) throws IOException {
        logger.enableANSI()
                .addFile(new File("merger.log").getAbsoluteFile(), true, LogAmount.NORMAL)
                .addFile(new File("debug.log").getAbsoluteFile(), true, LogAmount.DEBUG);

        logger.info("\nStarting MapcrafterMerger v0.0.1-SNAPSHOT...\n\n");

        logger.info("Nuking old output directories...");
        Arrays.stream(TYPES).parallel().map(s -> new File(DST, s)).forEach(RM_RF);

        logger.info("Searching for inputs...");
        Collection<File> validInputs = Arrays.stream(ROOT.listFiles())
                .parallel()
                .filter(File::isDirectory)
                .filter(f -> new File(f, "done").exists())
                .map(f -> new File(f, "mapcrafter"))
                .map(File::getAbsoluteFile)
                .collect(Collectors.toSet());
        logger.info("Found %d inputs: %s", validInputs.size(), validInputs);

        for (String type : TYPES) {
            File typeDst = new File(DST, type).getAbsoluteFile();

            logger.info("Running for type \"%s\"", type);
            Logger channel = logger.channel(type);

            QuadTree<File> tree = new QuadTree<>();

            channel.info("Locating highest complete images in the directory tree...");
            Collection<Tile> tiles = validInputs.stream()
                    .parallel()
                    .map((IOFunction<File, Tile>) f -> {
                        Tile tile = new Tile(new File(f, type));
                        {
                            Stack<Integer> stack = new Stack<>();
                            searchForFullImagesRecursive(channel, tree, stack, tile.file());
                        }
                        channel.info("Finished searching in \"%s\".", f);
                        return tile;
                    })
                    .collect(Collectors.toSet());
            channel.info("Image hierarchy tree built successfully!");

            channel.trace(tree.toString());

            tree.forEachValue((stack, file) -> channel.info("  %s -> \"%s\"", stack, file));

            channel.info("Building symlinks...");
            tree.forEachValue((IOBiConsumer<Stack<Integer>, File>) (stack, file) -> {
                String thePath = toPath(stack);

                File dstFile = new File(typeDst, thePath + ".png");
                PFiles.ensureDirectoryExists(dstFile.getParentFile());
                Files.createSymbolicLink(dstFile.toPath(), file.toPath());
                channel.debug("Creating symlink from \"%s\" to \"%s\"", file, dstFile);

                dstFile = new File(typeDst, thePath);
                String srcPath = file.getAbsolutePath();
                file = new File(srcPath.substring(0, srcPath.length() - 4));
                Files.createSymbolicLink(dstFile.toPath(), file.toPath());
                channel.debug("Creating symlink from \"%s\" to \"%s\"", file, dstFile);
            });

            while (tree.depth() > 1) {
                channel.info("quadtree depth: %d", tree.depth());

                channel.info("Generating scaled-down images...");
                //genScaledImagesRecursive(channel, new Stack<>());
                Map<Stack<Integer>, BufferedImage> images = new HashMap<>();
                tree.forEachValueAtDepth(tree.depth(), (IOBiConsumer<Stack<Integer>, File>) (stack, file) -> {
                    BufferedImage dst;
                    {
                        Stack<Integer> key = QuadTree.copy(stack);
                        key.pop();
                        dst = images.computeIfAbsent(key, s -> new BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB));
                    }
                    scaleDownImage(ImageIO.read(file), dst, Sector.fromOffsetIndex(stack.peek()));
                });
                channel.info("Writing scaled-down images...");
                images.forEach((IOBiConsumer<Stack<Integer>, BufferedImage>) (stack, img) -> {
                    File file = new File(typeDst, toPath(stack, ".png"));
                    ImageIO.write(img, "png", file);
                    tree.set(stack, file);
                });
            }

            {
                channel.info("Generating base image...");
                BufferedImage img = new BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB);
                for (int i = 1; i <= 4; i++) {
                    scaleDownImage(ImageIO.read(new File(typeDst, i + ".png")), img, Sector.fromOffsetIndex(i));
                }
                ImageIO.write(img, "png", new File(typeDst, "base.png"));
            }

            channel.success("Done!");
        }
        logger.success("Done!");
    }

    private static void searchForFullImagesRecursive(@NonNull Logger channel, @NonNull QuadTree<File> tree, @NonNull Stack<Integer> stack, @NonNull File file) throws IOException {
        Collection<Integer> images = new LinkedList<>();
        Collection<Integer> dirs = new LinkedList<>();
        for (File subFile : file.listFiles()) {
            String fileName = subFile.getName();
            if (subFile.isFile() && fileName.matches("[1234]\\.png")) {
                images.add(Integer.parseInt(fileName.substring(0, 1)));
            } else if (subFile.isDirectory() && fileName.matches("[1234]")) {
                dirs.add(Integer.parseInt(fileName));
            }
        }

        SEARCH:
        for (Iterator<Integer> itr = images.iterator(); itr.hasNext(); ) {
            File imgFile = new File(file, stack.push(itr.next()) + ".png");
            channel.debug("Trying \"%s\"...", imgFile);
            BufferedImage img = ImageIO.read(imgFile);
            for (int x = img.getWidth() - 1; x >= 0; x--) {
                for (int y = img.getHeight() - 1; y >= 0; y--) {
                    if ((img.getRGB(x, y) & 0xFF000000) == 0) {
                        channel.debug("Found a transparent pixel at (%d,%d)", x, y);
                        stack.pop();
                        continue SEARCH;
                    }
                }
            }
            channel.trace("Found complete image: \"%s\"!", imgFile);
            if (!tree.set(stack, imgFile)) {
                channel.debug("Couldn't set image \"%s\" to path %s!", imgFile, stack);
                //throw new IllegalStateException(String.format("Couldn't set image \"%s\" to path %s!", imgFile, stack));
            }
            stack.pop();
            itr.remove();
        }

        for (Integer imgName : images) {
            //the only things that will be left in here are ones that are not already set
            stack.push(imgName);
            if (dirs.contains(imgName)) {
                searchForFullImagesRecursive(channel, tree, stack, new File(file, imgName.toString()));
            } else {
                throw new IllegalStateException(String.format("Unable to find child for path: %s", stack));
            }
            stack.pop();
        }
    }

    private static void genScaledImagesRecursive(@NonNull Logger channel, @NonNull Stack<Integer> stack) throws IOException    {
    }

    private static String toPath(@NonNull List<Integer> stack) {
        return toPath(stack, "", "");
    }

    private static String toPath(@NonNull List<Integer> stack, @NonNull String suffix) {
        return toPath(stack, "", suffix);
    }

    private static String toPath(@NonNull List<Integer> stack, @NonNull String prefix, @NonNull String suffix) {
        StringJoiner joiner = new StringJoiner("/", prefix, suffix);
        for (int i : stack) {
            joiner.add(String.valueOf(i));
        }
        return joiner.toString();
    }

    private static void scaleDownImage(@NonNull BufferedImage src, @NonNull BufferedImage dst, @NonNull Sector sector) {
        assert256x256(src);
        assert256x256(dst);

        final int offX;
        final int offZ;

        switch (sector) {
            case TOP_LEFT:
                offX = offZ = 0;
                break;
            case TOP_RIGHT:
                offX = 0;
                offZ = 128;
                break;
            case BOTTOM_LEFT:
                offX = 128;
                offZ = 0;
                break;
            case BOTTOM_RIGHT:
                offX = offZ = 128;
                break;
            default:
                throw new IllegalArgumentException();
        }

        for (int x = 254; x >= 0; x -= 2) {
            for (int y = 254; y >= 0; y -= 2) {
                int a = src.getRGB(x, y);
                int b = src.getRGB(x + 1, y);
                int c = src.getRGB(x, y + 1);
                int d = src.getRGB(x + 1, y + 1);

                if (((a | b | c | d) & 0xFF000000) != 0)    {
                    if (((a & 0xFF000000) == 0 || (b & 0xFF000000) == 0 || (c & 0xFF000000) == 0 || (d & 0xFF000000) == 0)) {
                        throw new IllegalStateException("Alpha values overlap!");
                    } else {
                        dst.setRGB(
                                (x >> 1) + offZ,
                                (y >> 1) + offX,
                                0xFF000000
                                        | (((((a >>> 16) & 0xFF) + ((b >>> 16) & 0xFF) + ((c >>> 16) & 0xFF) + ((d >>> 16) & 0xFF)) >> 2) << 16)
                                        | (((((a >>> 8) & 0xFF) + ((b >>> 8) & 0xFF) + ((c >>> 8) & 0xFF) + ((d >>> 8) & 0xFF)) >> 2) << 8)
                                        | (((a & 0xFF) + (b & 0xFF) + (c & 0xFF) + (d & 0xFF)) >> 2)
                        );
                    }
                } else {
                    dst.setRGB(
                            (x >> 1) + offZ,
                            (y >> 1) + offX,
                            0
                    );
                }
            }
        }
    }

    private static BufferedImage assert256x256(@NonNull BufferedImage img) {
        if (img.getWidth() != 256 || img.getHeight() != 256) {
            throw new IllegalArgumentException(String.format("Not a 256x256 image! (%dx%d)", img.getWidth(), img.getHeight()));
        }
        return img;
    }
}

@RequiredArgsConstructor
@Setter
@Getter
@Accessors(fluent = true, chain = true)
class Tile {
    @NonNull
    private final File file;

    private int    maxDepth      = -1;
    private Path[] firstComplete = null;
}

@RequiredArgsConstructor
@Accessors(fluent = true)
class Path {
    @Getter
    @NonNull
    private final int[] value;
    private int hashCode = 0;

    public Path(@NonNull List<Integer> from) {
        this(from.stream().mapToInt(Integer::intValue).toArray());
    }

    public File asFile(@NonNull Tile tile) {
        return this.asFile(tile.file());
    }

    public File asFile(@NonNull File root) {
        StringJoiner joiner = new StringJoiner("/", "", ".png");
        for (int i : this.value) {
            joiner.add(String.valueOf(i));
        }
        return new File(root, joiner.toString());
    }

    @Override
    public int hashCode() {
        int hashCode = this.hashCode;
        if (hashCode == 0) {
            for (int i = this.value.length - 1; i >= 0; i--) {
                hashCode += ((hashCode + i) * 31 + this.value[i]) * 31;
            }
            this.hashCode = hashCode;
        }
        return hashCode;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Path && (o == this || Arrays.equals(this.value, ((Path) o).value));
    }

    @Override
    public String toString() {
        return Arrays.toString(this.value);
    }
}
