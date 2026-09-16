package com.wms.model;

import java.math.BigDecimal;

public class DashboardStats {
    private long productCount;       // 商品种类数
    private long totalQuantity;      // 库存总数量
    private BigDecimal totalValue = BigDecimal.ZERO;  // 库存总值
    private long lowStockCount;      // 低库存预警数
    private long expiringSoonCount;  // 临期批次预警数(未过期,距到期<=商品预警天数,默认30)
    private long expiredCount;       // 已过期在库批次数

    public long getProductCount() { return productCount; }
    public void setProductCount(long productCount) { this.productCount = productCount; }
    public long getTotalQuantity() { return totalQuantity; }
    public void setTotalQuantity(long totalQuantity) { this.totalQuantity = totalQuantity; }
    public BigDecimal getTotalValue() { return totalValue; }
    public void setTotalValue(BigDecimal totalValue) { this.totalValue = totalValue; }
    public long getLowStockCount() { return lowStockCount; }
    public void setLowStockCount(long lowStockCount) { this.lowStockCount = lowStockCount; }
    public long getExpiringSoonCount() { return expiringSoonCount; }
    public void setExpiringSoonCount(long expiringSoonCount) { this.expiringSoonCount = expiringSoonCount; }
    public long getExpiredCount() { return expiredCount; }
    public void setExpiredCount(long expiredCount) { this.expiredCount = expiredCount; }
}