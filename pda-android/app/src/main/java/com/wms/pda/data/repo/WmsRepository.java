package com.wms.pda.data.repo;

import com.wms.pda.data.model.DashboardSnapshot;
import com.wms.pda.data.model.InboundOrder;
import com.wms.pda.data.model.InventoryItem;
import com.wms.pda.data.model.OutboundOrder;
import com.wms.pda.data.model.StocktakeOrder;

import java.util.List;

/**
 * 异步仓库接口。所有方法走 callback,UI 不阻塞。
 * 实现:MockWmsRepository(内存) / RemoteWmsRepository(真实后端)。
 */
public interface WmsRepository {

    // ---- 鉴权 ----
    void login(String username, String password, WmsCallback<String> cb);
    void logout(WmsCallback<Void> cb);

    // ---- 仪表盘 ----
    void loadDashboard(WmsCallback<DashboardSnapshot> cb);

    // ---- 入库(DRAFT 单流) ----
    void listInbound(boolean onlyPending, WmsCallback<List<InboundOrder>> cb);
    void createInbound(String supplier, String warehouse, String remark, WmsCallback<InboundOrder> cb);
    /** 选商品+数量+库位+备注追加到 DRAFT 单。返回最新单(含 items[]) */
    void addInboundItem(String orderId, int productId, int quantity, String supplier,
                        String rowRemark, WmsCallback<InboundOrder> cb);
    void commitInbound(String orderId, WmsCallback<InboundOrder> cb);
    void cancelInbound(String orderId, WmsCallback<Void> cb);

    // ---- 出库(DRAFT 单流 + 扫码累计) ----
    void listOutbound(boolean onlyPending, WmsCallback<List<OutboundOrder>> cb);
    void createOutbound(String customer, String warehouse, String remark, WmsCallback<OutboundOrder> cb);
    /** 出库扫码:每扫一码调一次,后端 inventory_item 校验后累加 item[].quantity */
    void submitOutboundScan(String orderId, String code, WmsCallback<OutboundOrder> cb);
    void commitOutbound(String orderId, WmsCallback<OutboundOrder> cb);
    void cancelOutbound(String orderId, WmsCallback<Void> cb);

    // ---- 库存 ----
    void searchInventory(String keyword, WmsCallback<List<InventoryItem>> cb);
    void transfer(String sku, String fromWh, String toWh, int qty, WmsCallback<Void> cb);
    void scrap(String sku, String warehouse, int qty, String reason, WmsCallback<Void> cb);
    void printBarcode(String sku, WmsCallback<Void> cb);

    // ---- 盘点 ----
    void listStocktake(boolean onlyPending, WmsCallback<List<StocktakeOrder>> cb);
    void createStocktake(String warehouse, StocktakeOrder.Mode mode, WmsCallback<StocktakeOrder> cb);
    /** 手填实盘数量;countedQty = -1 表示清空 */
    void recordActualQty(String orderId, String sku, int qty, WmsCallback<Void> cb);
    /** 扫码定位行,countedQty += 1 */
    void submitStocktakeScan(String orderId, String barcode, WmsCallback<StocktakeOrder> cb);
    void commitStocktake(String orderId, WmsCallback<StocktakeOrder> cb);

    // ---- 商品下拉:复用 searchInventory("") 拿到所有 InventoryItem,字段已够用(sku/name/barcode/uom) ----
}