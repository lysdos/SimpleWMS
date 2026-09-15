package com.wms.pda.data.model;

/**
 * UHF EPC 扫码结果(镜像后端 /orders/{id}/scan-epc 的 ScanResult)。
 * matched=false 时 reason 为失败原因;alreadyScanned=true 表示重复扫(非错误)。
 */
public class EpcScanResult {
    public boolean matched;
    public boolean alreadyScanned;
    public String reason;
    public String epc;
    public String sku;
    public Integer scannedQty;
    public Integer reservedCount;
    public Integer stillNeeded;
}
