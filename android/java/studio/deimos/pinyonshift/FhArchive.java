package studio.deimos.pinyonshift;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.Inflater;

/**
 * The game's zip archives, as tools/build-mod-archives.py reads and rewrites
 * them: members keep their compressed bytes and headers, a replaced member is
 * stored uncompressed (method 0), each central record's data-offset extra
 * field (id 0x1123) and media/zipmanifest.xml's line for the archive follow
 * the new layout. Plain Java, so tools can test it on a PC.
 */
final class FhArchive {
    private static final int LOCAL_SIGNATURE = 0x04034B50;
    private static final int CENTRAL_SIGNATURE = 0x02014B50;
    private static final int END_SIGNATURE = 0x06054B50;
    private static final int LOCAL_SIZE = 30;
    private static final int CENTRAL_SIZE = 46;
    private static final int END_SIZE = 22;
    private static final int DATA_OFFSET_EXTRA = 0x1123;
    static final int METHOD_STORED = 0;
    static final int METHOD_DEFLATE = 8;
    static final int METHOD_LZX = 21;

    /** Decompresses an LZX (XMemCompress) member: the runtime's decoder on the headset. */
    interface Lzx {
        byte[] decompress(byte[] compressed, int size) throws IOException;
    }

    static final class Member {
        final String name;
        final int method;
        final long compressed;
        final long size;
        final int offset;  // of the local header
        final byte[] record;  // the central record, raw

        Member(String name, int method, long compressed, long size, int offset, byte[] record) {
            this.name = name;
            this.method = method;
            this.compressed = compressed;
            this.size = size;
            this.offset = offset;
            this.record = record;
        }

        /** The name as the tools compare it: backslashes, lower case. */
        String key() {
            return name.replace('/', '\\').toLowerCase(Locale.ROOT);
        }
    }

    static final class Rebuilt {
        final byte[] data;
        final int dirstart, dirsize, direntries;

        Rebuilt(byte[] data, int dirstart, int dirsize, int direntries) {
            this.data = data;
            this.dirstart = dirstart;
            this.dirsize = dirsize;
            this.direntries = direntries;
        }
    }

    private FhArchive() {}

