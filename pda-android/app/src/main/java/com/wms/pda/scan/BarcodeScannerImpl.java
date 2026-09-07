package com.wms.pda.scan;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.util.Log;

/**
 * 光学条码实现 - 监听厂家扫码广播。
 * 厂家广播 action 通常为 "com.example.uhf.action.BARCODE"(参考 uhf-uart-demo)。
 */
public class BarcodeScannerImpl implements ScannerService {

    private static final String TAG = "BarcodeScannerImpl";
    private static final String ACTION_BARCODE = "com.example.uhf.action.BARCODE";
    private static final String EXTRA_BARCODE = "BARCODE";

    private Context appCtx;
    private ScannerCallback cb;
    private BroadcastReceiver receiver;
    private boolean ready;

    @Override
    public void init(Context ctx) {
        appCtx = ctx.getApplicationContext();
        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                if (intent == null) return;
                String code = intent.getStringExtra(EXTRA_BARCODE);
                if (code != null && !code.isEmpty() && cb != null) {
                    cb.onScanResult(new ScanResult(
                        code, ScanType.BARCODE, null, System.currentTimeMillis()));
                }
            }
        };
        IntentFilter filter = new IntentFilter(ACTION_BARCODE);
        appCtx.registerReceiver(receiver, filter);
        ready = true;
        Log.i(TAG, "Barcode listener registered");
    }

    @Override
    public void release() {
        if (receiver != null) {
            try { appCtx.unregisterReceiver(receiver); } catch (Exception e) { Log.w(TAG, e); }
            receiver = null;
        }
        ready = false;
    }

    @Override
    public void startScan(ScanType type) {
        if (type != ScanType.BARCODE) return;
        if (!ready) {
            if (cb != null) cb.onError(-100, "扫码设备未就绪");
            return;
        }
        // 光学扫码是「按扫描键」触发,无需主动 startScan
        // 这里仅做超时提示
        new Thread(() -> {
            try { Thread.sleep(5_000); }
            catch (InterruptedException ignored) {}
            if (cb != null) cb.onTimeout();
        }, "BarcodeTimeout").start();
    }

    @Override
    public void stopScan() { /* 光学扫码无后台线程 */ }

    @Override public boolean isReady() { return ready; }
    @Override public void setCallback(ScannerCallback cb) { this.cb = cb; }
}