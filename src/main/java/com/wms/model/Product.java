package com.wms.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class Product {
    private Integer id;
    private String sku;
    private String name;
    private String category;
    private String unit;
    private String location;
    private Integer quantity;
    private BigDecimal unitPrice;
    private Integer lowStock;
    private String remark;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Product() {}

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public String getSku() { return sku; }
    public void setSku(String sku) { this.sku = sku; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getUnit() { return unit; }
    public void setUnit(String unit) { this.unit = unit; }
    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }
    public Integer getQuantity() { return quantity; }
    public void setQuantity(Integer quantity) { this.quantity = quantity; }
    public BigDecimal getUnitPrice() { return unitPrice; }
    public void setUnitPrice(BigDecimal unitPrice) { this.unitPrice = unitPrice; }
    public Integer getLowStock() { return lowStock; }
    public void setLowStock(Integer lowStock) { this.lowStock = lowStock; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public BigDecimal getStockValue() {
        if (unitPrice == null || quantity == null) return BigDecimal.ZERO;
        return unitPrice.multiply(BigDecimal.valueOf(quantity));
    }
}