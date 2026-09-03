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

    // 联表展示(不存表)
    private String defaultSupplier;       // 默认供应商名
    private Integer defaultSupplierId;    // 默认供应商 id
    private java.util.List<SupplierRef> suppliers;

    public static class SupplierRef {
        private Integer id;
        private String name;
        private Boolean isDefault;
        public SupplierRef() {}
        public SupplierRef(Integer id, String name, Boolean isDefault) {
            this.id = id; this.name = name; this.isDefault = isDefault;
        }
        public Integer getId() { return id; }
        public void setId(Integer id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public Boolean getIsDefault() { return isDefault; }
        public void setIsDefault(Boolean isDefault) { this.isDefault = isDefault; }
    }

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

    public String getDefaultSupplier() { return defaultSupplier; }
    public void setDefaultSupplier(String defaultSupplier) { this.defaultSupplier = defaultSupplier; }
    public Integer getDefaultSupplierId() { return defaultSupplierId; }
    public void setDefaultSupplierId(Integer defaultSupplierId) { this.defaultSupplierId = defaultSupplierId; }
    public java.util.List<SupplierRef> getSuppliers() { return suppliers; }
    public void setSuppliers(java.util.List<SupplierRef> suppliers) { this.suppliers = suppliers; }

    // 服务层使用的关联 supplier 写入载体(不返回给前端)
    private transient java.util.List<ProductSupplier> suppliersAsList;
    public java.util.List<ProductSupplier> getSuppliersAsList() { return suppliersAsList; }
    public void setSuppliersAsList(java.util.List<ProductSupplier> suppliersAsList) { this.suppliersAsList = suppliersAsList; }

    public BigDecimal getStockValue() {
        if (unitPrice == null || quantity == null) return BigDecimal.ZERO;
        return unitPrice.multiply(BigDecimal.valueOf(quantity));
    }
}