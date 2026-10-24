# Dexter 0.1.0 — Containers

Dexter saves Java objects to disk and gives them back. You work with two classes:

- **`Container<T>`** holds **one** value, for example your app's settings.
- **`KeyContainer<K, T>`** holds **many** values, each under a key. It works like a `Map<K, T>` that is kept on disk.

Every operation returns a **`ContainerResult<T>`**. It tells you whether the operation worked, what happened, and gives you the value.

This README covers everything a Container user can call. It does not explain how Dexter works inside.

---

## Contents

1. [Setup](#1-setup)
2. [Quick start](#2-quick-start)
3. [Container: one value](#3-container-one-value)
4. [KeyContainer: many values by key](#4-keycontainer-many-values-by-key)
5. [Results: from simple to detailed](#5-results-from-simple-to-detailed)
6. [Every status, operation by operation](#6-every-status-operation-by-operation)
7. [Limits: maxLength and maxSize](#7-limits-maxlength-and-maxsize)
8. [Opening and closing](#8-opening-and-closing)
9. [What you can store](#9-what-you-can-store)
10. [Files on disk, crashes, and threads](#10-files-on-disk-crashes-and-threads)
11. [Using a Storage folder](#11-using-a-storage-folder)
12. [API reference](#12-api-reference)

---

## 1. Setup

Dexter needs **Java 11 or newer** and has no other dependencies. Add the jar to your classpath:

```
javac -cp Dexter-v0.1.0.jar MyApp.java
java  -cp Dexter-v0.1.0.jar:. MyApp        # use ; instead of : on Windows
```

You'll use these imports:

```java
import ContainerCore.Container.Container;        // one value
import ContainerCore.Container.KeyContainer;     // many values by key
import ContainerCore.Container.ContainerResult;  // what every operation returns
import ContainerCore.Utility.ContainerLimitType; // limits (section 7)
import StorageStatus.SP.SPStatus;                // a status: SPSuccess or SPFail
import StorageStatus.SP.SPSuccess;               // success statuses
import StorageStatus.SP.SPFail;                  // failure statuses
```

`Dexter-v0.1.0-sources.jar` contains the source code, which your IDE can use to show docs and let you step into the code.

---

## 2. Quick start

If you only want to save something and read it back, this is all you need:

```java
import ContainerCore.Container.KeyContainer;

public class QuickStart {
    public static void main(String[] args) throws Exception {
        try (KeyContainer<String, String> notes = new KeyContainer<>("notes", String.class, String.class)) {
            notes.write("monday", "Buy milk");
            notes.write("tuesday", "Call the bank");

            System.out.println(notes.read("monday").getValue());   // Buy milk
            System.out.println(notes.read().getValues());          // [Buy milk, Call the bank]
        }
    }
}
```

Run it again and the notes are still there. They are saved in the folder `notes.dex`, in the directory the program runs from.

You don't have to check results if you don't need to. When something goes wrong, `getValue()` simply returns `null`. [Section 5](#5-results-from-simple-to-detailed) shows how to find out what went wrong when you do care.

---

## 3. Container: one value

A `Container<T>` holds a single value of type `T`. Writing a new value replaces the old one.

```java
import java.io.Serializable;
import ContainerCore.Container.Container;

public class SettingsExample {
    static class Settings implements Serializable {
        private static final long serialVersionUID = 1L;
        String theme;
        int fontSize;
        Settings(String theme, int fontSize) { this.theme = theme; this.fontSize = fontSize; }
    }

    public static void main(String[] args) throws Exception {
        try (Container<Settings> settings = new Container<>("settings", Settings.class)) {
            settings.write(new Settings("dark", 14));       // store
            Settings current = settings.read().getValue();  // read back (null if nothing is stored)
            System.out.println(current.theme + " " + current.fontSize);

            settings.write(new Settings("light", 16));      // replaces the old value
            System.out.println(settings.length());          // 1 (a Container holds 0 or 1 values)

            settings.delete();                              // erase it
            System.out.println(settings.read().getValue()); // null
        }
    }
}
```

| Method | What it does |
|---|---|
| `write(T value)` | Stores `value`, replacing what was there. |
| `read()` | Returns the stored value through `getValue()`. |
| `delete()` | Erases the value. |

---

## 4. KeyContainer: many values by key

A `KeyContainer<K, T>` stores values of type `T`, each under a key of type `K`. Keys can be any `Serializable` type: `Integer`, `Long`, `String`, `UUID`, or your own class.

```java
import java.io.Serializable;
import java.util.List;
import ContainerCore.Container.KeyContainer;

public class UsersExample {
    static class User implements Serializable {
        private static final long serialVersionUID = 1L;
        String name;
        int age;
        User(String name, int age) { this.name = name; this.age = age; }
        @Override public String toString() { return name + " (" + age + ")"; }
    }

    public static void main(String[] args) throws Exception {
        try (KeyContainer<Integer, User> users = new KeyContainer<>("users", Integer.class, User.class)) {
            users.write(1, new User("Ashish", 25));
            users.write(2, new User("Maya", 31));
            users.write(1, new User("Ashish", 26));        // same key: replaces the old value

            User one = users.read(1).getValue();           // Ashish (26)
            List<User> everyone = users.read().getValues(); // every value
            boolean hasThree = users.containsKey(3);        // false

            User removed = users.delete(2).getValue();     // removes key 2 and returns its value
            System.out.println(one + " " + everyone + " " + hasThree + " removed " + removed);
            System.out.println(users.length() + " users, " + users.size() + " bytes");
        }
    }
}
```

| Method | What it does |
|---|---|
| `write(K key, T value)` | Stores `value` under `key`, replacing any value the key already had. |
| `read(K key)` | Returns the value under `key`. |
| `read()` | Returns **all** values: use `getValues()` for the list, while `getValue()` gives only the first one. The order is not guaranteed. |
| `delete(K key)` | Removes `key`. `getValue()` returns the value that was removed. |
| `containsKey(K key)` | `true` if a value is stored under `key`. |

`KeyContainer` also inherits the keyless methods, and they act on the **whole** container:

| Inherited method | On a KeyContainer |
|---|---|
| `write(T value)` | **Replaces everything** with this one value. Usually not what you want: use `write(key, value)`. |
| `delete()` | **Erases every key.** |
| `read()` | All values (see above). |

---

## 5. Results: from simple to detailed

Operations never throw. Each one returns a `ContainerResult<T>`, and you choose how much of it to look at.

### What a result contains

| Method | Returns |
|---|---|
| `getValue()` | The value the operation was about, or `null`. See the table below. |
| `getValues()` | Every value as an unmodifiable `List<T>`. For a keyed `read()` this is all values; otherwise it is `getValue()` as a list (empty when `getValue()` is `null`). |
| `hasSucceeded()` | `true` if the operation worked. |
| `getStatus()` | Exactly what happened: an `SPSuccess` value (for example `WRITE`) or an `SPFail` value (for example `KEY_NOT_FOUND`). |
| `getError()` | The exception behind a failure such as `READ_ERROR`, or `null`. |
| `getData()` / `getDataArray()` | The raw serialized bytes. You normally don't need these. |
| `toString()` | Readable text such as `WRITE (hello)`, `READ (3 values)`, `KEY_NOT_FOUND` or `WRITE_ERROR (java.io.IOException: ...)`. Useful in logs. |

What `getValue()` returns for each operation:

| Operation | `getValue()` on success |
|---|---|
| `write(value)` / `write(key, value)` | The value that was written. |
| `read()` on a `Container`, or `read(key)` | The value that was read. |
| `read()` on a `KeyContainer` | The first value (use `getValues()` to get all of them). |
| `delete(key)` | The value that was removed. |
| `delete()`, `recount()` | `null` |
| **Any failure** | `null` |

A result never changes after it's created, so you can keep it, compare it, or pass it to another thread.

### Level 1: just use the value

This is fine for scripts, tools, and any code where a missing value is acceptable:

```java
String note = notes.read("monday").getValue();
if (note != null) {
    System.out.println(note);
}
```

### Level 2: check pass or fail

```java
ContainerResult<User> result = users.write(7, new User("Ravi", 40));
if (!result.hasSucceeded()) {
    System.err.println("Could not save user 7: " + result);  // e.g. "LIMIT_EXCEEDED"
    if (result.getError() != null) {
        result.getError().printStackTrace();                  // the I/O problem behind it
    }
}
```

### Level 3: handle each outcome with `switch`

`getStatus()` returns an `SPStatus`, which is either an `SPSuccess` or an `SPFail` enum value. Check which one it is, then use `switch` on the enum. This shows exactly where something went wrong and lets you handle each case on its own:

```java
ContainerResult<User> result = users.read(42);
SPStatus status = result.getStatus();

if (status instanceof SPSuccess) {
    User user = result.getValue();
    System.out.println("Found " + user);
} else {
    switch ((SPFail) status) {
        case KEY_NOT_FOUND:
            System.out.println("No user 42: show a sign-up screen");
            break;
        case NULL_KEY:
        case KEY_TYPE_MISMATCH:
            throw new IllegalArgumentException("Bug: bad key passed to read()");
        case DESERIALIZE_ERROR:
            // The stored bytes don't match the User class any more (see section 9).
            System.err.println("User 42 is unreadable: " + result.getError());
            users.delete(42);                       // or migrate it, or keep it and warn
            break;
        case STORAGE_CLOSED:
            System.err.println("Container was closed: reopen it");
            break;
        case READ_ERROR:
            System.err.println("Disk problem: " + result.getError());  // the IOException
            break;
        default:
            System.err.println("Unexpected: " + result);
    }
}
```

You can do the same for a write, and decide what to do for each reason it might fail:

```java
ContainerResult<User> saved = users.write(id, user);
SPStatus status = saved.getStatus();

if (status == SPSuccess.WRITE) {
    System.out.println("Saved " + saved.getValue());
} else {
    switch ((SPFail) status) {
        case LIMIT_EXCEEDED:  System.out.println("Container is full: delete old users first"); break;
        case DATA_TOO_LARGE:  System.out.println("This user is too big for one record (about 4 KB)"); break;
        case KEY_TOO_LARGE:   System.out.println("The key is too big (max 1024 bytes)"); break;
        case SERIALIZE_ERROR: System.out.println("User has a field that isn't Serializable: " + saved.getError()); break;
        case NULL_WRITE:      System.out.println("Can't store null: use delete(key) instead"); break;
        case NULL_KEY:        System.out.println("Key is null"); break;
        case STORAGE_CLOSED:  System.out.println("Container is closed"); break;
        case WRITE_ERROR:     System.out.println("Disk problem, nothing was changed: " + saved.getError()); break;
        default:              System.out.println("Unexpected: " + saved);
    }
}
```

With Java 14 or newer you can use the arrow form and group cases:

```java
ContainerResult<User> result = users.read(42);
if (result.getStatus() instanceof SPFail) {
    switch ((SPFail) result.getStatus()) {
        case KEY_NOT_FOUND                     -> System.out.println("not there");
        case READ_ERROR, DESERIALIZE_ERROR     -> System.err.println("broken: " + result.getError());
        default                                -> System.err.println("other: " + result);
    }
}
```

Tip: a single helper method that turns a failed result into your app's own error or log message keeps the `switch` in one place.

---

## 6. Every status, operation by operation

### Success statuses (`SPSuccess`)

| Status | Returned by |
|---|---|
| `WRITE` | `write(...)` |
| `READ` | `read(...)`, `recount()` |
| `DELETE` | `delete(...)` |

`SPSuccess` also has `COMPACT`, `SYNC` and `CHECKPOINT`, but no Container operation returns them.

### Failure statuses (`SPFail`)

When an operation fails, **nothing is changed**: what was stored before is still stored.

| Status | Meaning | What to do |
|---|---|---|
| `NULL_READ` | `read()` on an empty container. | Treat it as "nothing saved yet". |
| `NULL_WRITE` | `write(null)`. | Null can't be stored; use `delete` instead. |
| `NULL_KEY` | The key was `null`. | Pass a key. |
| `KEY_TYPE_MISMATCH` | The key isn't of the container's key type. This can only happen through raw or unchecked generics. | A bug in the calling code. |
| `KEY_NOT_FOUND` | `read(key)` / `delete(key)` for a key that isn't stored. | Often a normal case: "not there". |
| `KEY_ENCODE_ERROR` | `write(key, value)`: the key couldn't be turned into bytes, for example a custom key class that fails to serialize. `getError()` has the cause. (A read or delete with such a key returns `KEY_NOT_FOUND`.) | Fix the key class. |
| `KEY_TOO_LARGE` | `write(key, value)`: the key is longer than 1024 bytes once encoded. (A read or delete with such a key returns `KEY_NOT_FOUND`, because it can never be stored.) | Use a shorter key. |
| `DATA_TOO_LARGE` | The value (plus key) doesn't fit in one record, which is about 4 KB. | Store less per value, or split it across keys. |
| `VALUE_TYPE_MISMATCH` | The value isn't of the container's value type. This can only happen through raw or unchecked generics. | A bug in the calling code. |
| `SERIALIZE_ERROR` | The value couldn't be serialized, for example a field that isn't `Serializable`. `getError()` has the cause. | Make every field `Serializable` or `transient`. |
| `DESERIALIZE_ERROR` | The stored bytes couldn't be turned back into `T`: the class changed incompatibly, or the container holds another type. `getError()` has the cause. | See [section 9](#9-what-you-can-store). |
| `LIMIT_EXCEEDED` | The write would take the container past `maxLength` or `maxSize`. | Delete something, or raise the limit. |
| `STORAGE_CLOSED` | The container was closed. | Open a new instance. |
| `READ_ERROR` / `WRITE_ERROR` / `DELETE_ERROR` | An I/O problem: disk full, permissions, a damaged file. `getError()` has the `IOException`. | Log it; retry or report it to the user. |

`SPFail` also has `COMPACT_ERROR`, `SYNC_ERROR` and `CHECKPOINT_ERROR`, but no Container operation returns them.

### Which failures each operation can return

| Operation | Possible failures |
|---|---|
| `Container.write(value)` | `NULL_WRITE`, `VALUE_TYPE_MISMATCH`, `SERIALIZE_ERROR`, `LIMIT_EXCEEDED`, `DATA_TOO_LARGE`, `WRITE_ERROR`, `STORAGE_CLOSED` |
| `Container.read()` | `NULL_READ`, `DESERIALIZE_ERROR`, `READ_ERROR`, `STORAGE_CLOSED` |
| `Container.delete()` | `DELETE_ERROR`, `STORAGE_CLOSED` |
| `KeyContainer.write(key, value)` | `NULL_KEY`, `KEY_TYPE_MISMATCH`, `KEY_ENCODE_ERROR`, `KEY_TOO_LARGE`, `NULL_WRITE`, `VALUE_TYPE_MISMATCH`, `SERIALIZE_ERROR`, `LIMIT_EXCEEDED`, `DATA_TOO_LARGE`, `WRITE_ERROR`, `STORAGE_CLOSED` |
| `KeyContainer.read(key)` | `NULL_KEY`, `KEY_TYPE_MISMATCH`, `KEY_NOT_FOUND`, `DESERIALIZE_ERROR`, `READ_ERROR`, `STORAGE_CLOSED` |
| `KeyContainer.read()` | `NULL_READ` (no keys), `DESERIALIZE_ERROR` (any value unreadable), `READ_ERROR`, `STORAGE_CLOSED` |
| `KeyContainer.delete(key)` | `NULL_KEY`, `KEY_TYPE_MISMATCH`, `KEY_NOT_FOUND`, `DELETE_ERROR`, `STORAGE_CLOSED` |
| `recount()` | `READ_ERROR`, `STORAGE_CLOSED` |

Note: `delete(key)` removes a value even if it can no longer be deserialized. In that case the result is `DELETE` and `getValue()` is `null`. This makes `delete(key)` the way to clear out an unreadable value.

---

## 7. Limits: maxLength and maxSize

A container can be capped by the **number of values** (`maxLength`), by their **total size in bytes** (`maxSize`), or by both. `0` means no limit, which is the default.

```java
// At most 1000 users:
KeyContainer<Integer, User> users =
    new KeyContainer<>("users", Integer.class, User.class, ContainerLimitType.LENGTH, 1000);

// At most 5 MB of log entries:
KeyContainer<Long, String> log =
    new KeyContainer<>("log", Long.class, String.class, ContainerLimitType.MEGABYTES, 5);
```

`ContainerLimitType` has these values:

| Value | Sets |
|---|---|
| `LENGTH` | `maxLength` = limit (number of values) |
| `BYTES`, `KILOBYTES`, `MEGABYTES`, `GIGABYTES` | `maxSize` = limit × 1, 1024, 1024², 1024³ bytes |
| `NO_LIMIT` | Clears both limits (the limit number is ignored) |

You can change limits at any time. They are saved with the container:

```java
users.setMaxLength(500);                           // number of values, 0 = none
users.setMaxSize(2 * 1024 * 1024);                 // bytes, 0 = none
users.setLimit(ContainerLimitType.KILOBYTES, 512); // same as setMaxSize(512 * 1024)
users.setLimit(ContainerLimitType.NO_LIMIT, 0);    // remove both limits

long count = users.length();      // how many values are stored
long bytes = users.size();        // their total size in bytes (serialized values, keys not counted)
boolean full = users.isFull();    // at maxLength or maxSize
```

How limits behave:

- A write that would go past a limit returns `LIMIT_EXCEEDED` and stores nothing.
- Limits only stop a container from **growing**. Replacing a value with one that is the same size or smaller, and deleting, always work.
- Lowering a limit below what's already stored doesn't delete anything; new growth is refused until you're under it again.
- The limit setters throw `IllegalArgumentException` for negative numbers. They throw `IOException` if the limit can't be saved, in which case the new limit still applies for this session.
- **Opening with a limit replaces the saved limits. Opening without one keeps the saved limits.** So `new KeyContainer<>("users", Integer.class, User.class)` reopens with whatever limits were set last time.

`length()` and `size()` are always correct, including after a crash (see [section 10](#10-files-on-disk-crashes-and-threads)). `recount()` recounts them from disk. You don't normally need it, because Dexter does this itself when it's needed.

---

## 8. Opening and closing

### Constructors

```java
new Container<>(name, valueType)
new Container<>(name, valueType, limitType, limit)
new Container<>(name, valueType, storageProviderType)
new Container<>(name, valueType, storageProviderType, limitType, limit)
new Container<>(storage, name, valueType, storageProviderType, limitType, limit)     // see section 11

new KeyContainer<>(name, keyType, valueType)
new KeyContainer<>(name, keyType, valueType, limitType, limit)
new KeyContainer<>(name, keyType, valueType, storageProviderType)
new KeyContainer<>(name, keyType, valueType, storageProviderType, limitType, limit)
new KeyContainer<>(storage, name, keyType, valueType, storageProviderType, limitType, limit)
```

- `name` is a path, relative or absolute: `"users"` is stored as the folder `users.dex`, and `"data/users"` as `data/users.dex`. If a container already exists at that path, it is opened with its data.
- **The parent folder must already exist.** Dexter creates the `.dex` folder but not the folders above it, so `"data/users"` throws `NoSuchFileException` (an `IOException`) if there's no `data` folder. Create the folder first if needed: `Files.createDirectories(Path.of("data"));`.
- `storageProviderType` decides how data is stored on disk. The only option is `StorageProviderType.PAGE_BASED`, which is also the default (`import Utility.StorageProviders.StorageProviderType;`).
- In the `Storage` constructors, `limitType` may be `null` to keep the saved limits.

Opening a container is the only point where an exception can be thrown, because constructors are declared `throws Exception`:

| Exception | When |
|---|---|
| `IllegalArgumentException` | Empty or null name, null `valueType` / `keyType` / `storageProviderType`, or a negative limit. |
| `StorageInUseException` | The container is already open, in this program or another one (see below). It's in `Utility.StorageProviders.PageBasedStorageProvider`. |
| `IOException` | The files couldn't be created or read: the parent folder doesn't exist (`NoSuchFileException`), no permission, or a damaged file. |

### One instance per container

A container can be open **once at a time**. Opening the same path again, from this program or another process, throws `StorageInUseException` until the first instance is closed. Share the one instance across your code and threads, because it's thread-safe.

### Closing

Always close a container when you're done. Closing saves `length`, `size` and the limits and releases the lock. `try-with-resources` does this for you:

```java
try (Container<Settings> settings = new Container<>("settings", Settings.class)) {
    // use it
}   // closed here, even if an exception was thrown
```

- `close()` can be called more than once. It throws `IOException` only if saving fails.
- After `close()`, every operation returns `STORAGE_CLOSED`, and `isClosed()` returns `true`.
- To use the container again, create a new instance with the same name.
- If your program exits without closing, nothing is lost (see [section 10](#10-files-on-disk-crashes-and-threads)), but the next open has to recount the values, which is slower for big containers.

---

## 9. What you can store

Values and keys must be `Serializable`. Most JDK types already are, including `String`, the number types, `UUID`, `LocalDate`, `ArrayList` and `HashMap`. For your own classes:

```java
class User implements Serializable {
    private static final long serialVersionUID = 1L;  // keep this fixed
    String name;
    int age;
    transient Connection db;  // not stored; comes back as null
}
```

- **Every field** must be `Serializable` or marked `transient`. Otherwise `write` returns `SERIALIZE_ERROR`.
- **Declare `serialVersionUID`** in every class you store. If you change the class later (for example by adding a field), values stored earlier still load, and the new fields come back as `null` / `0`. Without it, almost any change makes old values fail with `DESERIALIZE_ERROR`.
- **Size:** one value (plus its key) must fit in about 4 KB once serialized. For a `Container<byte[]>` that's about 4,000 bytes of data. Larger values return `DATA_TOO_LARGE`, so split them across several keys.
- **Keys** are compared by their serialized bytes, so use simple, stable key types: numbers, strings, `UUID`, or small classes whose fields never change.
- **Reading under another type:** opening an existing container as `Container<Integer>` when it holds `String`s gives `DESERIALIZE_ERROR` on read. Your data is untouched; reopen it with the right type.

---

## 10. Files on disk, crashes, and threads

### Files

A container named `users` is stored in the folder `users.dex`:

| File | Contents |
|---|---|
| `data.pb` | The values. |
| `index.pbi` | The key index (`KeyContainer` only). |
| `journal.wal` | Recent changes, used to recover after a crash. |
| `container.meta` | `length`, `size`, and limits. |
| `dex.lock` | The "in use" lock. |

To back up, move or delete a container, close it first and then copy, move or delete the whole `.dex` folder. Don't edit or delete the files inside it one by one.

### Crash safety

- **Every write and delete is all-or-nothing.** If your program crashes, is killed, or throws in the middle of an operation, the next open recovers by itself. The container is then exactly as it was before that operation, or after it, and never half-written.
- A program crash loses nothing that already returned success.
- On a **power cut or OS crash**, the last few operations before it may be lost. The container still opens in a consistent state.
- `length()` and `size()` stay correct either way: if the container wasn't closed cleanly, they are recounted when it's next opened.

### Threads

A container is **thread-safe**: share one instance across threads. Writes and deletes run one at a time, and reads can run alongside them. Every result is a new, immutable object, so passing it between threads is safe.

### Disk space

A deleted value's space is reused by later writes, so a container doesn't keep growing if you delete as much as you write. Dropping or rewriting a container (`delete()`) frees its space.

---

## 11. Using a Storage folder

`StorageCore.Storage` is a named folder for grouping containers. A container created inside it is stored at `<storage directory>/<name>`:

```java
import java.nio.file.Files;
import java.nio.file.Path;
import StorageCore.Storage;
import Utility.StorageProviders.StorageProviderType;

Files.createDirectories(Path.of("data/myapp"));     // the folder must exist (section 8)
Storage app = new Storage("data/myapp", "MyApp");
try (KeyContainer<Integer, User> users = new KeyContainer<>(
        app, "users", Integer.class, User.class, StorageProviderType.PAGE_BASED, null, 0)) {
    System.out.println(users.getContainerFullPath());   // data/myapp/users
}
```

Passing `null` as the limit type keeps the saved limits. You can also pass, for example, `ContainerLimitType.LENGTH, 100` to set a limit.

---

## 12. API reference

### `Container<T extends Serializable>` (implements `AutoCloseable`)

| Method | Description |
|---|---|
| `ContainerResult<T> write(T value)` | Stores `value`, replacing what was there. |
| `ContainerResult<T> read()` | The stored value. On a `KeyContainer`, all values. |
| `ContainerResult<T> delete()` | Erases everything. |
| `ContainerResult<T> recount()` | Recounts `length` and `size` from disk. Status `READ`; `getValue()` is `null`. |
| `void close() throws IOException` | Saves and releases the container. Can be called more than once. |
| `boolean isClosed()` | `true` after `close()`. |
| `long length()` | Number of values stored. |
| `long size()` | Total bytes of the stored values (serialized, keys not counted). |
| `long getMaxLength()` / `void setMaxLength(long) throws IOException` | Most values allowed; `0` = no limit. |
| `long getMaxSize()` / `void setMaxSize(long) throws IOException` | Most bytes allowed; `0` = no limit. |
| `void setLimit(ContainerLimitType, long) throws IOException` | Sets a limit in a unit (see [section 7](#7-limits-maxlength-and-maxsize)). |
| `boolean isFull()` | `true` at `maxLength` or `maxSize`. |
| `String getContainerName()` | The name it was opened with. |
| `String getContainerFullPath()` | The path on disk, without `.dex`. |
| `StorageProviderType getStorageProviderType()` | `PAGE_BASED`. |
| `Class<T> getValueType()` | The value class. |

### `KeyContainer<K extends Serializable, T extends Serializable>` (extends `Container<T>`)

Everything in `Container`, plus:

| Method | Description |
|---|---|
| `ContainerResult<T> write(K key, T value)` | Stores `value` under `key`, replacing any old value. |
| `ContainerResult<T> read(K key)` | The value under `key`. |
| `ContainerResult<T> read()` | All values (`getValues()`); `getValue()` is the first. |
| `ContainerResult<T> delete(K key)` | Removes `key`; `getValue()` is the removed value. |
| `boolean containsKey(K key)` | `true` if `key` is stored (`false` for `null`, an error, or after close). |
| `Class<K> getKeyType()` | The key class. |

`length()` is the number of keys. The inherited `write(T)` and `delete()` replace or erase **all** keys.

### `ContainerResult<T>` (extends `StorageStatus.SP.SPResult`)

| Method | Description |
|---|---|
| `T getValue()` | The value the operation was about, or `null` (see [section 5](#5-results-from-simple-to-detailed)). |
| `List<T> getValues()` | Every value; unmodifiable, never `null`. |
| `boolean hasSucceeded()` | Pass or fail. |
| `SPStatus getStatus()` | An `SPSuccess` or `SPFail` value. |
| `Exception getError()` | The cause of an I/O or serialization failure, or `null`. |
| `byte[] getData()` / `byte[][] getDataArray()` | The raw serialized bytes. |
| `String toString()` | A readable summary for logs. |

The static factory methods (`from`, `fromAll`, `failed`, and `of` / `ofAll` / `ofError` inherited from `SPResult`) are used inside Dexter. You don't need them to use a container.

### Status types (`StorageStatus.SP`)

| Type | Values |
|---|---|
| `SPStatus` | Interface implemented by both enums below. |
| `SPSuccess` | `READ`, `WRITE`, `DELETE` (also `COMPACT`, `SYNC`, `CHECKPOINT`, which containers don't return) |
| `SPFail` | `NULL_READ`, `NULL_WRITE`, `NULL_KEY`, `KEY_TYPE_MISMATCH`, `KEY_NOT_FOUND`, `KEY_ENCODE_ERROR`, `KEY_TOO_LARGE`, `DATA_TOO_LARGE`, `READ_ERROR`, `WRITE_ERROR`, `DELETE_ERROR`, `STORAGE_CLOSED`, `LIMIT_EXCEEDED`, `SERIALIZE_ERROR`, `VALUE_TYPE_MISMATCH`, `DESERIALIZE_ERROR` (also `COMPACT_ERROR`, `SYNC_ERROR`, `CHECKPOINT_ERROR`, which containers don't return) |

### Other types

| Type | Description |
|---|---|
| `ContainerCore.Utility.ContainerLimitType` | `LENGTH`, `BYTES`, `KILOBYTES`, `MEGABYTES`, `GIGABYTES`, `NO_LIMIT`. |
| `Utility.StorageProviders.StorageProviderType` | `PAGE_BASED`, the default and only option. |
| `StorageCore.Storage` | A folder for grouping containers: `new Storage(directory, name)`. |
| `Utility.StorageProviders.PageBasedStorageProvider.StorageInUseException` | Thrown when a container is opened twice. |

---

## Changes in 0.1.0

- **Containers are the public API.** `Container<T>` holds one value and `KeyContainer<K, T>` holds many by key. You work with objects instead of bytes.
- **`ContainerResult<T>`** returns the value itself (`getValue()` / `getValues()`).
- **Limits:** `maxLength` / `maxSize`, with `length()` / `size()` that survive restarts and crashes.
- **Statuses renamed:** `PBResult` / `PBSuccess` / `PBFail` → `SPResult` / `SPSuccess` / `SPFail`, in the new package `StorageStatus.SP`, with `SPStatus` as their common interface. New statuses: `LIMIT_EXCEEDED`, `SERIALIZE_ERROR`, `VALUE_TYPE_MISMATCH`, `DESERIALIZE_ERROR`.
- **Existing storages open unchanged.** A storage made with 0.0.x can be opened as a `Container` / `KeyContainer` if its values were Java-serialized objects. Its `length` and `size` are counted on the first open.
