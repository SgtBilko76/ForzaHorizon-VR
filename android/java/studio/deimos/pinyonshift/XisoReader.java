package studio.deimos.pinyonshift;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Reads an Xbox 360 disc image (.iso) the player made from their own disc:
 * the game partition's XDVDFS file system, as extract-xiso reads it. Plain
 * Java, so tools can test it on a PC against a real image.
 *
 * The partition starts at one of the known offsets (XGD3, XGD2, XGD1 or a
 * bare XDVDFS image); its volume descriptor in sector 32 starts with
 * "MICROSOFT*XBOX*MEDIA" and names the root directory. A directory is a
 * binary tree of entries: left and right child offsets (in 4-byte units from
 * the directory start), the first sector and size of the file, attributes
 * (0x10 = directory) and the name. Sectors are 2048 bytes.
 */
final class XisoReader {
    static final int SECTOR = 2048;
    private static final long[] PARTITIONS = {0x2080000L, 0xFD90000L, 0x18300000L, 0L};
    private static final byte[] MAGIC = "MICROSOFT*XBOX*MEDIA".getBytes(StandardCharsets.US_ASCII);
    private static final int ATTRIBUTE_DIRECTORY = 0x10;
    private static final int MAX_DEPTH = 64;
    private static final long MAX_DIRECTORY = 64L << 20;

    static final class Entry {
        final String path;  // "/" separated, relative to the disc's root
        final long offset;  // in the image
        final long size;

        Entry(String path, long offset, long size) {
            this.path = path;
            this.offset = offset;
            this.size = size;
        }
    }

    interface Progress {
        /** Bytes of the current file written so far. */
        void written(long bytes);
    }

    private final FileChannel image;
    private final long partition;
    private final long length;

    private XisoReader(FileChannel image, long partition) throws IOException {
        this.image = image;
        this.partition = partition;
        this.length = image.size();
    }

    /** Opens the image's game partition; IOException when it is no Xbox disc image. */
    static XisoReader open(FileChannel image) throws IOException {
        for (long partition : PARTITIONS) {
            if (partition + 0x10000 + SECTOR > image.size()) continue;
            ByteBuffer magic = ByteBuffer.allocate(MAGIC.length);
            readFully(image, magic, partition + 0x10000);
            if (java.util.Arrays.equals(magic.array(), MAGIC)) return new XisoReader(image, partition);
        }
        throw new IOException("this file is not an Xbox 360 disc image (.iso)");
    }

    /** Every file on the disc, outside the folders named in skip (top-level names). */
    List<Entry> files(Set<String> skip) throws IOException {
        ByteBuffer descriptor = ByteBuffer.allocate(28).order(ByteOrder.LITTLE_ENDIAN);
        readFully(image, descriptor, partition + 0x10000);
        long rootSector = descriptor.getInt(20) & 0xFFFFFFFFL;
        long rootSize = descriptor.getInt(24) & 0xFFFFFFFFL;
        List<Entry> out = new ArrayList<>();
        directory(rootSector, rootSize, "", skip, out, 0);
        return out;
    }

    private void directory(long sector, long size, String prefix, Set<String> skip, List<Entry> out, int depth)
            throws IOException {
        if (size == 0) return;
        if (depth > MAX_DEPTH || size > MAX_DIRECTORY) throw new IOException("damaged disc image (directory)");
        ByteBuffer table = ByteBuffer.allocate((int) size).order(ByteOrder.LITTLE_ENDIAN);
        readFully(image, table, position(sector));
        // Walk the tree without recursion on the sibling links (a directory
        // can hold thousands of files); each entry is visited once.
        List<Integer> pending = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        pending.add(0);
        while (!pending.isEmpty()) {
            int at = pending.remove(pending.size() - 1);
            if (at < 0 || at + 14 > size || !seen.add(at)) continue;
            int left = table.getShort(at) & 0xFFFF;
            int right = table.getShort(at + 2) & 0xFFFF;
            if (left == 0xFFFF && right == 0xFFFF) continue;  // padding: no entry here
            long first = table.getInt(at + 4) & 0xFFFFFFFFL;
            long length = table.getInt(at + 8) & 0xFFFFFFFFL;
            int attributes = table.get(at + 12) & 0xFF;
            int nameLength = table.get(at + 13) & 0xFF;
            if (at + 14 + nameLength > size) throw new IOException("damaged disc image (name)");
            byte[] raw = new byte[nameLength];
            for (int i = 0; i < nameLength; i++) raw[i] = table.get(at + 14 + i);
            String name = new String(raw, StandardCharsets.ISO_8859_1);
            if (left != 0 && left != 0xFFFF) pending.add(left * 4);
            if (right != 0 && right != 0xFFFF) pending.add(right * 4);
            if (name.isEmpty() || name.equals(".") || name.equals("..") || name.contains("/")
                    || name.contains("\\")) {
                throw new IOException("damaged disc image (file name)");
            }
            if (depth == 0 && skip.contains(name)) continue;
            String path = prefix + name;
            if ((attributes & ATTRIBUTE_DIRECTORY) != 0) {
                directory(first, length, path + "/", skip, out, depth + 1);
            } else {
                long offset = position(first);
                if (offset + length > this.length) throw new IOException("the disc image is incomplete");
                out.add(new Entry(path, offset, length));
            }
        }
    }

    private long position(long sector) {
        return partition + sector * SECTOR;
    }

    /** Writes one file of the disc to target. */
    void extract(Entry entry, File target, Progress progress) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocateDirect(1 << 20);
        byte[] bytes = new byte[buffer.capacity()];
        long done = 0;
        try (OutputStream out = new FileOutputStream(target)) {
            while (done < entry.size) {
                buffer.clear();
                buffer.limit((int) Math.min(buffer.capacity(), entry.size - done));
                readFully(image, buffer, entry.offset + done);
                buffer.flip();
                int count = buffer.remaining();
                buffer.get(bytes, 0, count);
                out.write(bytes, 0, count);
                done += count;
                if (progress != null) progress.written(done);
            }
        }
    }

    private static void readFully(FileChannel channel, ByteBuffer buffer, long position) throws IOException {
        while (buffer.hasRemaining()) {
            int read = channel.read(buffer, position);
            if (read < 0) throw new IOException("the disc image is incomplete");
            position += read;
        }
    }
}