    private static ByteBuffer le(byte[] data) {
        return ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static int u16(ByteBuffer buffer, int at) {
        return buffer.getShort(at) & 0xFFFF;
    }

    private static long u32(ByteBuffer buffer, int at) {
        return buffer.getInt(at) & 0xFFFFFFFFL;
    }

    private static int endRecord(byte[] data) throws IOException {
        for (int at = data.length - 4; at >= 0; at--) {
            if (data[at] == 0x50 && data[at + 1] == 0x4B && data[at + 2] == 0x05 && data[at + 3] == 0x06) {
                if (at + END_SIZE > data.length) break;
                return at;
            }
        }
        throw new IOException("no end of central directory");
    }

    static List<Member> members(byte[] data) throws IOException {
        ByteBuffer buffer = le(data);
        int end = endRecord(data);
        int count = u16(buffer, end + 10);
        long size = u32(buffer, end + 12);
        int offset = (int) u32(buffer, end + 16);
        List<Member> out = new ArrayList<>();
        int cursor = offset;
        for (int i = 0; i < count; i++) {
            if (cursor + CENTRAL_SIZE > data.length) throw new IOException("central directory is truncated");
            if (buffer.getInt(cursor) != CENTRAL_SIGNATURE) throw new IOException("bad central record at " + cursor);
            int nameSize = u16(buffer, cursor + 28), extraSize = u16(buffer, cursor + 30),
                    commentSize = u16(buffer, cursor + 32);
            int length = CENTRAL_SIZE + nameSize + extraSize + commentSize;
            byte[] raw = new byte[length];
            System.arraycopy(data, cursor, raw, 0, length);
            String name = new String(data, cursor + CENTRAL_SIZE, nameSize, StandardCharsets.ISO_8859_1);
            out.add(new Member(name, u16(buffer, cursor + 10), u32(buffer, cursor + 20), u32(buffer, cursor + 24),
                    (int) u32(buffer, cursor + 42), raw));
            cursor += length;
        }
        if (cursor - offset != size) throw new IOException("central directory size mismatch");
        return out;
    }

    private static int bodyAt(byte[] data, Member member) throws IOException {
        ByteBuffer buffer = le(data);
        if (buffer.getInt(member.offset) != LOCAL_SIGNATURE) {
            throw new IOException("bad local header for " + member.name);
        }
        return member.offset + LOCAL_SIZE + u16(buffer, member.offset + 26) + u16(buffer, member.offset + 28);
    }

    /** A member, decompressed. */
    static byte[] read(byte[] data, String key, Lzx lzx) throws IOException {
        for (Member member : members(data)) {
            if (!member.key().equals(key)) continue;
            int body = bodyAt(data, member);
            byte[] compressed = new byte[(int) member.compressed];
            System.arraycopy(data, body, compressed, 0, compressed.length);
            switch (member.method) {
                case METHOD_STORED:
                    return compressed;
                case METHOD_DEFLATE: {
                    Inflater inflater = new Inflater(true);
                    inflater.setInput(compressed);
                    byte[] out = new byte[(int) member.size];
                    try {
                        int done = 0;
                        while (done < out.length && !inflater.finished()) {
                            int n = inflater.inflate(out, done, out.length - done);
                            if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break;
                            done += n;
                        }
                        if (done != out.length) throw new IOException(member.name + ": short deflate data");
                    } catch (java.util.zip.DataFormatException error) {
                        throw new IOException(member.name + ": " + error.getMessage());
                    } finally {
                        inflater.end();
                    }
                    return out;
                }
                case METHOD_LZX: {
                    byte[] out = lzx.decompress(compressed, (int) member.size);
                    if (out == null || out.length != member.size) {
                        throw new IOException(member.name + ": the LZX data could not be read");
                    }
                    return out;
                }
                default:
                    throw new IOException(member.name + ": unknown compression " + member.method);
            }
        }
        throw new IOException("the archive has no " + key);
    }

    /** The archive with replacements (by key()) stored uncompressed. */
    static Rebuilt rebuild(byte[] data, Map<String, byte[]> replacements) throws IOException {
        List<Member> members = members(data);
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length + 1024);
        ByteArrayOutputStream central = new ByteArrayOutputStream();
        for (Member member : members) {
            int body = bodyAt(data, member);
            int newOffset = out.size();
            byte[] record = member.record.clone();
            ByteBuffer recordBuffer = le(record);
            byte[] replacement = replacements.get(member.key());
            if (replacement == null) {
                out.write(data, member.offset, body + (int) member.compressed - member.offset);
            } else {
                CRC32 crc32 = new CRC32();
                crc32.update(replacement);
                int crc = (int) crc32.getValue();
                byte[] header = new byte[LOCAL_SIZE];
                System.arraycopy(data, member.offset, header, 0, LOCAL_SIZE);
                ByteBuffer local = le(header);
                local.putShort(6, (short) (u16(local, 6) & ~0x0008));  // no data descriptor
                local.putShort(8, (short) METHOD_STORED);
                local.putInt(14, crc);
                local.putInt(18, replacement.length);
                local.putInt(22, replacement.length);
                out.write(header, 0, LOCAL_SIZE);
                out.write(data, member.offset + LOCAL_SIZE, body - member.offset - LOCAL_SIZE);
                out.write(replacement, 0, replacement.length);
                recordBuffer.putShort(8, (short) (u16(recordBuffer, 8) & ~0x0008));
                recordBuffer.putShort(10, (short) METHOD_STORED);
                recordBuffer.putInt(16, crc);
                recordBuffer.putInt(20, replacement.length);
                recordBuffer.putInt(24, replacement.length);
            }
            recordBuffer.putInt(42, newOffset);
            setDataOffset(recordBuffer, newOffset + (body - member.offset));
            central.write(record, 0, record.length);
        }
        int dirstart = out.size();
        byte[] directory = central.toByteArray();
        out.write(directory, 0, directory.length);
        byte[] end = new byte[END_SIZE];
        ByteBuffer endBuffer = le(end);
        endBuffer.putInt(0, END_SIGNATURE);
        endBuffer.putShort(8, (short) members.size());
        endBuffer.putShort(10, (short) members.size());
        endBuffer.putInt(12, directory.length);
        endBuffer.putInt(16, dirstart);
        out.write(end, 0, END_SIZE);
        return new Rebuilt(out.toByteArray(), dirstart, directory.length + END_SIZE, members.size());
    }

