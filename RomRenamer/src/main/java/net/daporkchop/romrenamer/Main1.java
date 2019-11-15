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

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class Main1 {
    public static final File            ROOT            = new File("/media/daporkchop/TooMuchStuff/ROMs");
    //"Destination/Language" field (4th character of gamecode)
    //i would normally avoid using a boxed collection but this program only has to run once very fast so i don't care too much
    public static final List<Character> VALID_LANGUAGES = Arrays.asList(
            'E', //English/USA
            'L', //USA #2
            'O', //International
            'T', //USA+AUS
            'V' //EUR+AUS
    );

    public static final Pattern PATTERN_STRIP_NDS_EXTENSION = Pattern.compile("^(.*?)\\.nds$");
    public static final Pattern PATTERN_STRIP_NUMBER_PREFIX = Pattern.compile("^[0-9]{4} - (.*?)$");
    public static final Pattern PATTERN_FIND_STRIP_BRACES   = Pattern.compile("\\(([^)]*?)\\)");
    public static final Pattern PATTERN_FIX_SUFFIXES        = Pattern.compile("^(.*?), (A|The)");
    public static final Pattern PATTERN_FIND_SUFFIX_FOREIGN = Pattern.compile("^(.*?), (Le|Die|De)($| )");
    public static final Pattern PATTERN_STRIP_VERSION       = Pattern.compile(" (V|v)ersion");

    public static final ThreadLocal<ByteBuffer> BUFFER_CACHE = ThreadLocal.withInitial(() -> ByteBuffer.allocateDirect(4096));

    public static void main(String[] args) {
        File[] allFiles = Objects.requireNonNull(ROOT.listFiles());
        System.out.printf("Found %d potential candidate ROMs.\n", allFiles.length);

        System.out.println("  Fixing up file names...");
        Collection<ROM> roms = Arrays.stream(allFiles)
                .filter(File::isFile)
                .filter(file -> PATTERN_STRIP_NDS_EXTENSION.matcher(file.getName()).find())
                .filter(file -> file.length() >= 4096L)
                .map(file -> {
                    ROM rom = new ROM();
                    rom.file = file;
                    rom.name = file.getName();
                    return rom;
                })
                .peek(rom -> {
                    Matcher matcher = PATTERN_STRIP_NDS_EXTENSION.matcher(rom.name);
                    if (matcher.find()) {
                        rom.name = matcher.group(1);
                    }
                })
                .peek(rom -> {
                    if (rom.name.indexOf('_') != -1) {
                        rom.name = rom.name.replace('_', ' ');
                    }
                })
                .peek(rom -> rom.name = rom.name.trim())
                .filter(rom -> !rom.name.isEmpty())
                .filter(rom -> {
                    Matcher matcher = PATTERN_STRIP_NUMBER_PREFIX.matcher(rom.name);
                    if (matcher.find()) {
                        rom.name = matcher.group(1);
                        return true;
                    } else {
                        return false;
                    }
                })
                .peek(rom -> {
                    Matcher matcher = PATTERN_FIND_STRIP_BRACES.matcher(rom.name);
                    while (matcher.find()) {
                        if ("DSi Enhanced".equalsIgnoreCase(matcher.group(1))) {
                            rom.dsiEnhanced = true;
                        }
                    }
                    rom.name = matcher.replaceAll("").trim();
                })
                .peek(rom -> {
                    Matcher matcher = PATTERN_FIX_SUFFIXES.matcher(rom.name);
                    if (matcher.find()) {
                        rom.name = String.format("%s %s", matcher.group(2), matcher.group(1));
                    }
                })
                .filter(rom -> !PATTERN_FIND_SUFFIX_FOREIGN.matcher(rom.name).find())
                .peek(rom -> {
                    Matcher matcher = PATTERN_STRIP_VERSION.matcher(rom.name);
                    if (matcher.find()) {
                        rom.name = matcher.replaceAll("");
                    }
                })
                .peek(rom -> {
                    if (rom.dsiEnhanced) {
                        rom.name += " (DSi Enhanced)";
                    }
                })
                //.peek(rom -> System.out.println(rom.name))
                .collect(Collectors.toSet());

        System.out.println(roms.size());

        System.out.println("  Checking language based on actual contents of ROM file");
        roms = roms.parallelStream()
                .peek(rom -> {
                    try {
                        ByteBuffer buffer = BUFFER_CACHE.get();
                        buffer.clear();
                        try (FileChannel ch = FileChannel.open(rom.file.toPath(), StandardOpenOption.READ)) {
                            ch.read(buffer);
                        }
                        if (buffer.hasRemaining()) {
                            throw new IllegalStateException(String.format("Only read %d/%d bytes!", buffer.position(), buffer.capacity()));
                        }
                        rom.language = (char) buffer.get(0x00C + 0x003);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                })
                .filter(rom -> VALID_LANGUAGES.contains(rom.language))
                .collect(Collectors.toSet());

        System.out.println(roms.size());

        Map<String, ROM> map = new HashMap<>();
        roms.forEach(rom -> {
            ROM other;
            if ((other = map.putIfAbsent(rom.name, rom)) != null) {
                //replace if the region type is lower priority than this
                if (VALID_LANGUAGES.indexOf(other.language) > VALID_LANGUAGES.indexOf(rom.language) && !map.replace(rom.name, other, rom)) {
                    throw new IllegalStateException();
                }
            }
        });

        System.out.println(map.size());
        map.keySet().forEach(System.out::println);

        System.out.println("  Renaming files");
        map.values().parallelStream().forEach(rom -> {
            if (!rom.file.renameTo(new File(ROOT, rom.name + ".nds"))) {
                throw new IllegalStateException();
            }
        });

        System.out.println("  Deleting other roms");
        File oldDir = new File(ROOT, "old");
        if (oldDir.exists() || !oldDir.mkdir()) {
            throw new IllegalStateException();
        }
        Collection<File> usedRomFiles = map.values().stream().map(rom -> rom.file).collect(Collectors.toSet());
        Arrays.stream(allFiles)
                .filter(file -> !usedRomFiles.contains(file))
                .parallel()
                .filter(File::isFile)
                .forEach(file -> {
                    if (!file.renameTo(new File(oldDir, file.getName()))) {
                        throw new IllegalStateException();
                    }
                });
        System.out.println("Done!");
    }

    public static class ROM {
        File   file;
        String name;
        char    language    = 0;
        boolean dsiEnhanced = false;

        @Override
        public int hashCode() {
            return this.name.hashCode();
        }

        @Override
        public boolean equals(Object o) {
            return this.name.equals(o);
        }
    }
}
