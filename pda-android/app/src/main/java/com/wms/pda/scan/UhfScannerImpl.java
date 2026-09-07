package com.wms.pda.scan;

import android.content.Context;
import android.util.Log;

import com.example.uhf.UhfInfo;
import com.example.uhf.Reader;

import java.util.HashMap;
import java.util.List;

/**
 * UHF RFID 实现 - 走厂商 SDK。
 * 厂商 SDK 参考 uhf-uart-demo/ (com.example.uhf.UhfInfo + Reader)。
 * 若厂商 SDK 类不存在(普通 Android 设备),init() 抛异常,UI 层降级。
 */
public class UhfScannerImpl implements ScannerService {

    private static final String TAG = "UhfScannerImpl";
    private UhfInfo uhfInfo;
    private Reader mReader;
    private ScannerCallback cb;
    private boolean ready;
    private volatile boolean scanning;
    private Thread scanThread;

    @Override
    public void init(Context ctx) throws Exception {
        uhfInfo = new UhfInfo();
        mReader = uhfInfo.getReader();
        if (mReader == null) {
            throw new IllegalStateException("UHF 模组初始化失败:Reader 为 null");
        }
        ready = true;
        Log.i(TAG, "UHF initialized");
    }

    @Override
    public void release() {
        stopScan();
        if (mReader != null) {
            try { mReader.free(); } catch (Exception e) { Log.w(TAG, "free failed", e); }
            mReader = null;
        }
        ready = false;
    }

    @Override
    public void startScan(ScanType type) {
        if (type != ScanType.RFID_EPC) return;
        if (!ready || mReader == null) {
            if (cb != null) cb.onError(-100, "UHF 未就绪");
            return;
        }
        stopScan();
        scanning = true;
        scanThread = new Thread(() -> {
            long deadline = System.currentTimeMillis() + 5_000;
            try {
                while (scanning && System.currentTimeMillis() < deadline) {
                    List<HashMap<String, String>> tags = mReader.inventoryRealTime();
                    if (tags != null) {
                        for (HashMap<String, String> tag : tags) {
                            String epc = tag.get("EPC");
                            if (epc == null || epc.isEmpty()) continue;
                            String rssi = tag.get("RSSI");
                            if (cb != null) {
                                cb.onScanResult(new ScanResult(
                                    epc, ScanType.RFID_EPC, rssi, System.currentTimeMillis()));
                            }
                            stopScan();
                            return;
                        }
                    }
                    Thread.sleep(150);
                }
                if (scanning && cb != null) cb.onTimeout();
            } catch (Exception e) {
                Log.e(TAG, "scan failed", e);
                if (cb != null) cb.onError(-1, e.getMessage());
            }
        }, "UhfScanThread");
        scanThread.start();
    }

    @Override
    public void stopScan() {
        scanning = false;
        if (scanThread != null) {
            scanThread.interrupt();
            scanThread = null;
        }
    }

    @Override public boolean isReady() { return ready; }
    @Override public void setCallback(ScannerCallback cb) { this.cb = cb; }
}