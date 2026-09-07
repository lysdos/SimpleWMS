package com.wms.pda.scan;

import android.content.Context;
import android.util.Log;

import com.rscja.deviceapi.RFIDWithUHFUART;
import com.rscja.deviceapi.entity.UHFTAGInfo;

/**
 * UHF RFID 实现 - 走东集 iData 真实 SDK(DeviceAPI,包 com.rscja.*)。
 * 单例 RFIDWithUHFUART:init() 打开串口模组,inventorySingleTag() 单步读卡。
 */
public class UhfScannerImpl implements ScannerService {

    private static final String TAG = "UhfScannerImpl";
    private RFIDWithUHFUART reader;   // 真实 SDK 单例
    private ScannerCallback cb;
    private boolean ready;
    private volatile boolean scanning;
    private Thread scanThread;

    @Override
    public void init(Context ctx) throws Exception {
        reader = RFIDWithUHFUART.getInstance();   // 可能抛 ConfigurationException
        if (reader == null || !reader.init()) {
            ready = false;
            throw new IllegalStateException("UHF 模组初始化失败(无 RFID 硬件或 init 返回 false)");
        }
        ready = true;
        Log.i(TAG, "UHF initialized");
    }

    @Override
    public void release() {
        stopScan();
        if (reader != null) {
            try { reader.free(); } catch (Exception e) { Log.w(TAG, "free failed", e); }
            reader = null;
        }
        ready = false;
    }

    @Override
    public void startScan(ScanType type) {
        if (type != ScanType.RFID_EPC) return;
        if (!ready || reader == null) {
            if (cb != null) cb.onError(-100, "UHF 未就绪");
            return;
        }
        stopScan();
        scanning = true;
        scanThread = new Thread(() -> {
            long deadline = System.currentTimeMillis() + 5_000;
            try {
                // 单步盘点轮询:读到 1 个有效 EPC 即返回;5 秒超时 onTimeout
                while (scanning && System.currentTimeMillis() < deadline) {
                    UHFTAGInfo tag = reader.inventorySingleTag();
                    if (tag != null) {
                        String epc = tag.getEPC();
                        if (epc != null && !epc.isEmpty()) {
                            String rssi = tag.getRssi();
                            if (cb != null) cb.onScanResult(new ScanResult(
                                epc, ScanType.RFID_EPC, rssi, System.currentTimeMillis()));
                            stopScan();
                            return;
                        }
                    }
                    Thread.sleep(100);
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
        // 保险:停止连续盘点(如残留)
        try { if (reader != null) reader.stopInventory(); } catch (Exception ignored) {}
    }

    @Override public boolean isReady() { return ready; }
    @Override public void setCallback(ScannerCallback cb) { this.cb = cb; }
}
