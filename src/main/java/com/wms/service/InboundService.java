package com.wms.service;

import com.wms.dao.InboundOrderDao;
import com.wms.dao.InventoryLogDao;
import com.wms.dao.ProductDao;
import com.wms.model.InboundOrder;
import com.wms.model.InboundOrderItem;
import com.wms.model.InventoryLog;
import com.wms.model.Product;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class InboundService {

    private final ProductDao productDao;
    private final InventoryLogDao logDao;
    private final InboundOrderDao orderDao;

    @Autowired
    public InboundService(ProductDao productDao, InventoryLogDao logDao, InboundOrderDao orderDao) {
        this.productDao = productDao;
        this.logDao = logDao;
        this.orderDao = orderDao;
    }

    /** 单条入库,保持老接口签名(内部走批量,只传 1 条明细) */
    public InventoryLog doInbound(int productId, int quantity, String remark, String operator) {
        List<BatchItem> items = new ArrayList<>();
        BatchItem it = new BatchItem();
        it.productId = productId;
        it.quantity = quantity;
        it.rowRemark = remark;
        items.add(it);
        InboundOrder order = doInboundBatch(items, null, remark, operator);
        InventoryLog mirror = new InventoryLog();
        mirror.setType(InventoryLog.TYPE_IN);
        mirror.setProductId(productId);
        mirror.setQuantity(quantity);
        mirror.setOrderId(order.getId());
        return mirror;
    }

    /**
     * 批量入库:事务内完成
     *   1) 同 (productId, supplier) 合并数量(允许不同供应商)
     *   2) 逐商品加库存(adjustQuantity)
     *   3) 写入 inbound_order + inbound_order_item(明细带 supplier)
     *   4) 写入 inventory_log(关联 orderId)
     * 任何一步异常 → 整体回滚,库存与流水/单据保持一致
     */
    @Transactional
    public InboundOrder doInboundBatch(List<BatchItem> items,
                                       String externalNo,
                                       String orderRemark,
                                       String operator) {
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("入库明细不能为空");
        }
        if (operator == null || operator.isBlank()) operator = "操作员";

        // 1. 校验 + 标准化 supplier(空串归一为 null)
        for (BatchItem b : items) {
            if (b.productId == null) throw new IllegalArgumentException("请选择商品");
            if (b.quantity == null || b.quantity <= 0) {
                throw new IllegalArgumentException("入库数量必须大于 0");
            }
            b.supplier = blankToNull(b.supplier);
            b.rowRemark = blankToNull(b.rowRemark);
        }

        // 2. 同 (productId, supplier) 合并数量
        Map<String, BatchItem> merged = new LinkedHashMap<>();
        for (BatchItem raw : items) {
            String key = raw.productId + "|" + (raw.supplier == null ? "" : raw.supplier);
            BatchItem acc = merged.get(key);
            if (acc == null) {
                merged.put(key, raw);
            } else {
                acc.quantity += raw.quantity;
                if ((acc.rowRemark == null || acc.rowRemark.isBlank())
                        && raw.rowRemark != null && !raw.rowRemark.isBlank()) {
                    acc.rowRemark = raw.rowRemark;
                }
            }
        }

        // 3. 写主表(supplier 字段已移至明细,主表不再存)
        InboundOrder order = new InboundOrder();
        order.setOrderNo(genOrderNo());
        order.setExternalNo(blankToNull(externalNo));
        order.setRemark(blankToNull(orderRemark));
        order.setOperator(operator);
        int totalQty = 0;
        for (BatchItem b : merged.values()) totalQty += b.quantity;
        order.setTotalQuantity(totalQty);
        order.setItemCount(merged.size());
        int orderId = orderDao.insertOrder(order);
        order.setId(orderId);

        // 4. 逐行加库存 + 写明细 + 写流水
        List<InboundOrderItem> itemEntities = new ArrayList<>();
        for (BatchItem b : merged.values()) {
            Product p = productDao.findById(b.productId)
                    .orElseThrow(() -> new IllegalArgumentException("商品不存在, id=" + b.productId));
            int before = p.getQuantity();
            int rows = productDao.adjustQuantity(b.productId, b.quantity);
            if (rows == 0) throw new IllegalStateException("入库失败,商品状态异常 id=" + b.productId);
            int after = before + b.quantity;

            InboundOrderItem it = new InboundOrderItem();
            it.setOrderId(orderId);
            it.setProductId(p.getId());
            it.setSku(p.getSku());
            it.setProductName(p.getName());
            it.setSupplier(b.supplier);
            it.setQuantity(b.quantity);
            it.setUnitPrice(p.getUnitPrice() == null ? BigDecimal.ZERO : p.getUnitPrice());
            it.setBeforeQty(before);
            it.setAfterQty(after);
            it.setRowRemark(b.rowRemark);
            orderDao.insertItem(it);
            itemEntities.add(it);

            InventoryLog log = new InventoryLog();
            log.setType(InventoryLog.TYPE_IN);
            log.setProductId(p.getId());
            log.setSku(p.getSku());
            log.setProductName(p.getName());
            log.setQuantity(b.quantity);
            log.setBeforeQty(before);
            log.setAfterQty(after);
            // 流水备注:行级 supplier 优先 + 整单备注
            StringBuilder rb = new StringBuilder();
            if (b.supplier != null) rb.append("供应商:").append(b.supplier);
            String rk = b.rowRemark;
            if (rk != null) {
                if (rb.length() > 0) rb.append(" | ");
                rb.append(rk);
            } else if (order.getRemark() != null) {
                if (rb.length() > 0) rb.append(" | ");
                rb.append(order.getRemark());
            }
            log.setRemark(rb.length() == 0 ? null : rb.toString());
            log.setOperator(operator);
            log.setOrderId(orderId);
            logDao.insert(log);
        }
        order.setItems(itemEntities);
        return order;
    }

    @Transactional(readOnly = true)
    public InboundOrder getOrder(int id) {
        InboundOrder o = orderDao.findOrderById(id);
        if (o == null) return null;
        o.setItems(orderDao.findItemsByOrderId(id));
        return o;
    }

    /** 分页查询入库单列表(不含明细,详情另调 getOrder) */
    @Transactional(readOnly = true)
    public List<InboundOrder> listOrders(String beginDate, String endDate,
                                          String keyword, String operator,
                                          int page, int pageSize) {
        return orderDao.findOrders(beginDate, endDate, keyword, operator, page, pageSize);
    }

    @Transactional(readOnly = true)
    public long countOrders(String beginDate, String endDate, String keyword, String operator) {
        return orderDao.countOrders(beginDate, endDate, keyword, operator);
    }

    private static String genOrderNo() {
        // IN-yyyyMMddHHmmss-3位随机
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        int rnd = (int) (Math.random() * 900) + 100;
        return "IN-" + ts + "-" + rnd;
    }

    private static String blankToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /** 控制器层的入参载体,避免直接暴露 DTO */
    public static class BatchItem {
        public Integer productId;
        public Integer quantity;
        public String supplier;   // 行级供应商(可选)
        public String rowRemark;
    }
}
