package com.wms.model;

import java.time.LocalDateTime;

public class InventoryLog {
    /** 出入库类型 */
    public static final String TYPE_IN  = "IN";   // 入库
    public static final String TYPE_OUT = "OUT";  // 出库

    private Integer id;
    private String type;
    private Integer productId;
    private String sku;
    private String productName;
    private Integer quantity;
    private Integer beforeQty;
    private Integer afterQty;
    private String remark;
    private String operator;
    private Integer orderId;
    private LocalDateTime createdAt;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public Integer getProductId() { return productId; }
    public void setProductId(Integer productId) { this.productId = productId; }
    public String getSku() { return sku; }
    public void setSku(String sku) { this.sku = sku; }
    public String getProductName() { return productName; }
    public void setProductName(String productName) { this.productName = productName; }
    public Integer getQuantity() { return quantity; }
    public void setQuantity(Integer quantity) { this.quantity = quantity; }
    public Integer getBeforeQty() { return beforeQty; }
    public void setBeforeQty(Integer beforeQty) { this.beforeQty = beforeQty; }
    public Integer getAfterQty() { return afterQty; }
    public void setAfterQty(Integer afterQty) { this.afterQty = afterQty; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public String getOperator() { return operator; }
    public void setOperator(String operator) { this.operator = operator; }
    public Integer getOrderId() { return orderId; }
    public void setOrderId(Integer orderId) { this.orderId = orderId; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}