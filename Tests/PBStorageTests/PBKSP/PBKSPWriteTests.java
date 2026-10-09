package PBStorageTests.PBKSP;

import static PBStorageTests.PBTestUtils.*;
import static org.junit.jupiter.api.Assertions.*;

import java.io.Serializable;
import java.nio.file.Path;
import java.util.Objects;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import StorageStatus.PB.PBFail;
import StorageStatus.PB.PBStatus;
import StorageStatus.PB.PBSuccess;
import Utility.StorageProviders.PageBasedStorageProvider.PBKeyStorageProvider;
import Utility.StorageProviders.PageBasedStorageProvider.PBStorageProvider;
import Utility.Serialization.Serializer;

public class PBKSPWriteTests {
    @TempDir
    Path dir;

    /*
    100-byte values with Integer keys: each record takes a 17-byte slot entry
    plus 5 + 100 record bytes, so RECORDS_PER_PAGE of them fill page 0 and
    leave too little room for another one.
    */
    static final int VALUE_SIZE = 100;
    static final int RECORD_SIZE = INT_KEY_BYTES + VALUE_SIZE;
    static final int RECORDS_PER_PAGE = (PAGE - PAGE_HEADER) / (SLOT_ENTRY + RECORD_SIZE);

    static final class CustomKey implements Serializable {
        private static final long serialVersionUID = 1L;
        final String name;
        final int id;

        CustomKey(String name, int id) {
            this.name = name;
            this.id = id;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof CustomKey && ((CustomKey) o).name.equals(name) && ((CustomKey) o).id == id;
        }

        @Override
        public int hashCode() {
            return Objects.hash(name, id);
        }
    }

    /*
    Serializable by declaration, but its field isn't, so encoding the key fails.
    */
    static final class UnencodableKey implements Serializable {
        private static final long serialVersionUID = 1L;
        final Object payload = new Object();
    }

    private PBKeyStorageProvider<Integer> fillOnePage(String path) throws Exception {
        PBKeyStorageProvider<Integer> ksp = new PBKeyStorageProvider<>(path, Integer.class);
        for (int i = 0; i < RECORDS_PER_PAGE; i++) {
            assertSuccess(ksp.write(i, bytes(VALUE_SIZE, i)));
        }
        assertEquals(lengthForPages(1), fileLength(path), "precondition: page 0 is full");
        return ksp;
    }

    // ---- results and validation -------------------------------------------

    @Test
    public void writeReturnsWriteWithWrittenData() throws Exception {
        PBKeyStorageProvider<Integer> ksp = new PBKeyStorageProvider<>(storagePath(dir, "ksp"), Integer.class);
        byte[] payload = { 4, 5, 6 };

        PBStatus status = ksp.write(1, payload);

        assertEquals(PBSuccess.WRITE, status);
        assertSuccess(status);
        assertArrayEquals(payload, data(status));
        assertNull(dataArray(status));
    }

    @Test
    public void writeNullKeyFails() throws Exception {
        PBKeyStorageProvider<Integer> ksp = new PBKeyStorageProvider<>(storagePath(dir, "ksp"), Integer.class);
        assertFail(PBFail.NULL_KEY, ksp.write(null, bytes(5, 1)));
    }

    @Test
    public void writeNullDataFails() throws Exception {
        PBKeyStorageProvider<Integer> ksp = new PBKeyStorageProvider<>(storagePath(dir, "ksp"), Integer.class);
        assertFail(PBFail.NULL_WRITE, ksp.write(1, null));
    }

    @Test
    public void writeEmptyDataFails() throws Exception {
        PBKeyStorageProvider<Integer> ksp = new PBKeyStorageProvider<>(storagePath(dir, "ksp"), Integer.class);
        assertFail(PBFail.NULL_WRITE, ksp.write(1, new byte[0]));
    }

