package com.wms.pda.data.model;

import java.util.List;

/**
 * 盘点单。盘点方式:全盘/动碰盘/抽样盘。
 * 提交后系统自动比对 账面 vs 实盘,产出盘盈/盘亏。
 */
public class StocktakeOrder {
    public String id;
    public String warehouse;
    public Mode mode;
    public Enums.DocStatus status;
    public String createdAt;
    public List<Line> lines;

    public StocktakeOrder() {}

    public StocktakeOrder(String id, String warehouse, Mode mode, Enums.DocStatus status,
                          String createdAt, List<Line> lines) {
        this.id = id;
        this.warehouse = warehouse;
        this.mode = mode;
        this.status = status;
        this.createdAt = createdAt;
        this.lines = lines;
    }

    public enum Mode {
        ALL("全盘"),
        MOVEMENT("动碰盘"),
        SAMPLE("抽样盘");
        public final String label;
        Mode(String label) { this.label = label; }
    }

    public static class Line {
        public String sku;
        public String name;
        public String barcode;
        public int bookQty;     // 账面
        public int actualQty;   // 实盘(初始为 -1 表示未盘)
        public String location;

        public Line() {}

        public Line(String sku, String name, String barcode, int bookQty, int actualQty, String location) {
            this.sku = sku;
            this.name = name;
            this.barcode = barcode;
            this.bookQty = bookQty;
            this.actualQty = actualQty;
            this.location = location;
        }

        public int diff() {
            return actualQty < 0 ? 0 : (actualQty - bookQty);
        }
    }
}
