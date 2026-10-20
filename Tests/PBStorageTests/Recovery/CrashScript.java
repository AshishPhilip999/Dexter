package PBStorageTests.Recovery;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import StorageStatus.PB.PBResult;
import Utility.StorageProviders.PageBasedStorageProvider.PBKeyStorageProvider;

/*
A deterministic stream of operations (operation i depends only on the seed and
i), used by the crash tests both to drive a storage and to rebuild what the
storage must hold after any number of completed operations.
*/
final class CrashScript {
    static final int KEYS = 300;

    private CrashScript() {
    }

    static final class Op {
        final boolean delete;
        final int key;
        final byte[] value;

        Op(boolean delete, int key, byte[] value) {
            this.delete = delete;
            this.key = key;
            this.value = value;
        }
    }

    static Op op(long seed, int i) {
        return op(seed, i, KEYS);
    }

    /*
    The same, over keys 0..keys-1 (a large key space spreads the work over many
    pages, so the journal applies changes to the files between checkpoints).
    */
    static Op op(long seed, int i, int keys) {
        Random random = new Random(seed * 1_000_003L + i);
        int key = random.nextInt(keys);
        if (random.nextInt(4) == 0) {
            return new Op(true, key, null);
        }
        byte[] value = new byte[8 + random.nextInt(300)];
        ByteBuffer.wrap(value).putInt(i).putInt(key);
        for (int b = 8; b < value.length; b++) {
            value[b] = (byte) (i + b);
        }
        return new Op(false, key, value);
    }

    static PBResult apply(PBKeyStorageProvider<Integer> storage, Op op) {
        return op.delete ? storage.delete(op.key) : storage.write(op.key, op.value);
    }

    /*
    What the storage holds after a number of operations.
    */
    static final class Model {
        final Map<Integer, byte[]> state = new HashMap<>();
        int applied;

        void apply(Op op) {
            if (op.delete) {
                this.state.remove(op.key);
            } else {
                this.state.put(op.key, op.value);
            }
            this.applied++;
        }

        boolean matches(Map<Integer, byte[]> actual) {
            if (actual.size() != this.state.size()) {
                return false;
            }
            for (Map.Entry<Integer, byte[]> entry : this.state.entrySet()) {
                if (!Arrays.equals(entry.getValue(), actual.get(entry.getKey()))) {
                    return false;
                }
            }
            return true;
        }
    }

    static Map<Integer, byte[]> contents(PBKeyStorageProvider<Integer> storage) {
        return contents(storage, KEYS);
    }

    static Map<Integer, byte[]> contents(PBKeyStorageProvider<Integer> storage, int keys) {
        Map<Integer, byte[]> actual = new HashMap<>();
        for (int key = 0; key < keys; key++) {
            PBResult result = storage.read(key);
            if (result.hasSucceeded()) {
                actual.put(key, result.getData());
            } else if (result.getError() != null) {
                throw new AssertionError("key " + key + " -> " + result);
            }
        }
        return actual;
    }

    /*
    The number of operations n, at least from and at most to, after which the
    storage holds exactly actual; -1 if there is none (a half-applied operation).
    */
    static int completedOperations(Map<Integer, byte[]> actual, long seed, int from, int to) {
        return completedOperations(actual, seed, from, to, KEYS);
    }

    static int completedOperations(Map<Integer, byte[]> actual, long seed, int from, int to, int keys) {
        Model model = new Model();
        for (int i = 0; i < from; i++) {
            model.apply(op(seed, i, keys));
        }
        for (int n = from; n <= to; n++) {
            if (model.matches(actual)) {
                return n;
            }
            model.apply(op(seed, n, keys));
        }
        return -1;
    }

    /*
    Copies a storage's files as they are on disk right now: what a crash at this
    moment would leave behind (the lock isn't copied; a crash releases it).
    */
    static void crashImage(Path fromFolder, Path toFolder) throws Exception {
        Files.createDirectories(toFolder);
        for (String name : new String[] { "data.pb", "index.pbi", "journal.wal" }) {
            Path file = fromFolder.resolve(name);
            if (Files.exists(file)) {
                Files.copy(file, toFolder.resolve(name));
            }
        }
    }
}
