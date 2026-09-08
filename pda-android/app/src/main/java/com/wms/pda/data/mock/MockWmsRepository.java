package com.wms.pda.data.mock;

import android.os.Handler;
import android.os.Looper;

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
import java.util.Arrays;
import java.util.List;

/**
 * 仓库的 Mock 实现(全部数据存在内存里)。所有方法在主线程立即回调,行为等价旧同步版。
 * UI 行为真实可见,接后端时整体替换为 RemoteWmsRepository(签名一致)。
 */
public class MockWmsRepository implements WmsRepository {

    private static MockWmsRepository INSTANCE;

    public static synchronized MockWmsRepository get() {
        if (INSTANCE == null) INSTANCE = new MockWmsRepository();
        return INSTANCE;
    }

    private final List<InboundOrder> inboundOrders = new ArrayList<>();
    private final List<OutboundOrder> outboundOrders = new ArrayList<>();
    private final List<InventoryItem> inventory = new ArrayList<>();
    private final List<StocktakeOrder> stocktakes = new ArrayList<>();
    private final List<OpLog> ops = new ArrayList<>();
    private int inboundSeq = 1001, outboundSeq = 2001, stocktakeSeq = 3001;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private MockWmsRepository() { seed(); }

    /** 立即在调用线程同步执行 body,再切回主线程触发回调(模拟异步)。 */
    private <T> void reply(WmsCallback<T> cb, T value) {
        if (cb == null) return;
        ui.post(() -> cb.onSuccess(value));
    }
    private void replyErr(WmsCallback<?> cb, int code, String msg) {
        if (cb == null) return;
        ui.post(() -> cb.onError(code, msg));
    }

    private void seed() {
        inventory.add(new InventoryItem("SKU-1001", "蓝牙条码扫描枪",   "6901234567001", "主仓", "A-01-03", 48, 42, 6, "台", false));
        inventory.add(new InventoryItem("SKU-1002", "热敏标签打印机",   "6901234567002", "主仓", "A-01-04", 12, 10, 2, "台", true));
        inventory.add(new InventoryItem("SKU-1003", "工业 PDA 终端",   "6901234567003", "主仓", "A-02-01",  6,  4, 2, "台", true));
        inventory.add(new InventoryItem("SKU-1004", "不干胶标签 50x30", "6901234567004", "主仓", "B-03-02", 320, 320, 0, "卷", false));
        inventory.add(new InventoryItem("SKU-1005", "碳带 110mm x 300m", "6901234567005", "主仓", "B-03-05", 18, 16, 2, "卷", false));
        inventory.add(new InventoryItem("SKU-1006", "周转箱 600x400",   "6901234567006", "分仓", "C-01-01", 75, 70, 5, "个", false));
        inventory.add(new InventoryItem("SKU-1007", "防静电手套",       "6901234567007", "分仓", "C-02-02", 200, 200, 0, "双", false));

        inboundOrders.add(new InboundOrder(
                "RK" + inboundSeq++, "上海某科技公司", "主仓", "采购到货",
                Enums.DocStatus.PENDING, "2026-09-08 09:12",
                Arrays.asList(
                        new InboundOrder.InboundLine("SKU-1001", "蓝牙条码扫描枪", "6901234567001", 10, "台"),
                        new InboundOrder.InboundLine("SKU-1002", "热敏标签打印机", "6901234567002",  4, "台"))));

        inboundOrders.add(new InboundOrder(
                "RK" + inboundSeq++, "深圳某物联公司", "分仓", "调拨入库",
                Enums.DocStatus.PENDING, "2026-09-08 10:05",
                Arrays.asList(
                        new InboundOrder.InboundLine("SKU-1006", "周转箱 600x400", "6901234567006", 30, "个"))));

        inboundOrders.add(new InboundOrder(
                "RK" + (inboundSeq - 10), "上海某科技公司", "主仓", "",
                Enums.DocStatus.DONE, "2026-09-07 16:40",
                Arrays.asList(new InboundOrder.InboundLine("SKU-1004", "不干胶标签 50x30", "6901234567004", 80, "卷"))));

        outboundOrders.add(new OutboundOrder(
                "CK" + outboundSeq++, "生产线 A", "主仓", "工单领料",
                Enums.DocStatus.PENDING, "2026-09-08 08:30",
                Arrays.asList(
                        new OutboundOrder.Line("SKU-1001", "蓝牙条码扫描枪", "6901234567001", 2, "台"),
                        new OutboundOrder.Line("SKU-1004", "不干胶标签 50x30", "6901234567004", 20, "卷"))));

        outboundOrders.add(new OutboundOrder(
                "CK" + outboundSeq++, "工程部", "主仓", "部门领用",
                Enums.DocStatus.PENDING, "2026-09-08 11:00",
                Arrays.asList(
                        new OutboundOrder.Line("SKU-1007", "防静电手套", "6901234567007", 10, "双"))));

        stocktakes.add(new StocktakeOrder(
                "PD" + stocktakeSeq++, "主仓", StocktakeOrder.Mode.ALL, Enums.DocStatus.PENDING,
                "2026-09-08 07:30",
                Arrays.asList(
                        new StocktakeOrder.Line("SKU-1001", "蓝牙条码扫描枪", "6901234567001", 48, -1, "A-01-03"),
                        new StocktakeOrder.Line("SKU-1002", "热敏标签打印机", "6901234567002", 12, -1, "A-01-04"),
                        new StocktakeOrder.Line("SKU-1003", "工业 PDA 终端", "6901234567003",  6, -1, "A-02-01"))));

        ops.add(new OpLog("2026-09-08 11:20", "入库", "RK1003", "扫描入库 2 行 4 件"));
        ops.add(new OpLog("2026-09-08 10:55", "盘点", "PD3001", "生成全盘单"));
        ops.add(new OpLog("2026-09-08 10:05", "出库", "CK2001", "生产线 A 领料"));
        ops.add(new OpLog("2026-09-08 09:12", "入库", "RK1001", "创建入库单"));
        ops.add(new OpLog("2026-09-07 16:40", "入库", "RK0993", "完成入库 80 卷标签"));
    }

