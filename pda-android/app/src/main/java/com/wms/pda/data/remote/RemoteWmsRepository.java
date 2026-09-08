package com.wms.pda.data.remote;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.wms.pda.data.api.ApiClient;
import com.wms.pda.data.api.dto.Dto;
import com.wms.pda.data.model.DashboardSnapshot;
import com.wms.pda.data.model.Enums;
import com.wms.pda.data.model.InboundOrder;
import com.wms.pda.data.model.InventoryItem;
import com.wms.pda.data.model.OpLog;
import com.wms.pda.data.model.OutboundOrder;
import com.wms.pda.data.model.StocktakeOrder;
import com.wms.pda.data.repo.WmsCallback;
import com.wms.pda.data.repo.WmsRepository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 真实后端仓库实现。OkHttp + Gson + ApiClient,所有方法异步,回调切到主线程(ApiClient 内部已做)。
 *
 * 入参策略:
 * - POST /orders 入参不带 supplier/warehouse(后端字段),supplier 在 items 行级
 * - PDA "选商品" 走 searchInventory() 拿到 InventoryItem,字段已含 sku/name/...
 *
 * 响应翻译:
 * - 后端 InboundOrder.orderNo → PDA model.id(展示用)
 * - 后端 item.productName/sku/qty/location → PDA InboundLine.sku/name/qty/uom(uom 暂用 "件")
 */
public class RemoteWmsRepository implements WmsRepository {

    private final ApiClient api;

    public RemoteWmsRepository(ApiClient api) {
        this.api = api;
    }

    // ---- 鉴权 ----
    @Override
    public void login(String username, String password, WmsCallback<String> cb) {
        Map<String, String> body = new HashMap<>();
        body.put("username", username);
        body.put("password", password);
        // 登录响应 data 是 Map(JsonObject),不进 LoginResponse,直接拿 JsonObject
        api.post("/api/auth/login", body, JsonObject.class, new ApiClient.Callback<JsonObject>() {
            @Override public void onSuccess(JsonObject data) {
                if (data == null || !data.has("token")) {
                    cb.onError(-3, "登录响应缺少 token");
                    return;
                }
                String token = data.get("token").getAsString();
                String role = data.has("role") && !data.get("role").isJsonNull()
                        ? data.get("role").getAsString() : "OPERATOR";
                api.setToken(token);
                // 把 role 也通过一个静态通道传给 UI 层
                lastLoginRole = role;
                cb.onSuccess(token);
            }
            @Override public void onError(int code, String msg) { cb.onError(code, msg); }
        });
    }

    /** 最近一次登录的 role(LoginActivity 登录成功后读一次)。 */
    private volatile String lastLoginRole = "OPERATOR";
    public String getLastLoginRole() { return lastLoginRole; }

    @Override
    public void logout(WmsCallback<Void> cb) {
        api.post("/api/auth/logout", null, Void.class, new ApiClient.Callback<Void>() {
            @Override public void onSuccess(Void v) {
                api.setToken(null);
                cb.onSuccess(null);
            }
            @Override public void onError(int code, String msg) { cb.onError(code, msg); }
        });
    }

    // ---- 仪表盘 ----
    @Override
    public void loadDashboard(WmsCallback<DashboardSnapshot> cb) {
        api.get("/api/dashboard/stats", Dto.DashboardStats.class, new ApiClient.Callback<Dto.DashboardStats>() {
            @Override public void onSuccess(Dto.DashboardStats s) {
                DashboardSnapshot snap = new DashboardSnapshot(
                        s == null ? 0 : nz(s.todayInbound),
                        s == null ? 0 : nz(s.todayOutbound),
                        0,  // pendingStocktake 待 listStocktake 算,这里先放 0
                        s == null ? 0 : nz(s.lowStockCount),
                        new ArrayList<OpLog>()  // TODO: 接 /api/dashboard/recent-ops
                );
                cb.onSuccess(snap);
            }
            @Override public void onError(int code, String msg) { cb.onError(code, msg); }
        });
    }

