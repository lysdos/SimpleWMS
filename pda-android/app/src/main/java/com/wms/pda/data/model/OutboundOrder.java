package com.wms.pda.data.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 出库单(主单 + 明细)。对接 /api/outbound/orders,字段 1:1 对应后端 JSON。
 */
public class OutboundOrder {
    public Integer dbId;          // 后端数据库自增 id(详情接口需要)
    public String id;             // 后端 orderNo(展示用单号)
    public String customer;       // 客户/部门
    public String externalNo;     // 外部单号
    public String createdBy;       // 创建人
    public Integer totalQuantity; // 出库总件数(后端字段)
    public Integer itemCount;     // 出库行数(后端字段)
    public String remark;         // 备注
    public Enums.DocStatus status;
    public String createdAt;      // 创建时间(ISO 字符串)
    public List<Line> lines;
    public List<String> scannedCodes; // 后端已扫码确认的库存单元编码(回看已扫标记用)

    public OutboundOrder() {}

    public OutboundOrder(Integer dbId, String id, String customer, String externalNo,
                         String createdBy, Integer totalQuantity, Integer itemCount,
                         String remark, Enums.DocStatus status, String createdAt,
                         List<Line> lines) {
        this.dbId = dbId;
        this.id = id;
        this.customer = customer;
        this.externalNo = externalNo;
        this.createdBy = createdBy;
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
        public String location;      // 空 = 自动(跨库位找货),实际分布看 picks
        public String rowRemark;
        public int qty;
        public List<String> codes;   // 每个库存单元的编码(逐件显示用)
        /** 拣货分布:库位码 → 件数。行上 location 为空时,现场靠这个知道去哪儿拿货。 */
        public java.util.Map<String, Integer> picks;
        /** 库位码 → 该库位取到的码列表(与 picks 同源同序)。逐件列表按仓库分组显示靠它。 */
        public java.util.Map<String, List<String>> codesByLocation;

        public Line() {}
    }
}