    // The title reads each member's absolute data offset from an extra field
    // (id 0x1123, 4 bytes) of its central record.
    private static void setDataOffset(ByteBuffer record, int offset) {
        int at = CENTRAL_SIZE + u16(record, 28);
        int end = at + u16(record, 30);
        while (at + 4 <= end) {
            int key = u16(record, at), size = u16(record, at + 2);
            if (key == DATA_OFFSET_EXTRA && size == 4) {
                record.putInt(at + 4, offset);
                return;
            }
            at += 4 + size;
        }
    }

    // ---- media/zipmanifest.xml -------------------------------------------------

    private static Matcher manifestLine(String manifest, String archive) throws IOException {
        String guest = "game:\\" + archive.replace('/', '\\');
        Matcher match = Pattern.compile("(<Zip\\b[^>]*\\bpath=\"" + Pattern.quote(guest) + "\"[^>]*/>)",
                Pattern.CASE_INSENSITIVE).matcher(manifest);
        if (!match.find()) throw new IOException(guest + " is not in zipmanifest.xml");
        return match;
    }

    /** Only archives whose end record agrees with their manifest line are rebuilt. */
    static void checkSupported(byte[] data, String manifest, String archive) throws IOException {
        String line = manifestLine(manifest, archive).group(1);
        long[] values = new long[3];
        String[] keys = {"dirstart", "dirsize", "direntries"};
        for (int i = 0; i < 3; i++) {
            Matcher value = Pattern.compile("\\b" + keys[i] + "=\"(\\d+)\"").matcher(line);
            if (!value.find()) throw new IOException(archive + " has no " + keys[i] + " in zipmanifest.xml");
            values[i] = Long.parseLong(value.group(1));
        }
        ByteBuffer buffer = le(data);
        int end = endRecord(data);
        int disk = u16(buffer, end + 4), count = u16(buffer, end + 10);
        long size = u32(buffer, end + 12), offset = u32(buffer, end + 16);
        if (disk != 0 || offset != values[0] || size + END_SIZE != values[1] || count != values[2]) {
            throw new IOException(archive + " is not a plain zip archive");
        }
    }

    static String updateManifest(String manifest, String archive, Rebuilt rebuilt) throws IOException {
        Matcher match = manifestLine(manifest, archive);
        String line = match.group(1);
        String[] keys = {"dirstart", "dirsize", "direntries"};
        int[] values = {rebuilt.dirstart, rebuilt.dirsize, rebuilt.direntries};
        for (int i = 0; i < 3; i++) {
            Matcher value = Pattern.compile("\\b" + keys[i] + "=\"\\d+\"").matcher(line);
            int count = 0;
            StringBuffer replaced = new StringBuffer();
            while (value.find()) {
                value.appendReplacement(replaced, keys[i] + "=\"" + values[i] + "\"");
                count++;
            }
            value.appendTail(replaced);
            if (count != 1) throw new IOException(archive + " has no " + keys[i] + " in zipmanifest.xml");
            line = replaced.toString();
        }
        return manifest.substring(0, match.start()) + line + manifest.substring(match.end());
    }
}
