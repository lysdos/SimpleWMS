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
    public Integer quantity;    // 原入库数量
    public Integer remaining;   // 当前剩余 = onHand
    public String status;        // IN_STOCK / OUT / SCRAPPED / TRANSFERRED
    public Integer inboundOrderId;
    public Integer outboundOrderId;
    public String createdBy;
    public String createdAt;

    public InventoryItem() {}
}