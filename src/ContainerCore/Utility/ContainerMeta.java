package ContainerCore.Utility;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.zip.CRC32;

/*
A container's metadata, kept in a small file next to its storage
(container.meta for page-based storage): how many values it holds, their
total size, its limits, and whether it was closed cleanly.

While a container is open the file is marked "open". close() writes the final
counts and marks it "closed". If the program stops without close(), the next
open finds the file still marked "open" (or damaged, or missing) and counts the
values again from the storage, so the counts are always right.

Format (52 bytes, big-endian):
    magic "DXCM"(4) version(4) closed(4: 1 = closed cleanly, 0 = open)
    padding(4) length(8) size(8) maxLength(8) maxSize(8) crc32(4)
*/
public final class ContainerMeta {
    private static final int MAGIC = 0x4458434D; // "DXCM"
    private static final int VERSION = 1;
    private static final int SIZE = 52;

    public long length;
    public long size;
    public long maxLength;
    public long maxSize;

    /*
    True if the file was written by close(), so length and size can be trusted.
    */
    public boolean closedCleanly;

    /*
    The metadata in file, or null if the file is missing or damaged.
    */
    public static ContainerMeta load(Path file) {
        try {
            if (!Files.isRegularFile(file) || Files.size(file) != SIZE) {
                return null;
            }
            ByteBuffer buffer = ByteBuffer.wrap(Files.readAllBytes(file));
            CRC32 crc = new CRC32();
            crc.update(buffer.array(), 0, SIZE - 4);
            if ((int) crc.getValue() != buffer.getInt(SIZE - 4)
                || buffer.getInt() != MAGIC || buffer.getInt() != VERSION) {
                return null;
            }
            ContainerMeta meta = new ContainerMeta();
            meta.closedCleanly = buffer.getInt() == 1;
            buffer.getInt();
            meta.length = buffer.getLong();
            meta.size = buffer.getLong();
            meta.maxLength = buffer.getLong();
            meta.maxSize = buffer.getLong();
            if (meta.length < 0 || meta.size < 0 || meta.maxLength < 0 || meta.maxSize < 0) {
                return null;
            }
            return meta;
        } catch (IOException e) {
            return null;
        }
    }

    /*
    Writes the metadata to file in one write and flushes it to disk.
    */
    public void save(Path file) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(SIZE);
        buffer.putInt(MAGIC).putInt(VERSION).putInt(this.closedCleanly ? 1 : 0).putInt(0)
              .putLong(this.length).putLong(this.size).putLong(this.maxLength).putLong(this.maxSize);
        CRC32 crc = new CRC32();
        crc.update(buffer.array(), 0, SIZE - 4);
        buffer.putInt((int) crc.getValue());
        buffer.flip();

        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            while (buffer.hasRemaining()) {
                channel.write(buffer, buffer.position());
            }
            channel.truncate(SIZE);
            channel.force(true);
        }
    }
}
