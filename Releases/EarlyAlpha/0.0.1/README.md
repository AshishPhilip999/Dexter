# Dexter: page-based storage for Java

Dexter is a small Java library for storing byte data in a single file on disk. It provides two classes:

| Class | Use it when |
|---|---|
| `PBStorageProvider` | You want to keep **one** value in a file, such as a settings object or a snapshot, and replace it as a whole. |
| `PBKeyStorageProvider<K>` | You want to store **many** values, each under its own key, and read, overwrite or delete them one at a time, like a small persistent `Map<K, byte[]>`. |

Both classes keep their data in a `.pb` file that survives program restarts. Both store `byte[]`, so you choose how to turn objects into bytes (the bundled `Serializer` uses Java serialization). Every read, write and delete returns a status object instead of throwing.

- **Package:** `Utility.StorageProviders.PageBasedStorageProvider`
- **Jar:** `Dexter-v0.0.1.jar` (early alpha)
- **Version:** 0.0.1
- **Requires:** Java 11 or newer
- **Dependencies:** none (JDK only)

---

## Contents

1. [Installation](#1-installation)
2. [Quick start](#2-quick-start)
3. [How it works](#3-how-it-works)
4. [PBStorageProvider reference](#4-pbstorageprovider-reference)
5. [PBKeyStorageProvider reference](#5-pbkeystorageproviderk-reference)
6. [Status types](#6-status-types)
7. [Keys and key encoding](#7-keys-and-key-encoding)
8. [Limits and constants](#8-limits-and-constants)
9. [File format](#9-file-format)
10. [Performance](#10-performance)
11. [Thread safety, durability and caveats](#11-thread-safety-durability-and-caveats)
12. [Supporting classes](#12-supporting-classes)

---

## 1. Installation

Add `Dexter-v0.0.1.jar` to your classpath. `Dexter-v0.0.1-sources.jar` is optional; it lets your IDE show the library's source.

**Command line**

```bash
javac -cp Dexter-v0.0.1.jar -d out MyApp.java
java  -cp Dexter-v0.0.1.jar:out MyApp          # on Windows use ; instead of :
```

**VS Code** (`.vscode/settings.json`)

```json
{
    "java.project.referencedLibraries": [ "lib/Dexter-v0.0.1.jar" ]
}
```

**Gradle**

```groovy
dependencies {
    implementation files('lib/Dexter-v0.0.1.jar')
}
```

**Maven:** install the jar into your local repository once, then depend on it.

```bash
mvn install:install-file -Dfile=Dexter-v0.0.1.jar -DgroupId=dexter \
    -DartifactId=dexter -Dversion=0.0.1 -Dpackaging=jar
```

```xml
<dependency>
    <groupId>dexter</groupId>
    <artifactId>dexter</artifactId>
    <version>0.0.1</version>
</dependency>
```

---

## 2. Quick start

### One value with `PBStorageProvider`

```java
import StorageStatus.PB.OperationStatus;
import StorageStatus.PB.PBStatus;
import StorageStatus.PB.PBSuccess;
import Utility.Serialization.Serializer;
import Utility.StorageProviders.PageBasedStorageProvider.PBStorageProvider;

PBStorageProvider settings = new PBStorageProvider("data/settings");   // uses data/settings.pb

settings.write(Serializer.serialize("dark-mode"));

PBStatus status = settings.read();
if (status.getStatus() == OperationStatus.SUCCESS) {
    byte[] bytes = ((PBSuccess) status).getData();
    String value = Serializer.deserialize(bytes);                       // "dark-mode"
}
```

### Many values by key with `PBKeyStorageProvider`

```java
import StorageStatus.PB.PBFail;
import StorageStatus.PB.PBStatus;
import StorageStatus.PB.PBSuccess;
import Utility.Serialization.Serializer;
import Utility.StorageProviders.PageBasedStorageProvider.PBKeyStorageProvider;

PBKeyStorageProvider<Integer> users = new PBKeyStorageProvider<>("data/users", Integer.class);

users.write(1, Serializer.serialize("Ashish"));
users.write(2, Serializer.serialize("Priya"));
users.write(1, Serializer.serialize("Ashish R"));      // overwrites key 1

PBStatus one = users.read(1);
if (one instanceof PBSuccess) {
    String name = Serializer.deserialize(((PBSuccess) one).getData());   // "Ashish R"
}

if (users.read(99) == PBFail.KEY_NOT_FOUND) { /* no such key */ }

PBStatus all = users.read();                            // every record
byte[][] records = ((PBSuccess) all).getDataArray();    // 2 entries

users.delete(2);                                        // remove one key
users.delete();                                         // remove everything
```

When the program restarts, `new PBKeyStorageProvider<>("data/users", Integer.class)` loads the file and every key is readable again.

> **Copy data out of a status straight away.** `PBSuccess.READ` and `PBSuccess.WRITE` are shared enum constants, so the next call on any storage replaces their data. See [section 11](#11-thread-safety-durability-and-caveats).

---

## 3. How it works

### Files

The `path` passed to a constructor is the file name **without extension**; the library adds `.pb`. `new PBStorageProvider("data/settings")` uses `data/settings.pb`. If the file doesn't exist it is created; its parent folder must exist.

A file is a 256-byte **file header** followed by fixed-size **pages** of 4,090 bytes. The file header records the key type for keyed storage.

### Pages and slots

Each page is a *slotted page*:

```
| page header (34 B) | slot 0 | slot 1 | ... →      free space      ← ... | record 1 | record 0 |
                       slot directory grows forward          records grow backward from the page end
```

- **Slot:** a 17-byte entry in the page's slot directory that says where a record is, how long it is, how many of its bytes are the key, and whether it's deleted.
- **Record:** the bytes themselves, `[key bytes][data bytes]`. Keyless records have no key bytes.
- **Page header:** tracks the slot count and the free space between the slot directory (`freeSpaceStart`) and the records (`freeSpaceEnd`).

A record is located by its **RecordID**, `(pageNumber, slotNumber)`.

### Writing a keyed record

`PBKeyStorageProvider.write(key, data)` picks a place for the record in this order:

1. **Reuse a deleted slot (best fit).** Deleted slots are kept in a tree ordered by size. The smallest deleted slot that is big enough is reused in place.
2. **Append to the last page**, if it has room for one more slot entry plus the record.
3. **Start a new page** otherwise.

Each slot remembers its original size (`fullLength`) separately from the size of the record currently in it (`length`). A slot that once held 100 bytes can take a 40-byte record and later a 100-byte record again.

Overwriting a key writes the new record first, then marks the old slot deleted, which makes it reusable by later writes.

### Opening a file

The constructor reads every page header and slot entry back into memory. `PBKeyStorageProvider` then reads the key stored in front of each live record and rebuilds its key-to-RecordID map. Deleted slots are collected into the free-slot tree, so reuse also works after a restart.

---

## 4. `PBStorageProvider` reference

```java
package Utility.StorageProviders.PageBasedStorageProvider;
public class PBStorageProvider
```

Single-value storage: holds one record. Writing replaces everything in the file. It is also the base class of `PBKeyStorageProvider` and owns all page, slot and file handling.

### Constructor

#### `PBStorageProvider(String path) throws Exception`

Opens `path + ".pb"`, creating it if needed, and loads its pages and slots.

| | |
|---|---|
| `path` | File path without extension. |
| Throws | An `IOException` subtype if the file can't be opened or created (for example, the folder doesn't exist). `IllegalStateException` if the file is corrupt or uses an older, incompatible format. |

It can open a file written by `PBKeyStorageProvider`; `read()` then returns its first live record.

### Public methods

#### `PBStatus write(byte[] data)`

Replaces the whole contents of the file with this one record. All existing pages are dropped and the file is truncated. The key type in the file header (if any) is kept.

| Result | When |
|---|---|
| `PBSuccess.WRITE` | Written. `getData()` returns `data`. |
| `PBFail.NULL_WRITE` | `data` is `null` or empty. |
| `PBFail.DATA_TOO_LARGE` | `data` is longer than 4,039 bytes. |
| `PBFail.WRITE_ERROR` | An I/O error occurred. |

A failed write leaves the existing contents untouched.

#### `PBStatus read()`

Returns the stored record: the first live record in page and slot order.

| Result | When |
|---|---|
| `PBSuccess.READ` | `getData()` returns the record's bytes. `getDataArray()` is `null`. |
| `PBFail.NULL_READ` | Nothing is stored. |
| `PBFail.READ_ERROR` | An I/O error occurred. |

#### `PBStatus delete()`

Erases every record. The file is truncated back to its 256-byte header; the stored key type is kept.

| Result | When |
|---|---|
| `PBSuccess.DELETE` | Erased, including when the storage was already empty. |
| `PBFail.DELETE_ERROR` | An I/O error occurred. |

### Protected methods (for subclasses)

These exist so `PBKeyStorageProvider`, or your own subclass, can build on the page and slot layer.

| Method | What it does |
|---|---|
| `byte[][] readAllRecords()` | Data of every live record in page and slot order, without key bytes. Deleted slots are skipped. |
| `String getStoredKeyType()` | Key type class name stored in the file header, or `null`. |
| `void setStoredKeyType(String keyTypeName)` | Stores a key type class name in the file header (at most 254 UTF-8 bytes). |
| `List<Page> getPages()` | Read-only view of the loaded pages. |
| `PBFail validateRecord(int keyLength, byte[] data)` | `NULL_WRITE` or `DATA_TOO_LARGE` if the record is invalid, otherwise `null`. |
| `RecordID placeRecord(byte[] keyBytes, byte[] data)` | Writes the record using best-fit reuse, then the last page, then a new page, and returns where it went. Call `validateRecord` first. |
| `void markSlotDeleted(RecordID recordID)` | Marks a slot deleted on disk and adds it to the free-slot tree. Does nothing if the slot is already deleted or doesn't exist. |
| `Slot getSlot(RecordID recordID)` | The slot at that location, or `null`. |
| `byte[] readSlot(Slot slot)` | The slot's data bytes (after the key). |
| `byte[] readSlotKey(Slot slot)` | The slot's key bytes. |

### Fields (internal state)

All fields are private; they are listed here to explain what an instance holds.

| Field | Type | Purpose |
|---|---|---|
| `NO_KEY` | `static final byte[]` | Empty key used for keyless records. |
| `pages` | `List<Page>` | Every page in the file, loaded at startup and kept in memory. Index = page number. |
| `freeSlots` | `TreeMap<Short, Deque<RecordID>>` | Deleted slots available for reuse, keyed by `fullLength`. Used for best-fit lookup. |
| `storedKeyType` | `String` | Key type class name from the file header, or `null` for keyless files. |
| `randomAccessFileService` | `RandomAccessFileService` | The open file and all low-level reads and writes. |

---

## 5. `PBKeyStorageProvider<K>` reference

```java
package Utility.StorageProviders.PageBasedStorageProvider;
public class PBKeyStorageProvider<K extends Serializable> extends PBStorageProvider
```

Keyed storage: many records, each under a key of type `K`. Keys are checked twice:

- **When you compile:** through the generic `K`, so `write("x", data)` on a `PBKeyStorageProvider<Integer>` is a compile error.
- **When it runs:** through the `Class<K>` passed to the constructor, which also catches raw-typed or unchecked callers.

The key type is saved in the file header, so a file can't be reopened with a different key type.

### Constructor

#### `PBKeyStorageProvider(String path, Class<K> keyType) throws Exception`

Opens or creates `path + ".pb"`, loads its pages, binds the key type and rebuilds the key map.

| | |
|---|---|
| `path` | File path without extension. |
| `keyType` | The key class, for example `Integer.class` or `String.class`. Primitive classes are accepted and mapped to their wrapper (`int.class` → `Integer.class`). |
| New or keyless file | `keyType` is stored in the file header. A keyless file's existing record stays and appears in `read()`. |
| File with a key type | Must match `keyType` exactly. |
| Throws | `IllegalArgumentException` if `keyType` is `null`, or if its class name is longer than 254 bytes. `IllegalStateException` if the file stores a different key type, or is corrupt. An `IOException` subtype if the file can't be opened. `ClassNotFoundException` if stored custom keys can't be deserialized. |

```java
PBKeyStorageProvider<String> sp = new PBKeyStorageProvider<>("data/cache", String.class);
```

There is one constructor. A mismatch is reported by an exception, since a Java constructor can't return `null`:

```java
try {
    PBKeyStorageProvider<Integer> sp = new PBKeyStorageProvider<>(path, Integer.class);
} catch (IllegalStateException e) {
    // the file holds a different key type
}
```

### Public methods

#### `PBStatus write(K key, byte[] data)`

Stores `data` under `key`. If the key exists, its value is replaced (upsert).

Checks run in this order; the first failure is returned:

| Result | When |
|---|---|
| `PBFail.NULL_KEY` | `key` is `null`. |
| `PBFail.KEY_TYPE_MISMATCH` | `key` isn't an instance of the storage's key type (only possible from raw or unchecked code). |
| `PBFail.NULL_WRITE` | `data` is `null` or empty. |
| `PBFail.KEY_ENCODE_ERROR` | The key couldn't be encoded (for example, a custom key with a non-serializable field). |
| `PBFail.DATA_TOO_LARGE` | Encoded key + data is longer than 4,039 bytes. For an `Integer` key that's 4,034 data bytes. |
| `PBFail.WRITE_ERROR` | An I/O error occurred. |
| `PBSuccess.WRITE` | Written. `getData()` returns `data`. |

The record goes into the best-fitting deleted slot, else the last page, else a new page (see [section 3](#3-how-it-works)).

#### `PBStatus write(byte[] data)` (override)

The same as `PBStorageProvider.write`: replaces **everything** with one keyless record. It also clears the key map, so every key now returns `KEY_NOT_FOUND`. The key type is kept, and keyed writes work afterwards.

#### `PBStatus read(K key)`

Reads the value stored under `key`.

| Result | When |
|---|---|
| `PBSuccess.READ` | `getData()` returns the value. `getDataArray()` is `null`. |
| `PBFail.NULL_KEY` | `key` is `null`. |
| `PBFail.KEY_TYPE_MISMATCH` | `key` has the wrong type (raw or unchecked callers). |
| `PBFail.KEY_NOT_FOUND` | No value is stored under `key`. |
| `PBFail.READ_ERROR` | An I/O error occurred. |

#### `PBStatus read()` (override)

Reads **every** live record.

| Result | When |
|---|---|
| `PBSuccess.READ` | `getDataArray()` returns one `byte[]` per record, data only (no key bytes). `getData()` returns the first of them. |
| `PBFail.NULL_READ` | Nothing is stored. |
| `PBFail.READ_ERROR` | An I/O error occurred. |

Records are in page and slot order. That is write order until deleted slots start being reused, after which newer records can appear in older positions. Each overwritten key appears once, with its latest value; deleted keys don't appear.

```java
byte[][] records = ((PBSuccess) sp.read()).getDataArray();
for (byte[] r : records) {
    MyObject o = Serializer.deserialize(r);
}
```

#### `PBStatus delete(K key)`

Deletes the value stored under `key`. Its slot is marked deleted and becomes reusable by later writes.

| Result | When |
|---|---|
| `PBSuccess.DELETE` | Deleted. |
| `PBFail.NULL_KEY` | `key` is `null`. |
| `PBFail.KEY_TYPE_MISMATCH` | `key` has the wrong type. |
| `PBFail.KEY_NOT_FOUND` | No value is stored under `key`, including a key already deleted. |
| `PBFail.DELETE_ERROR` | An I/O error occurred. |

#### `PBStatus delete()` (override)

Erases every record and clears the key map. The file is truncated to its header and keeps its key type. Returns `PBSuccess.DELETE`, or `PBFail.DELETE_ERROR` on an I/O error.

#### `boolean containsKey(K key)`

`true` if a value is stored under `key`. Returns `false` for `null` or a key of the wrong type, instead of failing.

#### `Class<?> getKeyType()`

The key type this storage accepts. For `int.class` it returns `Integer.class`.

#### `void reconstructKeyIndex() throws Exception`

Rebuilds the key map by reading the key stored in front of every live record. The constructor calls it; you normally don't need to. It works from the pages loaded when the storage was opened, so it doesn't pick up changes made to the file by another program. Reopen the storage for that.

Throws `IllegalStateException` if a stored key isn't of the storage's key type.

### Fields (internal state)

| Field | Type | Purpose |
|---|---|---|
| `keyIndex` | `Map<K, RecordID>` | Key → location of its live record. Held in memory; rebuilt from the file on open. |
| `keyType` | `Class<?>` | The accepted key type (wrapper class for primitives). Checked against every key and against the file header. |

Everything listed under `PBStorageProvider` is inherited.

---

## 6. Status types

Package `StorageStatus.PB`. Every read, write and delete returns a `PBStatus`.

### `PBStatus` (interface)

```java
OperationStatus getStatus();     // SUCCESS or FAIL
```

### `OperationStatus` (enum)

`SUCCESS`, `FAIL`.

### `PBSuccess` (enum, `getStatus()` = `SUCCESS`)

| Constant | Returned by | Data |
|---|---|---|
| `READ` | `read()`, `read(key)` | `getData()`: the record. For the keyed `read()`, `getDataArray()` holds all records and `getData()` the first. |
| `WRITE` | `write(data)`, `write(key, data)` | `getData()`: the bytes written. |
| `DELETE` | `delete()`, `delete(key)` | None. |

| Method | Purpose |
|---|---|
| `byte[] getData()` | Single record or written bytes. |
| `byte[][] getDataArray()` | All records (keyed `read()` only), otherwise `null`. |
| `setData(byte[])`, `setDataArray(byte[][])` | Setters used by the library. |
| `static PBSuccess read(byte[])`, `readAll(byte[][])`, `write(byte[])` | Helpers the library uses to set the data on `READ` or `WRITE` and return it. |

### `PBFail` (enum, `getStatus()` = `FAIL`)

| Constant | Meaning |
|---|---|
| `NULL_READ` | `read()` with nothing stored. |
| `NULL_WRITE` | Write with `null` or empty data. |
| `NULL_KEY` | Keyed operation with a `null` key. |
| `KEY_TYPE_MISMATCH` | Key isn't of the storage's key type. |
| `KEY_NOT_FOUND` | `read(key)` or `delete(key)` for a key that isn't stored. |
| `KEY_ENCODE_ERROR` | The key couldn't be encoded. |
| `DATA_TOO_LARGE` | Key + data don't fit in one page (more than 4,039 bytes). |
| `READ_ERROR` | I/O error while reading. |
| `WRITE_ERROR` | I/O error while writing. |
| `DELETE_ERROR` | I/O error while deleting. |

### Checking results

```java
PBStatus s = sp.read(42);

if (s.getStatus() == OperationStatus.SUCCESS) { ... }   // any success
if (s == PBFail.KEY_NOT_FOUND) { ... }                  // a specific outcome
if (s instanceof PBSuccess) {
    byte[] data = ((PBSuccess) s).getData();
}
```

---

## 7. Keys and key encoding

Any `Serializable` class can be a key. Keys are stored in the file next to their data so the key map can be rebuilt on open. `KeyCodec` encodes each key as **1 type-tag byte followed by its raw value**, which is much smaller than Java serialization:

| Key type | Stored size | With Java serialization |
|---|---|---|
| `Integer`, `Float` | 5 bytes | 79–81 bytes |
| `Long`, `Double` | 9 bytes | 82–84 bytes |
| `Short`, `Character` | 3 bytes | 50–77 bytes |
| `Byte`, `Boolean` | 2 bytes | 47–75 bytes |
| `String` | 1 + UTF-8 length | larger |
| Any other `Serializable` class | 1 + its serialized size | — |

Custom key classes must:

- implement `Serializable`, with `Serializable` fields;
- implement `equals` and `hashCode`, because the key map is a `HashMap`;
- still be loadable (same class name) when the file is reopened.

```java
final class UserKey implements Serializable {
    private static final long serialVersionUID = 1L;
    final String region;
    final int id;
    UserKey(String region, int id) { this.region = region; this.id = id; }
    @Override public boolean equals(Object o) {
        return o instanceof UserKey && ((UserKey) o).region.equals(region) && ((UserKey) o).id == id;
    }
    @Override public int hashCode() { return java.util.Objects.hash(region, id); }
}

PBKeyStorageProvider<UserKey> sp = new PBKeyStorageProvider<>("data/users", UserKey.class);
```

---

## 8. Limits and constants

| Limit | Value |
|---|---|
| Largest record (key bytes + data bytes) | **4,039 bytes**: page 4,090 − page header 34 − slot entry 17 |
| Largest value with an `Integer` key | 4,034 bytes |
| Largest keyless value | 4,039 bytes |
| Key type class name | 254 UTF-8 bytes |
| File header | 256 bytes |
| Page size | 4,090 bytes |
| Records per page | depends on size: about 33 records of 100 bytes with `Integer` keys |
| Smallest file | 256 bytes (empty storage) |

These values come from the `PBOffSets` enum (package `Utility.StorageProviders.PageBasedStorageProvider.Utility`):

| Constant | Value | Meaning |
|---|---|---|
| `FILE_HEADER` | 256 | Bytes before page 0 (holds the key type). |
| `PAGE` | 4090 | Bytes per page. |
| `HEADER` | 34 | Bytes per page header. |
| `SLOT` | 17 | Bytes per slot entry. |

---

## 9. File format

All numbers are big-endian.

**File header:** bytes 0–255

| Offset | Size | Field |
|---|---|---|
| 0 | 2 | Length of the key type name (0 = no key type) |
| 2 | n | Key type class name, UTF-8, for example `java.lang.Integer` |
| 2+n | rest | Zero padding up to 256 bytes |

**Page `p`** starts at `256 + p × 4090`.

Page header (34 bytes):

| Offset | Size | Field |
|---|---|---|
| 0 | 8 | Page offset in the file (used as a consistency check) |
| 8 | 2 | Slot count |
| 10 | 8 | `freeSpaceStart`: where the next slot entry goes |
| 18 | 8 | `freeSpaceEnd`: where the record area begins |
| 26 | 8 | `freeBytes` |

Slot entry (17 bytes each, starting right after the page header):

| Offset | Size | Field |
|---|---|---|
| 0 | 8 | Record offset in the file |
| 8 | 2 | `fullLength`: bytes reserved when the slot was created |
| 10 | 2 | `length`: current record length (key + data), ≤ `fullLength` |
| 12 | 4 | `keyLength`: 0 for keyless records |
| 16 | 1 | Deleted flag (1 = deleted) |

Record at the slot's offset: `[keyLength bytes of encoded key][length − keyLength bytes of data]`.

Encoded key: `[1-byte tag][value]`. Tags: 1 `Integer`, 2 `Long`, 3 `Short`, 4 `Byte`, 5 `Double`, 6 `Float`, 7 `Character`, 8 `Boolean`, 9 `String` (UTF-8), 127 Java-serialized object.

---

## 10. Performance

Measured on a 2-core Linux machine with a virtual disk. Your numbers will differ, but the ratios hold.

| Workload | Time per write | Writes/second |
|---|---|---|
| New keys, 100-byte values | 3.6 µs | ~278,000 |
| New keys, 1 KB values | 4.3 µs | ~232,000 |
| New keys, 4 KB values | 7.8 µs | ~129,000 |
| Overwrites, 100-byte values | 2.9 µs | ~344,000 |
| Random writes on a 1M-record (124 MB) file, behind a lock | — | ~298,000 |
| Every write forced to disk with `fsync` (not done by the library) | ~117 µs | ~8,500 |

- **Opening a file:** about 1.5 s for 1 million records, because every slot and key is read at startup.
- **Time complexity of `write(key, data)`:** O(log F + r), where F is the number of distinct deleted-slot sizes and r is the record size. It doesn't depend on the number of records.
- **System calls:** a write makes 3 (slot entry, record, page header), plus about 4 when an overwrite marks the old slot deleted.

---

## 11. Thread safety, durability and caveats

- **Not thread-safe.** Use one thread, or wrap calls in a lock. An uncontended `ReentrantLock` costs almost nothing.

  ```java
  private final ReentrantLock lock = new ReentrantLock();
  PBStatus put(int k, byte[] v) {
      lock.lock();
      try { return sp.write(k, v); } finally { lock.unlock(); }
  }
  ```

- **One program per file.** Each instance keeps pages, keys and free slots in memory. If two programs, or two instances, write the same file, each one's in-memory state goes out of date and data can be overwritten. File locking alone doesn't fix this.
- **Statuses are shared.** `PBSuccess.READ` and `PBSuccess.WRITE` are single enum constants, so the next call overwrites their data:

  ```java
  PBSuccess a = (PBSuccess) sp.read(1);
  PBSuccess b = (PBSuccess) sp.read(2);
  a.getData();   // now returns key 2's value: a and b are the same object
  ```

  Take the data out right after each call, and don't share results across threads.
- **Not crash-durable.** Writes go to the operating system's file cache without `fsync`. A crash or power loss can lose recent writes even though they returned `WRITE`.
- **Records are limited to about 4 KB.** Anything larger returns `DATA_TOO_LARGE`. Split large values across several keys.
- **Memory use.** All keys and slot metadata are held in memory: fine for hundreds of thousands of records, not hundreds of millions.
- **The file never shrinks** except through `delete()` or a keyless `write(data)`. Deleted slots are reused by best fit, but unused bytes inside reused slots aren't recovered.
- **No `close()` method.** The file stays open until the object is garbage collected. Avoid creating many instances for the same file.
- **Overwrites don't reuse their own slot.** The new value is placed before the old slot is freed, so an overwrite takes a different slot. The old slot is then free for the next write.

---

## 12. Supporting classes

These are public because the providers use them across packages. You don't normally need them, apart from `Serializer`.

### `Utility.Serialization.Serializer`

| Method | Purpose |
|---|---|
| `static byte[] serialize(Object obj) throws IOException` | Java-serializes an object to bytes. |
| `static <T> T deserialize(byte[] data) throws IOException, ClassNotFoundException` | Turns bytes back into an object. |

### `Utility.Encoding.KeyCodec`

| Method | Purpose |
|---|---|
| `static byte[] encode(Serializable key) throws Exception` | `[tag][value]` encoding described in [section 7](#7-keys-and-key-encoding). |
| `static Object decode(byte[] bytes) throws Exception` | The reverse; throws `IllegalStateException` for corrupt bytes. |

### `Utility.Encoding.KeyTag` (enum)

The type tags: `INTEGER`(1), `LONG`(2), `SHORT`(3), `BYTE`(4), `DOUBLE`(5), `FLOAT`(6), `CHARACTER`(7), `BOOLEAN`(8), `STRING`(9), `SERIALIZED`(127).

| Method | Purpose |
|---|---|
| `getValue()` | The tag byte. |
| `getType()` | The Java class (`null` for `SERIALIZED`). |
| `static forType(Class<?>)` | The tag for a class, or `SERIALIZED`. |
| `static fromValue(byte)` | The tag for a tag byte. |

### `Utility.FileServices.RandomAccessFileService`

Low-level file access used by the providers. Writes are positional, so each write is one system call.

| Method | Purpose |
|---|---|
| `RandomAccessFileService(String path, Extension extension)` | Opens `path + extension` for reading and writing. |
| `long length()` | File size in bytes. |
| `void truncate()` | Cuts the file to 0 bytes. |
| `void write(long pos, byte[] data)` | Writes `data` at `pos`. |
| `void writeInSequence(long pos, byte[][] values)` | Writes the values back to back at `pos`, in one call. |
| `byte[] read(long pos, short length)` | Reads `length` bytes at `pos`. |
| `void writeLong(long pos, long)`, `void writeShort(long pos, short)` | Write a single number at `pos`. |
| `String getPath()`, `Extension getExtension()` | What was opened. |

### `Utility.Constants.Extension` (enum)

`DAT(".dat")`, `JSON(".json")`, `PB(".pb")`. `getValue()` returns the extension. The providers use `PB`.

### Page-layer classes

Package `Utility.StorageProviders.PageBasedStorageProvider.Utility`.

| Class | Purpose |
|---|---|
| `Page` | One page in memory: `getPageNumber()`, `getHeader()`, `getSlots()`, `setPageHeader(...)`, `addSlot(slot, length)` (new slot; updates the header), `restoreSlot(slot)` (slot loaded from disk). |
| `PageHeader` | A page's header: `getOffSet()`, `getSlotCount()`, `getFreeSpaceStart()`, `getFreeSpaceEnd()`, `getFreeBytes()`, `incrementSlotCount()`, `updateFreeSace(length)`. A constructor for a new page and one that restores from disk. |
| `Slot` | A slot entry: `getOffset()`, `getFullLength()`, `getLength()`, `getKeyLength()`, `getDataLength()`, `getDataOffset()`, `isDeleted()`, `deletePage(boolean)` (sets the deleted flag), `reuse(length, keyLength)` (puts a new record into a deleted slot; throws if it exceeds `fullLength`). |
| `RecordID` | A record's location: public final fields `pageNumber` and `slotNumber`. |
| `PBOffSets` | Layout sizes; see [section 8](#8-limits-and-constants). |
