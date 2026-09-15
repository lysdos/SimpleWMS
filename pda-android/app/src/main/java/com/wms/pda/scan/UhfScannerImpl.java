package com.wms.pda.scan;

import android.content.Context;
import android.util.Log;

import com.rscja.deviceapi.RFIDWithUHFUART;
import com.rscja.deviceapi.entity.UHFTAGInfo;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * UHF RFID 实现 - CHAINWAY DeviceAPI SDK.
 *
 * 参考 huaxiaapp SdkRfidAdapter 写法,轮询模型:
 *   startInventoryTag() 启动盘点
 *   readTagFromBuffer() 轮询读取标签(EPC)
 *   stopInventory() 停止
 *   free() 释放
 */
public class UhfScannerImpl implements ScannerService {

    private static final String TAG = "UhfScannerImpl";
    private static final long POLL_IDLE_SLEEP_MS = 20L;
    private static final long SAME_EPC_DEBOUNCE_MS = 2000L;

    private volatile RFIDWithUHFUART reader;
    private final AtomicBoolean powered = new AtomicBoolean(false);
    private final AtomicBoolean scanning = new AtomicBoolean(false);
    private volatile Thread pollThread;
    private ScannerCallback cb;
    /** 连续盘点时同一 EPC 的防抖:记录每个 EPC 最近一次上报时间 */
    private final java.util.concurrent.ConcurrentHashMap<String, Long> lastEmitByEpc =
            new java.util.concurrent.ConcurrentHashMap<>();

    @Override
    public void init(Context ctx) throws Exception {
        try {
            RFIDWithUHFUART r = RFIDWithUHFUART.getInstance();
            if (r == null) throw new Exception("UHF 模块不可用");
            boolean ok = r.init();
            if (!ok) throw new Exception("UHF init() 返回 false");
            reader = r;
            powered.set(ok);
            Log.i(TAG, "UHF init success");
        } catch (Throwable t) {
            Log.e(TAG, "UHF init failed", t);
            throw new Exception("UHF SDK init failed: " + t.getMessage(), t);
        }
    }

    @Override
    public void release() {
        stopScan();
        try {
            RFIDWithUHFUART r = reader;
            if (r != null) {
                r.free();
            }
        } catch (Throwable t) {
            Log.w(TAG, "release failed", t);
        }
        powered.set(false);
        reader = null;
    }

    @Override
    public void startScan(ScanType type) {
        if (type != ScanType.RFID_EPC) return;
        if (!isReady()) {
            if (cb != null) cb.onError(-100, "UHF 未就绪(SDK 未就绪或硬件未连接)");
            return;
        }
        stopScan();
        scanning.set(true);
        lastEmitByEpc.clear();

        // 启动盘点
        boolean started;
        try {
            started = reader.startInventoryTag();
        } catch (Throwable t) {
            Log.e(TAG, "startInventoryTag failed", t);
            scanning.set(false);
            if (cb != null) cb.onError(-1, "启动盘点异常: " + t.getMessage());
            return;
        }
        if (!started) {
            scanning.set(false);
            if (cb != null) cb.onError(-2, "启动盘点失败");
            return;
        }
        Log.i(TAG, "UHF inventory started");

        // 启动轮询线程(连续盘点模式:读到标签不停,直到用户按"停止")
        pollThread = new Thread(this::pollLoop, "uhf-poll");
        pollThread.setDaemon(true);
        pollThread.start();
    }

    @Override
    public void stopScan() {
        if (!scanning.compareAndSet(true, false)) return;
        try {
            RFIDWithUHFUART r = reader;
            if (r != null) r.stopInventory();
        } catch (Throwable t) {
            Log.w(TAG, "stopInventory failed", t);
        }
        Thread t = pollThread;
        if (t != null) {
            t.interrupt();
            pollThread = null;
        }
    }

    @Override
    public boolean isReady() {
        return powered.get() && reader != null;
    }

    @Override
    public void setCallback(ScannerCallback cb) {
        this.cb = cb;
    }

    /**
     * 轮询线程:持续读取 readTagFromBuffer() 直到用户停止扫描。
     * 读到有效 EPC 就回调(同一 EPC 2 秒内防抖不重复上报),盘点继续跑。
     */
    private void pollLoop() {
        while (scanning.get()) {
            RFIDWithUHFUART r = reader;
            if (r == null) return;
            UHFTAGInfo info = null;
            try {
                info = r.readTagFromBuffer();
            } catch (Throwable t) {
                Log.w(TAG, "readTagFromBuffer failed", t);
            }
            if (info != null) {
                String epc = info.getEPC();
                if (epc != null && !epc.isEmpty() && shouldEmit(epc)) {
                    Log.i(TAG, "epc emitted: " + epc + " (len=" + epc.length() + ", rssi=" + info.getRssi() + ")");
                    if (cb != null) {
                        cb.onScanResult(new ScanResult(
                                epc, ScanType.RFID_EPC, info.getRssi(),
                                System.currentTimeMillis()));
                    }
                }
            } else {
                try { Thread.sleep(POLL_IDLE_SLEEP_MS); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /** 同一 EPC 在 DEBOUNCE 窗口内只上报一次;窗口外允许重扫(标签可能被重新放回) */
    private boolean shouldEmit(String epc) {
        long now = System.currentTimeMillis();
        Long last = lastEmitByEpc.put(epc, now);
        return last == null || now - last >= SAME_EPC_DEBOUNCE_MS;
    }
}
