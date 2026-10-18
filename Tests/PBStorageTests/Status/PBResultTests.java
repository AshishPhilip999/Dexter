package PBStorageTests.Status;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import StorageStatus.PB.PBFail;
import StorageStatus.PB.PBResult;
import StorageStatus.PB.PBSuccess;

public class PBResultTests {

    @Test
    public void resultWithoutData() {
        PBResult result = PBResult.of(PBSuccess.DELETE);

        assertEquals(PBSuccess.DELETE, result.getStatus());
        assertTrue(result.hasSucceeded());
        assertNull(result.getData());
        assertNull(result.getDataArray());
    }

    @Test
    public void resultWithData() {
        byte[] data = { 1, 2, 3 };
        PBResult result = PBResult.of(PBSuccess.READ, data);

        assertEquals(PBSuccess.READ, result.getStatus());
        assertSame(data, result.getData());
        assertNull(result.getDataArray());
    }

    @Test
    public void resultWithSeveralRecordsExposesTheFirstAsData() {
        byte[][] records = { { 1 }, { 2 } };
        PBResult result = PBResult.ofAll(PBSuccess.READ, records);

        assertSame(records, result.getDataArray());
        assertSame(records[0], result.getData());
        assertNull(PBResult.ofAll(PBSuccess.READ, new byte[0][]).getData(), "no records, no first record");
    }

    @Test
    public void failuresAreNotSuccess() {
        PBResult result = PBResult.of(PBFail.KEY_NOT_FOUND);

        assertFalse(result.hasSucceeded());
        assertEquals(PBFail.KEY_NOT_FOUND, result.getStatus());
    }

    @Test
    public void hasSucceededFollowsTheStatus() {
        for (PBSuccess success : PBSuccess.values()) {
            assertTrue(PBResult.of(success).hasSucceeded(), success.name());
            assertTrue(PBResult.of(success, new byte[] { 1 }).hasSucceeded(), success.name());
        }
        for (PBFail fail : PBFail.values()) {
            assertFalse(PBResult.of(fail).hasSucceeded(), fail.name());
            assertFalse(PBResult.ofAll(fail, null).hasSucceeded(), fail.name());
        }
    }

    @Test
    public void successesAndPlainFailuresHaveNoError() {
        assertNull(PBResult.of(PBSuccess.READ, new byte[] { 1 }).getError());
        assertNull(PBResult.ofAll(PBSuccess.READ, new byte[1][]).getError());
        assertNull(PBResult.of(PBFail.KEY_NOT_FOUND).getError());
    }

    @Test
    public void failureCarriesItsCause() {
        java.io.IOException cause = new java.io.IOException("No space left on device");
        PBResult result = PBResult.ofError(PBFail.WRITE_ERROR, cause);

        assertFalse(result.hasSucceeded());
        assertEquals(PBFail.WRITE_ERROR, result.getStatus());
        assertSame(cause, result.getError());
        assertNull(result.getData());
        assertEquals("WRITE_ERROR (java.io.IOException: No space left on device)", result.toString());
        assertThrows(IllegalArgumentException.class, () -> PBResult.ofError(null, cause));
    }

    @Test
    public void statusIsRequired() {
        assertThrows(IllegalArgumentException.class, () -> PBResult.of(null));
        assertThrows(IllegalArgumentException.class, () -> PBResult.of(null, new byte[] { 1 }));
    }

    @Test
    public void everyCallMakesANewResult() {
        assertNotSame(PBResult.of(PBSuccess.WRITE, new byte[] { 1 }), PBResult.of(PBSuccess.WRITE, new byte[] { 1 }));
    }

    @Test
    public void toStringShowsStatusAndSize() {
        assertEquals("KEY_NOT_FOUND", PBResult.of(PBFail.KEY_NOT_FOUND).toString());
        assertEquals("READ (3 bytes)", PBResult.of(PBSuccess.READ, new byte[3]).toString());
        assertEquals("READ (2 records)", PBResult.ofAll(PBSuccess.READ, new byte[2][]).toString());
    }
}
