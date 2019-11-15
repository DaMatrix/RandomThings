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

import net.daporkchop.lib.common.util.PorkUtil;

import java.io.IOException;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel.MapMode;

public class IconTitleNDS implements AutoCloseable {
    protected final RomHeadersNDS parent;
    protected final MappedByteBuffer map;
    protected final int version;
    protected RomTitle title;
    protected RomIcon icon;

    public IconTitleNDS(RomHeadersNDS parent) throws IOException {
        if (parent == null) {
            throw new NullPointerException("parent");
        } else {
            this.parent = parent;
            int offset = parent.headersRegion.getInt(104);
            int size = 0x2340 + 0x80;
            this.map = parent.channel.map(MapMode.READ_WRITE, (long)offset, (long)size);
            this.map.order(ByteOrder.LITTLE_ENDIAN);
            this.version = this.map.getShort(0) & '\uffff';
        }
    }

    public synchronized RomTitle getTitle() {
        if (this.title == null) {
            byte[] buf = new byte[256];
            this.map.position(RomLanguage.ENGLISH.titleOffset);
            this.map.get(buf);
            this.title = new RomTitle(buf);
        }

        return this.title;
    }

    public synchronized RomIcon getIcon() {
        if (this.icon == null) {
            this.icon = new RomIcon(this.map);
        }

        return this.icon;
    }

    public void close() {
        PorkUtil.release(this.map);
    }

    public RomHeadersNDS getParent() {
        return this.parent;
    }

    public MappedByteBuffer getMap() {
        return this.map;
    }

    public int getVersion() {
        return this.version;
    }
}

