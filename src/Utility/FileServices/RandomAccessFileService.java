package Utility.FileServices;

import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

import Utility.Constants.Extension;

public class RandomAccessFileService {
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
    Writes data at seekPos in one positional write.
    */
    public void write(long seekPos, byte[] data) throws Exception {
        this.writeFully(ByteBuffer.wrap(data), seekPos);
    }

    public byte[] read(long seekPos, short dataLength) throws Exception {
        this.randomAccessFile.seek(seekPos);
        byte[] data = new byte[dataLength];
        this.randomAccessFile.readFully(data);

        return data;
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