    // ---- 鉴权 ----
    @Override public void login(String username, String password, WmsCallback<String> cb) { reply(cb, "mock-token-" + username); }
    @Override public void logout(WmsCallback<Void> cb) { reply(cb, null); }

    // ---- 仪表盘 ----
    @Override
    public void loadDashboard(WmsCallback<DashboardSnapshot> cb) {
        int inToday = 0, outToday = 0;
        for (InboundOrder o : inboundOrders)   if (o.createdAt.startsWith("2026-09-08")) inToday++;
        for (OutboundOrder o : outboundOrders) if (o.createdAt.startsWith("2026-09-08")) outToday++;
        int pendingSt = 0;
        for (StocktakeOrder s : stocktakes) if (s.status == Enums.DocStatus.PENDING) pendingSt++;
        int low = 0;
        for (InventoryItem i : inventory) if (i.lowStock) low++;
        reply(cb, new DashboardSnapshot(inToday, outToday, pendingSt, low, new ArrayList<>(ops)));
    }

    // ---- 入库 ----
    @Override
    public void listInbound(boolean onlyPending, WmsCallback<List<InboundOrder>> cb) {
        List<InboundOrder> r = new ArrayList<>();
        for (InboundOrder o : inboundOrders) if (!onlyPending || o.status == Enums.DocStatus.PENDING) r.add(o);
        reply(cb, r);
    }

    @Override
    public void createInbound(String supplier, String warehouse, String remark, WmsCallback<InboundOrder> cb) {
        InboundOrder o = new InboundOrder(
                "RK" + inboundSeq++, supplier, warehouse, remark,
                Enums.DocStatus.PENDING, "2026-09-08 12:00", new ArrayList<>());
        inboundOrders.add(0, o);
        ops.add(0, new OpLog("2026-09-08 12:00", "入库", o.id, "创建入库单"));
        reply(cb, o);
    }

    @Override
    public void addInboundItem(String orderId, int productId, int quantity, String supplier,
                               String rowRemark, WmsCallback<InboundOrder> cb) {
        InboundOrder o = findInbound(orderId);
        if (o == null) { replyErr(cb, 404, "单据不存在"); return; }
        // mock 简化:不查 productId,用 qty 占位
        o.lines.add(new InboundOrder.InboundLine("SKU-" + productId, "商品" + productId, "", quantity, "件"));
        reply(cb, o);
    }

    @Override
    public void commitInbound(String orderId, WmsCallback<InboundOrder> cb) {
        InboundOrder o = findInbound(orderId);
        if (o == null) { replyErr(cb, 404, "单据不存在"); return; }
        o.status = Enums.DocStatus.DONE;
        for (InboundOrder.InboundLine l : o.lines) {
            for (InventoryItem i : inventory) {
                if (i.sku.equals(l.sku)) {
                    i.onHand += l.qty;
                    i.available += l.qty;
                    break;
                }
            }
        }
        ops.add(0, new OpLog("2026-09-08 12:30", "入库", o.id, "完成入库 " + o.lines.size() + " 行"));
        reply(cb, o);
    }

