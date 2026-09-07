package com.wms.pda.model;

import java.util.List;

/** 入库单明细 DTO - 镜像后端 InboundOrderItem(含 codes 列表)。 */
public class InboundOrderItemDto {
    public Integer id;
    public Integer orderId;
    public Integer productId;
    public String sku;
    public String productName;
    public String supplier;
    public Integer quantity;
    public String unitPrice;        // 后端 BigDecimal,Gson 当字符串吃
    public Integer beforeQty;
    public Integer afterQty;
    public String location;
    public String rowRemark;
    public String createdAt;        // ISO 字符串
    /** 该明细行所有 inventory_item 24 位编码(commit 时由 service 填充)。 */
    public List<String> codes;
}