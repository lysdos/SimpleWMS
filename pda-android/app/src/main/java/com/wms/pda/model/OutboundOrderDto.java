package com.wms.pda.model;

import java.util.List;

/** 镜像后端 OutboundOrder 响应。 */
public class OutboundOrderDto {
    public Integer id;
    public String orderNo;
    public String customer;
    public String externalNo;
    public String remark;
    public String createdBy;
    public Integer totalQuantity;
    public Integer itemCount;
    public String status;            // DRAFT / COMMITTED / CANCELLED
    public String createdAt;
    public List<OutboundOrderItemDto> items;

    public static class OutboundOrderItemDto {
        public Integer id;
        public Integer orderId;
        public Integer productId;
        public String sku;
        public String productName;
        public Integer quantity;
        public String unitPrice;
        public Integer beforeQty;
        public Integer afterQty;
        public String location;
        public String rowRemark;
    }
}
