package Utility.StorageProviders.PageBasedStorageProvider;

import java.io.IOException;
import java.nio.file.Path;

/*
Thrown by the PBStorageProvider / PBKeyStorageProvider constructors when the
storage is already open: by another program, or by another instance in this
program that hasn't been closed. Only one instance may have a storage open at
a time; close() the other one first.
*/
public class StorageInUseException extends IOException {
    private static final long serialVersionUID = 1L;

    private final Path folder;

    public StorageInUseException(Path folder, String detail) {
        super("Storage " + folder + " is already open " + detail);
        this.folder = folder;
    }

    /*
    The .dex folder that is in use.
    */
    public Path getFolder() {
        return this.folder;
    }
}
