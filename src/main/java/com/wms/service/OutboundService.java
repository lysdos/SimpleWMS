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

        // 新建 DRAFT 单 + 写 DRAFT 明细(不调 adjustQuantity、不写 inventory_log)
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
        }
        o=orderDao.findOrderById(id);o.setItems(orderDao.findItemsByOrderId(id));
        return o;
    }
    @Transactional public OutboundOrder createDraft(String customer,String externalNo,String remark,String operator){if(operator==null||operator.isBlank())operator="操作员";OutboundOrder o=new OutboundOrder();o.setOrderNo(genOrderNo());o.setCustomer(blank(customer));o.setExternalNo(blank(externalNo));o.setRemark(blank(remark));o.setOperator(operator);o.setTotalQuantity(0);o.setItemCount(0);o.setStatus(OutboundOrder.STATUS_DRAFT);int id=orderDao.insertOrder(o);o.setId(id);return getOrder(id);}
    @Transactional public OutboundOrder scanCodeToDraft(int orderId,String code){OutboundOrder o=requireDraft(orderId);InventoryItem inv=itemDao.findByCode(code);if(inv==null)throw new IllegalArgumentException("码不存在");if(InventoryItem.STATUS_OUT.equals(inv.getStatus()))throw new IllegalStateException("已出库");if(InventoryItem.STATUS_SCRAPPED.equals(inv.getStatus()))throw new IllegalStateException("已报废");if(!InventoryItem.STATUS_IN_STOCK.equals(inv.getStatus()))throw new IllegalStateException("库存状态不可出库");Product p=productDao.findById(inv.getProductId()).orElseThrow(()->new IllegalArgumentException("商品不存在"));List<OutboundOrderItem> lines=orderDao.findItemsByOrderId(orderId);OutboundOrderItem line=lines.stream().filter(x->x.getProductId().equals(inv.getProductId())).findFirst().orElse(null);if(line==null){line=new OutboundOrderItem();line.setOrderId(orderId);line.setProductId(p.getId());line.setSku(p.getSku());line.setProductName(p.getName());line.setQuantity(1);line.setUnitPrice(p.getUnitPrice()==null?BigDecimal.ZERO:p.getUnitPrice());line.setLocation(blank(p.getLocation()));line.setBeforeQty(p.getQuantity());line.setAfterQty(p.getQuantity()-1);orderDao.insertItem(line);}else{orderDao.updateItemQuantity(line.getId(),line.getQuantity()+1);}orderDao.refreshAggregates(orderId);return getOrder(orderId);}
    @Transactional public OutboundOrder commitDraft(int id){OutboundOrder o=requireDraft(id);List<OutboundOrderItem> lines=orderDao.findItemsByOrderId(id);if(o.getTotalQuantity()==null||o.getTotalQuantity()<=0)throw new IllegalStateException("草稿单不能为空");String operator=o.getOperator();if(operator==null||operator.isBlank())operator="操作员";for(OutboundOrderItem x:lines){Product p=productDao.findById(x.getProductId()).orElseThrow(()->new IllegalArgumentException("商品不存在"));String loc=blank(p.getLocation())==null?"默认库位":p.getLocation();if((itemDao.sumRemaining(p.getId(),loc)==null?0:itemDao.sumRemaining(p.getId(),loc))<x.getQuantity())throw new IllegalStateException("库存不足");int before=p.getQuantity();if(productDao.adjustQuantity(p.getId(),-x.getQuantity())==0)throw new IllegalStateException("出库失败");itemDao.consumeForOutbound(p.getId(),loc,x.getQuantity(),id);int after=before-x.getQuantity();persistOutboundLog(x,operator,id,before,after);}orderDao.updateStatus(id,OutboundOrder.STATUS_COMMITTED);return getOrder(id);}

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
    @Transactional public void cancelDraft(int id){OutboundOrder o=requireDraft(id);orderDao.deleteItemsByOrderId(id);orderDao.updateStatus(id,OutboundOrder.STATUS_CANCELLED);}

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
     * UHF-Rader 扫到一个 EPC,前端调用此接口:
     *  1) 验证 EPC 在 IN_STOCK 库存里存在
     *  2) 验证 EPC 所属 SKU 在出库单明细里
     *  3) 调 consumeForOutbound 扣 1 件 + 写 OUT 流水
     *  4) 已 OUT 的 EPC 重复扫 → 报"已扫过"
     *  5) 不在任何出库单里的 EPC → 报"不在出库单中"
     */
    @Transactional
    public ScanResult scanEpc(int orderId, String epc, String operator) {
        if (epc == null || epc.isBlank()) {
            return ScanResult.fail("EPC 不能为空");
        }
        epc = epc.trim().toUpperCase();
        // 12 字节 EPC = 24 hex(标准 UHF Gen2 EPC = 96 bit),与 inventory_item.code(VARCHAR(24))等长
        if (epc.length() != 24) {
            return ScanResult.fail("EPC 长度必须是 24 位十六进制(12 字节 = 96 bit UHF Gen2),收到 " + epc.length() + " 位: " + epc);
        }
        // 全 hex 字符
        for (int i = 0; i < epc.length(); i++) {
            char c = epc.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'A' && c <= 'F'))) {
                return ScanResult.fail("EPC 含非十六进制字符: " + epc);
            }
        }

        OutboundOrder o = requireDraft(orderId);
        if (operator == null || operator.isBlank()) operator = o.getOperator();

        // 1) 查 inventory_item(RIGHT(code, 24) = EPC, status=IN_STOCK)
        InventoryItem inv = itemDao.findByEpcAndStatus(epc, InventoryItem.STATUS_IN_STOCK);
        if (inv == null) {
            // 不在库 或 不在 IN_STOCK
            InventoryItem any = itemDao.findByEpc(epc);
            if (any == null) {
                return ScanResult.fail("EPC 不在系统库存中: " + epc);
            }
            if (InventoryItem.STATUS_OUT.equals(any.getStatus())) {
                return ScanResult.fail("EPC 已出库: " + epc);
            }
            if (InventoryItem.STATUS_SCRAPPED.equals(any.getStatus())) {
                return ScanResult.fail("EPC 已报废: " + epc);
            }
            return ScanResult.fail("EPC 库存状态不可出库: " + any.getStatus());
        }

        // 2) 验证 SKU 在出库单明细里
        List<OutboundOrderItem> lines = orderDao.findItemsByOrderId(orderId);
        OutboundOrderItem target = lines.stream()
                .filter(x -> x.getProductId().equals(inv.getProductId()))
                .findFirst().orElse(null);
        if (target == null) {
            return ScanResult.fail("EPC 属于 SKU " + inv.getSku() + ",不在本出库单中");
        }

        // 3) 验证 SKU 总数还没扫完(target.quantity = 已扫 + 待扫)
        //    累加方式:每扫一个就 target.quantity + 1
        int before = target.getQuantity();
        orderDao.updateItemQuantity(target.getId(), before + 1);
        // 4) 扣 1 件 inventory_item + 写 OUT 流水
        String loc = blank(inv.getLocationCode()) == null ? "默认库位" : inv.getLocationCode();
        int productBefore = productDao.findById(inv.getProductId()).map(Product::getQuantity).orElse(0);
        itemDao.consumeForOutboundByEpc(epc, orderId);
        productDao.adjustQuantity(inv.getProductId(), -1);
        int productAfter = productBefore - 1;
        // 5) 写 OUT 流水(按 -1 记)
        InventoryLog log = new InventoryLog();
        log.setType(InventoryLog.TYPE_OUT);
        log.setProductId(inv.getProductId());
        log.setSku(inv.getSku());
        log.setProductName(inv.getProductName());
        log.setQuantity(1);
        log.setBeforeQty(productBefore);
        log.setAfterQty(productAfter);
        log.setRemark("UHF 扫码出库, epc=" + epc);
        log.setOperator(operator);
        log.setOrderId(orderId);
        logDao.insert(log);

        // 6) 刷主单聚合
        orderDao.refreshAggregates(orderId);

        return ScanResult.ok(target.getId(), inv.getSku(),
                inv.getCode().length() >= 8 ? inv.getCode().substring(inv.getCode().length() - 8) : inv.getCode(),
                before + 1, orderId);
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
