package com.wms.pda.data.repo;

import com.wms.pda.data.model.DashboardSnapshot;
import com.wms.pda.data.model.InboundOrder;
import com.wms.pda.data.model.InventoryItem;
import com.wms.pda.data.model.OutboundOrder;
import com.wms.pda.data.model.StocktakeOrder;

import java.util.List;

/**
 * 异步仓库接口。所有方法走 callback,UI 不阻塞。
 * 实现:RemoteWmsRepository(真实后端)。
 */
public interface WmsRepository {

    // ---- 鉴权 ----
    void login(String username, String password, WmsCallback<String> cb);
    void logout(WmsCallback<Void> cb);

    // ---- 仪表盘 ----
    void loadDashboard(WmsCallback<DashboardSnapshot> cb);

    // ---- 入库(DRAFT 单流) ----
    void listInbound(boolean onlyPending, WmsCallback<List<InboundOrder>> cb);
    void getInboundOrder(String orderId, WmsCallback<InboundOrder> cb);
    void createInbound(String supplier, String warehouse, String remark, WmsCallback<InboundOrder> cb);
    /** 选商品+数量+库位+备注追加到 DRAFT 单。返回最新单(含 items[]);location 传 null = 用商品默认库位 */
    void addInboundItem(String orderId, int productId, int quantity, String supplier,
                        String rowRemark, String location, String productionDate, Integer shelfLifeDays,
                        WmsCallback<InboundOrder> cb);
    /** 修改 DRAFT 单头(外部单号/备注) */
    void updateInboundHeader(String orderId, String externalNo, String remark, WmsCallback<InboundOrder> cb);
    /** 修改 DRAFT 单明细行(可换商品,换时后端重建占位码);location 传 null = 保持原库位不动 */
    void updateInboundItem(String orderId, String itemId, int productId, int quantity,
                           String supplier, String rowRemark, String location, String productionDate,
                           Integer shelfLifeDays, WmsCallback<InboundOrder> cb);
    /** 删除 DRAFT 单明细行 */
    void deleteInboundItem(String orderId, String itemId, WmsCallback<InboundOrder> cb);
    /** 商品列表(添加明细选商品用,去重后的商品而非库存单元) */
    void listProducts(WmsCallback<List<com.wms.pda.data.model.Product>> cb);
    /** 供应商列表(下拉选项) */
    void listSuppliers(WmsCallback<List<com.wms.pda.data.model.Supplier>> cb);
    void commitInbound(String orderId, WmsCallback<InboundOrder> cb);
    void cancelInbound(String orderId, WmsCallback<Void> cb);

    // ---- 出库(DRAFT 单流 + 扫码累计) ----
    void listOutbound(boolean onlyPending, WmsCallback<List<OutboundOrder>> cb);
    void getOutboundOrder(String orderDbId, WmsCallback<OutboundOrder> cb);
    void createOutbound(String customer, String externalNo, String remark, WmsCallback<OutboundOrder> cb);
    /** 出库扫码:每扫一码调一次,后端 inventory_item 校验后累加 item[].quantity */
    void submitOutboundScan(String orderId, String code, WmsCallback<OutboundOrder> cb);
    /** UHF EPC 扫码:走后端 /scan-epc 专用接口(校验本单 RESERVED + 幂等) */
    void submitOutboundEpcScan(String orderId, String epc, WmsCallback<com.wms.pda.data.model.EpcScanResult> cb);
    /** 撤销本单全部扫码确认(仅 DRAFT 单),返回清除条数 */
    void resetOutboundScans(String orderId, WmsCallback<Integer> cb);
    void commitOutbound(String orderId, WmsCallback<OutboundOrder> cb);
    void cancelOutbound(String orderId, WmsCallback<Void> cb);
    /** 修改 DRAFT 出库单头(客户/外部单号/备注) */
    void updateOutboundHeader(String orderId, String customer, String externalNo,
                              String remark, WmsCallback<OutboundOrder> cb);
    /** 给 DRAFT 出库单追加一条明细(后端同时锁定库存单元)。返回最新单(含 lines) */
    void addOutboundItem(String orderId, int productId, int quantity, String rowRemark,
                         WmsCallback<OutboundOrder> cb);

    // ---- 库存 ----
    /** @param status 空=全部状态;@param expiryStatus 空=全部,EXPIRING=临期,EXPIRED=已过期 */
    void searchInventory(String keyword, String status, String expiryStatus,
                         WmsCallback<List<InventoryItem>> cb);
    /**
     * 启用库位列表(调拨目标 / 盘点「按库位」下拉用)。
     * 带 warehouseName,调用方按仓库做两级选择(见 ui.common.LocationPicker);
     * 发给后端的仍是裸 code。
     */
    void listLocations(WmsCallback<List<com.wms.pda.data.model.Location>> cb);
    /** 调拨库存单元到目标库位 */
    void transfer(int itemId, String toLocation, WmsCallback<Void> cb);
    /** 报废库存单元 */
    void scrap(int itemId, String reason, WmsCallback<Void> cb);

    // ---- 盘点 ----
    void listStocktake(boolean onlyPending, WmsCallback<List<StocktakeOrder>> cb);
    void getStocktake(String orderDbId, WmsCallback<StocktakeOrder> cb);
    /** @param warehouseId 仅 scope=WAREHOUSE 时需要;keyword 为可选的库位码列表(逗号分隔,空=整个仓库) */
    void createStocktake(String scope, String keyword, Integer warehouseId,
                         String remark, WmsCallback<StocktakeOrder> cb);
    /** 手填实盘数量;countedQty = -1 表示清空 */
    void recordActualQty(String orderId, String sku, int qty, WmsCallback<Void> cb);
    /** 扫码定位行;单外码/非在库码由后端自动追加盘盈行 */
    void submitStocktakeScan(String orderId, String barcode, WmsCallback<StocktakeOrder> cb);
    /** 删除盘点明细行(仅 DRAFT);用于清理盘盈自动追加的多余行 */
    void deleteStocktakeItem(String orderDbId, String itemId, WmsCallback<Void> cb);
    void commitStocktake(String orderId, WmsCallback<StocktakeOrder> cb);

    // ---- 商品下拉:复用 searchInventory("") 拿到所有 InventoryItem,字段已够用(sku/name/barcode/uom) ----
}