package Utility.FileServices;

import java.io.EOFException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

import Utility.Constants.Extension;

public class RandomAccessFileService implements AutoCloseable {
    private RandomAccessFile randomAccessFile;

    /*
    Channel of randomAccessFile, used for positional writes: the position is
    passed with the bytes, so a write is one system call instead of seek + write.
    */
    private FileChannel channel;
    private String path;
    private Extension extension;

    public String getPath() {
        return this.path;
    }

    public Extension getExtension() {
        return this.extension;
    }

    public RandomAccessFileService(String path, Extension extension) throws Exception {
        this.path = path;
        this.extension = extension;

        String fullPath = path + extension.getValue();
        this.randomAccessFile = new RandomAccessFile(fullPath, "rw");
        this.channel = this.randomAccessFile.getChannel();
    }

    public long length() throws Exception {
        return this.randomAccessFile.length();
    }

    public void truncate() throws Exception {
        this.randomAccessFile.setLength(0);
    }

    /*
    Cuts the file down to length bytes (no-op if it is already shorter).
    */
    public void truncate(long length) throws Exception {
        if (length < this.randomAccessFile.length()) {
            this.randomAccessFile.setLength(length);
        }
    }

    /*
    Makes sure everything written so far is on the disk, not just in the OS
    cache (fsync).
    */
    public void force() throws Exception {
        this.channel.force(true);
    }

    @Override
    public void close() throws IOException {
        this.randomAccessFile.close();
    }

    /*
    Writes data at seekPos in one positional write.
    */
    public void write(long seekPos, byte[] data) throws Exception {
        this.writeFully(ByteBuffer.wrap(data), seekPos);
    }

    public byte[] read(long seekPos, short dataLength) throws Exception {
        return this.read(seekPos, (int) dataLength);
    }

    /*
    Reads length bytes at seekPos with positional reads (no seek), e.g. a whole
    page in one call. Throws EOFException if the file ends first.
    */
    public byte[] read(long seekPos, int length) throws Exception {
        ByteBuffer buffer = ByteBuffer.allocate(length);
        long position = seekPos;
        while (buffer.hasRemaining()) {
            int read = this.channel.read(buffer, position);
            if (read < 0) {
                throw new EOFException("Read past end of file at " + position);
            }
            position += read;
        }
        return buffer.array();
    }

    public void writeLong(long seekPos, long value) throws Exception {
        this.randomAccessFile.seek(seekPos);
        this.randomAccessFile.writeLong(value);
    }

    public void writeShort(long seekPos, short value) throws Exception {
        this.randomAccessFile.seek(seekPos);
        this.randomAccessFile.writeShort(value);
    }

    /*
    Writes the values back to back starting at seekPos. They are copied into one
    buffer first, so all fields go out in a single positional write instead of
    a seek plus one write per field.
    */
    public void writeInSequence(long seekPos, byte[][] values) throws Exception {
        int totalLength = 0;
        for (byte[] value : values) {
            totalLength += value.length;
        }

        ByteBuffer buffer = ByteBuffer.allocate(totalLength);
        for (byte[] value : values) {
            buffer.put(value);
        }
        buffer.flip();

        this.writeFully(buffer, seekPos);
    }

    /*
    FileChannel.write may write fewer bytes than asked, so keep going until the
    buffer is drained. For regular files this is normally a single call.
    */
    private void writeFully(ByteBuffer buffer, long position) throws Exception {
        while (buffer.hasRemaining()) {
            position += this.channel.write(buffer, position);
        }
    }
}
