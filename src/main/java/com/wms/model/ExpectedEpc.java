package com.wms.model;

/**
 * UHF RFID 出库扫码:DTO,给前端 / UHF-Rader 展示"应该扫哪些 EPC"。
 *
 * 演示口径:EPC = inventory_item.code(24 字符,同 WMS 打印的条码)。
 * 真实生产:UHF 标签有自己的 ID(通常 12 字节 24 hex),需要在入库时
 *         单独扫描标签与 code 关联,届时 inventory_item 会加 epc 列,
 *         本 DTO 的 epc 字段也改成读 inventory_item.epc。
 */
public class ExpectedEpc {
    public final int productId;
    public final String sku;
    public final String productName;
    public final String code;       // 24 字符 code(WMS 系统内的"条码")
    public final String epc;        // 演示口径下与 code 相同(便于 UHF-Rader 对照)
    public final int alreadyScanned;
    public final int stillNeeded;

    public ExpectedEpc(int productId, String sku, String productName, String code,
                        int alreadyScanned, int stillNeeded) {
        this.productId = productId;
        this.sku = sku;
        this.productName = productName;
        this.code = code;
        // 演示口径:epc == code。生产时改为读 inventory_item.epc 字段
        this.epc = code;
        this.alreadyScanned = alreadyScanned;
        this.stillNeeded = stillNeeded;
    }
}
