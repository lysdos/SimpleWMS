package com.wms.model;

/**
 * UHF RFID 出库扫码:扫码结果 DTO。
 * matched=true → 扫码确认成功(不扣库存,等 commit)
 * matched=false → 失败,reason 给前端做语音/UI 报警
 */
public class ScanResult {
    public final boolean matched;
    public final String reason;
    public final Integer itemId;
    public final String sku;
    public final String epc;
    public final Integer scannedQty;
    public final Integer orderId;
    /** 本出库单该 SKU 锁定的总件数(用于 UI 展示 "已扫 X / 共锁定 Y") */
    public Integer reservedCount;
    /** 还差几件 = reservedCount - scannedQty */
    public Integer stillNeeded;

    private ScanResult(boolean m, String r, Integer i, String s, String e, Integer sq, Integer oid) {
        matched = m; reason = r; itemId = i; sku = s; epc = e; scannedQty = sq; orderId = oid;
    }

    public static ScanResult ok(int lineId, String sku, String epc, int scannedQty, int orderId) {
        return new ScanResult(true, "ok", lineId, sku, epc, scannedQty, orderId);
    }
    public static ScanResult fail(String reason) {
        return new ScanResult(false, reason, null, null, null, null, null);
    }

    public ScanResult withReservedCount(int n) { this.reservedCount = n; return this; }
    public ScanResult withStillNeeded(int n) { this.stillNeeded = n; return this; }
}
