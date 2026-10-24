package Utility.StorageProviders;

import java.io.Serializable;
import java.nio.file.Path;

import Utility.StorageProviders.PageBasedStorageProvider.PBKeyStorageProvider;
import Utility.StorageProviders.PageBasedStorageProvider.PBStorageProvider;
import Utility.StorageProviders.PageBasedStorageProvider.Utility.DexBundle;

/*
The kinds of storage a Container can use. Each value knows how to open its
storage provider and where a container keeps its metadata next to it, so a new
kind of storage is added here, without changing Container.

PAGE_BASED   PBStorageProvider / PBKeyStorageProvider: a .dex folder holding
             data.pb, index.pbi, journal.wal; the container's metadata goes
             in the same folder as container.meta.
*/
public enum StorageProviderType {
    PAGE_BASED {
        @Override
        public DexterFileService open(String path) throws Exception {
            return new PBStorageProvider(path);
        }

        @Override
        public <K extends Serializable> DexterKeyFileService<K> openKeyed(String path, Class<K> keyType) throws Exception {
            return new PBKeyStorageProvider<>(path, keyType);
        }

        @Override
        public Path metaFile(String path) {
            return DexBundle.folderOf(path).resolve(META_FILE);
        }
    };

    /*
    File name of a container's metadata (length, size and limits).
    */
    public static final String META_FILE = "container.meta";

    /*
    Opens (or creates) keyless storage at path.
    */
    public abstract DexterFileService open(String path) throws Exception;

    /*
    Opens (or creates) keyed storage at path for keys of keyType.
    */
    public abstract <K extends Serializable> DexterKeyFileService<K> openKeyed(String path, Class<K> keyType) throws Exception;

    /*
    Where a container on the storage at path keeps its metadata. Only used
    after the storage is open (and locked).
    */
    public abstract Path metaFile(String path);
}
