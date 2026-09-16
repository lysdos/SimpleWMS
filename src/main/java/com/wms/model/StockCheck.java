package com.wms.model;

import java.time.LocalDateTime;
import java.util.List;

public class StockCheck {
    public static final String STATUS_DRAFT     = "DRAFT";
    public static final String STATUS_COMMITTED = "COMMITTED";
    public static final String STATUS_CANCELLED = "CANCELLED";

    public static final String SCOPE_ALL      = "ALL";
    public static final String SCOPE_SKU      = "SKU";
    public static final String SCOPE_LOCATION = "LOCATION";

    private Integer id;
    private String checkNo;
    private String scope;
    private String scopeKeyword;
    private String remark;
    private String createdBy;
    private String status;
    private Integer totalCount;
    private Integer countedCount;
    private Integer diffCount;
    private LocalDateTime committedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    private List<StockCheckItem> items;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public String getCheckNo() { return checkNo; }
    public void setCheckNo(String checkNo) { this.checkNo = checkNo; }
    public String getScope() { return scope; }
    public void setScope(String scope) { this.scope = scope; }
    public String getScopeKeyword() { return scopeKeyword; }
    public void setScopeKeyword(String scopeKeyword) { this.scopeKeyword = scopeKeyword; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getTotalCount() { return totalCount; }
    public void setTotalCount(Integer totalCount) { this.totalCount = totalCount; }
    public Integer getCountedCount() { return countedCount; }
    public void setCountedCount(Integer countedCount) { this.countedCount = countedCount; }
    public Integer getDiffCount() { return diffCount; }
    public void setDiffCount(Integer diffCount) { this.diffCount = diffCount; }
    public LocalDateTime getCommittedAt() { return committedAt; }
    public void setCommittedAt(LocalDateTime committedAt) { this.committedAt = committedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public List<StockCheckItem> getItems() { return items; }
    public void setItems(List<StockCheckItem> items) { this.items = items; }
}
