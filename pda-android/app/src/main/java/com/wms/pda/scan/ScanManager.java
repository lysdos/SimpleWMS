package com.wms.pda.scan;

import android.content.Context;
import android.util.Log;

/** 扫码门面 - UI 层只调这里,不知道底层是哪个厂。 */
public class ScanManager {

    private static final String TAG = "ScanManager";
    private static volatile ScanManager INSTANCE;

    private final Context appCtx;
    private final UhfScannerImpl uhf;
    private final BarcodeScannerImpl barcode;

    private ScanManager(Context ctx) {
        this.appCtx = ctx.getApplicationContext();
        this.uhf = new UhfScannerImpl();
        this.barcode = new BarcodeScannerImpl();
    }

    public static ScanManager get(Context ctx) {
        if (INSTANCE == null) {
            synchronized (ScanManager.class) {
                if (INSTANCE == null) {
                    INSTANCE = new ScanManager(ctx);
                }
            }
        }
        return INSTANCE;
    }

    /** 启动一次扫描。type 决定走 UHF 还是光学。 */
    public void startScan(ScanType type, ScannerCallback cb) {
        ScannerService svc = (type == ScanType.RFID_EPC) ? uhf : barcode;
        if (!svc.isReady()) {
            try {
                svc.init(appCtx);
            } catch (Throwable e) {
                Log.e(TAG, "init failed", e);
                cb.onError(-100, "扫码设备未就绪: " + e.getMessage());
                return;
            }
        }
        svc.setCallback(cb);
        svc.startScan(type);
    }

    public void stopScan() {
        if (uhf.isReady()) uhf.stopScan();
        if (barcode.isReady()) barcode.stopScan();
    }

    /**
     * 主动初始化硬件(不启动扫描)。失败抛 RuntimeException,UI 层捕获并 toast。
     * 用于 HomeActivity 进入时主动探测,而不是等第一次扫码才暴露问题。
     */
    public void ensureReady(ScanType type) {
        ScannerService svc = (type == ScanType.RFID_EPC) ? uhf : barcode;
        if (!svc.isReady()) {
            try {
                svc.init(appCtx);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    /** 释放(应用退出时调用)。 */
    public void release() {
        uhf.release();
        barcode.release();
    }

    /** 设备状态 - 用于 UI 显示。 */
    public boolean isUhfReady() { return uhf.isReady(); }
    public boolean isBarcodeReady() { return barcode.isReady(); }
}