    @Override
    public void cancelInbound(String orderId, WmsCallback<Void> cb) {
        InboundOrder o = findInbound(orderId);
        if (o == null) { replyErr(cb, 404, "单据不存在"); return; }
        o.status = Enums.DocStatus.CANCELLED;
        reply(cb, null);
    }

    private InboundOrder findInbound(String id) {
        for (InboundOrder o : inboundOrders) if (o.id.equals(id)) return o;
        return null;
    }

    // ---- 出库 ----
    @Override
    public void listOutbound(boolean onlyPending, WmsCallback<List<OutboundOrder>> cb) {
        List<OutboundOrder> r = new ArrayList<>();
        for (OutboundOrder o : outboundOrders) if (!onlyPending || o.status == Enums.DocStatus.PENDING) r.add(o);
        reply(cb, r);
    }

    @Override
    public void createOutbound(String customer, String warehouse, String remark, WmsCallback<OutboundOrder> cb) {
        OutboundOrder o = new OutboundOrder(
                "CK" + outboundSeq++, customer, warehouse, remark,
                Enums.DocStatus.PENDING, "2026-09-08 12:10", new ArrayList<>());
        outboundOrders.add(0, o);
        ops.add(0, new OpLog("2026-09-08 12:10", "出库", o.id, "创建出库单"));
        reply(cb, o);
    }

    @Override
    public void submitOutboundScan(String orderId, String code, WmsCallback<OutboundOrder> cb) {
        OutboundOrder o = findOutbound(orderId);
        if (o == null) { replyErr(cb, 404, "单据不存在"); return; }
        for (OutboundOrder.Line l : o.lines) {
            if (code.equals(l.barcode)) { l.qty += 1; reply(cb, o); return; }
        }
        // 反查库存条码
        for (InventoryItem i : inventory) {
            if (code.equals(i.barcode)) {
                o.lines.add(new OutboundOrder.Line(i.sku, i.name, i.barcode, 1, i.uom));
                reply(cb, o); return;
            }
        }
        o.lines.add(new OutboundOrder.Line("?", "未知条码 " + code, code, 1, "件"));
        reply(cb, o);
    }

    @Override
    public void commitOutbound(String orderId, WmsCallback<OutboundOrder> cb) {
        OutboundOrder o = findOutbound(orderId);
        if (o == null) { replyErr(cb, 404, "单据不存在"); return; }
        o.status = Enums.DocStatus.DONE;
        for (OutboundOrder.Line l : o.lines) {
            for (InventoryItem i : inventory) {
                if (i.sku.equals(l.sku)) {
                    i.onHand = Math.max(0, i.onHand - l.qty);
                    i.available = Math.max(0, i.available - l.qty);
                    break;
                }
            }
        }
        ops.add(0, new OpLog("2026-09-08 12:40", "出库", o.id, "完成出库 " + o.lines.size() + " 行"));
        reply(cb, o);
    }

    @Override
    public void cancelOutbound(String orderId, WmsCallback<Void> cb) {
        OutboundOrder o = findOutbound(orderId);
        if (o == null) { replyErr(cb, 404, "单据不存在"); return; }
        o.status = Enums.DocStatus.CANCELLED;
        reply(cb, null);
    }

    private OutboundOrder findOutbound(String id) {
        for (OutboundOrder o : outboundOrders) if (o.id.equals(id)) return o;
        return null;
    }

    // ---- 库存 ----
    @Override
    public void searchInventory(String keyword, WmsCallback<List<InventoryItem>> cb) {
        if (keyword == null || keyword.trim().isEmpty()) { reply(cb, new ArrayList<>(inventory)); return; }
        String k = keyword.trim().toLowerCase();
        List<InventoryItem> r = new ArrayList<>();
        for (InventoryItem i : inventory) {
            if (i.sku.toLowerCase().contains(k)
                    || i.name.toLowerCase().contains(k)
                    || i.barcode.toLowerCase().contains(k)) r.add(i);
        }
        reply(cb, r);
    }

    @Override
    public void transfer(String sku, String fromWh, String toWh, int qty, WmsCallback<Void> cb) {
        InventoryItem src = null, dst = null;
        for (InventoryItem i : inventory) {
            if (i.sku.equals(sku) && i.warehouse.equals(fromWh)) src = i;
            if (i.sku.equals(sku) && i.warehouse.equals(toWh))   dst = i;
        }
        if (src == null || qty <= 0 || qty > src.available) { replyErr(cb, 400, "数量超过可用数"); return; }
        src.onHand -= qty; src.available -= qty;
        if (dst == null) {
            dst = new InventoryItem(src.sku, src.name, src.barcode, toWh, "—",
                    qty, qty, 0, src.uom, false);
            inventory.add(dst);
        } else {
            dst.onHand += qty; dst.available += qty;
        }
        ops.add(0, new OpLog("2026-09-08 13:00", "调拨", sku, fromWh + " → " + toWh + " " + qty + src.uom));
        reply(cb, null);
    }

