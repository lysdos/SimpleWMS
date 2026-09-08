package com.wms.model;

import java.time.LocalDateTime;

/**
 * 库位字典。商品表 product.location 字段以 code/name 字符串形式引用,
 * 不做数据库外键(避免级联删除与历史数据兼容性麻烦)。
 */
public class Location {

    private Integer id;
    private String code;        // 库位编码,如 "A-01-03"
    private String name;        // 库位名称,可空(便于直接用 code 显示)
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
