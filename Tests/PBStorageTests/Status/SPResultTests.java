package PBStorageTests.Status;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import StorageStatus.SP.SPFail;
import StorageStatus.SP.SPResult;
import StorageStatus.SP.SPSuccess;

public class SPResultTests {

    @Test
    public void resultWithoutData() {
        SPResult result = SPResult.of(SPSuccess.DELETE);

        assertEquals(SPSuccess.DELETE, result.getStatus());
        assertTrue(result.hasSucceeded());
        assertNull(result.getData());
        assertNull(result.getDataArray());
    }

    @Test
    public void resultWithData() {
        byte[] data = { 1, 2, 3 };
        SPResult result = SPResult.of(SPSuccess.READ, data);

        assertEquals(SPSuccess.READ, result.getStatus());
        assertSame(data, result.getData());
        assertNull(result.getDataArray());
    }

    @Test
    public void resultWithSeveralRecordsExposesTheFirstAsData() {
        byte[][] records = { { 1 }, { 2 } };
        SPResult result = SPResult.ofAll(SPSuccess.READ, records);

        assertSame(records, result.getDataArray());
        assertSame(records[0], result.getData());
        assertNull(SPResult.ofAll(SPSuccess.READ, new byte[0][]).getData(), "no records, no first record");
    }

    @Test
    public void failuresAreNotSuccess() {
        SPResult result = SPResult.of(SPFail.KEY_NOT_FOUND);

        assertFalse(result.hasSucceeded());
        assertEquals(SPFail.KEY_NOT_FOUND, result.getStatus());
    }

    @Test
    public void hasSucceededFollowsTheStatus() {
        for (SPSuccess success : SPSuccess.values()) {
            assertTrue(SPResult.of(success).hasSucceeded(), success.name());
            assertTrue(SPResult.of(success, new byte[] { 1 }).hasSucceeded(), success.name());
        }
        for (SPFail fail : SPFail.values()) {
            assertFalse(SPResult.of(fail).hasSucceeded(), fail.name());
            assertFalse(SPResult.ofAll(fail, null).hasSucceeded(), fail.name());
        }
    }

    @Test
    public void successesAndPlainFailuresHaveNoError() {
        assertNull(SPResult.of(SPSuccess.READ, new byte[] { 1 }).getError());
        assertNull(SPResult.ofAll(SPSuccess.READ, new byte[1][]).getError());
        assertNull(SPResult.of(SPFail.KEY_NOT_FOUND).getError());
    }

    @Test
    public void failureCarriesItsCause() {
        java.io.IOException cause = new java.io.IOException("No space left on device");
        SPResult result = SPResult.ofError(SPFail.WRITE_ERROR, cause);

        assertFalse(result.hasSucceeded());
        assertEquals(SPFail.WRITE_ERROR, result.getStatus());
        assertSame(cause, result.getError());
        assertNull(result.getData());
        assertEquals("WRITE_ERROR (java.io.IOException: No space left on device)", result.toString());
        assertThrows(IllegalArgumentException.class, () -> SPResult.ofError(null, cause));
    }

    @Test
    public void statusIsRequired() {
        assertThrows(IllegalArgumentException.class, () -> SPResult.of(null));
        assertThrows(IllegalArgumentException.class, () -> SPResult.of(null, new byte[] { 1 }));
    }

    @Test
    public void everyCallMakesANewResult() {
        assertNotSame(SPResult.of(SPSuccess.WRITE, new byte[] { 1 }), SPResult.of(SPSuccess.WRITE, new byte[] { 1 }));
    }

    @Test
    public void toStringShowsStatusAndSize() {
        assertEquals("KEY_NOT_FOUND", SPResult.of(SPFail.KEY_NOT_FOUND).toString());
        assertEquals("READ (3 bytes)", SPResult.of(SPSuccess.READ, new byte[3]).toString());
        assertEquals("READ (2 records)", SPResult.ofAll(SPSuccess.READ, new byte[2][]).toString());
    }
}
