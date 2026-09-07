package com.wms.pda.scan;

import java.util.Objects;

/** 扫码/RFID 扫描结果。 */
public final class ScanResult {
    private final String code;
    private final ScanType type;
    private final String rssi;       // 光学场景为 null
    private final long timestamp;

    public ScanResult(String code, ScanType type, String rssi, long timestamp) {
        this.code = Objects.requireNonNull(code, "code");
        this.type = Objects.requireNonNull(type, "type");
        this.rssi = rssi;
        this.timestamp = timestamp;
    }

    public String getCode() { return code; }
    public ScanType getType() { return type; }
    public String getRssi() { return rssi; }
    public long getTimestamp() { return timestamp; }

    @Override public String toString() {
        return type + "(" + code + (rssi != null ? ",rssi=" + rssi : "") + ")";
    }
}