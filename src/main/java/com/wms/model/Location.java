package com.wms.model;

import java.time.LocalDateTime;

/**
 * 库位字典。商品表 product.location 字段以 code/name 字符串形式引用,
 * 不做数据库外键(避免级联删除与历史数据兼容性麻烦)。
 *
 * 库位编码**全局唯一**(跨仓库不重复),仓库只是它的上层分组
 * (warehouse_id)。所以库存单元、出入库明细里存的裸库位码依然能唯一定位。
 */
public class Location {

    /**
     * 兜底库位码。商品没设库位、入库明细也没指定库位时落到这里。
     * 这个字符串是**载荷性的**:入库(写 inventory_item.location_code)、
     * 出库(锁定/推导商品库位)、商品保存(空值归一)都拿它当兜底,
     * 改它等于改那几处的行为 —— 所以只留一份,不要在各处再写字面量。
     */
    public static final String FALLBACK_CODE = "默认库位";

    private Integer id;
    private String code;        // 库位编码,如 "A-01-03"
    private String name;        // 库位名称,可空(便于直接用 code 显示)
    private Integer warehouseId;   // 所属仓库 → warehouse.id(空=未分配)
    private String warehouseName;  // 展示用:仓库名(列表/详情 join 带出,非表字段)
    private String remark;
    private Boolean enabled;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Integer getWarehouseId() { return warehouseId; }
    public void setWarehouseId(Integer warehouseId) { this.warehouseId = warehouseId; }
    public String getWarehouseName() { return warehouseName; }
    public void setWarehouseName(String warehouseName) { this.warehouseName = warehouseName; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    /** 展示用:有 name 用 name,否则用 code */
    public String getDisplayName() {
        return (name != null && !name.isBlank()) ? name : code;
    }
}
