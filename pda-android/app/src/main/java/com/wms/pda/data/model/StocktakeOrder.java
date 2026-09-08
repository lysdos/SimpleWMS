package com.wms.pda.data.model;

import java.util.List;

/**
 * 盘点单(主单 + 明细)。
 * 对接 /api/stock-checks,字段 1:1 对应后端 JSON。
 */
public class StocktakeOrder {
    public Integer dbId;          // 后端数据库自增 id(详情/扫码接口需要)
    public String id;             // 后端 checkNo(展示用单号)
    public String scope;          // ALL / SKU / LOCATION
    public String scopeKeyword;   // scope 过滤值
    public String operator;       // 操作员
    public Integer totalCount;    // 行数
    public Integer countedCount;  // 已扫行数
    public Integer diffCount;     // 差异行数
    public String remark;
    public Enums.DocStatus status;
    public String createdAt;
    public List<Line> lines;

    public StocktakeOrder() {}

    public StocktakeOrder(Integer dbId, String id, String scope, String scopeKeyword,
                         String operator, Integer totalCount, Integer countedCount,
                         Integer diffCount, String remark, Enums.DocStatus status,
                         String createdAt, List<Line> lines) {
        this.dbId = dbId;
        this.id = id;
        this.scope = scope;
        this.scopeKeyword = scopeKeyword;
        this.operator = operator;
        this.totalCount = totalCount;
        this.countedCount = countedCount;
        this.diffCount = diffCount;
        this.remark = remark;
        this.status = status;
        this.createdAt = createdAt;
        this.lines = lines;
    }

    /** ALL 全盘 / SKU 抽样盘 / LOCATION 动碰盘(按 sku/location 过滤) */
    public enum Mode {
        ALL("全盘"),
        SKU("抽样盘"),
        LOCATION("动碰盘");
        public final String label;
        Mode(String label) { this.label = label; }
    }

    public static class Line {
        public Integer id;
        public Integer inventoryItemId;
        public String code;            // 24 位 inventory_item.code
        public String sku;
        public String productName;
        public String locationCode;
        public Integer systemQty;      // 账面
        public Integer countedQty;     // 实盘(null = 未盘)
        public Integer diff;
        public String rowRemark;

        public Line() {}

        public Line(Integer id, Integer inventoryItemId, String code, String sku,
                    String productName, String locationCode, Integer systemQty,
                    Integer countedQty, Integer diff, String rowRemark) {
            this.id = id;
            this.inventoryItemId = inventoryItemId;
            this.code = code;
            this.sku = sku;
            this.productName = productName;
            this.locationCode = locationCode;
            this.systemQty = systemQty;
            this.countedQty = countedQty;
            this.diff = diff;
            this.rowRemark = rowRemark;
        }
    }
}