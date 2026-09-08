package com.wms.pda.data.model;

/**
 * 库存行(单个 SKU 在某仓库的当前在库情况)。
 */
public class InventoryItem {
    public Integer id;        // 后端 inventory_item.id(调 /scrap /transfer 用)
    public Integer productId; // 后端 product.id(调 /api/inbound/orders/{id}/items 用)
    public String sku;
    public String name;
    public String barcode;
    public String warehouse;
    public String location;
    public int onHand;       // 在库
    public int available;    // 可用
    public int locked;       // 锁定
    public String uom;
    public boolean lowStock; // 是否触发预警(用于仪表盘统计 & 列表角标)

    public InventoryItem() {}

    public InventoryItem(String sku, String name, String barcode, String warehouse,
                         String location, int onHand, int available, int locked,
                         String uom, boolean lowStock) {
        this.sku = sku;
        this.name = name;
        this.barcode = barcode;
        this.warehouse = warehouse;
        this.location = location;
        this.onHand = onHand;
        this.available = available;
        this.locked = locked;
        this.uom = uom;
        this.lowStock = lowStock;
    }
}
