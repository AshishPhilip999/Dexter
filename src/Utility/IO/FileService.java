package Utility.IO;

import java.io.RandomAccessFile;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

import StorageStatus.Writer.IOStatus;
import Utility.Serialization.Serializer;

public abstract class FileService<K extends Serializable> {
    protected String filePath;
    protected FileType fileType;

    protected Map<K, Long> offsets;
    protected Map<Integer, Integer> vacancyMap;

    private RandomAccessFile randomAccessFile;
    private RandomAccessFile offSetsAccesFile;

    public FileService(String filePath) {
        this.filePath = filePath;
    }

    // writes data to the file and returns IOStatus
    public abstract IOStatus write(byte[] data);

    // writes data to the file with a specific key and returns IOStatus of the
    // written data
    // This method is useful for writing multiple data entries to the same file,
    // each associated with a unique key.
    public abstract IOStatus write(byte[] data, K key);

    // reads entire bytes data from file and returns it as IOStatus
    public abstract IOStatus read();

    // reads bytes data of the specific key appended into the file and returns it as
    // IOStatus
    // This method is useful for reading specific data entries from the file based
    // on their associated keys.
    public abstract IOStatus read(K key);

    public abstract IOStatus delete();

    public abstract IOStatus delete(K keK);

    public FileType getFileType() {
        return fileType;
    }

    public void appendFileExtension() {
        this.filePath += fileType.getExtension();
    }

    public int getWrittenDataCount() {
        return this.offsets.size();
    }

    public long getWrittenDataSize() {
        try {
            return this.randomAccessFile.length();
        } catch (Exception e) {
            e.printStackTrace();
            return 0;
        }
    }

    public void close() throws Exception {
        if (randomAccessFile != null) {
            randomAccessFile.close();
        }
        if (offSetsAccesFile != null) {
            offSetsAccesFile.close();
        }
    }

    protected RandomAccessFile getReadFileService() {
        return this.getFileService(AccessFileType.READ);
    }

    protected RandomAccessFile getWriteFileService() {
        return this.getFileService(AccessFileType.WRITE);
    }

    protected RandomAccessFile getOffSetAccessFile() {
        return this.offSetsAccesFile;
    }

    private RandomAccessFile getFileService(AccessFileType fileType) {
        Path path = Paths.get(this.filePath);
        if (fileType == AccessFileType.READ) {
            return this.configureFileService(null, path, fileType);
        } else if (fileType == AccessFileType.WRITE || (fileType == AccessFileType.READ_WRITE)) {
            return this.configureFileService(path.getParent(), path, fileType);
        }

        // Log: Do not have a case for null fileType.
        return null;
    }

    private RandomAccessFile configureFileService(Path parentPath, Path filePath, AccessFileType fileType) {
        if (fileType == AccessFileType.READ) {
            if (!Files.exists(filePath)) {
                // Log: File Path does not exist.
                this.randomAccessFile = null;
                return null;
            }
            // Log: File Path exists.
        } else if (fileType == AccessFileType.READ || fileType == AccessFileType.READ_WRITE) {
            if (!Files.exists(parentPath)) {
                // Log: Parent path does not exist
                this.randomAccessFile = null;
                return null;
            }
             // Log: Parent Path exists.
        }

        try {
            this.offSetsAccesFile = new RandomAccessFile(filePath.toString() + ".index", "rw");
            this.loadKeyOffSetMapping();
            return new RandomAccessFile(filePath.toString(), "rw");
        } catch (Exception e) {
            // Log Exception
            return null;
        }
    }

    private void loadKeyOffSetMapping() {
        if (this.offSetsAccesFile == null) {
            // Log: offSetAccessFile cannot be null here
            return;
        }
        offsets = new HashMap<>();

        try {
            this.offSetsAccesFile.seek(0);
            while (this.offSetsAccesFile.getFilePointer() < this.offSetsAccesFile.length()) {
                // read the offset of the data corresponding to the key.
                int keyLength = this.offSetsAccesFile.readInt();

                // read the key itself.
                byte[] keyBytes = new byte[keyLength];
                this.offSetsAccesFile.readFully(keyBytes);

                K key = Serializer.deserialize(keyBytes);
                // read the offset of the data corresponding to the key.
                long offset = this.offSetsAccesFile.readLong();

                offsets.put(key, offset);
            }
        } catch (Exception e) {
            // Log Excepton
        }
    }
}
