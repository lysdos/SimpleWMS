package com.wms.pda.scan;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.rscja.barcode.BarcodeDecoder;
import com.rscja.barcode.BarcodeFactory;
import com.rscja.barcode.BarcodeUtility;
import com.rscja.deviceapi.entity.BarcodeEntity;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * CHAINWAY 硬件条码扫把头实现。
 * PDA 内置物理扫码头,比 CameraX + ML Kit 快 ~5x。
 *
 * CHAINWAY HC72 等 MTK 平台设备的扫描头走 KeyboardEmulator(键盘助手模式),
 * 通过广播 com.rscja.scanner.action.SCAN_RESULT_BROADCAST 传递扫码结果,
 * 而非 BarcodeDecoder.setDecodeCallback()。
 * 本实现同时注册广播接收器 + setDecodeCallback,兼容两种机制。
 */
public class BarcodeHardwareScannerImpl implements ScannerService {

    private static final String TAG = "BarcodeHwScanner";
    private static final long DEBOUNCE_MS = 1500L;
    private static final String ACTION_SCAN_RESULT = "com.rscja.scanner.action.SCAN_RESULT_BROADCAST";
    private static final String EXTRA_DATA = "data";
    private static final String EXTRA_SCAN_STATE = "SCAN_STATE";

    private final AtomicBoolean scanning = new AtomicBoolean(false);
    private volatile boolean ready = false;
    private ScannerCallback cb;
    private BarcodeDecoder decoder;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private long lastEmitMs = 0L;
    private String lastEmitCode = "";
    private Context appCtx;
    private volatile boolean receiverRegistered = false;

    /** 广播接收器:接收 CHAINWAY KeyboardEmulator 扫码结果 */
    private final BroadcastReceiver scanReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!scanning.get()) return;
            String raw = intent.getStringExtra(EXTRA_DATA);
            if (raw == null || raw.isEmpty()) return;
            Log.d(TAG, "broadcast received barcode: " + raw);
            emitResult(raw);
        }
    };

    @Override
    public void init(Context ctx) throws Exception {
        try {
            appCtx = ctx.getApplicationContext();
            BarcodeDecoder d = BarcodeFactory.getInstance().getBarcodeDecoder();
            boolean ok = d.open(ctx);
            if (!ok) throw new Exception("BarcodeDecoder.open() 返回 false");
            decoder = d;
            // 关闭 SDK 自带成功音,由 BeepUtil 统一管理,避免双重响
            try {
                BarcodeUtility.getInstance().enablePlaySuccessSound(ctx, false);
            } catch (Throwable ignored) {}
            ready = true;
            Log.i(TAG, "hardware barcode scanner init success");
        } catch (Throwable t) {
            Log.e(TAG, "hardware barcode scanner init failed", t);
            ready = false;
            throw new Exception("硬件扫把头初始化失败: " + t.getMessage(), t);
        }
    }

    @Override
    public void release() {
        stopScan();
        if (decoder != null) {
            try {
                decoder.setDecodeCallback(null);
                decoder.close();
            } catch (Throwable t) {
                Log.w(TAG, "release failed", t);
            }
            decoder = null;
        }
        ready = false;
        lastEmitMs = 0L;
        lastEmitCode = "";
    }

    @Override
    public void startScan(ScanType type) {
        if (type != ScanType.BARCODE) return;
        if (!isReady()) {
            if (cb != null) cb.onError(-100, "硬件扫把头未就绪");
            return;
        }
        stopScan();
        scanning.set(true);
        lastEmitMs = 0L;
        lastEmitCode = "";

        // 注册广播接收器(兼容 CHAINWAY HC72 等走 KeyboardEmulator 的设备)
        registerReceiverIfNeeded();

        // 同时设置 DecodeCallback(兼容直接回调的设备)
        decoder.setDecodeCallback(new BarcodeDecoder.DecodeCallback() {
            @Override
            public void onDecodeComplete(BarcodeEntity barcodeEntity) {
                if (!scanning.get()) return;
                if (barcodeEntity.getResultCode() != BarcodeDecoder.DECODE_SUCCESS) return;
                String raw = barcodeEntity.getBarcodeData();
                if (raw == null || raw.isEmpty()) return;
                Log.d(TAG, "callback received barcode: " + raw);
                emitResult(raw);
            }
        });
        decoder.startScan();
        Log.i(TAG, "hardware barcode scan started");
    }

    @Override
    public void stopScan() {
        scanning.set(false);
        unregisterReceiverIfNeeded();
        if (decoder != null) {
            try {
                decoder.stopScan();
                decoder.setDecodeCallback(null);
            } catch (Throwable t) {
                Log.w(TAG, "stopScan failed", t);
            }
        }
        synchronized (this) {
            lastEmitMs = 0L;
            lastEmitCode = "";
        }
    }

    @Override
    public boolean isReady() {
        return ready && decoder != null;
    }

    @Override
    public void setCallback(ScannerCallback cb) {
        this.cb = cb;
    }

    /** 去重 + 切主线程回调(广播和 DecodeCallback 共用) */
    private void emitResult(String raw) {
        long now = System.currentTimeMillis();
        synchronized (BarcodeHardwareScannerImpl.this) {
            if (raw.equals(lastEmitCode) && now - lastEmitMs < DEBOUNCE_MS) {
                return; // 同一根条码1500ms内不重复上报
            }
            lastEmitCode = raw;
            lastEmitMs = now;
        }
        // 先设 scanning=false 防止重复触发,再回调
        scanning.set(false);
        mainHandler.post(() -> {
            if (cb != null) {
                cb.onScanResult(new ScanResult(
                        raw, ScanType.BARCODE, null,
                        System.currentTimeMillis()));
            }
        });
        // CHAINWAY decoder 第一次解码后进入 idle, 延迟重启以避免立即读到同一根条码形成循环
        mainHandler.postDelayed(() -> {
            if (isReady() && !scanning.get()) {
                scanning.set(true);
                // 重新注册广播接收器 + 重新启动 decoder
                registerReceiverIfNeeded();
                decoder.setDecodeCallback(new BarcodeDecoder.DecodeCallback() {
                    @Override
                    public void onDecodeComplete(BarcodeEntity entity) {
                        if (!scanning.get()) return;
                        if (entity.getResultCode() != BarcodeDecoder.DECODE_SUCCESS) return;
                        String r = entity.getBarcodeData();
                        if (r == null || r.isEmpty()) return;
                        Log.d(TAG, "callback received barcode: " + r);
                        emitResult(r);
                    }
                });
                decoder.startScan();
                Log.d(TAG, "decoder restarted after delay");
            }
        }, 500L); // 500ms 延迟,给用户时间移开扫描头
    }

    private void registerReceiverIfNeeded() {
        if (receiverRegistered || appCtx == null) return;
        try {
            IntentFilter filter = new IntentFilter(ACTION_SCAN_RESULT);
            appCtx.registerReceiver(scanReceiver, filter);
            receiverRegistered = true;
            Log.d(TAG, "scan broadcast receiver registered");
        } catch (Throwable t) {
            Log.w(TAG, "registerReceiver failed", t);
        }
    }

    private void unregisterReceiverIfNeeded() {
        if (!receiverRegistered || appCtx == null) return;
        try {
            appCtx.unregisterReceiver(scanReceiver);
        } catch (Throwable t) {
            Log.w(TAG, "unregisterReceiver failed", t);
        }
        receiverRegistered = false;
    }
}
