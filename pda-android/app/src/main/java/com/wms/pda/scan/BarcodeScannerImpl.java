package com.wms.pda.scan;

import android.content.Context;
import android.util.Log;

import com.rscja.barcode.BarcodeDecoder;
import com.rscja.barcode.BarcodeFactory;

/**
 * 光学条码实现 - 走东集 iData 真实 SDK(BarcodeFactory / BarcodeDecoder)。
 * decoder.open() 打开摄像头扫码模组;按扫描键或 startScan() 后由 DecodeCallback 回调结果。
 */
public class BarcodeScannerImpl implements ScannerService {

    private static final String TAG = "BarcodeScannerImpl";
    private BarcodeDecoder decoder;
    private ScannerCallback cb;
    private Context appCtx;
    private boolean ready;

    @Override
    public void init(Context ctx) {
        appCtx = ctx.getApplicationContext();
        try {
            decoder = BarcodeFactory.getInstance().getBarcodeDecoder();
            if (decoder == null || !decoder.open(appCtx)) {
                throw new IllegalStateException("扫码模块打开失败");
            }
            decoder.setDecodeCallback(result -> {
                String data = (result == null) ? null : result.getBarcodeData();
                if (data != null && !data.isEmpty() && cb != null) {
                    cb.onScanResult(new ScanResult(data, ScanType.BARCODE, null,
                        System.currentTimeMillis()));
                }
            });
            ready = true;
            Log.i(TAG, "Barcode decoder opened");
        } catch (Throwable e) {
            ready = false;
            Log.e(TAG, "Barcode init failed", e);
        }
    }

    @Override
    public void release() {
        if (decoder != null) {
            try { decoder.stopScan(); } catch (Exception ignored) {}
            try { decoder.close(); } catch (Exception ignored) {}
            decoder = null;
        }
        ready = false;
    }

    @Override
    public void startScan(ScanType type) {
        if (type != ScanType.BARCODE) return;
        if (!ready || decoder == null) {
            if (cb != null) cb.onError(-100, "扫码设备未就绪");
            return;
        }
        boolean ok;
        try {
            ok = decoder.startScan();
        } catch (Exception e) {
            ok = false;
        }
        if (!ok) {
            if (cb != null) cb.onError(-1, "启动扫码失败");
        }
        // 光学扫码是硬件按键/startScan 触发,回调里出结果;无后台超时线程
    }

    @Override public void stopScan() {
        if (decoder != null) { try { decoder.stopScan(); } catch (Exception ignored) {} }
    }
    @Override public boolean isReady() { return ready; }
    @Override public void setCallback(ScannerCallback cb) { this.cb = cb; }
}
