package com.wms.pda.data.model;

import java.util.List;

/**
 * 入库单(主单 + 明细)。对接 /api/inbound/orders,字段 1:1 对应后端 JSON。
 */
public class InboundOrder {
    public Integer dbId;          // 后端数据库自增 id(详情接口需要)
    public String id;             // 后端 orderNo(展示用单号)
    public String externalNo;     // 外部单号
    public String createdBy;       // 创建人
    public Integer totalQuantity; // 入库总件数(后端字段)
    public Integer itemCount;     // 入库行数(后端字段)
    public String remark;         // 备注
    public Enums.DocStatus status;
    public String createdAt;      // 创建时间(ISO 字符串)
    public List<InboundLine> lines;

    public InboundOrder() {}

    public InboundOrder(String id, String externalNo, String createdBy, Integer totalQuantity,
                        Integer itemCount, String remark, Enums.DocStatus status,
                        String createdAt, List<InboundLine> lines) {
        this.id = id;
        this.externalNo = externalNo;
        this.createdBy = createdBy;
        this.totalQuantity = totalQuantity;
        this.itemCount = itemCount;
        this.remark = remark;
        this.status = status;
        this.createdAt = createdAt;
        this.lines = lines;
    }

    public static class InboundLine {
        public Integer id;          // 后端 line.id
        public Integer productId;   // 后端 productId
        public String sku;
        public String name;         // 后端 productName
        public String supplier;     // 行级供应商
        public String unitPrice;    // BigDecimal → String
        public Integer beforeQty;
        public Integer afterQty;
        public String location;     // 库位
        public String rowRemark;    // 行级备注
        public String productionDate; // 生产日期(yyyy-MM-dd,可空)
        public Integer shelfLifeDays; // 保质期天数(可空)
        public int qty;             // 后端 quantity
        public List<String> codes;  // commit 后生成的 24 位码

        public InboundLine() {}
    }
}
