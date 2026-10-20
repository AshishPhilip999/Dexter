package Utility.StorageProviders.PageBasedStorageProvider;

/*
How hard an operation works to survive a power cut before it returns.

Either way, every operation is all-or-nothing and a storage is never left
half-updated: the journal (journal.wal) is replayed on the next open. They
differ only in what a power cut or OS crash (not a program crash) can lose:

NORMAL (default)  The journal is flushed to disk at checkpoints and by sync().
                  A power cut can lose the last operations before it; the
                  storage reopens as it was at some earlier operation. A killed
                  or crashed program loses nothing.
SYNC              The journal is flushed (fsync) before every write / delete
                  returns, so nothing that returned success is lost. Slower:
                  each operation waits for the disk.
*/
public enum Durability {
    NORMAL,
    SYNC
}
