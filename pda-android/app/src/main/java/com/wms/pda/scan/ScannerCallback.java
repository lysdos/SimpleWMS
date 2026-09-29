package com.wms.pda.scan;

/** 扫码结果回调。 */
public interface ScannerCallback {
    void onScanResult(ScanResult result);
    void onError(int code, String msg);
}