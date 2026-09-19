package com.wms.pda.data.model;

/**
 * 库位(/api/locations,调拨目标 / 盘点「按库位」的下拉项)。
 *
 * 库位编码**全局唯一**(跨仓库不重复),所以发给后端的永远是裸 code;
 * warehouseName 只用于两级下拉的展示与分组。
 * warehouseName 为空 = 该库位未分配仓库,分组时归入「(未分配)」。
 */
public class Location {
    public Integer id;
    public String code;
    public String name;
    public Integer warehouseId;
    public String warehouseName;
    public Boolean enabled;
}
