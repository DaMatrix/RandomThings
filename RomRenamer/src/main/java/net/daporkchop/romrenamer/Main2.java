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

package net.daporkchop.romrenamer;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.madgag.gif.fmsware.AnimatedGifEncoder;
import net.daporkchop.lib.common.misc.file.PFiles;
import net.daporkchop.lib.encoding.Hexadecimal;
import net.daporkchop.lib.nds.RomNDS;
import net.daporkchop.lib.nds.header.RomIcon;
import net.daporkchop.lib.unsafe.PUnsafe;

import javax.imageio.ImageIO;
import javax.swing.ImageIcon;
import javax.swing.JFrame;
import javax.swing.JLabel;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.stream.Collectors;

import static net.daporkchop.lib.math.primitive.BinMath.*;

/**
 * @author DaPorkchop_
 */
public class Main2 {
    public static final File ROOT = new File("/home/daporkchop/10.0.0.20/Torrents/ROMs");
    public static final File DST  = new File("/home/daporkchop/10.0.0.20/Misc/DS/Repo");

    public static final ThreadLocal<TLData> TL_DATA = ThreadLocal.withInitial(TLData::new);

    public static final String DIGEST_ALG = "SHA-256";

    public static void main(String... args) {
        final File infoDir = PFiles.ensureDirectoryExists(new File(DST, "info"));
        final File repoDir = PFiles.ensureDirectoryExists(new File(DST, "repo"));

        PFiles.rmContentsParallel(infoDir);
        PFiles.rmContentsParallel(repoDir);

        Map<String, Object> mutexes = Collections.synchronizedMap(new WeakHashMap<>());
        Arrays.stream(Objects.requireNonNull(ROOT.listFiles()))
                .filter(File::isFile)
                .map(File::toPath)
                .parallel()
                .filter(path -> {
                    Object mutex = null;
                    try (RomNDS rom = new RomNDS(path)) {
                        FileChannel channel = rom.getChannel();
                        if (!isPow2(channel.size())) {
                            System.err.printf("Alert: rom \"%s\" is not a power of 2! (%d bytes)\n", path, channel.size());
                            return true;
                        }

                        String gamecode = rom.getHeaders().getUnitcode() == 0x02 ? rom.getHeaders().getGamecode().replace("NTR-", "TWL-") : rom.getHeaders().getGamecode();
                        File manifestDir = PFiles.ensureDirectoryExists(new File(infoDir, rom.getHeaders().getName()));

                        PUnsafe.monitorEnter(mutex = mutexes.computeIfAbsent(new String(rom.getHeaders().getName()), s -> new Object[0]));

                        String hash = Hexadecimal.encode(hash(channel, DIGEST_ALG));
                        try {
                            File romFile = new File(repoDir, String.format("%s/%s/%s.nds", hash.subSequence(0, 2), hash.subSequence(2, 4), hash));
                            if (romFile.exists()) {
                                throw new FileAlreadyExistsException(romFile.getAbsolutePath());
                            } else {
                                PFiles.ensureDirectoryExists(romFile.getParentFile());
                                Files.createSymbolicLink(romFile.toPath(), path);
                            }
                        } catch (FileAlreadyExistsException e)  {
                            System.err.printf("Alert: rom \"%s\" already exists in destination directory (%s hash: \"%s\")\n", path, DIGEST_ALG, hash);
                            return true;
                        }

                        MappedByteBuffer buffer = rom.getHeaders().getIconTitle().getMap();
                        {
                            File manifestFile = new File(manifestDir, "manifest.json");
                            JsonObject object = new JsonObject();
                            if (manifestFile.exists()) {
                                try (Reader reader = new BufferedReader(new InputStreamReader(new FileInputStream(manifestFile)))) {
                                    object = new JsonParser().parse(reader).getAsJsonObject();
                                }
                            }

                            if (!object.has("roms")) {
                                object.add("roms", new JsonObject());
                            }
                            {
                                JsonObject roms = object.getAsJsonObject("roms");
                                String destination = String.valueOf(rom.getHeaders().getGamecode().charAt(3 + 4));
                                if (!roms.has(destination)) {
                                    roms.add(destination, new JsonObject());
                                }
                                JsonObject romObj = roms.getAsJsonObject(destination);

                                if (romObj.has("version") && romObj.get("version").getAsInt() >= rom.getHeaders().getVersion()) {
                                    return true;
                                }
                                romObj.addProperty("version", rom.getHeaders().getVersion());
                                romObj.addProperty("hash", hash);
                            }

                            if (!object.has("titles")) {
                                object.add("titles", new JsonObject());
                            }
                            {
                                JsonObject titles = object.getAsJsonObject("titles");
                                char[] arr = new char[0x100 >> 1];

                                readTitle(buffer, 0, TitleLanguage.JP, arr).addTo(titles);
                                readTitle(buffer, 0, TitleLanguage.EN, arr).addTo(titles);
                                readTitle(buffer, 0, TitleLanguage.FR, arr).addTo(titles);
                                readTitle(buffer, 0, TitleLanguage.DE, arr).addTo(titles);
                                readTitle(buffer, 0, TitleLanguage.IT, arr).addTo(titles);
                                readTitle(buffer, 0, TitleLanguage.ES, arr).addTo(titles);
                                if (rom.getHeaders().getIconTitle().getVersion() >= 0x0002) {
                                    readTitle(buffer, 0, TitleLanguage.CN, arr).addTo(titles);
                                }
                                if (rom.getHeaders().getIconTitle().getVersion() >= 0x0003) {
                                    readTitle(buffer, 0, TitleLanguage.KR, arr).addTo(titles);
                                }
                            }

                            object.addProperty("name", rom.getHeaders().getName());
                            object.addProperty("gamecode", gamecode);
                            object.addProperty("makercode", rom.getHeaders().getMakercode());
                            object.addProperty("unitcode", rom.getHeaders().getUnitcode());
                            object.addProperty("animatedIcon", rom.getHeaders().getIconTitle().getVersion() >= 0x0103);

                            try (FileChannel ch = FileChannel.open(manifestFile.toPath(), StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                                ch.write(ByteBuffer.wrap(new GsonBuilder().setPrettyPrinting().create().toJson(object).getBytes(StandardCharsets.UTF_8)));
                            }
                        }

                        ImageIO.write(rom.getHeaders().getIconTitle().getIcon().getAsBufferedImage(), "png", new File(manifestDir, "icon.png"));

                        if (rom.getHeaders().getIconTitle().getVersion() >= 0x0103) {
                            //convert animated DSi icon to gif and store it
                            byte[][] pixelss = new byte[8][512];
                            short[][] palettes = new short[8][16];

                            for (int i = 0; i < 8; i++) {
                                for (int j = 0; j < 512; j++) {
                                    pixelss[i][j] = buffer.get(0x1240 + 0x200 * i + j);
                                }
                                for (int j = 0; j < 16; j++) {
                                    palettes[i][j] = buffer.getShort(0x2240 + 0x20 * i + j * 2);
                                }
                            }

                            AnimatedGifEncoder encoder = new AnimatedGifEncoder();
                            encoder.setRepeat(0);
                            encoder.setFrameRate(60.0f);
                            encoder.setBackground(Color.BLACK);
                            encoder.setSize(32, 32);
                            encoder.setTransparent(new Color(0, true), true);
                            encoder.setDispose(2);
                            encoder.start(manifestDir.getAbsolutePath() + "/icon.gif");

                            BufferedImage image = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
                            buffer.position(0x2340);
                            int s;
                            while (((s = buffer.getShort() & 0xFFFF) & 0xFF) != 0) {
                                BufferedImage src = new RomIcon(palettes[(s >>> 11) & 0x7], pixelss[(s >>> 8) & 0x7]).getAsBufferedImage();
                                BufferedImage target;
                                if ((s & 0xC000) != 0) {
                                    target = image;
                                    for (int x = 0; x < 32; x++) {
                                        for (int y = 0; y < 32; y++) {
                                            target.setRGB(
                                                    (s & 0x4000) != 0 ? x ^ 0x1F : x,
                                                    (s & 0x8000) != 0 ? y ^ 0x1F : y,
                                                    src.getRGB(x, y)
                                            );
                                        }
                                    }
                                } else {
                                    target = src;
                                }
                                for (int i = (s & 0xFF) - 1; i >= 0; i--) {
                                    encoder.addFrame(target);
                                }
                            }
                            if (!encoder.finish()) {
                                throw new IllegalStateException();
                            }
                        }

                        return false;
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    } finally {
                        if (mutex != null)  {
                            PUnsafe.monitorExit(mutex);
                        }
                    }
                })
                .collect(Collectors.toList())
                .forEach(System.out::println);
    }

    public static void showImages(BufferedImage... images) {
        JFrame frame = new JFrame();
        frame.getContentPane().setLayout(new FlowLayout());
        for (BufferedImage image : images) {
            frame.getContentPane().add(new JLabel(new ImageIcon(image)));
        }
        frame.pack();
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                synchronized (frame) {
                    frame.notifyAll();
                }
            }
        });
        frame.setVisible(true);
        synchronized (frame) {
            try {
                frame.wait();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }

    public static Title readTitle(MappedByteBuffer buffer, int titleOffset, TitleLanguage language, char[] arr) {
        try {
            Title title = new Title();
            title.language = language;
            titleOffset += 0x0240 + language.ordinal() * 0x100;

            for (int i = 0; i < arr.length; i++) {
                arr[i] = buffer.getChar(titleOffset + (i << 1));
            }

            int j = 0;
            while (arr[j] != 0x000A) {
                j++;
            }
            title.title = new String(arr, 0, j);
            int i = j + 1;
            j = 0;
            while (arr[i + j] != 0x000A && arr[i + j] != 0x0000) {
                j++;
            }
            if (arr[i + j] == 0x000A) {
                title.subTitle = new String(arr, i, j);
                i += j + 1;
                j = 0;
                while (arr[i + j] != 0x0000) {
                    j++;
                }
                title.manufacturer = new String(arr, i, j);
            } else {
                title.manufacturer = new String(arr, i, j);
            }

            return title;
        } catch (ArrayIndexOutOfBoundsException e) {
            return new Title.Noop();
        }
    }

    public static byte[] hash(FileChannel channel, String alg) throws IOException {
        try {
            ByteBuffer buffer = TL_DATA.get().hashBuffer;
            MessageDigest digest = MessageDigest.getInstance(alg);

            long pos = 0L;
            long size = channel.size();
            while (pos < size) {
                buffer.clear();
                pos += channel.read(buffer, pos);
                digest.update((ByteBuffer) buffer.flip());
            }
            return digest.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    public enum TitleLanguage {
        JP,
        EN,
        FR,
        DE,
        IT,
        ES,
        CN,
        KR
    }

    public static final class TLData {
        public final ByteBuffer hashBuffer = ByteBuffer.allocateDirect(1 << 20);
    }

    public static class Title {
        public TitleLanguage language;
        public String        title;
        public String        subTitle;
        public String        manufacturer;

        @Override
        public String toString() {
            return String.format(
                    "%s: \"%s\", \"%s\", \"%s\"",
                    this.language,
                    this.title,
                    this.subTitle == null ? "(no subtitle)" : this.subTitle,
                    this.manufacturer
            );
        }

        public void addTo(JsonObject dst) {
            dst.addProperty(
                    this.language.name().toLowerCase(),
                    this.subTitle == null
                            ? String.format("%s\n%s", this.title, this.manufacturer)
                            : String.format("%s\n%s\n%s", this.title, this.subTitle, this.manufacturer)
            );
        }

        public static final class Noop extends Title {
            @Override
            public String toString() {
                return "(no title)";
            }

            @Override
            public void addTo(JsonObject dst) {
            }
        }
    }
}
