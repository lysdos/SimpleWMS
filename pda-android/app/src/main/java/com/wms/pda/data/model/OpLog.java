package com.wms.pda.data.model;

/**
 * 仪表盘"最近操作"行。统一一种结构,展示入库/出库/盘点/调拨/报废等动作。
 * 存的都是**原始数据**,不含界面文案(要翻成中文/英文由 UI 层按资源来)。
 */
public class OpLog {
    public String time;
    public String type;      // 后端原始类型:IN / OUT / CHECK / TRANSFER / SCRAP
    public String refNo;     // 关联单号
    public String summary;   // 一句话描述

    public OpLog() {}

    public OpLog(String time, String type, String refNo, String summary) {
        this.time = time;
        this.type = type;
        this.refNo = refNo;
        this.summary = summary;
    }
}
