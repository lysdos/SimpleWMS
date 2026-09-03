package com.wms.service;

import com.wms.dao.InventoryLogDao;
import com.wms.dao.OutboundOrderDao;
import com.wms.dao.ProductDao;
import com.wms.model.InventoryLog;
import com.wms.model.OutboundOrder;
import com.wms.model.OutboundOrderItem;
import com.wms.model.Product;
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

    private final ProductDao productDao;
    private final InventoryLogDao logDao;
    private final OutboundOrderDao orderDao;

    @Autowired
    public OutboundService(ProductDao productDao, InventoryLogDao logDao, OutboundOrderDao orderDao) {
        this.productDao = productDao;
        this.logDao = logDao;
        this.orderDao = orderDao;
    }

    /** 单条出库(老接口,内部走批量,只传 1 条明细) */
    public InventoryLog doOutbound(int productId, int quantity, String remark, String operator) {
        List<BatchItem> items = new ArrayList<>();
        BatchItem it = new BatchItem();
        it.productId = productId;
        it.quantity = quantity;
        it.rowRemark = remark;
        items.add(it);
        OutboundOrder order = doOutboundBatch(items, null, null, remark, operator);
        InventoryLog mirror = new InventoryLog();
        mirror.setType(InventoryLog.TYPE_OUT);
        mirror.setProductId(productId);
        mirror.setQuantity(quantity);
        mirror.setOrderId(order.getId());
        return mirror;
    }

    /**
     * 批量出库:事务内完成
     *   1) 同商品合并数量
     *   2) 先校验每个商品的当前库存 >= 出库数量(任意不足 → 整体回滚)
     *   3) 逐商品减库存
     *   4) 写入 outbound_order + outbound_order_item
     *   5) 写入 inventory_log (关联 orderId)
     * 任何一步异常 → 整体回滚,库存与流水/单据保持一致
     */
    @Transactional
    public OutboundOrder doOutboundBatch(List<BatchItem> items,
                                         String customer,
                                         String externalNo,
                                         String orderRemark,
                                         String operator) {
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("出库明细不能为空");
        }
        if (operator == null || operator.isBlank()) operator = "操作员";

        // 1. 同 productId 合并
        Map<Integer, BatchItem> merged = new LinkedHashMap<>();
        for (BatchItem raw : items) {
            if (raw.productId == null) throw new IllegalArgumentException("请选择商品");
            if (raw.quantity == null || raw.quantity <= 0) {
                throw new IllegalArgumentException("出库数量必须大于 0");
            }
            BatchItem acc = merged.get(raw.productId);
            if (acc == null) {
                acc = new BatchItem();
                acc.productId = raw.productId;
                acc.quantity = raw.quantity;
                acc.rowRemark = raw.rowRemark;
                merged.put(raw.productId, acc);
            } else {
                acc.quantity += raw.quantity;
                if ((acc.rowRemark == null || acc.rowRemark.isBlank())
                        && raw.rowRemark != null && !raw.rowRemark.isBlank()) {
                    acc.rowRemark = raw.rowRemark;
                }
            }
        }

        // 2. 先校验所有商品库存(避免半提交状态)
        for (BatchItem b : merged.values()) {
            Product p = productDao.findById(b.productId)
                    .orElseThrow(() -> new IllegalArgumentException("商品不存在, id=" + b.productId));
            if (p.getQuantity() < b.quantity) {
                throw new IllegalStateException(
                        String.format("库存不足:当前库存 %d,需要出库 %d (SKU: %s)",
                                p.getQuantity(), b.quantity, p.getSku()));
            }
        }

        // 3. 写主表
        OutboundOrder order = new OutboundOrder();
        order.setOrderNo(genOrderNo());
        order.setCustomer(blankToNull(customer));
        order.setExternalNo(blankToNull(externalNo));
        order.setRemark(blankToNull(orderRemark));
        order.setOperator(operator);
        int totalQty = 0;
        for (BatchItem b : merged.values()) totalQty += b.quantity;
        order.setTotalQuantity(totalQty);
        order.setItemCount(merged.size());
        int orderId = orderDao.insertOrder(order);
        order.setId(orderId);

        // 4. 逐商品减库存 + 写明细 + 写流水
        List<OutboundOrderItem> itemEntities = new ArrayList<>();
        for (BatchItem b : merged.values()) {
            Product p = productDao.findById(b.productId)
                    .orElseThrow(() -> new IllegalArgumentException("商品不存在, id=" + b.productId));
            int before = p.getQuantity();
            int rows = productDao.adjustQuantity(b.productId, -b.quantity);
            if (rows == 0) throw new IllegalStateException("出库失败,商品状态异常 id=" + b.productId);
            int after = before - b.quantity;

            OutboundOrderItem it = new OutboundOrderItem();
            it.setOrderId(orderId);
            it.setProductId(p.getId());
            it.setSku(p.getSku());
            it.setProductName(p.getName());
            it.setQuantity(b.quantity);
            it.setUnitPrice(p.getUnitPrice() == null ? BigDecimal.ZERO : p.getUnitPrice());
            it.setBeforeQty(before);
            it.setAfterQty(after);
            it.setRowRemark(blankToNull(b.rowRemark));
            orderDao.insertItem(it);
            itemEntities.add(it);

            InventoryLog log = new InventoryLog();
            log.setType(InventoryLog.TYPE_OUT);
            log.setProductId(p.getId());
            log.setSku(p.getSku());
            log.setProductName(p.getName());
            log.setQuantity(b.quantity);
            log.setBeforeQty(before);
            log.setAfterQty(after);
            String rk = blankToNull(b.rowRemark);
            log.setRemark(rk != null ? rk : order.getRemark());
            log.setOperator(operator);
            log.setOrderId(orderId);
            logDao.insert(log);
        }
        order.setItems(itemEntities);
        return order;
    }

    @Transactional(readOnly = true)
    public OutboundOrder getOrder(int id) {
        OutboundOrder o = orderDao.findOrderById(id);
        if (o == null) return null;
        o.setItems(orderDao.findItemsByOrderId(id));
        return o;
    }

    @Transactional(readOnly = true)
    public List<OutboundOrder> listOrders(String beginDate, String endDate,
                                          String keyword, String operator,
                                          int page, int pageSize) {
        return orderDao.findOrders(beginDate, endDate, keyword, operator, page, pageSize);
    }

    @Transactional(readOnly = true)
    public long countOrders(String beginDate, String endDate, String keyword, String operator) {
        return orderDao.countOrders(beginDate, endDate, keyword, operator);
    }

    private static String genOrderNo() {
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        int rnd = (int) (Math.random() * 900) + 100;
        return "OUT-" + ts + "-" + rnd;
    }

    private static String blankToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /** 控制器层的入参载体 */
    public static class BatchItem {
        public Integer productId;
        public Integer quantity;
        public String rowRemark;
    }
}
