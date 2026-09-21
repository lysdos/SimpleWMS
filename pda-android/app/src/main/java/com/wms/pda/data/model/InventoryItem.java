package com.wms.pda.data.model;

/**
 * 库存单元(每件实物一行)。
 * 对接 /api/inventory/items,字段 1:1 对应后端 JSON(去掉 PDA 端没意义的 lowStock)。
 */
public class InventoryItem {
    public Integer id;          // 后端 inventory_item.id
    public String code;          // 后端 24 位 code
    public Integer productId;
    public String sku;
    public String productName;
    public String locationCode;  // 后端库位
    public String warehouseName; // 库位所属仓库名(可空;查不到就只显示库位码)
    public Integer quantity;    // 原入库数量
    public Integer remaining;   // 当前剩余 = onHand
    public String status;        // IN_STOCK / OUT / SCRAPPED / TRANSFERRED
    public String expiryDate;    // 到期日 yyyy-MM-dd(可空)
    public Integer expiryWarnDays; // 临期预警天数(可空=按 30 天)
    public Integer inboundOrderId;
    public Integer outboundOrderId;
    public String createdBy;
    public String createdAt;

    public InventoryItem() {}
}