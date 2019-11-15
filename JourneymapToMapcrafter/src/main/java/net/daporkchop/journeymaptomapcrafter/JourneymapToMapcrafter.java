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

package net.daporkchop.journeymaptomapcrafter;

import net.daporkchop.lib.common.function.io.IOConsumer;
import net.daporkchop.lib.common.function.io.IOSupplier;
import net.daporkchop.lib.common.function.throwing.ESupplier;
import net.daporkchop.lib.common.util.PorkUtil;
import net.daporkchop.lib.logging.LogAmount;
import net.daporkchop.mapcraftermerger.QuadTree;
import net.daporkchop.mapcraftermerger.Sector;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.Stack;

import static net.daporkchop.lib.logging.Logging.*;

/**
 * @author DaPorkchop_
 */
public class JourneymapToMapcrafter {
    public static QuadTree<File> images;

    public static File inputImg;
    public static int  textureSize;

    public static int width;
    public static int height;

    public static int depth;

    public static void main(String... args) throws IOException {
        logger.enableANSI().setLogAmount(LogAmount.DEBUG);

        inputImg = new File(args[0]);
        textureSize = Integer.parseInt(args[1]);

        ThreadLocal<ImageReader> READER_CACHE = ThreadLocal.withInitial((IOSupplier<ImageReader>) () -> {
            ImageInputStream stream = ImageIO.createImageInputStream(inputImg);
            ImageReader reader = ImageIO.getImageReaders(stream).next();
            reader.setInput(stream);
            return reader;
        });
        width = READER_CACHE.get().getWidth(0);
        height = READER_CACHE.get().getHeight(0);
        logger.info("Source image dimensions: %dx%d", width, height);
        if (width != height) throw new IllegalArgumentException("Image is not square!");

        depth = 0;
        for (int bpp = (width >> 1) / 512; bpp >= 1; bpp >>= 1) {
            depth++;
        }
        logger.info("Required depth: %d", depth);

        Collection<Stack<Integer>> paths = QuadTree.computeAllPossiblePaths(depth);
        paths.stream()
                //.parallel()
                .forEach((IOConsumer<Stack<Integer>>) path -> {
            ImageReader reader = READER_CACHE.get();
            int posX = width >> 1;
            int posY = width >> 1;
            int d = 1;
            for (Integer i : path)  {
                d++;
                Sector sector = Sector.fromOffsetIndex(i);
                posX += (width >> d) * sector.deltaX;
                posY += (width >> d) * sector.deltaY;
            }

            ImageReadParam param = reader.getDefaultReadParam();
            param.setSourceRegion(new Rectangle(posX, posY, textureSize, textureSize));
            PorkUtil.simpleDisplayImage(reader.read(0, param));
        });
    }
}
