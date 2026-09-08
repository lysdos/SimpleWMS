package com.wms.pda.data.model;

/**
 * 仪表盘"最近操作"行。统一一种结构,展示入库/出库/盘点/调拨/报废等动作。
 */
public class OpLog {
    public String time;
    public String type;      // 入库 / 出库 / 盘点 / 调拨 / 报废
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
