package com.wms.pda.data.model;

import java.util.List;

/**
 * 出库单(主单 + 明细)。
 */
public class OutboundOrder {
    public String id;
    public String customer;
    public String warehouse;
    public String remark;
    public Enums.DocStatus status;
    public String createdAt;
    public List<Line> lines;

    public OutboundOrder() {}

    public OutboundOrder(String id, String customer, String warehouse, String remark,
                         Enums.DocStatus status, String createdAt, List<Line> lines) {
        this.id = id;
        this.customer = customer;
        this.warehouse = warehouse;
        this.remark = remark;
        this.status = status;
        this.createdAt = createdAt;
        this.lines = lines;
    }

    public static class Line {
        public String sku;
        public String name;
        public String barcode;
        public int qty;
        public String uom;

        public Line() {}

        public Line(String sku, String name, String barcode, int qty, String uom) {
            this.sku = sku;
            this.name = name;
            this.barcode = barcode;
            this.qty = qty;
            this.uom = uom;
        }
    }
}
