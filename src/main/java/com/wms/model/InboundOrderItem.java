package com.wms.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public class InboundOrderItem {

    private Integer id;
    private Integer orderId;
    private Integer productId;
    private String sku;
    private String productName;
    private String supplier;
    private Integer quantity;
    private BigDecimal unitPrice;
    private Integer beforeQty;
    private Integer afterQty;
    private String location;
    private String rowRemark;
    private LocalDateTime createdAt;
    /** 一物一码:该明细行所有 inventory_item 24 位编码(commit 时由 service 填充) */
    private List<String> codes;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public Integer getOrderId() { return orderId; }
    public void setOrderId(Integer orderId) { this.orderId = orderId; }
    public Integer getProductId() { return productId; }
    public void setProductId(Integer productId) { this.productId = productId; }
    public String getSku() { return sku; }
    public void setSku(String sku) { this.sku = sku; }
    public String getProductName() { return productName; }
    public void setProductName(String productName) { this.productName = productName; }
    public String getSupplier() { return supplier; }
    public void setSupplier(String supplier) { this.supplier = supplier; }
    public Integer getQuantity() { return quantity; }
    public void setQuantity(Integer quantity) { this.quantity = quantity; }
    public BigDecimal getUnitPrice() { return unitPrice; }
    public void setUnitPrice(BigDecimal unitPrice) { this.unitPrice = unitPrice; }
    public Integer getBeforeQty() { return beforeQty; }
    public void setBeforeQty(Integer beforeQty) { this.beforeQty = beforeQty; }
    public Integer getAfterQty() { return afterQty; }
    public void setAfterQty(Integer afterQty) { this.afterQty = afterQty; }
    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }
    public String getRowRemark() { return rowRemark; }
    public void setRowRemark(String rowRemark) { this.rowRemark = rowRemark; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public List<String> getCodes() { return codes; }
    public void setCodes(List<String> codes) { this.codes = codes; }
}
