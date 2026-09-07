package com.wms.pda.scan;

import android.content.Context;
import android.util.Log;

import com.ubx.usdk.RFIDSDKManager;
import com.ubx.usdk.rfid.aidl.IRfidCallback;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * UHF RFID 实现 - UROVO 优博讯 SDK(URFIDLibrary v2.4.1226)。
 *
 * 参考 E:\Code\huaxiaapp\app\src\main\java\com\huaxia\inventory\rfid\SdkRfidAdapter.java 写法。
 *
 * 关键 API(已 javap 验证):
 *  - RFIDSDKManager.getInstance()             → 进程单例
 *  - init(InitListener) → void 异步回调 onStatus(boolean ready)
 *  - getRfidManager()                         → RfidManager
 *  - power(boolean)                           → 上电 / 下电
 *  - getRfidManager().isConnected()           → 硬件是否连接
 *  - getRfidManager().registerCallback(IRfidCallback) → 订阅 EPC 回调
 *  - getRfidManager().startInventory(int session) → 启动连续盘点(0..3 session)
 *  - getRfidManager().stopInventory()         → 停止
 *  - getRfidManager().disConnect()            → 断开
 *  - getRfidManager().release()               → 释放 SDK 资源
 */
public class UhfScannerImpl implements ScannerService {

    private static final String TAG = "UhfScannerImpl";
    private ScannerCallback cb;
    private volatile boolean ready;
    private final AtomicBoolean scanning = new AtomicBoolean(false);
    private volatile Thread scanThread;
    private IRfidCallback rfidCallback;

    @Override
    public void init(Context ctx) throws Exception {
        // RFIDSDKManager 是进程单例,init 异步回调
        try {
            RFIDSDKManager.getInstance().init(ready1 -> {
                ready = ready1;
                Log.i(TAG, "UHF SDK init ready=" + ready1
                        + " connected=" + safeIsConnected());
            });
        } catch (Throwable t) {
            // SDK init 本身可能抛 RuntimeException(PDA 未配 SDK 时)
            Log.e(TAG, "RFIDSDKManager.init 抛异常", t);
            throw new Exception("UHF SDK init failed: " + t.getMessage(), t);
        }
        // 兼容 ScannerService 同步契约:乐观设 true,实际就绪以 isReady() 为准
        ready = true;
    }

    @Override
    public void release() {
        stopScan();
        try {
            if (RFIDSDKManager.getInstance().getRfidManager() != null) {
                RFIDSDKManager.getInstance().getRfidManager().disConnect();
            }
            RFIDSDKManager.getInstance().power(false);
            RFIDSDKManager.getInstance().release();
        } catch (Throwable t) {
            Log.w(TAG, "release failed", t);
        }
        ready = false;
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

        // 注册回调 - SDK 内部 worker 线程触发,不能直接操作 UI
        if (rfidCallback == null) {
            rfidCallback = new IRfidCallback() {
                @Override
                public void onInventoryTag(String epc, String tid, String rssi) {
                    if (!scanning.get()) return;
                    if (epc == null || epc.isEmpty()) return;
                    // 抓到一个有效 EPC 就停(单步盘点),降低重复率
                    scanning.set(false);
                    stopInventoryInternal();
                    if (cb != null) {
                        cb.onScanResult(new ScanResult(
                                epc, ScanType.RFID_EPC, rssi, System.currentTimeMillis()));
                    }
                }

                @Override
                public void onInventoryTagEnd() {
                    Log.d(TAG, "onInventoryTagEnd");
                }
            };
        }
        try {
            RFIDSDKManager.getInstance().getRfidManager().registerCallback(rfidCallback);
        } catch (Throwable t) {
            Log.e(TAG, "registerCallback failed", t);
            if (cb != null) cb.onError(-1, "注册回调失败: " + t.getMessage());
            return;
        }

        // 5 秒超时线程
        scanThread = new Thread(() -> {
            try { Thread.sleep(5_000); } catch (InterruptedException ignored) {}
            if (scanning.get()) {
                scanning.set(false);
                stopInventoryInternal();
                if (cb != null) cb.onTimeout();
            }
        }, "UhfScanTimeout");
        scanThread.setDaemon(true);
        scanThread.start();

        // 启动盘点(session = 0)
        try {
            int rc = RFIDSDKManager.getInstance().getRfidManager().startInventory(0);
            if (rc != 0) {
                Log.w(TAG, "startInventory rc=" + rc);
                if (cb != null) cb.onError(-2, "启动盘点失败 rc=" + rc);
            } else {
                Log.i(TAG, "UHF inventory started");
            }
        } catch (Throwable t) {
            Log.e(TAG, "startInventory failed", t);
            if (cb != null) cb.onError(-1, "启动盘点异常: " + t.getMessage());
        }
    }

    @Override
    public void stopScan() {
        scanning.set(false);
        stopInventoryInternal();
    }

    private void stopInventoryInternal() {
        try {
            if (RFIDSDKManager.getInstance().getRfidManager() != null) {
                RFIDSDKManager.getInstance().getRfidManager().stopInventory();
            }
        } catch (Throwable t) {
            Log.w(TAG, "stopInventory failed", t);
        }
        if (rfidCallback != null) {
            try {
                RFIDSDKManager.getInstance().getRfidManager().unregisterCallback(rfidCallback);
            } catch (Throwable t) {
                Log.w(TAG, "unregisterCallback failed", t);
            }
        }
        if (scanThread != null) {
            scanThread.interrupt();
            scanThread = null;
        }
    }

    @Override
    public boolean isReady() {
        if (!ready) return false;
        return safeIsConnected();
    }

    private static boolean safeIsConnected() {
        try {
            return RFIDSDKManager.getInstance().getRfidManager() != null
                    && RFIDSDKManager.getInstance().getRfidManager().isConnected();
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public void setCallback(ScannerCallback cb) {
        this.cb = cb;
    }
}
