package com.wms.pda.scan;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.rscja.barcode.BarcodeDecoder;
import com.rscja.barcode.BarcodeFactory;
import com.rscja.deviceapi.entity.BarcodeEntity;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * CHAINWAY 硬件条码扫把头实现。
 * PDA 内置物理扫码头,比 CameraX + ML Kit 快 ~5x。
 *
 * 使用模式:
 *   init(ctx) → 打开硬件 SDK
 *   startScan(BARCODE) → 开始监听扫把头
 *   stopScan() → 停止
 *   release() → 释放
 */
public class BarcodeHardwareScannerImpl implements ScannerService {

    private static final String TAG = "BarcodeHwScanner";
    private static final long DEBOUNCE_MS = 1500L;

    private final AtomicBoolean scanning = new AtomicBoolean(false);
    private volatile boolean ready = false;
    private ScannerCallback cb;
    private BarcodeDecoder decoder;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private long lastEmitMs = 0L;
    private String lastEmitCode = "";

    @Override
    public void init(Context ctx) throws Exception {
        try {
            BarcodeDecoder d = BarcodeFactory.getInstance().getBarcodeDecoder();
            boolean ok = d.open(ctx);
            if (!ok) throw new Exception("BarcodeDecoder.open() 返回 false");
            decoder = d;
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

        decoder.setDecodeCallback(new BarcodeDecoder.DecodeCallback() {
            @Override
            public void onDecodeComplete(BarcodeEntity barcodeEntity) {
                if (!scanning.get()) return;
                if (barcodeEntity.getResultCode() != BarcodeDecoder.DECODE_SUCCESS) return;
                String raw = barcodeEntity.getBarcodeData();
                if (raw == null || raw.isEmpty()) return;
                long now = System.currentTimeMillis();
                synchronized (BarcodeHardwareScannerImpl.this) {
                    if (raw.equals(lastEmitCode) && now - lastEmitMs < DEBOUNCE_MS) {
                        return; // 去重
                    }
                    lastEmitCode = raw;
                    lastEmitMs = now;
                }
                // 切主线程回调
                mainHandler.post(() -> {
                    if (cb != null) {
                        scanning.set(false);
                        cb.onScanResult(new ScanResult(
                                raw, ScanType.BARCODE, null,
                                System.currentTimeMillis()));
                    }
                });
            }
        });
        decoder.startScan();
        Log.i(TAG, "hardware barcode scan started");
    }

    @Override
    public void stopScan() {
        scanning.set(false);
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
}