    // ---- 入库 ----
    @Override
    public void listInbound(boolean onlyPending, WmsCallback<List<InboundOrder>> cb) {
        String status = onlyPending ? "DRAFT" : "ALL";
        String path = "/api/inbound/orders?status=" + status + "&page=1&pageSize=200";
        // 列表响应是 ApiResponse<Page<InboundOrder>> —— 简化:直接当 List 接
        api.get(path, ListInboundWrap.class, new ApiClient.Callback<ListInboundWrap>() {
            @Override public void onSuccess(ListInboundWrap w) {
                List<InboundOrder> out = new ArrayList<>();
                if (w == null || w.list == null) { cb.onSuccess(out); return; }
                for (Dto.InboundOrder o : w.list) out.add(Mappers.toModel(o));
                cb.onSuccess(out);
            }
            @Override public void onError(int code, String msg) { cb.onError(code, msg); }
        });
    }

    @Override
    public void createInbound(String supplier, String warehouse, String remark, WmsCallback<InboundOrder> cb) {
        Map<String, Object> body = new HashMap<>();
        if (remark != null && !remark.isEmpty()) body.put("remark", remark);
        // supplier/warehouse 不发,后端不入库表(PDA 端记到行级 supplier)
        api.post("/api/inbound/orders", body, Dto.InboundOrder.class, new ApiClient.Callback<Dto.InboundOrder>() {
            @Override public void onSuccess(Dto.InboundOrder o) {
                InboundOrder m = Mappers.toModel(o);
                m.supplier = supplier;
                m.warehouse = warehouse;
                cb.onSuccess(m);
            }
            @Override public void onError(int code, String msg) { cb.onError(code, msg); }
        });
    }

    @Override
    public void addInboundItem(String orderId, int productId, int quantity, String supplier,
                               String rowRemark, WmsCallback<InboundOrder> cb) {
        Map<String, Object> body = new HashMap<>();
        body.put("productId", productId);
        body.put("quantity", quantity);
        if (supplier != null && !supplier.isEmpty()) body.put("supplier", supplier);
        if (rowRemark != null && !rowRemark.isEmpty()) body.put("rowRemark", rowRemark);
        api.post("/api/inbound/orders/" + orderId + "/items", body,
                Dto.InboundOrder.class, new ApiClient.Callback<Dto.InboundOrder>() {
            @Override public void onSuccess(Dto.InboundOrder o) {
                cb.onSuccess(Mappers.toModel(o));
            }
            @Override public void onError(int code, String msg) { cb.onError(code, msg); }
        });
    }

    @Override
    public void commitInbound(String orderId, WmsCallback<InboundOrder> cb) {
        api.post("/api/inbound/orders/" + orderId + "/commit", null,
                Dto.InboundOrder.class, new ApiClient.Callback<Dto.InboundOrder>() {
            @Override public void onSuccess(Dto.InboundOrder o) { cb.onSuccess(Mappers.toModel(o)); }
            @Override public void onError(int code, String msg) { cb.onError(code, msg); }
        });
    }

    @Override
    public void cancelInbound(String orderId, WmsCallback<Void> cb) {
        api.post("/api/inbound/orders/" + orderId + "/cancel", null,
                Void.class, new ApiClient.Callback<Void>() {
            @Override public void onSuccess(Void v) { cb.onSuccess(null); }
            @Override public void onError(int code, String msg) { cb.onError(code, msg); }
        });
    }

    // ---- 出库 ----
    @Override
    public void listOutbound(boolean onlyPending, WmsCallback<List<OutboundOrder>> cb) {
        String status = onlyPending ? "DRAFT" : "ALL";
        api.get("/api/outbound/orders?status=" + status + "&page=1&pageSize=200",
                ListOutboundWrap.class, new ApiClient.Callback<ListOutboundWrap>() {
            @Override public void onSuccess(ListOutboundWrap w) {
                List<OutboundOrder> out = new ArrayList<>();
                if (w == null || w.list == null) { cb.onSuccess(out); return; }
                for (Dto.OutboundOrder o : w.list) out.add(Mappers.toModel(o));
                cb.onSuccess(out);
            }
            @Override public void onError(int code, String msg) { cb.onError(code, msg); }
        });
    }

