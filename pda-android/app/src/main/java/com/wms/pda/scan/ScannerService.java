package com.wms.pda.scan;

import android.content.Context;

/** 扫码设备抽象接口(UHF / 光学共用)。 */
public interface ScannerService {
    /** 打开设备。失败抛 RuntimeException。 */
    void init(Context ctx) throws Exception;
    /** 关闭设备。 */
    void release();
    /** 启动一次扫描(单次或短轮询)。 */
    void startScan(ScanType type);
    /** 主动停止。 */
    void stopScan();
    /** 设备是否就绪。 */
    boolean isReady();
    /** 设置结果回调。 */
    void setCallback(ScannerCallback cb);
}