# Dexter: page-based storage for Java

Dexter is a Java library for storing byte data on disk. It provides two classes:

| Class | Use it when |
|---|---|
| `PBStorageProvider` | You want to keep **one** value, such as a settings object or a snapshot, and replace it as a whole. |
| `PBKeyStorageProvider<K>` | You want to store **many** values, each under its own key, and read, overwrite or delete them one at a time, like a persistent `Map<K, byte[]>`. |

Each storage is a `.dex` folder that survives program restarts. Both classes store `byte[]`, so you choose how to turn objects into bytes (the bundled `Serializer` uses Java serialization). Every read, write and delete returns a `PBResult` instead of throwing; a failure caused by an exception carries it, so you can see why.

- **Package:** `Utility.StorageProviders.PageBasedStorageProvider`
- **Jar:** `Dexter-v0.0.3.jar` (early alpha)
- **Version:** 0.0.3
- **Requires:** Java 11 or newer
- **Dependencies:** none (JDK only)

---

## Contents

1. [What's new in 0.0.3](#1-whats-new-in-003)
2. [Installation](#2-installation)
3. [Quick start](#3-quick-start)
4. [How it works](#4-how-it-works)
5. [PBStorageProvider reference](#5-pbstorageprovider-reference)
6. [PBKeyStorageProvider reference](#6-pbkeystorageproviderk-reference)
7. [Results and statuses](#7-results-and-statuses)
8. [Opening, closing and locking](#8-opening-closing-and-locking)
9. [Space, dead bytes and compaction](#9-space-dead-bytes-and-compaction)
10. [Keys and key encoding](#10-keys-and-key-encoding)
11. [Limits and constants](#11-limits-and-constants)
12. [File format](#12-file-format)
13. [Performance](#13-performance)
14. [Thread safety, durability and caveats](#14-thread-safety-durability-and-caveats)
15. [Supporting classes](#15-supporting-classes)
16. [Planned for the next version](#16-planned-for-the-next-version)

---

## 1. What's new in 0.0.3

**Breaking changes**

- **One instance per storage.** Opening a storage now locks its `.dex` folder. A second instance, in the same program or another one, gets a `StorageInUseException` until the first is closed. Code that opened the same storage twice without closing it worked by accident before (and could corrupt data); it now fails at the second `new`. See [section 8](#8-opening-closing-and-locking).
- **New enum value:** `PBFail.STORAGE_CLOSED`. A `switch` over `PBFail` needs the new case.
- **`DexBundle.open(path)` is replaced** by `createFolder(path)` and `prepare(path, folder)`. This only matters if you used the supporting class directly.

**New features**

- **`close()` and try-with-resources.** Both providers implement `Closeable`. `close()` waits for running operations, closes the files and releases the lock; afterwards every operation returns `STORAGE_CLOSED`. `isClosed()` tells you the state.
- **Lock across programs.** The lock is held through the operating system on `dex.lock`, so two programs can't open the same storage at once. It is released automatically when a program exits or crashes, and `StorageInUseException` names the process holding it.
- **Failures say why.** `PBResult.getError()` returns the exception behind a `READ_ERROR`, `WRITE_ERROR`, `DELETE_ERROR`, `COMPACT_ERROR` or `KEY_ENCODE_ERROR`, and `toString()` includes it. A `READ_ERROR` from a damaged index explains which page and slot don't match.

**Fixes**

- A failed open (wrong key type, corrupt file) no longer leaves anything open or locked.
- If `compact()` failed and then couldn't delete its temp file, the clean-up error hid the real one. The real cause is now reported, with the clean-up error attached as suppressed.
- Moving 0.0.1 files into the folder and deleting a leftover compaction file now happen only while holding the lock, so a second program can't delete the temp file of a compaction that is still running.

### Upgrading from 0.0.2

The file format is unchanged: open your storages as before. `dex.lock` is created in the folder on first open.

In your code, make sure each storage is opened once and closed when you're done:

```java
// 0.0.2: worked, but two instances on one storage could overwrite each other's data
PBKeyStorageProvider<Integer> a = new PBKeyStorageProvider<>("data/users", Integer.class);
a.write(1, data);
PBKeyStorageProvider<Integer> b = new PBKeyStorageProvider<>("data/users", Integer.class);   // 0.0.3: StorageInUseException

// 0.0.3: open once and share the instance (it is thread-safe), or close before reopening
try (PBKeyStorageProvider<Integer> users = new PBKeyStorageProvider<>("data/users", Integer.class)) {
    users.write(1, data);
}
```

Don't run 0.0.2 and 0.0.3 on the same storage at the same time: 0.0.2 ignores the lock.

### Upgrading from 0.0.1

0.0.3 opens 0.0.1 storages directly. On first open it creates `path.dex/`, moves `path.pb` into it as `data.pb`, counts the file's dead bytes and builds `index.pbi`. All records, keys and deleted slots carry over. The upgrade is one-way: 0.0.1 can't open a `.dex` folder.

Status checks change from the shared status constants to `PBResult`:

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

The full list of 0.0.2 changes (the `.dex` folder, the disk-based index, thread safety, compaction) is in the 0.0.2 README.

---

## 2. Installation

Add `Dexter-v0.0.3.jar` to your classpath. `Dexter-v0.0.3-sources.jar` is optional; it lets your IDE show the library's source.

**Command line**

```bash
javac -cp Dexter-v0.0.3.jar -d out MyApp.java
java  -cp Dexter-v0.0.3.jar:out MyApp          # on Windows use ; instead of :
```

**VS Code** (`.vscode/settings.json`)

```json
{
    "java.project.referencedLibraries": [ "lib/Dexter-v0.0.3.jar" ]
}
```

**Gradle**

```groovy
dependencies {
    implementation files('lib/Dexter-v0.0.3.jar')
}
```

**Maven:** install the jar into your local repository once, then depend on it.

```bash
mvn install:install-file -Dfile=Dexter-v0.0.3.jar -DgroupId=dexter \
    -DartifactId=dexter -Dversion=0.0.3 -Dpackaging=jar
```

```xml
<dependency>
    <groupId>dexter</groupId>
    <artifactId>dexter</artifactId>
    <version>0.0.3</version>
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

### Opening a storage

The constructor first creates the folder if needed and locks `dex.lock` (or throws `StorageInUseException`). Holding the lock, it moves 0.0.1 files in and deletes a leftover `data-compact.pb`. Then only the file headers are read. Data pages and index pages are loaded when first used and kept in LRU caches (1,024 pages each, about 4 MB each). Writes go straight to disk, so an evicted page never needs saving.

---

## 5. `PBStorageProvider` reference

```java
package Utility.StorageProviders.PageBasedStorageProvider;
public class PBStorageProvider implements Closeable
```

Single-value storage: holds one record, and writing replaces everything. It is also the base class of `PBKeyStorageProvider` and owns all page, slot and file handling.

### Constructor

#### `PBStorageProvider(String path) throws Exception`

Opens the storage folder `path` (+ `.dex`), creating it if needed, and locks it. Then moves 0.0.1 files (`path.pb`) into the folder if it has no `data.pb` yet, and deletes a `data-compact.pb` left behind by an interrupted `compact()`.

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

Replaces the whole contents with this one record. All existing pages are dropped and the file is truncated. The key type in the file header (if any) is kept, and the file gets a new file id.

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

Rewrites the data file with only the live records, packed tightly and in their current order. See [section 9](#9-space-dead-bytes-and-compaction).

| Status | When |
|---|---|
| `PBSuccess.COMPACT` | Done. `getDeadBytes()` is now 0. |
| `PBFail.COMPACT_ERROR` | An error occurred; `getError()` has the cause. See [When compaction fails](#when-compaction-fails). |

#### `long getDeadBytes()`

Bytes in the file's pages that hold no live data: deleted records, plus the unused part of slots reused for a smaller record. This is what `compact()` would give back.

#### `double getFragmentation()`

Dead bytes as a share of all page bytes, from `0.0` to `1.0`. `0.0` for an empty storage. After `close()`, this and `getDeadBytes()` keep their last values.

#### `long getFileSize() throws Exception`

Current size of `data.pb` in bytes. Throws `IllegalStateException` after `close()`.

#### `void close() throws IOException`

Closes the files and releases the lock, so the storage can be opened again by this program or another one. Waits for operations running on other threads to finish. Safe to call more than once. If closing a file fails, the lock is still released and the first error is thrown. See [section 8](#8-opening-closing-and-locking).

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
| `void onClose() throws IOException` | `close()` is running: close the subclass's own files. Runs before `data.pb` is closed and the lock released. May run on a partly built instance when a constructor failed, so check fields for `null`. |

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

Opens or creates the storage folder, binds the key type and opens `index.pbi`, rebuilding it if it doesn't belong to `data.pb`.

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

#### `PBResult write(byte[] data)` (override)

The same as `PBStorageProvider.write`: replaces **everything** with one keyless record and clears the index, so every key now returns `KEY_NOT_FOUND`. The key type is kept, and keyed writes work afterwards.

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

Deletes the value stored under `key`. Its slot is marked deleted and becomes reusable by later writes; if it was the last live record on the last page, that page (and any empty pages before it) is cut off the file.

| Status | When |
|---|---|
| `PBSuccess.DELETE` | Deleted. |
| `PBFail.NULL_KEY` | `key` is `null`. |
| `PBFail.KEY_TYPE_MISMATCH` | `key` has the wrong type. |
| `PBFail.KEY_NOT_FOUND` | No value is stored under `key`, including a key already deleted. |
| `PBFail.DELETE_ERROR` | An I/O error occurred. |

#### `PBResult delete()` (override)

Erases every record and clears the index. The file is truncated to its header and keeps its key type. Returns `PBSuccess.DELETE`, or `PBFail.DELETE_ERROR` on an I/O error.

#### `PBResult compact()` (inherited)

As for `PBStorageProvider`, and then the index is rebuilt for the new record positions, which also shrinks `index.pbi`.

#### `boolean containsKey(K key)`

`true` if a value is stored under `key`. Returns `false` for `null`, a key of the wrong type, on a read error, or after `close()`, instead of failing.

#### `Class<?> getKeyType()`

The key type this storage accepts. For `int.class` it returns `Integer.class`.

#### `void reconstructKeyIndex() throws Exception`

Rebuilds `index.pbi` from the data pages: every live keyed record goes into the key index, every deleted slot into the free-space tree. If two live records have the same key (possible after a crash in the middle of an overwrite), the later one is kept and the earlier one is marked deleted.

It runs automatically on open when the index is missing or doesn't belong to the data file. Call it yourself after a crash, or if `read(key)` returns `READ_ERROR` for a damaged index. Throws `IllegalStateException` if a stored key isn't of the storage's key type, or after `close()`.

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
| `COMPACT_ERROR` | Error while compacting; see [section 9](#when-compaction-fails). |
| `STORAGE_CLOSED` | The instance was closed with `close()`; open a new one to use the storage again. |

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

- Waits for operations running on other threads, closes `data.pb` (and `index.pbi`), then releases the lock.
- Safe to call more than once; `isClosed()` reports the state.
- Afterwards, operations return `PBFail.STORAGE_CLOSED`, `containsKey` returns `false`, and `getFileSize()` / `reconstructKeyIndex()` throw `IllegalStateException`. `getDeadBytes()`, `getFragmentation()` and `getFolder()` still answer.
- Nothing needs flushing: every write is already in the file when it returns. `close()` only frees the storage for the next opener.

### When the program ends

`close()` isn't called automatically, but the lock is released anyway: it is held through the operating system, which drops it when the process ends, however it ends (normal exit, `System.exit`, an uncaught exception, Ctrl+C, a kill or a crash). There is never a stale lock to remove. `dex.lock` itself stays in the folder; an unlocked `dex.lock` blocks nobody.

### Notes

- The lock is on `dex.lock`, not `data.pb`, because `compact()` replaces `data.pb` with a new file.
- Moving 0.0.1 files in and deleting a leftover compaction file happen only while holding the lock.
- Locks on network file systems (NFS, SMB) depend on the server and may not be enforced. The guarantee is for local disks.

---

## 9. Space, dead bytes and compaction

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

1. Copies every live record, in page and slot order, into `data-compact.pb`, packed with no gaps (each slot's `fullLength` equals its record length), then flushes it to disk.
2. Renames it over `data.pb` in one step, so the folder holds either the old file or the new one, never a mix. The new file has a new file id.
3. Keyed storage: rebuilds `index.pbi` from scratch, which also packs the index.

| | |
|---|---|
| Time | Proportional to the live data: about 4 s for 500,000 live records (see [section 13](#13-performance)). |
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
| Rebuilding the index (keyed) | Disk full or I/O error on `index.pbi`; a stored custom key that can no longer be deserialized | Data is compacted and complete, but this instance's index is incomplete: `read(key)` may report `KEY_NOT_FOUND` for keys that exist. Call `reconstructKeyIndex()` or reopen the storage (the index is rebuilt on open). |
| Reopening `data.pb` after the rename | The folder was removed or made unreadable | This instance can't use its file any more; later calls return `READ_ERROR` / `WRITE_ERROR`. Reopen the storage. |

If deleting the temp file also fails, the original cause is still the one reported; the clean-up error is attached to it as suppressed (`getError().getSuppressed()`).

---

## 10. Keys and key encoding

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

## 11. Limits and constants

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

Layout sizes come from the `PBOffSets` enum (package `Utility.StorageProviders.PageBasedStorageProvider.Utility`):

| Constant | Value | Meaning |
|---|---|---|
| `FILE_HEADER` | 256 | Bytes before page 0. |
| `PAGE` | 4090 | Bytes per data page. |
| `HEADER` | 34 | Bytes per page header. |
| `SLOT` | 17 | Bytes per slot entry. |

---

## 12. File format

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

## 13. Performance

Measured on a 2-core Linux machine with a virtual disk, 100-byte values and `Integer` keys unless stated. Your numbers will differ, but the ratios hold. The lock and the closed checks add no measurable cost.

| Workload | Time per operation | Operations/second |
|---|---|---|
| New keys, 100-byte values | 17.4 µs | ~58,000 |
| New keys, 1 KB values | 12.9 µs | ~78,000 |
| New keys, 4 KB values | 17.2 µs | ~58,000 |
| Overwrites (100,000 keys) | 23.0 µs | ~43,000 |
| Deletes (100,000 keys) | 22.2 µs | ~45,000 |
| Reads, storage fits in the cache (20,000 keys) | 1.1 µs | ~940,000 |
| 1,000,000 writes, new keys | 9.3 s in total | ~108,000 |
| Random reads, 1,000,000 keys | 15.5 µs | ~64,000 |
| Random overwrites, 1,000,000 keys | 25.8 µs | ~39,000 |

| 1,000,000 records | |
|---|---|
| `data.pb` / `index.pbi` | 124 MB / 30 MB |
| Opening the storage | under 1 ms |
| Java heap in use | about 12 MB |
| `compact()` with half the records deleted | 4.0 s; files shrink to 62 MB / 14 MB |

- **Reads** that hit the cache cost an index walk in memory plus one positional read of the record. Larger storages read index and data pages from disk (or the OS cache) more often.
- **Writes** are a handful of positional writes (record, slot entry, page header) plus index page writes; an overwrite also marks the old slot deleted. Nothing is forced to disk with `fsync`.
- **Time complexity:** `read(key)`, `write(key, data)` and `delete(key)` are O(log n) index page visits.

---

## 14. Thread safety, durability and caveats

- **Thread-safe within one instance.** Each instance has a `ReentrantReadWriteLock`: `read`, `read(key)` and `containsKey` share it and run in parallel; `write`, `delete`, `compact`, `reconstructKeyIndex` and `close` are exclusive. Reads still go one at a time through the page caches, so parallel reads don't scale linearly.
- **One instance per storage**, enforced by a lock: see [section 8](#8-opening-closing-and-locking).
- **Not crash-durable.** Writes go to the operating system's file cache without `fsync`; a power loss or OS crash can lose recent writes even though they returned `WRITE`. A program that is killed loses nothing that was already written, but a write in progress at that moment can leave the index out of step for that record; call `reconstructKeyIndex()` after such a crash. (`compact()` does flush its new file before swapping it in.)
- **A `delete(key)` that fails with `DELETE_ERROR`** may already have removed the key from the index while its record stays live: `read(key)` then says `KEY_NOT_FOUND`, but `read()` still returns the record, and `reconstructKeyIndex()` brings the key back. To be fixed in the next version.
- **Records are limited to about 4 KB.** Larger data returns `DATA_TOO_LARGE`; split it across several keys.
- **The index file only grows** between compactions: B+ tree nodes aren't merged on delete. `compact()` rebuilds it.
- **Overwrites don't reuse their own slot.** The new value is placed before the old slot is freed, so an overwrite takes a different slot; the old one is then free for the next write.
- **`read()` returns records in storage order**, not key order.

---

## 15. Supporting classes

These are public because the providers use them across packages. You don't normally need them, apart from `Serializer`.

### `Utility.Serialization.Serializer`

| Method | Purpose |
|---|---|
| `static byte[] serialize(Object obj) throws IOException` | Java-serializes an object to bytes. |
| `static <T> T deserialize(byte[] data) throws IOException, ClassNotFoundException` | Turns bytes back into an object. |

### `Utility.Encoding.KeyCodec`

| Method | Purpose |
|---|---|
| `static byte[] encode(Serializable key) throws Exception` | `[tag][value]` encoding described in [section 10](#10-keys-and-key-encoding). |
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

`DAT(".dat")`, `JSON(".json")`, `PB(".pb")`, `PBI(".pbi")`, `DEX(".dex")`. `getValue()` returns the extension.

### `Utility.StorageProviders.PageBasedStorageProvider.StorageInUseException`

Extends `IOException`. Thrown by the constructors when the storage is already open. `getFolder()` returns the `.dex` folder that is in use.

### Index classes

Package `Utility.StorageProviders.PageBasedStorageProvider.Index`.

| Class | Purpose |
|---|---|
| `BPlusTree` | Disk-based B+ tree of `byte[]` keys and values: `get`, `put` (returns the previous value), `remove`, `ceiling` (smallest key ≥ the given one), `isEmpty`, `height`. `MAX_KEY_SIZE` = 1024, `MAX_VALUE_SIZE` = 64. |
| `IndexFile` | The `.pbi` file: header, node pages and their LRU cache. `getDataFileId()` / `setDataFileId(long)`, `getPageCount()`, `getCachedPageCount()`, `clear()`, `close()`. `DEFAULT_CACHE_PAGES` = 1024, `PAGE_SIZE` = 4096, `MAX_TREES` = 8. |
| `Node` | One tree node; package-private apart from the class itself. |

### Page-layer classes

Package `Utility.StorageProviders.PageBasedStorageProvider.Utility`.

| Class | Purpose |
|---|---|
| `DexBundle` | The `.dex` folder: `folderOf(path)`, `createFolder(path)`, `prepare(path, folder)` (moves 0.0.1 files in, removes a leftover compact file; call while holding the lock), `dataPath`, `indexPath`, `compactPath`, `dataFile`, `compactFile`, `lockFile`. Constants `DATA_FILE`, `INDEX_FILE`, `COMPACT_FILE`, `LOCK_FILE`. |
| `DexLock` | The storage's lock: `static acquire(folder)` (throws `StorageInUseException`), `close()`. |
| `LruCache<K, V>` | Synchronized fixed-size LRU cache: `get`, `put`, `remove`, `clear`, `size`, `getCapacity`. |
| `Page` | One page in memory: `getPageNumber()`, `getHeader()`, `getSlots()`. |
| `PageHeader` | A page's header fields. |
| `Slot` | A slot entry: `getOffset()`, `getFullLength()`, `getLength()`, `getKeyLength()`, `getDataLength()`, `getDataOffset()`, `isDeleted()`, `reuse(length, keyLength)`. |
| `RecordID` | A record's location: public final fields `pageNumber` and `slotNumber`. |
| `PBOffSets` | Layout sizes; see [section 11](#11-limits-and-constants). |

---

## 16. Planned for the next version

- **Recover the index in place.** If the index rebuild fails after `compact()` has put the packed file in place, retry the rebuild before returning, so the instance stays correct whenever the rebuild can succeed.
- **Keep `delete(key)` consistent on failure.** Mark the slot deleted before removing the key from the index (or put the key back), so a `DELETE_ERROR` never leaves a live record without its key.

**Under consideration**

- A concurrent page cache, so parallel reads scale.
- Configurable cache sizes.
- Fewer bytes written per operation (only the changed parts of a page).

