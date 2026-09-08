package com.wms.pda.data.model;

import java.util.List;

/**
 * 仪表盘快照:4 个 KPI + 最近操作列表。
 */
public class DashboardSnapshot {
    public int inboundToday;
    public int outboundToday;
    public int pendingStocktake;
    public int lowStockSkuCount;
    public List<OpLog> recentOps;

    public DashboardSnapshot() {}

    public DashboardSnapshot(int inboundToday, int outboundToday, int pendingStocktake,
                             int lowStockSkuCount, List<OpLog> recentOps) {
        this.inboundToday = inboundToday;
        this.outboundToday = outboundToday;
        this.pendingStocktake = pendingStocktake;
        this.lowStockSkuCount = lowStockSkuCount;
        this.recentOps = recentOps;
    }
}