    @Test
    public void writeNullKeyIsCheckedBeforeData() throws Exception {
        PBKeyStorageProvider<Integer> ksp = new PBKeyStorageProvider<>(storagePath(dir, "ksp"), Integer.class);
        assertFail(PBFail.NULL_KEY, ksp.write(null, null));
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    @Test
    public void writeKeyOfWrongTypeFails() throws Exception {
        PBKeyStorageProvider raw = new PBKeyStorageProvider<>(storagePath(dir, "ksp"), Integer.class);
        assertFail(PBFail.KEY_TYPE_MISMATCH, raw.write("not-an-int", bytes(5, 1)));
    }

    @Test
    public void writeUnencodableKeyFails() throws Exception {
        PBKeyStorageProvider<UnencodableKey> ksp =
            new PBKeyStorageProvider<>(storagePath(dir, "ksp"), UnencodableKey.class);
        assertFail(PBFail.KEY_ENCODE_ERROR, ksp.write(new UnencodableKey(), bytes(5, 1)));
    }

    @Test
    public void writeMaxRecordSizeIncludingKeySucceeds() throws Exception {
        String path = storagePath(dir, "ksp");
        PBKeyStorageProvider<Integer> ksp = new PBKeyStorageProvider<>(path, Integer.class);
        byte[] payload = bytes(MAX_RECORD - INT_KEY_BYTES, 3);

        assertSuccess(ksp.write(1, payload));
        assertArrayEquals(payload, data(new PBKeyStorageProvider<>(path, Integer.class).read(1)));
    }

    @Test
    public void writeOneByteOverMaxRecordSizeIncludingKeyFails() throws Exception {
        PBKeyStorageProvider<Integer> ksp = new PBKeyStorageProvider<>(storagePath(dir, "ksp"), Integer.class);
        assertFail(PBFail.DATA_TOO_LARGE, ksp.write(1, bytes(MAX_RECORD - INT_KEY_BYTES + 1, 3)));
    }

    @Test
    public void failedWritesLeaveStorageUnchanged() throws Exception {
        PBKeyStorageProvider<Integer> ksp = new PBKeyStorageProvider<>(storagePath(dir, "ksp"), Integer.class);
        ksp.write(1, bytes(10, 1));

        ksp.write(null, bytes(10, 2));
        ksp.write(1, null);
        ksp.write(1, bytes(MAX_RECORD, 2));

        assertArrayEquals(bytes(10, 1), data(ksp.read(1)));
        assertEquals(1, dataArray(ksp.read()).length);
    }

    // ---- overwrite, many keys, persistence ---------------------------------

    @Test
    public void overwriteSameKeyReplacesValue() throws Exception {
        PBKeyStorageProvider<Integer> ksp = new PBKeyStorageProvider<>(storagePath(dir, "ksp"), Integer.class);
        ksp.write(1, bytes(10, 1));
        ksp.write(1, bytes(20, 2));

        assertArrayEquals(bytes(20, 2), data(ksp.read(1)));
        assertEquals(1, dataArray(ksp.read()).length);
    }

    @Test
    public void overwritePersistsAcrossReopen() throws Exception {
        String path = storagePath(dir, "ksp");
        PBKeyStorageProvider<Integer> ksp = new PBKeyStorageProvider<>(path, Integer.class);
        ksp.write(1, bytes(10, 1));
        ksp.write(1, bytes(20, 2));

        PBKeyStorageProvider<Integer> reopened = new PBKeyStorageProvider<>(path, Integer.class);
        assertArrayEquals(bytes(20, 2), data(reopened.read(1)));
        assertEquals(1, dataArray(reopened.read()).length);
    }

    @Test
    public void manyKeysAcrossSeveralPages() throws Exception {
        String path = storagePath(dir, "ksp");
        PBKeyStorageProvider<Integer> ksp = new PBKeyStorageProvider<>(path, Integer.class);
        int count = 300;
        for (int i = 0; i < count; i++) {
            assertSuccess(ksp.write(i, Serializer.serialize("value-" + i)));
        }
        assertTrue(fileLength(path) > lengthForPages(1), "precondition: spans several pages");

        PBKeyStorageProvider<Integer> reopened = new PBKeyStorageProvider<>(path, Integer.class);
        for (int i = 0; i < count; i++) {
            assertEquals("value-" + i, Serializer.deserialize(data(reopened.read(i))));
        }
        assertEquals(count, dataArray(reopened.read()).length);
    }

    @Test
    public void writeAfterReopenAppendsToExistingRecords() throws Exception {
        String path = storagePath(dir, "ksp");
        new PBKeyStorageProvider<>(path, Integer.class).write(1, bytes(10, 1));

        PBKeyStorageProvider<Integer> reopened = new PBKeyStorageProvider<>(path, Integer.class);
        reopened.write(2, bytes(10, 2));

        PBKeyStorageProvider<Integer> again = new PBKeyStorageProvider<>(path, Integer.class);
        assertArrayEquals(bytes(10, 1), data(again.read(1)));
        assertArrayEquals(bytes(10, 2), data(again.read(2)));
    }

    // ---- key types ---------------------------------------------------------

    private <K extends Serializable> void assertKeyRoundTrip(Class<K> type, K key) throws Exception {
        String path = storagePath(dir, "keys-" + type.getSimpleName());
        PBKeyStorageProvider<K> ksp = new PBKeyStorageProvider<>(path, type);
        assertSuccess(ksp.write(key, bytes(8, 5)));

        PBKeyStorageProvider<K> reopened = new PBKeyStorageProvider<>(path, type);
        assertArrayEquals(bytes(8, 5), data(reopened.read(key)), "key type " + type.getSimpleName());
    }

    @Test
    public void everySupportedKeyTypeRoundTripsAcrossReopen() throws Exception {
        assertKeyRoundTrip(Integer.class, -123456);
        assertKeyRoundTrip(Long.class, Long.MAX_VALUE);
        assertKeyRoundTrip(Short.class, Short.MIN_VALUE);
        assertKeyRoundTrip(Byte.class, (byte) -1);
        assertKeyRoundTrip(Double.class, 3.14159);
        assertKeyRoundTrip(Float.class, -2.5f);
        assertKeyRoundTrip(Character.class, '\u03A9');
        assertKeyRoundTrip(Boolean.class, true);
        assertKeyRoundTrip(String.class, "h\u00e9llo w\u00f6rld \u2713");
        assertKeyRoundTrip(String.class, "");
        assertKeyRoundTrip(CustomKey.class, new CustomKey("user", 42));
    }

    @Test
    public void primitiveKeyTypeIsTreatedAsItsWrapper() throws Exception {
        String path = storagePath(dir, "ksp");
        PBKeyStorageProvider<Integer> ksp = new PBKeyStorageProvider<>(path, int.class);

        assertEquals(Integer.class, ksp.getKeyType());
        assertSuccess(ksp.write(7, bytes(5, 7)));
        assertArrayEquals(bytes(5, 7), data(new PBKeyStorageProvider<>(path, Integer.class).read(7)));
    }

    // ---- constructor / key type binding -------------------------------------

    @Test
    public void constructorRejectsNullKeyType() {
        assertThrows(IllegalArgumentException.class,
            () -> new PBKeyStorageProvider<Integer>(storagePath(dir, "ksp"), null));
    }

    @Test
    public void reopenWithDifferentKeyTypeThrows() throws Exception {
        String path = storagePath(dir, "ksp");
        new PBKeyStorageProvider<>(path, Integer.class).write(1, bytes(5, 1));

        assertThrows(IllegalStateException.class, () -> new PBKeyStorageProvider<>(path, String.class));
    }

    @Test
    public void reopenEmptyKeyedFileWithDifferentKeyTypeThrows() throws Exception {
        String path = storagePath(dir, "ksp");
        new PBKeyStorageProvider<>(path, Long.class);

        assertThrows(IllegalStateException.class, () -> new PBKeyStorageProvider<>(path, Integer.class));
    }

    @Test
    public void keyedStorageCanAdoptKeylessFile() throws Exception {
        String path = storagePath(dir, "sp");
        new PBStorageProvider(path).write(bytes(5, 1));

        PBKeyStorageProvider<Integer> ksp = new PBKeyStorageProvider<>(path, Integer.class);
        assertSuccess(ksp.write(1, bytes(5, 2)));

        PBKeyStorageProvider<Integer> reopened = new PBKeyStorageProvider<>(path, Integer.class);
        assertArrayEquals(bytes(5, 2), data(reopened.read(1)));
        assertEquals(2, dataArray(reopened.read()).length);
    }

    // ---- keyless write on a keyed storage ------------------------------------

    @Test
    public void keylessWriteReplacesAllKeyedRecords() throws Exception {
        String path = storagePath(dir, "ksp");
        PBKeyStorageProvider<Integer> ksp = new PBKeyStorageProvider<>(path, Integer.class);
        for (int i = 0; i < 5; i++) {
            ksp.write(i, bytes(10, i));
        }

        PBStatus status = ksp.write(bytes(3, 9));

        assertEquals(PBSuccess.WRITE, status);
        assertFail(PBFail.KEY_NOT_FOUND, ksp.read(0));
        assertFalse(ksp.containsKey(0));
        byte[][] records = dataArray(ksp.read());
        assertEquals(1, records.length);
        assertArrayEquals(bytes(3, 9), records[0]);
    }

    @Test
    public void keylessWriteKeepsKeyTypeAndAllowsKeyedWritesAfter() throws Exception {
        String path = storagePath(dir, "ksp");
        PBKeyStorageProvider<Integer> ksp = new PBKeyStorageProvider<>(path, Integer.class);
        ksp.write(1, bytes(10, 1));
        ksp.write(bytes(3, 9));
        ksp.write(2, bytes(10, 2));

        PBKeyStorageProvider<Integer> reopened = new PBKeyStorageProvider<>(path, Integer.class);
        assertFail(PBFail.KEY_NOT_FOUND, reopened.read(1));
        assertArrayEquals(bytes(10, 2), data(reopened.read(2)));
        assertThrows(IllegalStateException.class, () -> new PBKeyStorageProvider<>(path, String.class));
    }

    @Test
    public void failedKeylessWriteKeepsKeyedRecords() throws Exception {
        PBKeyStorageProvider<Integer> ksp = new PBKeyStorageProvider<>(storagePath(dir, "ksp"), Integer.class);
        ksp.write(1, bytes(10, 1));

        assertFail(PBFail.NULL_WRITE, ksp.write(null));

        assertArrayEquals(bytes(10, 1), data(ksp.read(1)));
    }

    // ---- deleted-slot reuse (best fit) --------------------------------------

    @Test
    public void newKeyReusesDeletedSlotInsteadOfNewPage() throws Exception {
        String path = storagePath(dir, "ksp");
        PBKeyStorageProvider<Integer> ksp = fillOnePage(path);

        ksp.delete(0);
        assertSuccess(ksp.write(1000, bytes(VALUE_SIZE, 77)));

        assertEquals(lengthForPages(1), fileLength(path));
        assertArrayEquals(bytes(VALUE_SIZE, 77), data(ksp.read(1000)));
        assertFail(PBFail.KEY_NOT_FOUND, ksp.read(0));
    }

    @Test
    public void smallerRecordReusesDeletedSlot() throws Exception {
        String path = storagePath(dir, "ksp");
        PBKeyStorageProvider<Integer> ksp = fillOnePage(path);

        ksp.delete(0);
        assertSuccess(ksp.write(1000, bytes(10, 77)));

        assertEquals(lengthForPages(1), fileLength(path));
        assertArrayEquals(bytes(10, 77), data(new PBKeyStorageProvider<>(path, Integer.class).read(1000)));
    }

    @Test
    public void reuseChecksFullLengthNotCurrentLength() throws Exception {
        String path = storagePath(dir, "ksp");
        PBKeyStorageProvider<Integer> ksp = fillOnePage(path);

        // A 105-byte slot now holds a 15-byte record; its fullLength stays 105.
        ksp.delete(0);
        ksp.write(1000, bytes(10, 1));
        ksp.delete(1000);

        // A full-size record still fits that slot.
        assertSuccess(ksp.write(1001, bytes(VALUE_SIZE, 2)));
        assertEquals(lengthForPages(1), fileLength(path));
        assertArrayEquals(bytes(VALUE_SIZE, 2), data(new PBKeyStorageProvider<>(path, Integer.class).read(1001)));
    }

    @Test
    public void fullLengthSurvivesReopen() throws Exception {
        String path = storagePath(dir, "ksp");
        PBKeyStorageProvider<Integer> ksp = fillOnePage(path);
        ksp.delete(0);
        ksp.write(1000, bytes(10, 1));

        PBKeyStorageProvider<Integer> reopened = new PBKeyStorageProvider<>(path, Integer.class);
        reopened.delete(1000);
        assertSuccess(reopened.write(1001, bytes(VALUE_SIZE, 2)));

        assertEquals(lengthForPages(1), fileLength(path));
    }

    @Test
    public void recordLargerThanEveryDeletedSlotGoesToNewPage() throws Exception {
        String path = storagePath(dir, "ksp");
        PBKeyStorageProvider<Integer> ksp = fillOnePage(path);

        ksp.delete(0);
        assertSuccess(ksp.write(1000, bytes(VALUE_SIZE + 1, 9)));

        assertEquals(lengthForPages(2), fileLength(path));
        assertArrayEquals(bytes(VALUE_SIZE + 1, 9), data(new PBKeyStorageProvider<>(path, Integer.class).read(1000)));
    }

    @Test
    public void bestFitPicksSmallestSlotThatFits() throws Exception {
        String path = storagePath(dir, "ksp");
        PBKeyStorageProvider<Integer> ksp = new PBKeyStorageProvider<>(path, Integer.class);

        // One big (200) and one small (20) record, then 100-byte records until page 0 is full.
        ksp.write(-1, bytes(200, 1));
        ksp.write(-2, bytes(20, 2));
        int free = (PAGE - PAGE_HEADER) - 2 * SLOT_ENTRY - (INT_KEY_BYTES + 200) - (INT_KEY_BYTES + 20);
        int fillers = free / (SLOT_ENTRY + RECORD_SIZE);
        for (int i = 0; i < fillers; i++) {
            ksp.write(i, bytes(VALUE_SIZE, 3));
        }
        assertEquals(lengthForPages(1), fileLength(path), "precondition: page 0 is full");

        ksp.delete(-1);
        ksp.delete(-2);

        // Best fit sends the small record to the small slot, leaving the big slot for the big record.
        assertSuccess(ksp.write(500, bytes(20, 4)));
        assertSuccess(ksp.write(501, bytes(200, 5)));

        assertEquals(lengthForPages(1), fileLength(path));
        PBKeyStorageProvider<Integer> reopened = new PBKeyStorageProvider<>(path, Integer.class);
        assertArrayEquals(bytes(20, 4), data(reopened.read(500)));
        assertArrayEquals(bytes(200, 5), data(reopened.read(501)));
    }

    @Test
    public void deletedSlotsAreReusableAfterReopen() throws Exception {
        String path = storagePath(dir, "ksp");
        PBKeyStorageProvider<Integer> ksp = fillOnePage(path);
        ksp.delete(0);

        PBKeyStorageProvider<Integer> reopened = new PBKeyStorageProvider<>(path, Integer.class);
        assertSuccess(reopened.write(1000, bytes(VALUE_SIZE, 6)));

        assertEquals(lengthForPages(1), fileLength(path));
    }

    @Test
    public void repeatedOverwritesKeepFileBounded() throws Exception {
        String path = storagePath(dir, "ksp");
        PBKeyStorageProvider<Integer> ksp = new PBKeyStorageProvider<>(path, Integer.class);
        int keys = 50;
        for (int i = 0; i < keys; i++) {
            ksp.write(i, bytes(VALUE_SIZE, 0));
        }
        long afterFirstRound = fileLength(path);

        for (int round = 1; round <= 20; round++) {
            for (int i = 0; i < keys; i++) {
                assertSuccess(ksp.write(i, bytes(VALUE_SIZE, round)));
            }
        }

        assertTrue(fileLength(path) <= afterFirstRound + PAGE, "file must not keep growing");
        PBKeyStorageProvider<Integer> reopened = new PBKeyStorageProvider<>(path, Integer.class);
        for (int i = 0; i < keys; i++) {
            assertArrayEquals(bytes(VALUE_SIZE, 20), data(reopened.read(i)));
        }
        assertEquals(keys, dataArray(reopened.read()).length);
    }
}
