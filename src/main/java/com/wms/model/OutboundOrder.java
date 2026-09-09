package com.wms.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public class OutboundOrder {

    /** 状态机常量:DRAFT=草稿 / COMMITTED=已提交 / CANCELLED=已取消 */
    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_COMMITTED = "COMMITTED";
    public static final String STATUS_CANCELLED = "CANCELLED";

    private Integer id;
    private String orderNo;
    private String customer;
    private String externalNo;
    private String remark;
    private String operator;
    private Integer totalQuantity;
    private Integer itemCount;
    private LocalDateTime createdAt;
    private String status;
    private List<OutboundOrderItem> items;
    // ===== UHF 派单字段 =====
    private LocalDateTime uhfDispatchedAt;
    private String uhfDispatchedBy;
    private String uhfDeviceCode;
    private LocalDateTime uhfClaimedAt;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public String getOrderNo() { return orderNo; }
    public void setOrderNo(String orderNo) { this.orderNo = orderNo; }
    public String getCustomer() { return customer; }
    public void setCustomer(String customer) { this.customer = customer; }
    public String getExternalNo() { return externalNo; }
    public void setExternalNo(String externalNo) { this.externalNo = externalNo; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public String getOperator() { return operator; }
    public void setOperator(String operator) { this.operator = operator; }
    public Integer getTotalQuantity() { return totalQuantity; }
    public void setTotalQuantity(Integer totalQuantity) { this.totalQuantity = totalQuantity; }
    public Integer getItemCount() { return itemCount; }
    public void setItemCount(Integer itemCount) { this.itemCount = itemCount; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public List<OutboundOrderItem> getItems() { return items; }
    public void setItems(List<OutboundOrderItem> items) { this.items = items; }

    public LocalDateTime getUhfDispatchedAt() { return uhfDispatchedAt; }
    public void setUhfDispatchedAt(LocalDateTime uhfDispatchedAt) { this.uhfDispatchedAt = uhfDispatchedAt; }
    public String getUhfDispatchedBy() { return uhfDispatchedBy; }
    public void setUhfDispatchedBy(String uhfDispatchedBy) { this.uhfDispatchedBy = uhfDispatchedBy; }
    public String getUhfDeviceCode() { return uhfDeviceCode; }
    public void setUhfDeviceCode(String uhfDeviceCode) { this.uhfDeviceCode = uhfDeviceCode; }
    public LocalDateTime getUhfClaimedAt() { return uhfClaimedAt; }
    public void setUhfClaimedAt(LocalDateTime uhfClaimedAt) { this.uhfClaimedAt = uhfClaimedAt; }

    /** 整单估算金额(出库价值,按当时单价) */
    public BigDecimal estimateTotalAmount() {
        if (items == null) return BigDecimal.ZERO;
        BigDecimal sum = BigDecimal.ZERO;
        for (OutboundOrderItem it : items) {
            BigDecimal p = it.getUnitPrice() == null ? BigDecimal.ZERO : it.getUnitPrice();
            sum = sum.add(p.multiply(BigDecimal.valueOf(it.getQuantity() == null ? 0 : it.getQuantity())));
        }
        return sum;
    }
}
