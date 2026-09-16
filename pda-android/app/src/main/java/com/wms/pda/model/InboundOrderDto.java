package com.wms.pda.model;

import java.util.List;

/** 入库单 DTO - 镜像后端 InboundOrder。 */
public class InboundOrderDto {
    public Integer id;
    public String orderNo;
    public String externalNo;
    public String remark;
    public String createdBy;
    public Integer totalQuantity;
    public Integer itemCount;
    public String createdAt;        // ISO 字符串,Gson 直接吃
    public String status;           // DRAFT / COMMITTED / CANCELLED
    public List<InboundOrderItemDto> items;
}