    @Override
    public void createOutbound(String customer, String warehouse, String remark, WmsCallback<OutboundOrder> cb) {
        Map<String, Object> body = new HashMap<>();
        if (customer != null && !customer.isEmpty()) body.put("customer", customer);
        if (remark != null && !remark.isEmpty()) body.put("remark", remark);
        api.post("/api/outbound/orders", body, Dto.OutboundOrder.class, new ApiClient.Callback<Dto.OutboundOrder>() {
            @Override public void onSuccess(Dto.OutboundOrder o) {
                OutboundOrder m = Mappers.toModel(o);
                m.warehouse = warehouse;
                cb.onSuccess(m);
            }
            @Override public void onError(int code, String msg) { cb.onError(code, msg); }
        });
    }

    @Override
    public void submitOutboundScan(String orderId, String code, WmsCallback<OutboundOrder> cb) {
        Map<String, Object> body = new HashMap<>();
        body.put("code", code);
        api.post("/api/outbound/orders/" + orderId + "/scan", body,
                Dto.OutboundOrder.class, new ApiClient.Callback<Dto.OutboundOrder>() {
            @Override public void onSuccess(Dto.OutboundOrder o) { cb.onSuccess(Mappers.toModel(o)); }
            @Override public void onError(int code, String msg) { cb.onError(code, msg); }
        });
    }

    @Override
    public void commitOutbound(String orderId, WmsCallback<OutboundOrder> cb) {
        api.post("/api/outbound/orders/" + orderId + "/commit", null,
                Dto.OutboundOrder.class, new ApiClient.Callback<Dto.OutboundOrder>() {
            @Override public void onSuccess(Dto.OutboundOrder o) { cb.onSuccess(Mappers.toModel(o)); }
            @Override public void onError(int code, String msg) { cb.onError(code, msg); }
        });
    }

    @Override
    public void cancelOutbound(String orderId, WmsCallback<Void> cb) {
        api.post("/api/outbound/orders/" + orderId + "/cancel", null,
                Void.class, new ApiClient.Callback<Void>() {
            @Override public void onSuccess(Void v) { cb.onSuccess(null); }
            @Override public void onError(int code, String msg) { cb.onError(code, msg); }
        });
    }

    // ---- 库存 ----
    @Override
    public void searchInventory(String keyword, WmsCallback<List<InventoryItem>> cb) {
        String k = keyword == null ? "" : keyword.trim();
        String path = k.isEmpty()
                ? "/api/inventory/items?page=1&pageSize=200"
                : "/api/inventory/items?page=1&pageSize=200";  // 后端 inventory 不支持 keyword,简化
        api.get(path, ListInvItemWrap.class, new ApiClient.Callback<ListInvItemWrap>() {
            @Override public void onSuccess(ListInvItemWrap w) {
                List<InventoryItem> out = new ArrayList<>();
                if (w == null || w.list == null) { cb.onSuccess(out); return; }
                for (Dto.InventoryItem it : w.list) out.add(Mappers.toModel(it));
                cb.onSuccess(out);
            }
            @Override public void onError(int code, String msg) { cb.onError(code, msg); }
        });
    }

    @Override
    public void transfer(String sku, String fromWh, String toWh, int qty, WmsCallback<Void> cb) {
        // 后端 transfer 是按 inventory_item.id 操作的,PDA 端按 sku 不行;降级为弹错。
        cb.onError(501, "PDA 暂不支持按 SKU 调拨,请用扫码按 inventory_item 操作");
    }

    @Override
    public void scrap(String sku, String warehouse, int qty, String reason, WmsCallback<Void> cb) {
        cb.onError(501, "PDA 暂不支持按 SKU 报废,请用扫码按 inventory_item 操作");
    }

