package com.wms.pda.scan;

import android.content.Context;
import android.util.Log;

/**
 * 扫码门面 - UI 层只调这里,不知道底层是哪个厂。
 *
 * 策略:
 *   RFID_EPC → UhfScannerImpl(CHAINWAY RFIDWithUHFUART)
 *   BARCODE  → BarcodeHardwareScannerImpl(CHAINWAY 物理扫把头)
 *               失败降级 → BarcodeScannerImpl(CameraX + ML Kit)
 */
public class ScanManager {

    private static final String TAG = "ScanManager";
    private static volatile ScanManager INSTANCE;

    private final Context appCtx;
    private final UhfScannerImpl uhf;
    private final BarcodeScannerImpl barcodeCamera;
    private final BarcodeHardwareScannerImpl barcodeHw;

    /** 条码是否只能走摄像头兜底。null = 还没探测,见 {@link #usesCameraBarcode()}。 */
    private Boolean cameraBarcode;

    private ScanManager(Context ctx) {
        this.appCtx = ctx.getApplicationContext();
        this.uhf = new UhfScannerImpl();
        this.barcodeCamera = new BarcodeScannerImpl();
        this.barcodeHw = new BarcodeHardwareScannerImpl();
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
        if (type == ScanType.RFID_EPC) {
            startUhf(cb);
        } else {
            startBarcode(cb);
        }
    }

    private void startUhf(ScannerCallback cb) {
        if (!uhf.isReady()) {
            try {
                uhf.init(appCtx);
            } catch (Throwable e) {
                Log.e(TAG, "UHF init failed", e);
                cb.onError(-100, "UHF 未就绪: " + e.getMessage());
                return;
            }
        }
        uhf.setCallback(cb);
        uhf.startScan(ScanType.RFID_EPC);
    }

    /**
     * 条码扫描:有硬件扫把头就用它,没有才降级到 CameraX(摄像头预览由 UI 侧的
     * {@link #attachBarcodeCamera} 绑定)。走哪条路探测一次就定下来,见 {@link #usesCameraBarcode()}。
     */
    private void startBarcode(ScannerCallback cb) {
        if (usesCameraBarcode()) {
            Log.i(TAG, "barcode: using camera fallback");
            barcodeCamera.setCallback(cb);
            barcodeCamera.startScan(ScanType.BARCODE);
            return;
        }
        Log.i(TAG, "barcode: using hardware scanner");
        barcodeHw.setCallback(cb);
        barcodeHw.startScan(ScanType.BARCODE);
    }

    /**
     * 条码是否只能走摄像头(= 本机没有硬件扫把头)。
     *
     * **探测一次、结果定下来**,不每次重探:厂商 SDK 在没有该硬件的设备上会出现
     * "第一次 init 失败、后面某次却成功"。判定来回翻的现场表现是 —— 第一次点「条码扫码」
     * 弹出摄像头面板,之后再也弹不出来(第二次被判成"有扫码头",走原地流程,于是什么也扫不到)。
     * 顺带也免掉每次点击都白等一次 `open()`(没有该硬件时要等它失败)。
     */
    public boolean usesCameraBarcode() {
        if (cameraBarcode == null) {
            ensureReady(ScanType.BARCODE);
            cameraBarcode = !barcodeHw.isReady();
        }
        return cameraBarcode;
    }

    public void stopScan() {
        if (uhf.isReady()) uhf.stopScan();
        if (barcodeHw.isReady()) barcodeHw.stopScan();
        if (barcodeCamera.isReady()) barcodeCamera.stopScan();
    }

    /**
     * 由 Activity 在摄像头页调,绑定 CameraX 预览 + ML Kit 识别。
     * 当硬件扫把头不可用时,需要调此方法绑定摄像头预览。
     */
    public void attachBarcodeCamera(androidx.lifecycle.LifecycleOwner owner,
                                    androidx.camera.view.PreviewView previewView) {
        barcodeCamera.attachCamera(owner, previewView);
    }

    /** 释放摄像头,Activity onPause 时调。 */
    public void detachBarcodeCamera() {
        barcodeCamera.detachCamera();
    }

    /**
     * 主动初始化硬件(不启动扫描)。失败抛 RuntimeException,UI 层捕获并 toast。
     */
    public void ensureReady(ScanType type) {
        ScannerService svc = (type == ScanType.RFID_EPC) ? uhf : barcodeHw;
        if (!svc.isReady()) {
            try {
                svc.init(appCtx);
            } catch (Exception e) {
                // 硬件条码 init 失败不抛异常(降级摄像头)
                if (type == ScanType.RFID_EPC) throw new RuntimeException(e);
                Log.w(TAG, "hardware barcode ensureReady failed, will fallback", e);
            }
        }
    }

    /** 释放(应用退出时调用)。 */
    public void release() {
        uhf.release();
        barcodeHw.release();
        barcodeCamera.release();
        cameraBarcode = null;   // 扫码头已经关掉,下次要重新探测
    }

    /** 设备状态 - 用于 UI 显示。 */
    public boolean isUhfReady() { return uhf.isReady(); }
    public boolean isBarcodeReady() { return barcodeHw.isReady() || barcodeCamera.isReady(); }
}
