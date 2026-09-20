package com.wms.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public class OutboundOrderItem {

    private Integer id;
    private Integer orderId;
    private Integer productId;
    private String sku;
    private String productName;
    private Integer quantity;
    private BigDecimal unitPrice;
    private Integer beforeQty;
    private Integer afterQty;
    private String location;
    private String rowRemark;
    private LocalDateTime createdAt;
    private List<String> codes; // 每个库存单元的编码(逐件显示用)
    /**
     * 拣货分布:库位码 → 件数(该行实际从各库位取几件),不存表。
     *
     * 出库改成"自动跨库位找货"之后,行上的 {@link #location} 是空的(表示自动),
     * 现场要知道去哪儿拿货只能看这个 —— 它按**真实锁定的库存单元**统计。
     */
    private Map<String, Integer> picks;

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
    public Map<String, Integer> getPicks() { return picks; }
    public void setPicks(Map<String, Integer> picks) { this.picks = picks; }
}
