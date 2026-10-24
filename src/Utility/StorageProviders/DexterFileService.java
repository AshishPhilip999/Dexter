package Utility.StorageProviders;

import java.io.Closeable;

import StorageStatus.SP.SPResult;

/*
A storage provider: where a Container keeps its bytes. Containers only talk to
this interface (or DexterKeyFileService for keyed containers), so another kind
of storage can be added later by implementing it and adding a
StorageProviderType for it.

Every operation returns a SPResult and never throws. close() releases the
storage (files, locks) and is the one method that may throw.

Implemented by PBStorageProvider (page-based storage).
*/
public interface DexterFileService extends Closeable {
    /*
    Replaces everything stored with this one value.
    */
    SPResult write(byte[] data);

    /*
    The stored value (keyless storage), or every record (keyed storage).
    */
    SPResult read();

    /*
    Erases everything.
    */
    SPResult delete();
}
