package com.wms.pda.model;

public class InventoryItemDto {
    public Integer id;
    public String code;
    public Integer productId;
    public String sku;
    public String productName;
    public String locationCode;
    public Integer quantity;
    public Integer remaining;
    public String status;
    public Integer inboundOrderId;
    public Integer outboundOrderId;
    public String createdBy;
    public String createdAt;     // ISO 字符串,Gson 直接吃
    public String updatedAt;
}
