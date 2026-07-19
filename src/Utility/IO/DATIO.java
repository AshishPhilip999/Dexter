package Utility.IO;

import java.io.ByteArrayOutputStream;
import java.io.RandomAccessFile;

import StorageStatus.SPStatus;
import StorageStatus.Writer.IOStatus;
import StorageStatus.Writer.FailStatus.IODeleteFail;
import StorageStatus.Writer.FailStatus.IOReadFail;
import StorageStatus.Writer.FailStatus.IOWriteFail;
import StorageStatus.Writer.SuccessStatus.IODeleteSuccess;
import StorageStatus.Writer.SuccessStatus.IOReadSuccess;
import StorageStatus.Writer.SuccessStatus.IOWriteSuccess;
import Utility.Serialization.Serializer;

import java.io.Serializable;

public class DATIO<K extends Serializable> extends FileService<K> {
    public DATIO(String filePath) throws Exception {
        super(filePath);
    }

    @Override
    public synchronized IOStatus write(byte[] data) {
        RandomAccessFile randomAccessFile = this.getWriteFileService();
        if (randomAccessFile == null) {
            return IOWriteFail.FILE_NOT_FOUND;
        }

        if (data == null) {
            return IOWriteFail.NULL_DATA_PROVIDED;
        }

        try {
            // Emptying the file before writing new data to ensure that the file only
            randomAccessFile.setLength(0);

            // contains the new data.
            this.put(data, 0, randomAccessFile);

            return IOWriteSuccess.FINISH_WRITE;
        } catch (Exception e) {
            // Log the exception
            return IOWriteFail.CANNOT_WRITE;
        }
    }

    @Override
    public IOStatus read() {
        RandomAccessFile randomAccessFile = this.getReadFileService();
        if (randomAccessFile == null) {
            return IOReadFail.FILE_NOT_FOUND;
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();

        try {
            randomAccessFile.seek(0);

            while (randomAccessFile.getFilePointer() < randomAccessFile.length()) {
                // Read payload length
                int length = randomAccessFile.readInt();

                // Read payload
                byte[] data = new byte[length];
                randomAccessFile.readFully(data);

                // Append payload only
                out.write(data);
            }
            IOReadSuccess success = IOReadSuccess.FINISH_READ;
            success.setData(out.toByteArray());

            return success;
        } catch (Exception e) {
            // Log exception

            return IOReadFail.CANNOT_READ;
        }
    }

    @Override
    public synchronized IOStatus write(byte[] data, K key) {
        RandomAccessFile randomAccessFile = this.getWriteFileService();
        RandomAccessFile offSetsAccesFile = this.getOffSetAccessFile();
        if (randomAccessFile == null || offSetsAccesFile == null) {
            return IOWriteFail.FILE_NOT_FOUND;
        }

        if (data == null) {
            return IOWriteFail.NULL_DATA_PROVIDED;
        }

        if (this.offsets.containsKey(key)) {
            return IOWriteFail.DUPLICATE_KEY_FOUND;
        }

        try {
            long offSet = randomAccessFile.length();
            this.updateOffSetMap(key, offSet, offSetsAccesFile);
            this.put(data, offSet, randomAccessFile);
        } catch (Exception e) {
            // Log Exception
            return IOWriteFail.CANNOT_WRITE;
        }

        return IOWriteSuccess.FINISH_WRITE;
    }

    @Override
    public IOStatus read(K key) {
        RandomAccessFile randomAccessFile = this.getReadFileService();
        if (randomAccessFile == null) {
            return IOReadFail.FILE_NOT_FOUND;
        }

        if (!this.offsets.containsKey(key)) {
            return IOReadFail.KEY_NOT_FOUND;
        }
        try {
            byte[] data = this.get(key, randomAccessFile);
            IOReadSuccess success = IOReadSuccess.FINISH_READ;
            success.setData(data);

            return success;
        } catch (Exception e) {
            // Log exception
            return IOReadFail.CANNOT_READ;
        }
    }

    @Override
    public synchronized IOStatus delete() {
        RandomAccessFile randomAccessFile = this.getWriteFileService();
        if (randomAccessFile == null) {
            return IODeleteFail.FILE_NOT_FOUND;
        }

        try {
            IOStatus readStatus = this.read();
            if (readStatus.getSPStatus() == SPStatus.SUCCESS) {
                IOReadSuccess readSuccess = (IOReadSuccess) readStatus;

                // Remove all contents of the file.
                randomAccessFile.setLength(0);
                randomAccessFile.seek(0);

                IODeleteSuccess success = IODeleteSuccess.FINISH_DELETE;
                success.setData(readSuccess.getData());
                return success;
            }

            // Returns Failed read status.
            return readStatus;
        } catch (Exception e) {
            // Log the exception
            return IODeleteFail.CANNOT_DELETE;
        }
    }

    public synchronized IOStatus delete(K key) {
        
    }

    private void put(byte[] data, long offSet, RandomAccessFile randomAccessFile) throws Exception {
        randomAccessFile.seek(offSet);

        // write the length of the data.
        randomAccessFile.writeInt(data.length);

        // write the data itself.
        randomAccessFile.write(data);
    }

    private byte[] get(K key, RandomAccessFile randomAccessFile) throws Exception {
        long offSet = offsets.get(key);

        randomAccessFile.seek(offSet);

        // read the length of the data.
        int length = randomAccessFile.readInt();

        byte[] data = new byte[length];

        // read the data itself.
        randomAccessFile.readFully(data);

        return data;
    }

    private void updateOffSetMap(K key, long offSet, RandomAccessFile offSetsAccesFile) throws Exception {
        offsets.put(key, offSet);
        this.writeKey(key, offSet, offSetsAccesFile);
    }

    private void writeKey(K key, long offSet, RandomAccessFile offSetsAccesFile) throws Exception {
        long length = offSetsAccesFile.length();

        offSetsAccesFile.seek(length);

        byte[] keyBytes = Serializer.serialize(key);
        // write the length of the key.
        offSetsAccesFile.writeInt(keyBytes.length);
        // write the key itself.
        offSetsAccesFile.write(keyBytes);
        // write the offset of the data corresponding to the key.
        offSetsAccesFile.writeLong(offSet);
    }
}
