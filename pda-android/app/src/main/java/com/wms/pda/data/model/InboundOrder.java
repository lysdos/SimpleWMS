package com.wms.pda.data.model;

import java.util.List;

/**
 * 入库单(主单 + 明细)。对应 SimpleWMS 后端入库单表,接入时按真实字段映射。
 */
public class InboundOrder {
    public String id;             // 单号
    public String supplier;       // 供应商
    public String warehouse;      // 目标仓库
    public String remark;         // 备注
    public Enums.DocStatus status;
    public String createdAt;      // 创建时间(yyyy-MM-dd HH:mm)
    public List<InboundLine> lines;

    public InboundOrder() {}

    public InboundOrder(String id, String supplier, String warehouse, String remark,
                        Enums.DocStatus status, String createdAt, List<InboundLine> lines) {
        this.id = id;
        this.supplier = supplier;
        this.warehouse = warehouse;
        this.remark = remark;
        this.status = status;
        this.createdAt = createdAt;
        this.lines = lines;
    }

    public static class InboundLine {
        public String sku;
        public String name;
        public String barcode;     // 扫码后回填
        public int qty;
        public String uom;         // 单位

        public InboundLine() {}

        public InboundLine(String sku, String name, String barcode, int qty, String uom) {
            this.sku = sku;
            this.name = name;
            this.barcode = barcode;
            this.qty = qty;
            this.uom = uom;
        }
    }
}
