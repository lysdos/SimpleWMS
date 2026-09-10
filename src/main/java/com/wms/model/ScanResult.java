package com.wms.model;

/**
 * UHF RFID 出库扫码:扫码结果 DTO。
 * matched=true → 扫码确认成功(不扣库存,等 commit)
 * matched=false → 失败,reason 给前端做语音/UI 报警
 *
 * alreadyScanned=true 表示"该 EPC 本单此前已确认过"——这**不是错误**:
 * 读卡器是循环读卡的,同一个标签会被反复读到;终端重启后也会重扫。
 * 这类结果按"已扫"处理(matched 同为 true),只额外带一个标记,
 * 避免把已确认的进度打回,也避免给操作员误报报警音。
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
    /** true = 本单此前已确认过该 EPC(重复扫描的幂等返回) */
    public boolean alreadyScanned;

    private ScanResult(boolean m, String r, Integer i, String s, String e, Integer sq, Integer oid) {
        matched = m; reason = r; itemId = i; sku = s; epc = e; scannedQty = sq; orderId = oid;
    }

    public static ScanResult ok(int lineId, String sku, String epc, int scannedQty, int orderId) {
        return new ScanResult(true, "ok", lineId, sku, epc, scannedQty, orderId);
    }

    /** 本单已确认过该 EPC:语义上是"已扫"(matched=true),附 alreadyScanned 标记 */
    public static ScanResult already(int lineId, String sku, String epc, int scannedQty, int orderId) {
        ScanResult r = new ScanResult(true, "EPC 已扫过(本单)", lineId, sku, epc, scannedQty, orderId);
        r.alreadyScanned = true;
        return r;
    }

    public static ScanResult fail(String reason) {
        return new ScanResult(false, reason, null, null, null, null, null);
    }

    public ScanResult withReservedCount(int n) { this.reservedCount = n; return this; }
    public ScanResult withStillNeeded(int n) { this.stillNeeded = n; return this; }
}