    @Override
    public void printBarcode(String sku, WmsCallback<Void> cb) {
        cb.onError(501, "条码打印请走 PC 端");
    }

    // ---- 盘点 ----
    @Override
    public void listStocktake(boolean onlyPending, WmsCallback<List<StocktakeOrder>> cb) {
        String status = onlyPending ? "DRAFT" : "ALL";
        api.get("/api/stock-checks?status=" + status + "&page=1&pageSize=200",
                ListStockWrap.class, new ApiClient.Callback<ListStockWrap>() {
            @Override public void onSuccess(ListStockWrap w) {
                List<StocktakeOrder> out = new ArrayList<>();
                if (w == null || w.list == null) { cb.onSuccess(out); return; }
                for (Dto.StockCheck o : w.list) out.add(Mappers.toModel(o));
                cb.onSuccess(out);
            }
            @Override public void onError(int code, String msg) { cb.onError(code, msg); }
        });
    }

    @Override
    public void createStocktake(String warehouse, StocktakeOrder.Mode mode, WmsCallback<StocktakeOrder> cb) {
        Map<String, Object> body = new HashMap<>();
        body.put("scope", mode == null ? "ALL" : mode.name());
        // 后端 location 维度需要 LOCATION 模式 + keyword=仓库
        if (mode == StocktakeOrder.Mode.ALL) {
            body.put("keyword", "");
        }
        api.post("/api/stock-checks", body, Dto.StockCheck.class, new ApiClient.Callback<Dto.StockCheck>() {
            @Override public void onSuccess(Dto.StockCheck o) {
                StocktakeOrder m = Mappers.toModel(o);
                m.warehouse = warehouse;  // 后端不返回 warehouse(在 keyword 里)
                cb.onSuccess(m);
            }
            @Override public void onError(int code, String msg) { cb.onError(code, msg); }
        });
    }

    @Override
    public void recordActualQty(String orderId, String sku, int qty, WmsCallback<Void> cb) {
        // 需要先拿到 stock_check_item 的 itemId;简化:让 UI 维护 sku→itemId 映射
        // 此方法 PDA 端目前主要用于手填 EditText,直接 call /scan 替代
        cb.onError(501, "PDA 端手填请用扫码模式(每次扫码 +1)");
    }

    @Override
    public void submitStocktakeScan(String orderId, String barcode, WmsCallback<StocktakeOrder> cb) {
        Map<String, Object> body = new HashMap<>();
        body.put("code", barcode);
        api.post("/api/stock-checks/" + orderId + "/scan", body,
                Dto.StockCheck.class, new ApiClient.Callback<Dto.StockCheck>() {
            @Override public void onSuccess(Dto.StockCheck o) { cb.onSuccess(Mappers.toModel(o)); }
            @Override public void onError(int code, String msg) { cb.onError(code, msg); }
        });
    }

    @Override
    public void commitStocktake(String orderId, WmsCallback<StocktakeOrder> cb) {
        api.post("/api/stock-checks/" + orderId + "/commit", null,
                Dto.StockCheck.class, new ApiClient.Callback<Dto.StockCheck>() {
            @Override public void onSuccess(Dto.StockCheck o) { cb.onSuccess(Mappers.toModel(o)); }
            @Override public void onError(int code, String msg) { cb.onError(code, msg); }
        });
    }

    // ---- helpers ----
    private static int nz(Integer i) { return i == null ? 0 : i; }

    /** 后端分页响应壳:`{list, total, page, pageSize}` */
    public static class ListInboundWrap {
        public List<Dto.InboundOrder> list;
        public Long total;
    }
    public static class ListOutboundWrap {
        public List<Dto.OutboundOrder> list;
        public Long total;
    }
    public static class ListInvItemWrap {
        public List<Dto.InventoryItem> list;
        public Long total;
    }
    public static class ListStockWrap {
        public List<Dto.StockCheck> list;
        public Long total;
    }
}