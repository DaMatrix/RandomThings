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

package net.daporkchop.lib.nds.header;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;

public class RomIcon {
    protected final short[] palette;
    protected final byte[]  pixels;

    public RomIcon(ByteBuffer buf) {
        if (buf == null) {
            throw new NullPointerException("buf");
        } else {
            this.palette = new short[16];
            this.pixels = new byte[512];

            buf.position(32);
            buf.get(this.pixels);

            for (int i = 0; i < 16; ++i) {
                this.palette[i] = buf.getShort();
            }
        }
    }

    public RomIcon(short[] palette, byte[] pixels) {
        this.palette = palette;
        this.pixels = pixels;
    }

    public int getColor(int x, int y) {
        int tileX = x >>> 3;
        int tileY = y >>> 3;
        int b = (this.pixels[(tileY << 2 | tileX) << 5 | (y & 7) << 2 | x >>> 1 & 3] & 0xFF) >>> ((x & 1) == 0 ? 0 : 4) & 0xF;
        return b == 0 ? -1 : this.palette[b] & 0xFFFF;
    }

    public BufferedImage getAsBufferedImage() {
        BufferedImage img = new BufferedImage(32, 32, 2);

        for (int x = 31; x >= 0; --x) {
            for (int y = 31; y >= 0; --y) {
                int color = this.getColor(x, y);
                if (color == -1) {
                    img.setRGB(x, y, 0);
                } else {
                    img.setRGB(
                            x, y,
                            0xFF000000
                                    | color5bitTo8bit(color >>> 10)
                                    | (color5bitTo8bit(color >>> 5) << 8)
                                    | (color5bitTo8bit(color) << 16)
                    );
                }
                //img.setRGB(x, y, (color == 0 ? 0 : 0xFF000000) | ((color >>> 10 & 0x1F) << (0 + 3)) | ((color >>> 5 & 0x1F) << (8 + 3)) | ((color & 0x1F) << (16 + 3)));
            }
        }

        return img;
    }

    //sauce: https://stackoverflow.com/a/9069480/4395213
    private static int color5bitTo8bit(int color)   {
        return ((color & 0x1F) * 527 + 23) >>> 6;
    }

    private static int color6bitTo8bit(int color)   {
        return ((color & 0x3F) * 259 + 33) >>> 6;
    }

    private static int color8bitTo5bit(int color)   {
        return ((color & 0x1F) * 249 + 1014) >>> 11;
    }

    private static int color8bitTo6bit(int color)   {
        return ((color & 0x3F) * 253 + 505) >>> 10;
    }

    public short[] getPalette() {
        return this.palette;
    }

    public byte[] getPixels() {
        return this.pixels;
    }
}
