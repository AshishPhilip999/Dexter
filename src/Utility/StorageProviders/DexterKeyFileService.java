package Utility.StorageProviders;

import java.io.Serializable;

import StorageStatus.SP.SPResult;

/*
A storage provider that stores values under keys of type K. Adds the keyed
operations to DexterFileService; read() returns every record and write(data)
/ delete() still replace / erase everything.

Implemented by PBKeyStorageProvider (page-based storage).
*/
public interface DexterKeyFileService<K extends Serializable> extends DexterFileService {
    /*
    Stores data under key, replacing any value it had.
    */
    SPResult write(K key, byte[] data);

    /*
    The value stored under key.
    */
    SPResult read(K key);

    /*
    Removes key and its value.
    */
    SPResult delete(K key);
}
