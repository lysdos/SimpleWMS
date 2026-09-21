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
    /**
     * 拣货分布的另一半:库位码 → 该库位取到的 24 位码列表,不存表。
     *
     * 与 {@link #picks} **同源同序**(同一个循环里产出,都用 TreeMap 按库位码排序),
     * 所以 `keySet()` 恒等:同一个库位,picks 给件数、本字段给码。
     *
     * 为什么必须有它:{@link #codes} 是纯字符串列表、不带库位,而一个商品可能同时躺在
     * 主仓和副仓 —— 现场拿着几个码不知道哪个该放哪。两者**不能靠推算对应**:
     * picks 记的是件数、codes 是一单元一码,老数据(remaining>1)一个码算多件,切不出块。
     */
    private Map<String, List<String>> codesByLocation;

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
    public Map<String, List<String>> getCodesByLocation() { return codesByLocation; }
    public void setCodesByLocation(Map<String, List<String>> codesByLocation) { this.codesByLocation = codesByLocation; }
}
