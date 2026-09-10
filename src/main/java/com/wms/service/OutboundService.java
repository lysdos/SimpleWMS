package com.wms.service;

import com.wms.dao.InventoryItemDao;
import com.wms.dao.InventoryLogDao;
import com.wms.dao.OutboundOrderDao;
import com.wms.dao.ProductDao;
import com.wms.model.ExpectedEpc;
import com.wms.model.InventoryItem;
import com.wms.model.InventoryLog;
import com.wms.model.OutboundOrder;
import com.wms.model.OutboundOrderItem;
import com.wms.model.Product;
import com.wms.model.ScanResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class OutboundService {
    private final ProductDao productDao; private final InventoryLogDao logDao; private final OutboundOrderDao orderDao; private final InventoryItemDao itemDao;
    @Autowired public OutboundService(ProductDao p,InventoryLogDao l,OutboundOrderDao o,InventoryItemDao i){productDao=p;logDao=l;orderDao=o;itemDao=i;}
    /**
     * 单条出库(老接口):改为 DRAFT 语义,不再即时扣库存
     * 真实生效在 commitDraft() 里完成(adjustQuantity + consumeForOutbound + 写 OUT 流水)
     */
    @Transactional public OutboundOrder doOutbound(int productId,int quantity,String remark,String operator){
        BatchItem i=new BatchItem();i.productId=productId;i.quantity=quantity;i.rowRemark=remark;
        return doOutboundBatch(List.of(i),null,null,remark,operator,null);
    }
    @Transactional public OutboundOrder doOutboundBatch(List<BatchItem> items,String customer,String externalNo,String orderRemark,String operator){return doOutboundBatch(items,customer,externalNo,orderRemark,operator,null);}
    @Transactional public OutboundOrder doOutboundBatch(List<BatchItem> items,String customer,String externalNo,String orderRemark,String operator,Integer orderId){
        if(items==null||items.isEmpty())throw new IllegalArgumentException("出库明细不能为空");
        if(operator==null||operator.isBlank())operator="操作员";
        Map<Integer,BatchItem> m=new LinkedHashMap<>();
        for(BatchItem r:items){
            if(r.productId==null)throw new IllegalArgumentException("请选择商品");
            if(r.quantity==null||r.quantity<=0)throw new IllegalArgumentException("出库数量必须大于 0");
            BatchItem a=m.computeIfAbsent(r.productId,k->{BatchItem z=new BatchItem();z.productId=k;z.quantity=0;z.rowRemark=r.rowRemark;return z;});
            a.quantity+=r.quantity;
        }

        if(orderId!=null){
            // DRAFT 单追加明细分支:不校验库存、不调 adjustQuantity,只写 DRAFT 明细
            OutboundOrder o=orderDao.findOrderById(orderId);
            if(o==null||!OutboundOrder.STATUS_DRAFT.equals(o.getStatus()))throw new IllegalStateException("出库单不是草稿");
            for(BatchItem b:m.values()){
                Product p=productDao.findById(b.productId).orElseThrow(()->new IllegalArgumentException("商品不存在, id="+b.productId));
                OutboundOrderItem it=new OutboundOrderItem();
                it.setOrderId(orderId);it.setProductId(p.getId());it.setSku(p.getSku());it.setProductName(p.getName());
                it.setQuantity(b.quantity);it.setUnitPrice(p.getUnitPrice()==null?BigDecimal.ZERO:p.getUnitPrice());
                it.setLocation(blank(p.getLocation()));it.setRowRemark(blank(b.rowRemark));
                it.setBeforeQty(p.getQuantity());it.setAfterQty(p.getQuantity());  // DRAFT 阶段不动库存
                orderDao.insertItem(it);
            }
            orderDao.refreshAggregates(orderId);
            o=orderDao.findOrderById(orderId);o.setItems(orderDao.findItemsByOrderId(o.getId()));
            return o;
        }

        // 新建 DRAFT 单 + 写 DRAFT 明细 + 按方案 A 立刻锁定 N 个 inventory_item (RESERVED)
        OutboundOrder o=new OutboundOrder();
        o.setOrderNo(genOrderNo());
        o.setCustomer(blank(customer));
        o.setExternalNo(blank(externalNo));
        o.setRemark(blank(orderRemark));
        o.setOperator(operator);
        o.setStatus(OutboundOrder.STATUS_DRAFT);  // INSERT SQL 已写 status 列
        o.setTotalQuantity(m.values().stream().mapToInt(x->x.quantity).sum());
        o.setItemCount(m.size());
        int id=orderDao.insertOrder(o);o.setId(id);
        for(BatchItem b:m.values()){
            Product p=productDao.findById(b.productId).orElseThrow(()->new IllegalArgumentException("商品不存在, id="+b.productId));
            OutboundOrderItem it=new OutboundOrderItem();
            it.setOrderId(id);it.setProductId(p.getId());it.setSku(p.getSku());it.setProductName(p.getName());
            it.setQuantity(b.quantity);it.setUnitPrice(p.getUnitPrice()==null?BigDecimal.ZERO:p.getUnitPrice());
            it.setLocation(blank(p.getLocation()));it.setRowRemark(blank(b.rowRemark));
            it.setBeforeQty(p.getQuantity());it.setAfterQty(p.getQuantity());
            orderDao.insertItem(it);
            // ====== 方案 A:建单立刻锁定 EPC ======
            String loc = blank(p.getLocation()) == null ? "默认库位" : p.getLocation();
            itemDao.reserveForOutbound(p.getId(), loc, b.quantity, id);
        }
        o=orderDao.findOrderById(id);o.setItems(orderDao.findItemsByOrderId(id));
        return o;
    }
    @Transactional public OutboundOrder createDraft(String customer,String externalNo,String remark,String operator){if(operator==null||operator.isBlank())operator="操作员";OutboundOrder o=new OutboundOrder();o.setOrderNo(genOrderNo());o.setCustomer(blank(customer));o.setExternalNo(blank(externalNo));o.setRemark(blank(remark));o.setOperator(operator);o.setTotalQuantity(0);o.setItemCount(0);o.setStatus(OutboundOrder.STATUS_DRAFT);int id=orderDao.insertOrder(o);o.setId(id);return getOrder(id);}
    @Transactional public OutboundOrder scanCodeToDraft(int orderId,String code){OutboundOrder o=requireDraft(orderId);InventoryItem inv=itemDao.findByCode(code);if(inv==null)throw new IllegalArgumentException("码不存在");if(InventoryItem.STATUS_OUT.equals(inv.getStatus()))throw new IllegalStateException("已出库");if(InventoryItem.STATUS_SCRAPPED.equals(inv.getStatus()))throw new IllegalStateException("已报废");if(!InventoryItem.STATUS_IN_STOCK.equals(inv.getStatus()))throw new IllegalStateException("库存状态不可出库");Product p=productDao.findById(inv.getProductId()).orElseThrow(()->new IllegalArgumentException("商品不存在"));List<OutboundOrderItem> lines=orderDao.findItemsByOrderId(orderId);OutboundOrderItem line=lines.stream().filter(x->x.getProductId().equals(inv.getProductId())).findFirst().orElse(null);if(line==null){line=new OutboundOrderItem();line.setOrderId(orderId);line.setProductId(p.getId());line.setSku(p.getSku());line.setProductName(p.getName());line.setQuantity(1);line.setUnitPrice(p.getUnitPrice()==null?BigDecimal.ZERO:p.getUnitPrice());line.setLocation(blank(p.getLocation()));line.setBeforeQty(p.getQuantity());line.setAfterQty(p.getQuantity()-1);orderDao.insertItem(line);}else{orderDao.updateItemQuantity(line.getId(),line.getQuantity()+1);}orderDao.refreshAggregates(orderId);return getOrder(orderId);}
    @Transactional public OutboundOrder commitDraft(int id){
        OutboundOrder o=requireDraft(id);
        List<OutboundOrderItem> lines=orderDao.findItemsByOrderId(id);
        if(o.getTotalQuantity()==null||o.getTotalQuantity()<=0)throw new IllegalStateException("草稿单不能为空");
        // ====== 方案 A:commit 时不重新挑码,直接把所有 RESERVED 单元 → OUT ======
        List<com.wms.dao.InventoryItemDao.ConsumeRecord> records = itemDao.commitReservations(id);
        if (records.isEmpty()) {
            throw new IllegalStateException("草稿单未锁定任何 inventory_item,请先调 /orders/{id}/reserve 补全锁定");
        }
        // ====== 服务端校验:所有 RESERVED 单元都必须被 SCAN 日志确认过 ======
        int scannedCount = logDao.countScannedForOutboundAll(id);
        int lockedCount = records.stream().mapToInt(r -> r.quantity).sum();
        if (scannedCount < lockedCount) {
            // 回滚:把已经改 OUT 的重新退回去
            // 注:commitReservations 已经在本事务里执行,这里抛异常会回滚
            throw new IllegalStateException("还有 " + (lockedCount - scannedCount) + " 件未扫码确认,不能提交(已扫 " + scannedCount + " / 锁定 " + lockedCount + ")");
        }
        String operator=o.getOperator();
        if(operator==null||operator.isBlank())operator="操作员";
        // ====== 关键:先把订单状态从 DRAFT 切到 COMMITTED(带 WHERE 守卫)======
        // 第二个 commit 因状态不再 DRAFT 拿不到 0 行,直接抛错;否则下面 adjustQuantity
        // 会被并发重放、库存会被扣两次。
        // commitReservations 还在本事务里;若这一步影响 0 行,事务回滚即撤销前面改动。
        for(OutboundOrderItem x:lines){
            Product p=productDao.findById(x.getProductId()).orElseThrow(()->new IllegalArgumentException("商品不存在"));
            int before=p.getQuantity();
            if(productDao.adjustQuantity(p.getId(),-x.getQuantity())==0)throw new IllegalStateException("出库失败");
            int after=before-x.getQuantity();
            persistOutboundLog(x,operator,id,before,after);
        }
        if (orderDao.updateStatusFromDraft(id, OutboundOrder.STATUS_COMMITTED) == 0) {
            // 并发竞争:另一线程已先 commit / 取消过这张单
            throw new IllegalStateException("订单已被并发处理,放弃本次提交");
        }
        return getOrder(id);
    }

    /** 写一条出库流水(OUT),commitDraft 时调用 */
    private void persistOutboundLog(OutboundOrderItem it,String operator,int orderId,int before,int after){
        InventoryLog log=new InventoryLog();
        log.setType(InventoryLog.TYPE_OUT);
        log.setProductId(it.getProductId());
        log.setSku(it.getSku());
        log.setProductName(it.getProductName());
        log.setQuantity(it.getQuantity());
        log.setBeforeQty(before);
        log.setAfterQty(after);
        log.setRemark(it.getRowRemark());
        log.setOperator(operator);
        log.setOrderId(orderId);
        logDao.insert(log);
    }
    @Transactional public void cancelDraft(int id){
        OutboundOrder o=requireDraft(id);
        // ====== 方案 A:取消时把所有 RESERVED 单元 → IN_STOCK,清 outbound_order_id ======
        itemDao.releaseReservations(id);
        orderDao.deleteItemsByOrderId(id);
        if (orderDao.updateStatusFromDraft(id, OutboundOrder.STATUS_CANCELLED) == 0) {
            throw new IllegalStateException("订单状态已被并发修改,无法取消");
        }
    }

    /**
     * 老 DRAFT 单补全锁定:把已建但未锁的草稿行,按 FIFO 锁定 N 个 RESERVED 单元。
     * 适用于方案 A 上线前已存在的 DRAFT 单(如 OUT-20260909162933-717)。
     */
    @Transactional
    public OutboundOrder reserveDraft(int id){
        OutboundOrder o = requireDraft(id);
        List<OutboundOrderItem> lines = orderDao.findItemsByOrderId(id);
        if (lines.isEmpty()) throw new IllegalStateException("草稿单无明细行");
        for (OutboundOrderItem x : lines) {
            // 已经锁过的就跳过(以 outbound_order_id 匹配)
            Integer alreadyLocked = itemDao.countReservedForOrder(id, x.getProductId(), x.getLocation());
            int need = x.getQuantity() - (alreadyLocked == null ? 0 : alreadyLocked);
            if (need > 0) {
                String loc = blank(x.getLocation()) == null ? "默认库位" : x.getLocation();
                itemDao.reserveForOutbound(x.getProductId(), loc, need, id);
            }
        }
        return getOrder(id);
    }

    // ====== RFID / UHF 出库扫码集成 ======
    // 8 字节 EPC(16 hex) <-> inventory_item.code 后 8 字节(24 字符 code = 16 字符时间戳 + 8 字符 EPC)

    /**
     * 查询出库单期望扫码集合(每个明细行需要的 inventory_item id 列表)。
     * 供 UHF-Rader 端拉取,作为"扫什么不报警"的依据。
     */
    @Transactional(readOnly = true)
    public List<com.wms.model.ExpectedEpc> loadExpectedEpcs(int orderId) {
        OutboundOrder o = orderDao.findOrderById(orderId);
        if (o == null) throw new IllegalArgumentException("出库单不存在, id=" + orderId);
        return itemDao.findExpectedEpcsForOutbound(orderId);
    }

    /**
     * UHF-Rader 扫到一个 EPC,前端调用此接口(方案 A:严格 1:1,只接受本单 RESERVED 码):
     *  1) 验证 EPC = 24 hex(12 字节 UHF Gen2)
     *  2) 查 inventory_item 必须是 status=RESERVED 且 outbound_order_id=本单
     *     - 不在 RESERVED 集合 → 报错(可能是别的单的码、IN_STOCK、OUT、SCRAPPED 等)
     *  3) 命中后只是"扫码确认",不扣库存、不写流水(等 commit 统一处理)
     *  4) 重复扫同一个码 → 报"已扫过"(同一码只能确认一次)
     */
    @Transactional
    public ScanResult scanEpc(int orderId, String epc, String operator) {
        if (epc == null || epc.isBlank()) {
            return ScanResult.fail("EPC 不能为空");
        }
        epc = epc.trim().toUpperCase();
        if (epc.length() != 24) {
            return ScanResult.fail("EPC 长度必须是 24 位十六进制(12 字节 = 96 bit UHF Gen2),收到 " + epc.length() + " 位: " + epc);
        }
        for (int i = 0; i < epc.length(); i++) {
            char c = epc.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'A' && c <= 'F'))) {
                return ScanResult.fail("EPC 含非十六进制字符: " + epc);
            }
        }

        OutboundOrder o = requireDraft(orderId);
        if (operator == null || operator.isBlank()) operator = o.getOperator();

        // 方案 A:只接受"本单 RESERVED"的码
        InventoryItem inv = itemDao.findReservedByEpcForOrder(epc, orderId);
        if (inv == null) {
            // 给出更具体的错误原因
            InventoryItem any = itemDao.findByEpc(epc);
            if (any == null) {
                return ScanResult.fail("EPC 不在系统库存中: " + epc);
            }
            switch (any.getStatus()) {
                case InventoryItem.STATUS_RESERVED:
                    return ScanResult.fail("EPC 已被其他出库单锁定(" + any.getOutboundOrderId() + "): " + epc);
                case InventoryItem.STATUS_OUT:
                    return ScanResult.fail("EPC 已出库: " + epc);
                case InventoryItem.STATUS_SCRAPPED:
                    return ScanResult.fail("EPC 已报废: " + epc);
                case InventoryItem.STATUS_TRANSFERRED:
                    return ScanResult.fail("EPC 已调拨: " + epc);
                case InventoryItem.STATUS_PENDING:
                    return ScanResult.fail("EPC 未入库(状态 PENDING): " + epc);
                case InventoryItem.STATUS_IN_STOCK:
                    return ScanResult.fail("EPC 在库但未被本出库单锁定(请先调 /orders/{id}/reserve 补全): " + epc);
                default:
                    return ScanResult.fail("EPC 库存状态不可出库: " + any.getStatus());
            }
        }

        // 校验通过,记录扫码日志(不扣库存,等 commit)
        int scannedCount = logDao.countScannedForOutboundEpc(orderId, epc);
        if (scannedCount > 0) {
            return ScanResult.fail("EPC 已扫过(本单): " + epc);
        }
        // 写一条"扫码确认"日志(quantity=0,只是标记)
        InventoryLog log = new InventoryLog();
        log.setType(InventoryLog.TYPE_SCAN);
        log.setProductId(inv.getProductId());
        log.setSku(inv.getSku());
        log.setProductName(inv.getProductName());
        log.setQuantity(0);
        log.setBeforeQty(0);
        log.setAfterQty(0);
        log.setRemark("UHF 扫码确认, epc=" + epc);
        log.setOperator(operator);
        log.setOrderId(orderId);
        logDao.insert(log);

        // 已扫/还差统计
        int totalReserved = itemDao.countReservedForOrder(orderId, inv.getProductId(), inv.getLocationCode());
        int already = logDao.countScannedForOutbound(orderId, inv.getProductId());
        return ScanResult.ok(inv.getId(), inv.getSku(), epc, already, orderId)
                .withReservedCount(totalReserved)
                .withStillNeeded(Math.max(0, totalReserved - already));
    }

    private OutboundOrder requireDraft(int id){OutboundOrder o=orderDao.findOrderById(id);if(o==null)throw new IllegalArgumentException("出库单不存在, id="+id);if(!OutboundOrder.STATUS_DRAFT.equals(o.getStatus()))throw new IllegalStateException("出库单不是草稿");return o;}

    @Transactional(readOnly=true) public OutboundOrder getOrder(int id){OutboundOrder o=orderDao.findOrderById(id);if(o!=null)o.setItems(orderDao.findItemsByOrderId(id));return o;}
    @Transactional(readOnly=true) public List<OutboundOrder> listOrders(String b,String e,String k,String op,String st,int p,int s){return orderDao.findOrders(b,e,k,op,st,p,s);}
    @Transactional(readOnly=true) public long countOrders(String b,String e,String k,String op,String st){return orderDao.countOrders(b,e,k,op,st);}

    // ===== UHF 派单 =====
    /**
     * 前台 WMS 把出库单下发到指定 UHF 设备。
     * 状态必须是 DRAFT,同一单不能重复下发。
     * @return 完整的出库单(含明细)
     */
    @Transactional
    public OutboundOrder dispatchToUhf(int orderId, String deviceCode, String operator) {
        if (deviceCode == null || deviceCode.isBlank()) {
            throw new IllegalArgumentException("deviceCode 不能为空");
        }
        OutboundOrder o = orderDao.findOrderById(orderId);
        if (o == null) throw new IllegalArgumentException("出库单不存在, id=" + orderId);
        if (!OutboundOrder.STATUS_DRAFT.equals(o.getStatus())) {
            throw new IllegalStateException("只能下发 DRAFT 状态的出库单, 当前=" + o.getStatus());
        }
        if (o.getUhfDispatchedAt() != null) {
            throw new IllegalStateException("该出库单已下发到 UHF (" + o.getUhfDeviceCode() + "),不能重复下发");
        }
        int n = orderDao.dispatchToUhf(orderId, deviceCode.trim(), operator);
        if (n == 0) {
            throw new IllegalStateException("下发失败(并发冲突或状态变更),请重试");
        }
        return getOrder(orderId);
    }

    /**
     * UHF 设备拉取"待认领的派单"列表
     * @param deviceCode 传 null = 拉全部未认领的派单;传具体值 = 只拉发给自己的
     */
    @Transactional(readOnly = true)
    public List<OutboundOrder> listDispatched(String deviceCode, int limit) {
        List<OutboundOrder> list = orderDao.findDispatchedOrders(deviceCode, limit);
        for (OutboundOrder o : list) {
            o.setItems(orderDao.findItemsByOrderId(o.getId()));
        }
        return list;
    }

    /**
     * UHF 设备认领一个派单(同一设备可重复认领,跨设备互斥)。
     * 认领后该单进入"已锁定"状态,其他设备看不到。
     */
    @Transactional
    public OutboundOrder claimDispatch(int orderId, String deviceCode) {
        if (deviceCode == null || deviceCode.isBlank()) {
            throw new IllegalArgumentException("deviceCode 不能为空");
        }
        OutboundOrder o = orderDao.findOrderById(orderId);
        if (o == null) throw new IllegalArgumentException("出库单不存在, id=" + orderId);
        if (o.getUhfDispatchedAt() == null) {
            throw new IllegalStateException("该出库单尚未下发到 UHF,不能认领");
        }
        int n = orderDao.claimDispatch(orderId, deviceCode.trim());
        if (n == 0) {
            throw new IllegalStateException("认领失败(已被其他设备认领或状态已变)");
        }
        return getOrder(orderId);
    }

    private static String genOrderNo(){return "OUT-"+LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))+"-"+(int)(Math.random()*900+100);}private static String blank(String s){return s==null||s.trim().isEmpty()?null:s.trim();}
    public static class BatchItem{public Integer productId;public Integer quantity;public String rowRemark;}
}
