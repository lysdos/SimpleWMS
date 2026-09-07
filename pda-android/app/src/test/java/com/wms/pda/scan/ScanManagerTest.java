package com.wms.pda.scan;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class ScanManagerTest {

    @Test
    public void scanType_enumValues() {
        assertEquals(2, ScanType.values().length);
        assertNotNull(ScanType.valueOf("BARCODE"));
        assertNotNull(ScanType.valueOf("RFID_EPC"));
    }

    @Test
    public void scanResult_fieldsImmutable() {
        ScanResult r = new ScanResult("ABC123", ScanType.BARCODE, null, 1000L);
        assertEquals("ABC123", r.getCode());
        assertEquals(ScanType.BARCODE, r.getType());
        assertEquals(1000L, r.getTimestamp());
    }

    @Test(expected = NullPointerException.class)
    public void scanResult_nullCodeThrows() {
        new ScanResult(null, ScanType.BARCODE, null, 0L);
    }
}