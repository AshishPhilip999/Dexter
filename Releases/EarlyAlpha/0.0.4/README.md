# Dexter: page-based storage for Java

Dexter is a Java library for storing byte data on disk. It provides two classes:

| Class | Use it when |
|---|---|
| `PBStorageProvider` | You want to keep **one** value, such as a settings object or a snapshot, and replace it as a whole. |
| `PBKeyStorageProvider<K>` | You want to store **many** values, each under its own key, and read, overwrite or delete them one at a time, like a persistent `Map<K, byte[]>`. |

Each storage is a `.dex` folder that survives program restarts. Both classes store `byte[]`, so you choose how to turn objects into bytes (the bundled `Serializer` uses Java serialization). Every read, write and delete returns a `PBResult` instead of throwing; a failure caused by an exception carries it, so you can see why. Every write and delete is all-or-nothing and survives a crash: changes go through a journal that is replayed when the storage is next opened.

- **Package:** `Utility.StorageProviders.PageBasedStorageProvider`
- **Jar:** `Dexter-v0.0.4.jar` (early alpha)
- **Version:** 0.0.4
- **Requires:** Java 11 or newer
- **Dependencies:** none (JDK only)

---

## Contents

1. [What's new in 0.0.4](#1-whats-new-in-004)
2. [Installation](#2-installation)
3. [Quick start](#3-quick-start)
4. [How it works](#4-how-it-works)
5. [PBStorageProvider reference](#5-pbstorageprovider-reference)
6. [PBKeyStorageProvider reference](#6-pbkeystorageproviderk-reference)
7. [Results and statuses](#7-results-and-statuses)
8. [Opening, closing and locking](#8-opening-closing-and-locking)
9. [Crash safety and recovery](#9-crash-safety-and-recovery)
10. [Space, dead bytes and compaction](#10-space-dead-bytes-and-compaction)
11. [Keys and key encoding](#11-keys-and-key-encoding)
12. [Limits and constants](#12-limits-and-constants)
13. [File format](#13-file-format)
14. [Performance](#14-performance)
15. [Thread safety, durability and caveats](#15-thread-safety-durability-and-caveats)
16. [Supporting classes](#16-supporting-classes)
17. [Planned for the next version](#17-planned-for-the-next-version)

---

## 1. What's new in 0.0.4

**Crash safety and recovery**

- **Every write and delete is all-or-nothing.** Its changes are collected while it runs and committed as one checksummed record to a journal, `journal.wal`. If the program or the machine stops at any moment, the next open replays the journal and the storage is exactly as it was after some complete operation, never half-way through one. See [section 9](#9-crash-safety-and-recovery).
- **A killed or crashed program loses nothing** that had returned success.
- **A power cut never corrupts the storage.** With the default `Durability.NORMAL` it can lose the last operations before the cut; with `Durability.SYNC` (or after `sync()`) it loses nothing that returned success.
- **Failed operations leave no trace.** If an operation fails part-way (an I/O error, a corrupt page), its changes are discarded and the instance reloads its state, so the storage is as it was before the call. This fixes 0.0.3's `delete(key)` problem, where a failed delete could leave a record without its key.
- **`compact()` recovers its index in place.** If rebuilding the index fails, it is retried; if it still fails, every later call rebuilds it first and reports the error with its cause until it succeeds, instead of answering from a stale index.

**New API**

- `Durability` (`NORMAL`, `SYNC`) and constructors that take it: `new PBKeyStorageProvider<>(path, Integer.class, Durability.SYNC)`.
- `sync()`: flush the journal, so everything so far survives a power cut.
- `checkpoint()`: copy the journal into `data.pb` / `index.pbi` and empty it.
- `getDurability()`, `getRecoveredOperations()`.
- New statuses `PBSuccess.SYNC`, `PBSuccess.CHECKPOINT`, `PBFail.SYNC_ERROR`, `PBFail.CHECKPOINT_ERROR`. A `switch` over these enums needs the new cases.

**Changed behaviour**

- **`data.pb` and `index.pbi` on disk lag behind** while a storage is open: recent changes may still be only in the journal (and memory) until they are applied or checkpointed. Reads always return the latest data. If you look at or copy the files yourself while the storage is open, call `checkpoint()` first, or copy the whole `.dex` folder including `journal.wal`. `getFileSize()` gives the real size. See [Files on disk](#files-on-disk).
- **`close()` now checkpoints**, so after a clean close the files are complete and the journal is empty.
- **Writes are slower:** about 1.3–2.2× slower than 0.0.3, depending on the workload; reads are unchanged. See [section 14](#14-performance).
- **Up to 16 MB more memory** holds changes waiting to be applied.
- `IndexFile.clear()` now sets the data file id to 0 (an index being rebuilt belongs to no data file until it is complete).

### Upgrading from 0.0.3

The file format of `data.pb` and `index.pbi` is unchanged; `journal.wal` is created on first open. No code changes are needed.

Older versions don't know about the journal. If a storage was left behind by a crash, open it once with 0.0.4 (which replays the journal) before using 0.0.3 or earlier on it, or the operations still in the journal are lost. After a clean `close()` the journal is empty and older versions can read the storage.

### Upgrading from 0.0.1 or 0.0.2

0.0.4 opens storages written by any earlier version. From 0.0.1, the loose `path.pb` file is moved into `path.dex/` on first open. From 0.0.2, add `close()` (or try-with-resources) and open each storage only once at a time; see the 0.0.3 README. Status checks moved to `PBResult` in 0.0.2:

```java
// 0.0.1
PBStatus status = sp.read(1);
if (status.getStatus() == OperationStatus.SUCCESS) {
    byte[] data = ((PBSuccess) status).getData();
}

// 0.0.2 and later
PBResult result = sp.read(1);
if (result.hasSucceeded()) {
    byte[] data = result.getData();
}
```

---

## 2. Installation

Add `Dexter-v0.0.4.jar` to your classpath. `Dexter-v0.0.4-sources.jar` is optional; it lets your IDE show the library's source.

**Command line**

```bash
javac -cp Dexter-v0.0.4.jar -d out MyApp.java
java  -cp Dexter-v0.0.4.jar:out MyApp          # on Windows use ; instead of :
```

**VS Code** (`.vscode/settings.json`)

```json
{
    "java.project.referencedLibraries": [ "lib/Dexter-v0.0.4.jar" ]
}
```

**Gradle**

```groovy
dependencies {
    implementation files('lib/Dexter-v0.0.4.jar')
}
```

**Maven:** install the jar into your local repository once, then depend on it.

```bash
mvn install:install-file -Dfile=Dexter-v0.0.4.jar -DgroupId=dexter \
    -DartifactId=dexter -Dversion=0.0.4 -Dpackaging=jar
```

```xml
<dependency>
    <groupId>dexter</groupId>
    <artifactId>dexter</artifactId>
    <version>0.0.4</version>
</dependency>
```

---

## 3. Quick start

### One value with `PBStorageProvider`

```java
import StorageStatus.PB.PBResult;
import Utility.Serialization.Serializer;
import Utility.StorageProviders.PageBasedStorageProvider.PBStorageProvider;

try (PBStorageProvider settings = new PBStorageProvider("data/settings")) {   // uses data/settings.dex/
    settings.write(Serializer.serialize("dark-mode"));

    PBResult result = settings.read();
    if (result.hasSucceeded()) {
        String value = Serializer.deserialize(result.getData());           // "dark-mode"
    }
}
```

### Many values by key with `PBKeyStorageProvider`

```java
import StorageStatus.PB.PBFail;
import StorageStatus.PB.PBResult;
import Utility.Serialization.Serializer;
import Utility.StorageProviders.PageBasedStorageProvider.PBKeyStorageProvider;

try (PBKeyStorageProvider<Integer> users = new PBKeyStorageProvider<>("data/users", Integer.class)) {
    users.write(1, Serializer.serialize("Ashish"));
    users.write(2, Serializer.serialize("Priya"));
    users.write(1, Serializer.serialize("Ashish R"));      // overwrites key 1

    PBResult one = users.read(1);
    if (one.hasSucceeded()) {
        String name = Serializer.deserialize(one.getData());   // "Ashish R"
    } else if (one.getError() != null) {
        one.getError().printStackTrace();                  // why it failed, e.g. an IOException
    }

    if (users.read(99).getStatus() == PBFail.KEY_NOT_FOUND) { /* no such key */ }

    byte[][] records = users.read().getDataArray();         // every record: 2 entries

    users.delete(2);                                        // remove one key
    if (users.getFragmentation() > 0.5) {
        users.compact();                                    // give deleted space back
    }
}
```

When the program starts again, `new PBKeyStorageProvider<>("data/users", Integer.class)` opens the same folder and every key is readable again.

Open each storage **once** and share that instance: it is thread-safe. A second instance on the same storage throws `StorageInUseException` until the first is closed (see [section 8](#8-opening-closing-and-locking)).

Every write and delete above is all-or-nothing and survives a crash of the program. To make each write survive a power cut too, open the storage with `Durability.SYNC`, or call `sync()` at the moments that matter:

```java
try (PBKeyStorageProvider<Integer> orders = new PBKeyStorageProvider<>("data/orders", Integer.class, Durability.SYNC)) {
    orders.write(42, order);          // on the disk before write() returns
}

users.write(7, profile);
users.sync();                         // everything so far now survives a power cut
```

---

## 4. How it works

### Files

The `path` passed to a constructor names a folder; `.dex` is added if it isn't there already, so `"data/users"` and `"data/users.dex"` are the same storage. The folder is created if needed; its parent folder must exist.

```
users.dex/
    data.pb           records, in pages
    index.pbi         key index and free-space index (keyed storage only)
    data-compact.pb   only while compact() runs
    dex.lock          locked by the instance that has the storage open
    journal.wal       operations not yet copied into data.pb / index.pbi
```

`data.pb` is a 256-byte **file header** followed by fixed-size **pages** of 4,090 bytes. The file header holds the key type, a dead-byte count and a random **file id**. `index.pbi` records the id of the data file it was built for; if the ids don't match (index missing, copied from elsewhere, or out of date), the index is rebuilt from `data.pb` on open.

### Pages and slots

Each page is a *slotted page*:

```
| page header (34 B) | slot 0 | slot 1 | ... →      free space      ← ... | record 1 | record 0 |
                       slot directory grows forward          records grow backward from the page end
```

- **Slot:** a 17-byte entry in the page's slot directory saying where a record is, how long it is, how many of its bytes are the key, and whether it's deleted.
- **Record:** the bytes themselves, `[key bytes][data bytes]`. Keyless records have no key bytes.
- **Page header:** tracks the slot count and the free space between the slot directory (`freeSpaceStart`) and the records (`freeSpaceEnd`).

A record is located by its **RecordID**, `(pageNumber, slotNumber)`.

### The index

`index.pbi` holds two B+ trees in 4,096-byte pages:

| Tree | Maps | Used for |
|---|---|---|
| Key index | encoded key → `(page, slot)` of the key's live record | `read(key)`, `delete(key)`, `containsKey`, overwrites |
| Free space | `(fullLength, page, slot)` of every deleted slot, smallest first | finding the best-fitting deleted slot to reuse |

A lookup walks from the root to a leaf, reading only the pages on that path. With 1 million keys the tree is 3–4 levels deep, and the top levels stay in the cache.

### Writing a keyed record

`write(key, data)` picks a place for the record in this order:

1. **Reuse a deleted slot (best fit):** the smallest deleted slot that is big enough, found with one `ceiling` lookup in the free-space tree.
2. **Append to the last page**, if it has room for one more slot entry plus the record.
3. **Start a new page** otherwise.

Each slot remembers its original size (`fullLength`) separately from the size of the record currently in it (`length`). A slot that once held 100 bytes can take a 40-byte record and later a 100-byte record again.

Overwriting a key writes the new record first, points the key index at it, then marks the old slot deleted, which makes it reusable by later writes.

All of this happens in memory. When the operation finishes, everything it changed is committed to the journal as one record (see [section 9](#9-crash-safety-and-recovery)).

### Opening a storage

The constructor first creates the folder if needed and locks `dex.lock` (or throws `StorageInUseException`). Holding the lock, it moves 0.0.1 files in and deletes a leftover `data-compact.pb`. Then it **replays the journal**: whatever a previous run committed but hadn't copied into the files yet is written into them. Then only the file headers are read. Data pages and index pages are loaded when first used and kept in LRU caches (1,024 pages each, about 4 MB each). Changes go through the journal, so an evicted page never needs saving.

---

## 5. `PBStorageProvider` reference

```java
package Utility.StorageProviders.PageBasedStorageProvider;
public class PBStorageProvider implements Closeable
```

Single-value storage: holds one record, and writing replaces everything. It is also the base class of `PBKeyStorageProvider` and owns all page, slot and file handling.

### Constructor

#### `PBStorageProvider(String path) throws Exception`

Opens the storage folder `path` (+ `.dex`), creating it if needed, and locks it. Then moves 0.0.1 files (`path.pb`) into the folder if it has no `data.pb` yet, deletes a `data-compact.pb` left behind by an interrupted `compact()`, and replays the journal. Uses `Durability.NORMAL`.

#### `PBStorageProvider(String path, Durability durability) throws Exception`

The same, choosing how hard writes work to survive a power cut (see [section 9](#9-crash-safety-and-recovery)). `IllegalArgumentException` if `durability` is `null`.

| | |
|---|---|
| `path` | Folder path, with or without `.dex`. |
| Throws | `StorageInUseException` if the storage is already open, in this program or another one. Another `IOException` subtype if the folder or file can't be opened or created (for example, the parent folder doesn't exist, or `path.dex` is a file). `IllegalStateException` if the file is corrupt or uses an incompatible format. |

If the constructor throws, nothing stays open or locked. It can open a folder written by `PBKeyStorageProvider`; `read()` then returns its first live record.

### Constants

| Constant | Value | Meaning |
|---|---|---|
| `DEFAULT_CACHE_PAGES` | 1024 | Data pages kept in memory (about 4 MB). |

### Public methods

After `close()`, every method that returns a `PBResult` returns `PBFail.STORAGE_CLOSED` (arguments are still checked first). The tables below leave that out.

#### `PBResult write(byte[] data)`

Replaces the whole contents with this one record, as one operation: all existing pages are dropped and the file is truncated. The key type in the file header (if any) is kept, and the file gets a new file id.

| Status | When |
|---|---|
| `PBSuccess.WRITE` | Written. `getData()` returns `data`. |
| `PBFail.NULL_WRITE` | `data` is `null` or empty. |
| `PBFail.DATA_TOO_LARGE` | `data` is longer than 4,039 bytes. |
| `PBFail.WRITE_ERROR` | An I/O error occurred; `getError()` has the cause. |

A write that fails validation leaves the existing contents untouched.

#### `PBResult read()`

Returns the stored record: the first live record in page and slot order.

| Status | When |
|---|---|
| `PBSuccess.READ` | `getData()` returns the record's bytes. `getDataArray()` is `null`. |
| `PBFail.NULL_READ` | Nothing is stored. |
| `PBFail.READ_ERROR` | An I/O error occurred. |

#### `PBResult delete()`

Erases every record. The file is truncated back to its 256-byte header; the stored key type is kept.

| Status | When |
|---|---|
| `PBSuccess.DELETE` | Erased, including when the storage was already empty. |
| `PBFail.DELETE_ERROR` | An I/O error occurred. |

#### `PBResult compact()`

Rewrites the data file with only the live records, packed tightly and in their current order. See [section 10](#10-space-dead-bytes-and-compaction).

| Status | When |
|---|---|
| `PBSuccess.COMPACT` | Done. `getDeadBytes()` is now 0. |
| `PBFail.COMPACT_ERROR` | An error occurred; `getError()` has the cause. See [When compaction fails](#when-compaction-fails). |

#### `long getDeadBytes()`

Bytes in the file's pages that hold no live data: deleted records, plus the unused part of slots reused for a smaller record. This is what `compact()` would give back.

#### `double getFragmentation()`

Dead bytes as a share of all page bytes, from `0.0` to `1.0`. `0.0` for an empty storage. After `close()`, this and `getDeadBytes()` keep their last values.

#### `long getFileSize() throws Exception`

Size of the data file in bytes, including operations still in the journal (`data.pb` on disk reaches this size at the next apply or checkpoint). Throws `IllegalStateException` after `close()`.

#### `PBResult sync()`

Flushes the journal to disk, so every operation that returned success so far survives a power cut. With `Durability.SYNC` that is already true. Much cheaper than `checkpoint()`.

| Status | When |
|---|---|
| `PBSuccess.SYNC` | Done. |
| `PBFail.SYNC_ERROR` | An I/O error occurred; `getError()` has the cause. The operations are still in the journal; they just may not be on the disk yet. |

#### `PBResult checkpoint()`

Copies every journaled operation into `data.pb` / `index.pbi`, flushes them to disk and empties the journal. Happens on its own (see [section 9](#9-crash-safety-and-recovery)); call it to have the files complete now, for example before copying them elsewhere.

| Status | When |
|---|---|
| `PBSuccess.CHECKPOINT` | Done: the files hold everything and the journal is empty. |
| `PBFail.CHECKPOINT_ERROR` | An I/O error occurred; `getError()` has the cause. Nothing is lost: the journal keeps every change, and the next checkpoint (or the next open) applies it again. |

#### `Durability getDurability()`

`NORMAL` or `SYNC`, as given to the constructor.

#### `int getRecoveredOperations()`

How many operations were replayed from the journal when this instance opened the storage: work a previous run had committed but not yet copied into the files, typically because it crashed or was killed. 0 after a clean `close()`.

#### `void close() throws IOException`

Checkpoints (so the files are complete and the journal empty), closes the files and releases the lock, so the storage can be opened again by this program or another one. Waits for operations running on other threads to finish. Safe to call more than once. If the checkpoint or closing a file fails, the lock is still released and the first error is thrown; nothing is lost, as the journal is replayed on the next open. See [section 8](#8-opening-closing-and-locking).

#### `boolean isClosed()`

`true` once `close()` has been called.

#### `Path getFolder()`

The storage's `.dex` folder.

### Protected methods (for subclasses)

These exist so `PBKeyStorageProvider`, or your own subclass, can build on the page and slot layer. Hold the right lock while calling them.

| Method | What it does |
|---|---|
| `Lock readLock()`, `Lock writeLock()` | The instance's shared and exclusive locks (a `ReentrantReadWriteLock`). |
| `void ensureOpen()` | Throws `IllegalStateException` if the storage is closed; for methods that throw rather than return a `PBResult`. |
| `final void closeAfterFailedOpen(Exception cause)` | For subclass constructors: closes everything and releases the lock after opening failed, attaching any close error to `cause` as suppressed. |
| `final Journal journal()` | The storage's journal, to open more journaled files or switch to direct mode. |
| `final void commitOperation()` | Ends a successful operation: its changes become one journal record. Call under the write lock; if it throws, call `rollbackOperation`. |
| `final void rollbackOperation(Exception cause)` | Ends a failed operation: discards its changes and reloads the in-memory state. If reloading fails, the next call repairs first. |
| `final void repairIfNeeded()` | Call at the start of every operation, before taking its lock: repairs the instance (under the write lock) if an earlier failure left it needing repair. Throws if repairing fails. |
| `byte[][] readAllRecords()` | Data of every live record in page and slot order, without key bytes. |
| `String getStoredKeyType()` / `void setStoredKeyType(String)` | Key type class name in the file header (`null` if none). |
| `long getFileId()` | Random id of the data file (never 0). |
| `int getPageCount()`, `int getCachedPageCount()` | Pages in the file / held in memory. |
| `Page getPage(int pageNumber)` | The page, from the cache or read from disk in one call; `null` if out of range. |
| `PBFail validateRecord(int keyLength, byte[] data)` | `NULL_WRITE` or `DATA_TOO_LARGE` if the record is invalid, otherwise `null`. |
| `RecordID placeRecord(byte[] keyBytes, byte[] data)` | Writes the record (reused slot, else last page, else new page) and returns where it went. Call `validateRecord` first. |
| `void markSlotDeleted(RecordID)` | Marks a slot deleted, updates the dead-byte count, calls `onSlotFreed`, and trims empty pages if it was on the last page. |
| `Slot getSlot(RecordID)` | The slot at that location, or `null`. |
| `byte[] readSlot(Slot)`, `readSlotKey(Slot)`, `readRecord(Slot)` | The slot's data bytes, key bytes, or whole record. |

**Hooks** a subclass can override (the base versions do nothing):

| Hook | Called when |
|---|---|
| `RecordID takeFreeSlot(short recordLength)` | A record is about to be placed. Return a deleted slot with `fullLength >= recordLength` to reuse it, or `null` to append. |
| `void onSlotFreed(RecordID, Slot)` | A slot was just marked deleted. |
| `void onSlotDropped(RecordID, Slot)` | A deleted slot's page is being cut off the end of the file. |
| `void onCompacted()` | `compact()` has put the packed file in place (record positions changed). |
| `void onClose() throws IOException` | `close()` is running: close the subclass's own files. Runs after the checkpoint, before `data.pb` is closed and the lock released. May run on a partly built instance when a constructor failed, so check fields for `null`. |
| `void onCleared()` | `delete()` or a keyless `write()` just erased the data file. Runs inside that operation, so the subclass's changes commit or roll back with it. |
| `void onReload()` | After a rolled-back operation: reload the subclass's in-memory state from its files. |
| `boolean needsRepair()` / `void repair()` | Whether the instance must be repaired before the next operation, and the repair itself (under the write lock). Subclasses add their own conditions and call `super`. |

---

## 6. `PBKeyStorageProvider<K>` reference

```java
package Utility.StorageProviders.PageBasedStorageProvider;
public class PBKeyStorageProvider<K extends Serializable> extends PBStorageProvider
```

Keyed storage: many records, each under a key of type `K`. Keys are checked twice:

- **When you compile:** through the generic `K`, so `write("x", data)` on a `PBKeyStorageProvider<Integer>` is a compile error.
- **When it runs:** through the `Class<K>` passed to the constructor, which also catches raw-typed or unchecked callers.

The key type is saved in the file header, so a storage can't be reopened with a different key type.

### Constructor

#### `PBKeyStorageProvider(String path, Class<K> keyType) throws Exception`

Opens or creates the storage folder, replays its journal, binds the key type and opens `index.pbi`, rebuilding it if it doesn't belong to `data.pb`. Uses `Durability.NORMAL`.

#### `PBKeyStorageProvider(String path, Class<K> keyType, Durability durability) throws Exception`

The same, choosing the durability (see [section 9](#9-crash-safety-and-recovery)).

| | |
|---|---|
| `path` | Folder path, with or without `.dex`. |
| `keyType` | The key class, for example `Integer.class` or `String.class`. Primitive classes are accepted and mapped to their wrapper (`int.class` → `Integer.class`). |
| New or keyless storage | `keyType` is stored in the file header. A keyless storage's existing record stays and appears in `read()`. |
| Storage with a key type | Must match `keyType` exactly. |
| Throws | `StorageInUseException` if the storage is already open. `IllegalArgumentException` if `keyType` is `null` or its class name is longer than 234 bytes. `IllegalStateException` if the storage holds a different key type, or is corrupt. An `IOException` subtype if files can't be opened. `ClassNotFoundException` if the index has to be rebuilt and stored custom keys can't be deserialized. |

```java
try (PBKeyStorageProvider<Integer> sp = new PBKeyStorageProvider<>(path, Integer.class)) {
    // use sp
} catch (StorageInUseException e) {
    // another instance or program has it open
} catch (IllegalStateException e) {
    // the storage holds a different key type
}
```

If the constructor throws, nothing stays open or locked.

### Constants

| Constant | Value | Meaning |
|---|---|---|
| `MAX_KEY_BYTES` | 1024 | Largest encoded key (`KEY_TOO_LARGE` above it). |

### Public methods

As for `PBStorageProvider`, every method returning a `PBResult` returns `PBFail.STORAGE_CLOSED` after `close()`; the tables leave that out.

#### `PBResult write(K key, byte[] data)`

Stores `data` under `key`. If the key exists, its value is replaced (upsert).

Checks run in this order; the first failure is returned:

| Status | When |
|---|---|
| `PBFail.NULL_KEY` | `key` is `null`. |
| `PBFail.KEY_TYPE_MISMATCH` | `key` isn't an instance of the key type (only possible from raw or unchecked code). |
| `PBFail.NULL_WRITE` | `data` is `null` or empty. |
| `PBFail.KEY_ENCODE_ERROR` | The key couldn't be encoded (for example, a custom key with a non-serializable field); `getError()` has the cause. |
| `PBFail.KEY_TOO_LARGE` | The encoded key is longer than 1,024 bytes. |
| `PBFail.DATA_TOO_LARGE` | Encoded key + data is longer than 4,039 bytes. For an `Integer` key that's 4,034 data bytes. |
| `PBFail.WRITE_ERROR` | An I/O error occurred. |
| `PBSuccess.WRITE` | Written. `getData()` returns `data`. |

#### `PBResult write(byte[] data)` (inherited)

As for `PBStorageProvider`: replaces **everything** with one keyless record, and clears the index in the same operation, so every key now returns `KEY_NOT_FOUND`. The key type is kept, and keyed writes work afterwards.

#### `PBResult read(K key)`

| Status | When |
|---|---|
| `PBSuccess.READ` | `getData()` returns the value. `getDataArray()` is `null`. |
| `PBFail.NULL_KEY` | `key` is `null`. |
| `PBFail.KEY_TYPE_MISMATCH` | `key` has the wrong type (raw or unchecked callers). |
| `PBFail.KEY_NOT_FOUND` | No value is stored under `key` (including keys that can't be encoded or are too large to have been stored). |
| `PBFail.READ_ERROR` | An I/O error occurred, or the index points at a record that is deleted or holds a different key (a damaged index; see `reconstructKeyIndex()`). `getError()` says which. |

#### `PBResult read()` (override)

Reads **every** live record.

| Status | When |
|---|---|
| `PBSuccess.READ` | `getDataArray()` returns one `byte[]` per record, data only (no key bytes). `getData()` returns the first of them. |
| `PBFail.NULL_READ` | Nothing is stored. |
| `PBFail.READ_ERROR` | An I/O error occurred. |

Records are in page and slot order, not key order. That is write order until deleted slots start being reused, after which newer records can appear in older positions. Each key appears once, with its latest value.

```java
for (byte[] r : sp.read().getDataArray()) {
    MyObject o = Serializer.deserialize(r);
}
```

#### `PBResult delete(K key)`

Deletes the value stored under `key`: the key leaves the index and its slot is marked deleted (reusable by later writes), both or neither. If it was the last live record on the last page, that page (and any empty pages before it) is cut off the file.

| Status | When |
|---|---|
| `PBSuccess.DELETE` | Deleted. |
| `PBFail.NULL_KEY` | `key` is `null`. |
| `PBFail.KEY_TYPE_MISMATCH` | `key` has the wrong type. |
| `PBFail.KEY_NOT_FOUND` | No value is stored under `key`, including a key already deleted. |
| `PBFail.DELETE_ERROR` | An I/O error occurred. |

#### `PBResult delete()` (inherited)

Erases every record and clears the index, as one operation. The file is truncated to its header and keeps its key type. Returns `PBSuccess.DELETE`, or `PBFail.DELETE_ERROR` on an I/O error.

#### `PBResult compact()` (inherited)

As for `PBStorageProvider`, and then the index is rebuilt for the new record positions, which also shrinks `index.pbi`. If the rebuild fails it is tried once more; see [When compaction fails](#when-compaction-fails).

#### `boolean containsKey(K key)`

`true` if a value is stored under `key`. Returns `false` for `null`, a key of the wrong type, on a read error, or after `close()`, instead of failing.

#### `Class<?> getKeyType()`

The key type this storage accepts. For `int.class` it returns `Integer.class`.

#### `void reconstructKeyIndex() throws Exception`

Rebuilds `index.pbi` from the data pages: every live keyed record goes into the key index, every deleted slot into the free-space tree. If two live records have the same key (possible after a crash in the middle of an overwrite), the later one is kept and the earlier one is marked deleted.

It runs automatically on open when the index is missing or doesn't belong to the data file, after `compact()`, and before the next call after a failed rebuild. You normally don't need to call it; call it if `read(key)` returns `READ_ERROR` for a damaged index. Throws `IllegalStateException` if a stored key isn't of the storage's key type, or after `close()`.

The rebuild writes `index.pbi` directly rather than through the journal (it is itself the recovery path). While it runs the index is marked as belonging to no data file, and it only gets the data file's id after every node has been flushed to disk, so a rebuild interrupted by a crash is simply redone on the next open.

#### `void close() throws IOException` (inherited)

Also closes `index.pbi`.

---

## 7. Results and statuses

Package `StorageStatus.PB`.

### `PBResult`

Every read, write, delete and compact returns a new `PBResult`. A result keeps its own data: it can be stored, compared with later results, or handed to another thread.

| Method | Returns |
|---|---|
| `boolean hasSucceeded()` | The overall check: `true` if the operation passed (the status is a `PBSuccess`), `false` if it failed (a `PBFail`). |
| `PBStatus getStatus()` | The detailed outcome: a `PBSuccess` or `PBFail` value. |
| `byte[] getData()` | `READ`: the record (for the keyed `read()`, the first record). `WRITE`: the data written. Otherwise `null`. |
| `byte[][] getDataArray()` | Keyed `read()`: every record. Otherwise `null`. |
| `Exception getError()` | Why the operation failed, when an exception caused it: the exception behind a `READ_ERROR`, `WRITE_ERROR`, `DELETE_ERROR`, `COMPACT_ERROR` or `KEY_ENCODE_ERROR`. `null` for successes and for failures found by checking the input (`NULL_KEY`, `KEY_NOT_FOUND`, `DATA_TOO_LARGE`, `STORAGE_CLOSED`, ...). |
| `String toString()` | For example `READ (3 bytes)`, `READ (2 records)`, `KEY_NOT_FOUND`, `WRITE_ERROR (java.io.IOException: No space left on device)`. |
| `static of(PBStatus)`, `of(PBStatus, byte[])`, `ofAll(PBStatus, byte[][])` | Factories used by the providers. The status can't be `null`. |
| `static ofError(PBFail, Exception)` | A failure with its cause. Takes a `PBFail`, so a success can never carry an error. |

`getData()` returns the array itself, not a copy; for `WRITE` it's the array you passed in.

```java
PBResult result = sp.read(42);

if (result.hasSucceeded()) {
    byte[] data = result.getData();
} else if (result.getStatus() == PBFail.KEY_NOT_FOUND) {
    // a specific failure
} else if (result.getError() != null) {
    log(result.getError());     // e.g. java.io.IOException: No space left on device
}
```

### `PBStatus` (interface)

A marker implemented by `PBSuccess` and `PBFail`, so `getStatus()` can return either.

### `PBSuccess` (enum)

| Constant | Returned by |
|---|---|
| `READ` | `read()`, `read(key)` |
| `WRITE` | `write(data)`, `write(key, data)` |
| `DELETE` | `delete()`, `delete(key)` |
| `COMPACT` | `compact()` |
| `SYNC` | `sync()` |
| `CHECKPOINT` | `checkpoint()` |

### `PBFail` (enum)

| Constant | Meaning |
|---|---|
| `NULL_READ` | `read()` with nothing stored. |
| `NULL_WRITE` | Write with `null` or empty data. |
| `NULL_KEY` | Keyed operation with a `null` key. |
| `KEY_TYPE_MISMATCH` | Key isn't of the storage's key type. |
| `KEY_NOT_FOUND` | `read(key)` or `delete(key)` for a key that isn't stored. |
| `KEY_ENCODE_ERROR` | The key couldn't be encoded. |
| `KEY_TOO_LARGE` | The encoded key is longer than 1,024 bytes. |
| `DATA_TOO_LARGE` | Key + data don't fit in one page (more than 4,039 bytes). |
| `READ_ERROR` | I/O error while reading, or a damaged index. |
| `WRITE_ERROR` | I/O error while writing. |
| `DELETE_ERROR` | I/O error while deleting. |
| `COMPACT_ERROR` | Error while compacting; see [section 10](#when-compaction-fails). |
| `STORAGE_CLOSED` | The instance was closed with `close()`; open a new one to use the storage again. |
| `SYNC_ERROR` | I/O error while flushing the journal in `sync()`. |
| `CHECKPOINT_ERROR` | I/O error while copying the journal into the files in `checkpoint()`. Nothing is lost. |

The `..._ERROR` values (and `KEY_ENCODE_ERROR`) carry their cause in `getError()`.

---

## 8. Opening, closing and locking

### One instance per storage

Only one instance may have a storage open at a time. Each instance keeps its own picture of the file in memory (page count, cached pages, dead-byte count, cached index pages), so two instances writing the same storage would overwrite each other's records without noticing. Opening therefore locks the `.dex` folder:

| Second opener | Result |
|---|---|
| Another instance in the same program | `StorageInUseException`: "... is already open in this program (close() the other instance first)" |
| Another program | `StorageInUseException`: "... is already open by another program (pid 4242)" |
| After the first instance was closed, or its program exited | Opens normally |

`"data/users"` and `"data/users.dex"` are the same storage, so they lock each other. Different storages never block each other.

Open each storage once and share the instance; it is thread-safe:

```java
class UserRepository implements AutoCloseable {
    private final PBKeyStorageProvider<Integer> users;

    UserRepository() throws Exception {
        this.users = new PBKeyStorageProvider<>("data/users", Integer.class);
    }

    byte[] load(int id)         { return users.read(id).getData(); }
    void save(int id, byte[] d) { users.write(id, d); }

    @Override
    public void close() throws Exception { users.close(); }
}
```

### `close()`

- Waits for operations running on other threads, checkpoints (the files become complete and the journal empty), closes `data.pb` (and `index.pbi`), then releases the lock.
- Safe to call more than once; `isClosed()` reports the state.
- Afterwards, operations return `PBFail.STORAGE_CLOSED`, `containsKey` returns `false`, and `getFileSize()` / `reconstructKeyIndex()` throw `IllegalStateException`. `getDeadBytes()`, `getFragmentation()` and `getFolder()` still answer.
- Nothing is lost without it: every operation is in the journal when it returns, and the journal is replayed on the next open. `close()` leaves the files complete and frees the storage for the next opener.

### When the program ends

`close()` isn't called automatically, but the lock is released anyway: it is held through the operating system, which drops it when the process ends, however it ends (normal exit, `System.exit`, an uncaught exception, Ctrl+C, a kill or a crash). There is never a stale lock to remove. `dex.lock` itself stays in the folder; an unlocked `dex.lock` blocks nobody. Operations that were still only in the journal are replayed on the next open.

### Notes

- The lock is on `dex.lock`, not `data.pb`, because `compact()` replaces `data.pb` with a new file.
- Moving 0.0.1 files in and deleting a leftover compaction file happen only while holding the lock.
- Locks on network file systems (NFS, SMB) depend on the server and may not be enforced. The guarantee is for local disks.

---

## 9. Crash safety and recovery

### How an operation is made safe

Take `users.write(5, data)` where key 5 already exists:

| Step | What happens | Where it lives |
|---|---|---|
| 1 | The new record is placed in a slot; slot entry, page header and dead-byte count are updated | Memory only |
| 2 | The key index points key 5 at the new slot | Memory only |
| 3 | The old slot is marked deleted and added to the free-space tree | Memory only |
| 4 | **Commit:** the bytes that changed are packed into one record with a checksum and appended to `journal.wal` | The operating system's file cache |
| 5 | `Durability.SYNC` only: `journal.wal` is flushed to disk (fsync) | The disk |
| 6 | `write()` returns `WRITE` | |

`data.pb` and `index.pbi` aren't touched during the operation. They receive the changes later:

- **Apply:** when about 16 MB of changed blocks are waiting in memory, the journal is flushed to disk, the changes are written into `data.pb` / `index.pbi` (not flushed), and the memory is freed. The journal keeps the records.
- **Checkpoint:** when the journal reaches 64 MB, before `compact()`, on `close()` and on `checkpoint()`, the journal is flushed, the remaining changes are written, `data.pb` / `index.pbi` are flushed to disk, and only then is the journal emptied.

If an operation fails part-way (an exception in steps 1–4), its changes are simply discarded, the instance reloads its state from the files and journal, and the operation returns its `..._ERROR`. The storage is as it was before the call.

### Recovery on open

1. Records are read from `journal.wal` in order. Each must have a sane length, a matching checksum, the current salt and the next sequence number.
2. Each valid record's changes are written into `data.pb` / `index.pbi`. Reading stops at the first record that fails a check: that is the operation that was being written when the crash hit, and it is dropped (it never happened).
3. The files are flushed and the journal is emptied. `getRecoveredOperations()` reports how many operations were replayed.

Replaying a record writes the same bytes again, so it doesn't matter if some of them were already in the files: a crash during an apply, a checkpoint or recovery itself is recovered the same way. Replaying a 48 MB journal takes well under a second (see [section 14](#14-performance)).

### What a crash loses

| Crash during / after | Program crash (killed, JVM crash) | Power cut or OS crash, `NORMAL` | Power cut or OS crash, `SYNC` |
|---|---|---|---|
| Steps 1–3, or while the record is written | The operation didn't happen | The operation didn't happen | The operation didn't happen |
| After the record is written, before `write()` returns | The operation happened | May be lost | May be lost |
| After `write()` returned | Kept | May be lost* | Kept |

\* With `NORMAL`, a power cut loses the operations since the journal was last flushed (at the last apply, checkpoint or `sync()`). What is lost is always a tail of whole operations: the storage reopens as it was after some earlier operation, never half-way through one.

A program crash keeps everything because the journal record is already in the operating system's file cache, which the operating system writes to disk even after the program has died. A power cut never corrupts the storage because `data.pb` / `index.pbi` are only written after the journal holding those changes has been flushed, and every change written to them stays in the journal until they have been flushed too.

In the second row the operation survives even though the program never saw `WRITE`: the record was complete, the program just died before reporting it. Every storage has this gap; if your program must know, read the key after restarting.

### Choosing a durability

| | `Durability.NORMAL` (default) | `Durability.SYNC` |
|---|---|---|
| Program crash | Nothing that returned success is lost | Nothing that returned success is lost |
| Power cut / OS crash | Never corrupted; the last operations may be lost | Never corrupted; nothing that returned success is lost |
| Cost per write | About 20 µs | About 250 µs (each write waits for the disk) |

With `NORMAL`, call `sync()` at the moments that matter (after saving a user's document, say) to make everything so far survive a power cut.

### Files on disk

Because changes reach `data.pb` / `index.pbi` only at applies and checkpoints, **the files on disk lag behind while a storage is open**. Reads always return the latest data (they check the changes waiting in memory first); the lag only shows if you look at the files yourself:

| What you do while the storage is open | Result |
|---|---|
| `read(key)` right after `write(key, …)` | The latest value |
| Check the size of `data.pb` on disk | May be smaller; `getFileSize()` gives the real size |
| Copy only `data.pb` / `index.pbi` | May miss recent changes: call `checkpoint()` first |
| Copy the whole `.dex` folder, `journal.wal` included | Fine: opening the copy replays the journal |
| Edit `data.pb` with another tool | Don't: `close()` writes the instance's changes back over it |

After a clean `close()` the files are complete and the journal is empty.

### Limits

- The guarantees rely on the disk honouring flushes (fsync). On macOS, Java's flush doesn't empty the drive's own write cache, so a power cut can still lose the last flushed writes there; the storage is still never corrupted by a program crash.
- If a Java `Error` (such as `OutOfMemoryError`) is thrown in the middle of an operation and your program catches it and carries on, the half-done operation's changes are not rolled back and can be committed by the next operation. If the `Error` ends the program, nothing is affected.
- Older versions (0.0.3 and earlier) ignore the journal: open a crashed storage with 0.0.4 first.

---

## 10. Space, dead bytes and compaction

Deleting a record only marks its slot deleted. The bytes stay in the file until a later write reuses the slot, and a slot reused for a smaller record keeps its unused tail. These are **dead bytes**.

| Event | Dead bytes |
|---|---|
| `delete(key)`, or an overwrite (old version) | + the record's length |
| A deleted slot is reused | − the new record's length (its unused tail stays dead) |
| Empty pages at the end are trimmed | − their slots |
| `compact()`, `delete()`, keyless `write(data)` | reset to 0 |

The count is kept in the file header, so `getDeadBytes()` and `getFragmentation()` are instant and survive restarts.

### Automatic tail trimming

When a delete leaves the last page with no live records, that page is cut off the file, together with any empty pages directly before it. Deleting every record leaves just the 256-byte header. Trimming only helps at the end of the file: one live record on the last page keeps it.

### `compact()`

1. Checkpoints, so `data.pb` holds every operation and the journal is empty.
2. Copies every live record, in page and slot order, into `data-compact.pb`, packed with no gaps (each slot's `fullLength` equals its record length), then flushes it to disk.
3. Renames it over `data.pb` in one step, so the folder holds either the old file or the new one, never a mix. The new file has a new file id.
4. Keyed storage: rebuilds `index.pbi` from scratch, which also packs the index. If that fails, it is tried once more.

| | |
|---|---|
| Time | Proportional to the live data: about 4 s for 500,000 live records (see [section 14](#14-performance)). |
| Disk | Needs free space for one more copy of the live data while it runs. |
| Concurrency | Holds the write lock: other threads' reads and writes wait until it finishes. |
| Order | `read()` returns records in the same order before and after. |

Run it when fragmentation is high, for example at startup or after a large clean-up:

```java
if (storage.getFragmentation() > 0.5) {
    storage.compact();
}
```

### If the program stops during `compact()`

| Stopped | Result |
|---|---|
| Before the rename | `data.pb` is untouched. The leftover `data-compact.pb` is deleted on the next open. |
| After the rename, before the index rebuild finished | The new `data.pb` is complete. The index still carries the old file id, so it is rebuilt automatically on the next open. |

### When compaction fails

`compact()` returns `COMPACT_ERROR` instead of throwing, with the cause in `getError()`. What state the storage is in depends on where it failed:

| Failed while | Typical causes | State afterwards |
|---|---|---|
| Writing the packed copy | Disk full; no write permission on the `.dex` folder; a read error or corrupt page in `data.pb` | Unchanged. The temp file is deleted. |
| Renaming over `data.pb` | Read-only folder; another process has `data.pb` open (mainly Windows) | Unchanged. The original file is reopened. |
| Rebuilding the index (keyed), twice | Disk full or I/O error on `index.pbi`; a stored custom key that can no longer be deserialized | Data is compacted and complete. Every later call rebuilds the index first; until that succeeds it returns `READ_ERROR` / `WRITE_ERROR` / `DELETE_ERROR` with the cause, never a wrong answer from the stale index. The second failure is attached to the first as suppressed. |
| Reopening `data.pb` after the rename | The folder was removed or made unreadable | The next call tries to reopen it; until that works, calls return `READ_ERROR` / `WRITE_ERROR` with the cause. |

If deleting the temp file also fails, the original cause is still the one reported; the clean-up error is attached to it as suppressed (`getError().getSuppressed()`).

---

## 11. Keys and key encoding

Any `Serializable` class can be a key. Keys are stored in front of their data (so the index can be rebuilt) and in the index. `KeyCodec` encodes each key as **1 type-tag byte followed by its raw value**, much smaller than Java serialization:

| Key type | Stored size | With Java serialization |
|---|---|---|
| `Integer`, `Float` | 5 bytes | 79–81 bytes |
| `Long`, `Double` | 9 bytes | 82–84 bytes |
| `Short`, `Character` | 3 bytes | 50–77 bytes |
| `Byte`, `Boolean` | 2 bytes | 47–75 bytes |
| `String` | 1 + UTF-8 length | larger |
| Any other `Serializable` class | 1 + its serialized size | — |

The index compares **encoded bytes**, so two keys are the same key when they encode to the same bytes. For custom key classes this means:

- implement `Serializable`, with `Serializable` fields;
- equal keys must serialize to identical bytes: use simple fields (numbers, strings, enums), not hash-based collections whose order can vary;
- the class must still be loadable (same class name and `serialVersionUID`) when the storage is reopened or its index rebuilt;
- the encoded key must be at most 1,024 bytes.

```java
final class UserKey implements Serializable {
    private static final long serialVersionUID = 1L;
    final String region;
    final int id;
    UserKey(String region, int id) { this.region = region; this.id = id; }
}

PBKeyStorageProvider<UserKey> sp = new PBKeyStorageProvider<>("data/users", UserKey.class);
```

---

## 12. Limits and constants

| Limit | Value |
|---|---|
| Largest record (key bytes + data bytes) | **4,039 bytes**: page 4,090 − page header 34 − slot entry 17 |
| Largest value with an `Integer` key | 4,034 bytes |
| Largest keyless value | 4,039 bytes |
| Largest encoded key | 1,024 bytes (`MAX_KEY_BYTES`) |
| Key type class name | 234 UTF-8 bytes |
| Records per page | depends on size: 33 records of 100 bytes with `Integer` keys |
| Smallest data file | 256 bytes (empty storage) |
| Pages cached in memory | 1,024 data + 1,024 index pages (about 8 MB in total) |
| Changes waiting to be applied | Up to 4,096 blocks of 4 KB (16 MB; `Journal.APPLY_BLOCKS`) |
| Journal size before a checkpoint | 64 MB (`Journal.CHECKPOINT_BYTES`), also the most a recovery replays |

Layout sizes come from the `PBOffSets` enum (package `Utility.StorageProviders.PageBasedStorageProvider.Utility`):

| Constant | Value | Meaning |
|---|---|---|
| `FILE_HEADER` | 256 | Bytes before page 0. |
| `PAGE` | 4090 | Bytes per data page. |
| `HEADER` | 34 | Bytes per page header. |
| `SLOT` | 17 | Bytes per slot entry. |

---

## 13. File format

All numbers are big-endian.

### `data.pb`

**File header:** bytes 0–255

| Offset | Size | Field |
|---|---|---|
| 0 | 2 | Length of the key type name (0 = no key type) |
| 2 | n | Key type class name, UTF-8, for example `java.lang.Integer` (n ≤ 234) |
| 2+n | | Zero padding |
| 236 | 4 | Marker `"DXD1"`: the dead-byte count below is present |
| 240 | 8 | Dead bytes |
| 248 | 8 | File id (random, never 0) |

Files from 0.0.1 have zeros at 236–255; they get a file id and a dead-byte count on first open.

**Page `p`** starts at `256 + p × 4090`.

Page header (34 bytes):

| Offset | Size | Field |
|---|---|---|
| 0 | 8 | Page offset in the file (used as a consistency check) |
| 8 | 2 | Slot count |
| 10 | 8 | `freeSpaceStart`: where the next slot entry goes |
| 18 | 8 | `freeSpaceEnd`: where the record area begins |
| 26 | 8 | `freeBytes` |

Slot entry (17 bytes each, right after the page header):

| Offset | Size | Field |
|---|---|---|
| 0 | 8 | Record offset in the file |
| 8 | 2 | `fullLength`: bytes reserved when the slot was created |
| 10 | 2 | `length`: current record length (key + data), ≤ `fullLength` |
| 12 | 4 | `keyLength`: 0 for keyless records |
| 16 | 1 | Deleted flag (1 = deleted) |

Record at the slot's offset: `[keyLength bytes of encoded key][length − keyLength bytes of data]`.

Encoded key: `[1-byte tag][value]`. Tags: 1 `Integer`, 2 `Long`, 3 `Short`, 4 `Byte`, 5 `Double`, 6 `Float`, 7 `Character`, 8 `Boolean`, 9 `String` (UTF-8), 127 Java-serialized object.

### `dex.lock`

A text file holding the process id of the instance that last opened the storage (`pid 4242`), used in `StorageInUseException` messages. The lock is an operating-system lock on one byte at offset 4096, past the text, so the text stays readable on Windows while locked.

### `journal.wal`

All numbers big-endian.

**Header** (32 bytes): magic `"DXJ1"` (4), version 1 (4), salt (8), padding. The salt is new after every checkpoint, so records left over from before it are never replayed.

**Record** (one per operation):

| Field | Size | |
|---|---|---|
| Length | 4 | Bytes after this field, checksum included |
| Salt | 8 | Must match the header |
| Sequence | 8 | 0, 1, 2, … since the last checkpoint |
| Count | 4 | Number of changes |
| Changes | | See below |
| CRC32C | 4 | Over salt … changes |

| Change | Layout | Meaning |
|---|---|---|
| `WRITE` | type 1 (1), file (1), position (8), size (4), bytes | Write these bytes at this position |
| `TRUNCATE` | type 2 (1), file (1), length (8) | Cut the file to this length (if longer) |
| `LENGTH` | type 3 (1), file (1), length (8) | Make the file exactly this long |

File 1 is `data.pb`, file 2 is `index.pbi`. A record holds an operation's net effect per file: its lowest truncation, the bytes that changed in each 4 KB block it touched (runs of changed bytes; a block written several times is logged once), and the final length. A typical write's record is about 200 bytes.

### `index.pbi`

**Header** (64 bytes):

| Offset | Size | Field |
|---|---|---|
| 0 | 4 | Magic `"PBI1"` |
| 4 | 4 | Page count |
| 8 | 8 | Id of the data file this index was built for |
| 16 | 8 × 4 | Root page number of each tree (−1 = empty). Tree 0 = key index, tree 1 = free space. |

**Page `n`** (one B+ tree node) starts at `64 + n × 4096`:

| Field | Size | |
|---|---|---|
| Type | 1 | 1 = leaf, 2 = internal |
| Count | 2 | Number of keys |
| Next | 4 | Leaf: page of the next leaf (−1 = last) |
| Leaf entries | | `[keyLength(2)][key][valueLength(2)][value]` … |
| Internal entries | | `[child0(4)]` then `[keyLength(2)][key][child(4)]` … |

Keys are ordered by unsigned byte comparison. Key-index values are `[page(4)][slot(2)]`; free-space keys are `[fullLength(2)][page(4)][slot(2)]` with an empty value.

---

## 14. Performance

Measured on a 2-core Linux machine with a virtual disk, 100-byte values and `Integer` keys unless stated, `Durability.NORMAL`. Your numbers will differ, but the ratios hold.

| Workload | 0.0.4 | 0.0.3 |
|---|---|---|
| New keys, 100-byte values | 20.9 µs (~48,000/s) | 14.3 µs |
| New keys, 1 KB values | 21.8 µs (~46,000/s) | 14.5 µs |
| New keys, 4 KB values | 25.3 µs (~40,000/s) | 14.9 µs |
| Overwrites (100,000 keys) | 27.9 µs (~36,000/s) | 20.5 µs |
| Deletes (100,000 keys) | 43.8 µs (~23,000/s) | 21.8 µs |
| Reads, storage fits in the cache (20,000 keys) | 0.84 µs (~1,190,000/s) | 1.07 µs |
| 1,000,000 writes, new keys | 14.8 s (~68,000/s) | 9.9 s |
| Random reads, 1,000,000 keys | 16.9 µs (~59,000/s) | 15.6 µs |
| Random overwrites, 1,000,000 keys | 57.3 µs (~17,000/s) | 26.1 µs |
| New keys with `Durability.SYNC` | 258 µs (~3,900/s) | — |

| 1,000,000 records | |
|---|---|
| `data.pb` / `index.pbi` | 124 MB / 30 MB |
| Opening the storage | under 2 ms |
| Java heap in use | about 27 MB (up to 16 MB of it changes waiting to be applied) |
| `compact()` with half the records deleted | 4.7 s; files shrink to 62 MB / 14 MB |
| Recovery: replaying a 48 MB journal (47,000 operations) | 0.38 s |

- **Writes** cost more than in 0.0.3 because every change is recorded twice (journal, then the files) and the journal is flushed before the files are written. Random writes over a large storage cost the most, because they touch many different blocks between applies.
- **Reads** are unchanged; they check the changes waiting in memory before reading the files.
- **`sync()`** costs one disk flush (about 0.2 ms here); **`checkpoint()`** a few to tens of milliseconds, depending on how much is waiting.
- **Time complexity:** `read(key)`, `write(key, data)` and `delete(key)` are O(log n) index page visits.

---

## 15. Thread safety, durability and caveats

- **Thread-safe within one instance.** Each instance has a `ReentrantReadWriteLock`: `read`, `read(key)` and `containsKey` share it and run in parallel; `write`, `delete`, `compact`, `sync`, `checkpoint`, `reconstructKeyIndex` and `close` are exclusive. Reads still go one at a time through the page caches, so parallel reads don't scale linearly.
- **One instance per storage**, enforced by a lock: see [section 8](#8-opening-closing-and-locking).
- **Crash-safe; durable against power cuts with `SYNC` or `sync()`.** See [section 9](#9-crash-safety-and-recovery) for exactly what each crash can lose, and its limits.
- **The files on disk lag behind** while a storage is open; see [Files on disk](#files-on-disk).
- **Records are limited to about 4 KB.** Larger data returns `DATA_TOO_LARGE`; split it across several keys.
- **The index file only grows** between compactions: B+ tree nodes aren't merged on delete. `compact()` rebuilds it.
- **Overwrites don't reuse their own slot.** The new value is placed before the old slot is freed, so an overwrite takes a different slot; the old one is then free for the next write.
- **`read()` returns records in storage order**, not key order.

---

## 16. Supporting classes

These are public because the providers use them across packages. You don't normally need them, apart from `Serializer`.

### `Utility.Serialization.Serializer`

| Method | Purpose |
|---|---|
| `static byte[] serialize(Object obj) throws IOException` | Java-serializes an object to bytes. |
| `static <T> T deserialize(byte[] data) throws IOException, ClassNotFoundException` | Turns bytes back into an object. |

### `Utility.Encoding.KeyCodec`

| Method | Purpose |
|---|---|
| `static byte[] encode(Serializable key) throws Exception` | `[tag][value]` encoding described in [section 11](#11-keys-and-key-encoding). |
| `static Object decode(byte[] bytes) throws Exception` | The reverse; throws `IllegalStateException` for corrupt bytes. |

### `Utility.Encoding.KeyTag` (enum)

The type tags: `INTEGER`(1), `LONG`(2), `SHORT`(3), `BYTE`(4), `DOUBLE`(5), `FLOAT`(6), `CHARACTER`(7), `BOOLEAN`(8), `STRING`(9), `SERIALIZED`(127). Methods: `getValue()`, `getType()`, `static forType(Class<?>)`, `static fromValue(byte)`.

### `Utility.FileServices.RandomAccessFileService`

Low-level file access. Implements `AutoCloseable`.

| Method | Purpose |
|---|---|
| `RandomAccessFileService(String path, Extension extension)` | Opens `path + extension` for reading and writing. |
| `long length()` | File size in bytes. |
| `void truncate()`, `void truncate(long length)` | Cuts the file to 0 bytes / to `length` bytes. |
| `void write(long pos, byte[] data)` | Writes `data` at `pos` in one positional write. |
| `void writeInSequence(long pos, byte[][] values)` | Writes the values back to back at `pos`, in one call. |
| `byte[] read(long pos, int length)`, `read(long pos, short length)` | Reads `length` bytes at `pos`; throws `EOFException` past the end. |
| `void force()` | Flushes written data to disk (`fsync`). |
| `void close()` | Closes the file. |

### `Utility.Constants.Extension` (enum)

`DAT(".dat")`, `JSON(".json")`, `PB(".pb")`, `PBI(".pbi")`, `DEX(".dex")`, `WAL(".wal")`. `getValue()` returns the extension.

### `Utility.StorageProviders.PageBasedStorageProvider.Durability` (enum)

`NORMAL`, `SYNC`; see [section 9](#9-crash-safety-and-recovery).

### `Utility.StorageProviders.PageBasedStorageProvider.StorageInUseException`

Extends `IOException`. Thrown by the constructors when the storage is already open. `getFolder()` returns the `.dex` folder that is in use.

### Journal classes

Package `Utility.StorageProviders.PageBasedStorageProvider.Journal`.

| Class | Purpose |
|---|---|
| `Journal` | `journal.wal`: `static open(folder, durability)` (replays what a previous run left), `openFile(tag)` (`DATA_FILE` = 1, `INDEX_FILE` = 2), `commit()`, `rollback()`, `hasPending()`, `sync()`, `checkpoint()`, `setDirect(boolean)`, `forceFiles()`, `getRecoveredOperations()`, `getDurability()`, `size()`, `getCheckpointFailure()`, `close()`. Constants `APPLY_BLOCKS` = 4096, `CHECKPOINT_BYTES` = 64 MB. |
| `JournaledFile` | A `RandomAccessFileService` whose writes and truncations go through the journal: reads see the latest state from the changes waiting in memory, falling back to the file on disk. |

### Index classes

Package `Utility.StorageProviders.PageBasedStorageProvider.Index`.

| Class | Purpose |
|---|---|
| `BPlusTree` | Disk-based B+ tree of `byte[]` keys and values: `get`, `put` (returns the previous value), `remove`, `ceiling` (smallest key ≥ the given one), `isEmpty`, `height`. `MAX_KEY_SIZE` = 1024, `MAX_VALUE_SIZE` = 64. |
| `IndexFile` | The `.pbi` file: header, node pages and their LRU cache. `IndexFile(RandomAccessFileService, cachePages)` (an index in an already open, e.g. journaled, file), `getDataFileId()` / `setDataFileId(long)`, `getPageCount()`, `getCachedPageCount()`, `clear()` (also sets the data file id to 0), `reload()`, `force()`, `close()`. `DEFAULT_CACHE_PAGES` = 1024, `PAGE_SIZE` = 4096, `MAX_TREES` = 8. |
| `Node` | One tree node; package-private apart from the class itself. |

### Page-layer classes

Package `Utility.StorageProviders.PageBasedStorageProvider.Utility`.

| Class | Purpose |
|---|---|
| `DexBundle` | The `.dex` folder: `folderOf(path)`, `createFolder(path)`, `prepare(path, folder)` (moves 0.0.1 files in, removes a leftover compact file; call while holding the lock), `dataPath`, `indexPath`, `compactPath`, `journalPath`, `dataFile`, `compactFile`, `journalFile`, `lockFile`. Constants `DATA_FILE`, `INDEX_FILE`, `COMPACT_FILE`, `LOCK_FILE`, `JOURNAL_FILE`. |
| `DexLock` | The storage's lock: `static acquire(folder)` (throws `StorageInUseException`), `close()`. |
| `LruCache<K, V>` | Synchronized fixed-size LRU cache: `get`, `put`, `remove`, `clear`, `size`, `getCapacity`. |
| `Page` | One page in memory: `getPageNumber()`, `getHeader()`, `getSlots()`. |
| `PageHeader` | A page's header fields. |
| `Slot` | A slot entry: `getOffset()`, `getFullLength()`, `getLength()`, `getKeyLength()`, `getDataLength()`, `getDataOffset()`, `isDeleted()`, `reuse(length, keyLength)`. |
| `RecordID` | A record's location: public final fields `pageNumber` and `slotNumber`. |
| `PBOffSets` | Layout sizes; see [section 12](#12-limits-and-constants). |

---

## 17. Planned for the next version

**Under consideration**

- Roll back an operation when a Java `Error` (not only an `Exception`) is thrown part-way, for programs that catch `Error` and carry on.
- A concurrent page cache, so parallel reads scale.
- Configurable cache sizes and journal limits.
- Faster writes: fewer blocks copied per operation, and cheaper deletes from B+ tree nodes.

