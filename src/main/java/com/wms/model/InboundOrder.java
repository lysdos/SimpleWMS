package com.wms.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public class InboundOrder {

    /** 状态机常量:DRAFT=草稿 / COMMITTED=已提交 / CANCELLED=已取消 */
    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_COMMITTED = "COMMITTED";
    public static final String STATUS_CANCELLED = "CANCELLED";

    private Integer id;
    private String orderNo;
    private String externalNo;
    private String remark;
    private String createdBy;
    private Integer totalQuantity;
    private Integer itemCount;
    private LocalDateTime createdAt;
    private String status;
    private List<InboundOrderItem> items;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public String getOrderNo() { return orderNo; }
    public void setOrderNo(String orderNo) { this.orderNo = orderNo; }
    public String getExternalNo() { return externalNo; }
    public void setExternalNo(String externalNo) { this.externalNo = externalNo; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public Integer getTotalQuantity() { return totalQuantity; }
    public void setTotalQuantity(Integer totalQuantity) { this.totalQuantity = totalQuantity; }
    public Integer getItemCount() { return itemCount; }
    public void setItemCount(Integer itemCount) { this.itemCount = itemCount; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public List<InboundOrderItem> getItems() { return items; }
    public void setItems(List<InboundOrderItem> items) { this.items = items; }

    /** 整单估算金额(单价 × 入库数量,仅供参考) */
    public BigDecimal estimateTotalAmount() {
        if (items == null) return BigDecimal.ZERO;
        BigDecimal sum = BigDecimal.ZERO;
        for (InboundOrderItem it : items) {
            BigDecimal p = it.getUnitPrice() == null ? BigDecimal.ZERO : it.getUnitPrice();
            sum = sum.add(p.multiply(BigDecimal.valueOf(it.getQuantity() == null ? 0 : it.getQuantity())));
        }
        return sum;
    }
}
