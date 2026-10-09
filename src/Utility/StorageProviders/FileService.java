package Utility.StorageProviders;

import StorageStatus.Writer.IOStatus;

public abstract class FileService {
    public abstract IOStatus write() throws Exception;
}
