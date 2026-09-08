package com.wms.pda.data.model;

import java.util.List;

/**
 * 出库单(主单 + 明细)。对接 /api/outbound/orders,字段 1:1 对应后端 JSON。
 */
public class OutboundOrder {
    public Integer dbId;          // 后端数据库自增 id(详情接口需要)
    public String id;             // 后端 orderNo(展示用单号)
    public String customer;       // 客户/部门
    public String externalNo;     // 外部单号
    public String operator;       // 操作员
    public Integer totalQuantity; // 出库总件数(后端字段)
    public Integer itemCount;     // 出库行数(后端字段)
    public String remark;         // 备注
    public Enums.DocStatus status;
    public String createdAt;      // 创建时间(ISO 字符串)
    public List<Line> lines;

    public OutboundOrder() {}

    public OutboundOrder(Integer dbId, String id, String customer, String externalNo,
                         String operator, Integer totalQuantity, Integer itemCount,
                         String remark, Enums.DocStatus status, String createdAt,
                         List<Line> lines) {
        this.dbId = dbId;
        this.id = id;
        this.customer = customer;
        this.externalNo = externalNo;
        this.operator = operator;
        this.totalQuantity = totalQuantity;
        this.itemCount = itemCount;
        this.remark = remark;
        this.status = status;
        this.createdAt = createdAt;
        this.lines = lines;
    }

    public static class Line {
        public Integer id;
        public Integer productId;
        public String sku;
        public String name;
        public String unitPrice;     // BigDecimal → String
        public Integer beforeQty;
        public Integer afterQty;
        public String location;
        public String rowRemark;
        public int qty;
        public String uom;           // 后端无字段,PDA 端占位 "件"

        public Line() {}

        public Line(Integer id, Integer productId, String sku, String name,
                    String unitPrice, Integer beforeQty, Integer afterQty,
                    String location, String rowRemark, int qty, String uom) {
            this.id = id;
            this.productId = productId;
            this.sku = sku;
            this.name = name;
            this.unitPrice = unitPrice;
            this.beforeQty = beforeQty;
            this.afterQty = afterQty;
            this.location = location;
            this.rowRemark = rowRemark;
            this.qty = qty;
            this.uom = uom;
        }
    }
}