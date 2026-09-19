package com.wms.model;

import java.time.LocalDateTime;

/**
 * 仓库:库位的上层分组,每个库位归属一个仓库(location.warehouse_id)。
 *
 * 库位编码仍**全局唯一**(不随仓库重复),所以"按库位码定位库存"的既有逻辑
 * (inventory_item.location_code / 出入库单明细的 location 冗余等)不受影响;
 * 要按仓库维度筛选时,join location 即可。
 */
public class Warehouse {

    private Integer id;
    private String code;        // 仓库编码,如 "WH1"
    private String name;        // 仓库名称,如 "上海一号仓"
    private String remark;
    private Boolean enabled;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /** 展示用:名下库位数(列表查询带出,非表字段) */
    private Integer locationCount;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public Integer getLocationCount() { return locationCount; }
    public void setLocationCount(Integer locationCount) { this.locationCount = locationCount; }
}
