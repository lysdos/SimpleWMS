package com.wms.model;

import java.time.LocalDateTime;

public class StockCheckItem {

    private Integer id;
    private Integer checkId;
    private Integer inventoryItemId;
    private String code;
    private String sku;
    private String productName;
    private String locationCode;
    private Integer systemQty;
    private Integer countedQty;
    private Integer diff;
    private String rowRemark;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public Integer getCheckId() { return checkId; }
    public void setCheckId(Integer checkId) { this.checkId = checkId; }
    public Integer getInventoryItemId() { return inventoryItemId; }
    public void setInventoryItemId(Integer inventoryItemId) { this.inventoryItemId = inventoryItemId; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getSku() { return sku; }
    public void setSku(String sku) { this.sku = sku; }
    public String getProductName() { return productName; }
    public void setProductName(String productName) { this.productName = productName; }
    public String getLocationCode() { return locationCode; }
    public void setLocationCode(String locationCode) { this.locationCode = locationCode; }
    public Integer getSystemQty() { return systemQty; }
    public void setSystemQty(Integer systemQty) { this.systemQty = systemQty; }
    public Integer getCountedQty() { return countedQty; }
    public void setCountedQty(Integer countedQty) { this.countedQty = countedQty; }
    public Integer getDiff() { return diff; }
    public void setDiff(Integer diff) { this.diff = diff; }
    public String getRowRemark() { return rowRemark; }
    public void setRowRemark(String rowRemark) { this.rowRemark = rowRemark; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
