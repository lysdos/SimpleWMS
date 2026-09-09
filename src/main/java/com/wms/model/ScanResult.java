package com.wms.model;

/**
 * UHF RFID 出库扫码:扫码结果 DTO。
 * matched=true → 真实扣减了 1 件
 * matched=false → 没动库存,reason 给前端做语音/UI 报警
 */
public class ScanResult {
    public final boolean matched;
    public final String reason;
    public final Integer itemId;
    public final String sku;
    public final String epc;
    public final Integer scannedQty;
    public final Integer orderId;

    private ScanResult(boolean m, String r, Integer i, String s, String e, Integer sq, Integer oid) {
        matched = m; reason = r; itemId = i; sku = s; epc = e; scannedQty = sq; orderId = oid;
    }

    public static ScanResult ok(int lineId, String sku, String epc, int scannedQty, int orderId) {
        return new ScanResult(true, "ok", lineId, sku, epc, scannedQty, orderId);
    }
    public static ScanResult fail(String reason) {
        return new ScanResult(false, reason, null, null, null, null, null);
    }
}
