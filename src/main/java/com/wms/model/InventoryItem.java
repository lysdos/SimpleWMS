package com.wms.model;

import java.time.LocalDateTime;

/**
 * 库存单元 — 每个入库批次一行,带唯一 24 位编码。
 */
public class InventoryItem {

    /** 在库 — 已生效(commit 后);remaining>0 计入总库存 */
    public static final String STATUS_IN_STOCK     = "IN_STOCK";
    /** 已出库(quantity 全部消耗) */
    public static final String STATUS_OUT          = "OUT";
    /** 报废 */
    public static final String STATUS_SCRAPPED     = "SCRAPPED";
    /** 已调拨(原位置全部迁出) */
    public static final String STATUS_TRANSFERRED  = "TRANSFERRED";
    /** 待入库 — 仅作 24 位码占位(remaining=0);入库单 commit 后变为 IN_STOCK */
    public static final String STATUS_PENDING      = "PENDING";
    /** DRAFT 出库单已锁定但未 commit;取消时回退到 IN_STOCK,commit 后变为 OUT */
    public static final String STATUS_RESERVED     = "RESERVED";

    private Integer id;
    private String code;                // 24 位编码,UNIQUE
    private Integer productId;
    private String sku;
    private String productName;
    private String locationCode;        // 当前库位(调拨后会变)
    private Integer quantity;           // 原始入库数量
    private Integer remaining;          // 当前剩余数量(出库/报废会减少)
    private String status;

    private Integer inboundOrderId;
    private Integer inboundItemId;
    private Integer outboundOrderId;

    private LocalDateTime scrappedAt;
    private String scrappedRemark;

    private LocalDateTime transferredAt;
    private String transferredFrom;
    private String transferredTo;

    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public Integer getProductId() { return productId; }
    public void setProductId(Integer productId) { this.productId = productId; }
    public String getSku() { return sku; }
    public void setSku(String sku) { this.sku = sku; }
    public String getProductName() { return productName; }
    public void setProductName(String productName) { this.productName = productName; }
    public String getLocationCode() { return locationCode; }
    public void setLocationCode(String locationCode) { this.locationCode = locationCode; }
    public Integer getQuantity() { return quantity; }
    public void setQuantity(Integer quantity) { this.quantity = quantity; }
    public Integer getRemaining() { return remaining; }
    public void setRemaining(Integer remaining) { this.remaining = remaining; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getInboundOrderId() { return inboundOrderId; }
    public void setInboundOrderId(Integer inboundOrderId) { this.inboundOrderId = inboundOrderId; }
    public Integer getInboundItemId() { return inboundItemId; }
    public void setInboundItemId(Integer inboundItemId) { this.inboundItemId = inboundItemId; }
    public Integer getOutboundOrderId() { return outboundOrderId; }
    public void setOutboundOrderId(Integer outboundOrderId) { this.outboundOrderId = outboundOrderId; }
    public LocalDateTime getScrappedAt() { return scrappedAt; }
    public void setScrappedAt(LocalDateTime scrappedAt) { this.scrappedAt = scrappedAt; }
    public String getScrappedRemark() { return scrappedRemark; }
    public void setScrappedRemark(String scrappedRemark) { this.scrappedRemark = scrappedRemark; }
    public LocalDateTime getTransferredAt() { return transferredAt; }
    public void setTransferredAt(LocalDateTime transferredAt) { this.transferredAt = transferredAt; }
    public String getTransferredFrom() { return transferredFrom; }
    public void setTransferredFrom(String transferredFrom) { this.transferredFrom = transferredFrom; }
    public String getTransferredTo() { return transferredTo; }
    public void setTransferredTo(String transferredTo) { this.transferredTo = transferredTo; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