    @Override
    public void scrap(String sku, String warehouse, int qty, String reason, WmsCallback<Void> cb) {
        for (InventoryItem i : inventory) {
            if (i.sku.equals(sku) && i.warehouse.equals(warehouse)) {
                if (qty <= 0 || qty > i.onHand) { replyErr(cb, 400, "数量超过在库数"); return; }
                i.onHand -= qty;
                i.available -= qty;
                break;
            }
        }
        ops.add(0, new OpLog("2026-09-08 13:10", "报废", sku, warehouse + " " + qty + " 件 · " + reason));
        reply(cb, null);
    }

    @Override
    public void printBarcode(String sku, WmsCallback<Void> cb) {
        ops.add(0, new OpLog("2026-09-08 13:20", "打印", sku, "已生成条码 PDF (原型)"));
        reply(cb, null);
    }

    // ---- 盘点 ----
    @Override
    public void listStocktake(boolean onlyPending, WmsCallback<List<StocktakeOrder>> cb) {
        List<StocktakeOrder> r = new ArrayList<>();
        for (StocktakeOrder s : stocktakes) if (!onlyPending || s.status == Enums.DocStatus.PENDING) r.add(s);
        reply(cb, r);
    }

    @Override
    public void createStocktake(String warehouse, StocktakeOrder.Mode mode, WmsCallback<StocktakeOrder> cb) {
        List<StocktakeOrder.Line> lines = new ArrayList<>();
        for (InventoryItem i : inventory) {
            if (i.warehouse.equals(warehouse)) {
                lines.add(new StocktakeOrder.Line(i.sku, i.name, i.barcode, i.onHand, -1, i.location));
            }
        }
        StocktakeOrder s = new StocktakeOrder(
                "PD" + stocktakeSeq++, warehouse, mode, Enums.DocStatus.PENDING,
                "2026-09-08 13:30", lines);
        stocktakes.add(0, s);
        ops.add(0, new OpLog("2026-09-08 13:30", "盘点", s.id, "创建" + mode.label + "单"));
        reply(cb, s);
    }

    @Override
    public void recordActualQty(String orderId, String sku, int qty, WmsCallback<Void> cb) {
        StocktakeOrder s = findStocktake(orderId);
        if (s == null) { replyErr(cb, 404, "单据不存在"); return; }
        for (StocktakeOrder.Line l : s.lines) {
            if (l.sku.equals(sku)) { l.actualQty = qty; reply(cb, null); return; }
        }
        replyErr(cb, 404, "行不存在");
    }

    @Override
    public void submitStocktakeScan(String orderId, String barcode, WmsCallback<StocktakeOrder> cb) {
        StocktakeOrder s = findStocktake(orderId);
        if (s == null) { replyErr(cb, 404, "单据不存在"); return; }
        for (StocktakeOrder.Line l : s.lines) {
            if (barcode.equals(l.barcode)) {
                if (l.actualQty < 0) l.actualQty = l.bookQty + 1;
                else l.actualQty += 1;
                reply(cb, s); return;
            }
        }
        reply(cb, s);  // 不在单子里,静默
    }

    @Override
    public void commitStocktake(String orderId, WmsCallback<StocktakeOrder> cb) {
        StocktakeOrder s = findStocktake(orderId);
        if (s == null) { replyErr(cb, 404, "单据不存在"); return; }
        s.status = Enums.DocStatus.DONE;
        for (StocktakeOrder.Line l : s.lines) {
            if (l.actualQty < 0) continue;
            int diff = l.actualQty - l.bookQty;
            for (InventoryItem i : inventory) {
                if (i.sku.equals(l.sku) && i.warehouse.equals(s.warehouse)) {
                    i.onHand += diff;
                    i.available += diff;
                    break;
                }
            }
        }
        ops.add(0, new OpLog("2026-09-08 14:00", "盘点", s.id, "提交盘点,已校正库存"));
        reply(cb, s);
    }

    private StocktakeOrder findStocktake(String id) {
        for (StocktakeOrder s : stocktakes) if (s.id.equals(id)) return s;
        return null;
    }
}