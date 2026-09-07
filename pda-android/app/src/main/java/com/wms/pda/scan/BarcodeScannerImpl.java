package com.wms.pda.scan;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.util.Log;

/**
 * 光学条码实现 - 监听厂家扫码广播的兼容层。
 *
 * 当前 PDA(优博讯 UROVO)暂无独立 barcode SDK,本实现仅尝试注册通用扫码广播
 * ({@code com.ubx.action.BARCODE_SCAN} / 行业通用 {@code com.android.barcode.action.SCAN_DATA})。
 * 真实光学扫码需要参考 UROVO 文档接入对应 SDK;本版本 RFID 是主扫码路径。
 */
public class BarcodeScannerImpl implements ScannerService {

    private static final String TAG = "BarcodeScannerImpl";
    private static final String[] SCAN_ACTIONS = {
            "com.ubx.action.BARCODE_SCAN",   // UROVO PDA 通用扫码广播
            "com.android.barcode.action.SCAN_DATA"  // 行业兼容
    };

    private Context appCtx;
    private ScannerCallback cb;
    private BroadcastReceiver receiver;
    private volatile boolean ready;

    @Override
    public void init(Context ctx) {
        appCtx = ctx.getApplicationContext();
        try {
            receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent intent) {
                    if (intent == null) return;
                    String code = intent.getStringExtra("data")
                            != null ? intent.getStringExtra("data")
                            : intent.getStringExtra("BARCODE");
                    if (code != null && !code.isEmpty() && cb != null) {
                        cb.onScanResult(new ScanResult(
                                code, ScanType.BARCODE, null, System.currentTimeMillis()));
                    }
                }
            };
            IntentFilter filter = new IntentFilter();
            for (String action : SCAN_ACTIONS) filter.addAction(action);
            appCtx.registerReceiver(receiver, filter);
            ready = true;
            Log.i(TAG, "Barcode broadcast listener registered (UROVO compatible)");
        } catch (Throwable t) {
            Log.w(TAG, "Barcode init 失败,光学扫码不可用", t);
            ready = false;
        }
    }

    @Override
    public void release() {
        if (receiver != null) {
            try { appCtx.unregisterReceiver(receiver); } catch (Exception ignored) {}
            receiver = null;
        }
        ready = false;
    }

    @Override
    public void startScan(ScanType type) {
        if (type != ScanType.BARCODE) return;
        if (!ready) {
            if (cb != null) cb.onError(-100, "光学扫码未就绪(本 PDA 未配 Barcode SDK,仅 RFID 可用)");
            return;
        }
        // 广播是按键触发的,startScan 不必真起线程;超时只是兜底
        new Thread(() -> {
            try { Thread.sleep(5_000); } catch (InterruptedException ignored) {}
            if (cb != null) cb.onTimeout();
        }, "BarcodeTimeout").start();
    }

    @Override public void stopScan() { /* 广播无后台线程 */ }
    @Override public boolean isReady() { return ready; }
    @Override public void setCallback(ScannerCallback cb) { this.cb = cb; }
}
