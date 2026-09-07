package com.wms.service;

import com.wms.dao.InboundOrderDao;
import com.wms.dao.InventoryItemDao;
import com.wms.dao.InventoryLogDao;
import com.wms.dao.ProductDao;
import com.wms.model.InboundOrder;
import com.wms.model.InboundOrderItem;
import com.wms.model.InventoryItem;
import com.wms.model.InventoryLog;
import com.wms.model.Product;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
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
    private final InventoryItemDao itemDao;
    private final CodeGenerator codeGenerator;
    private final JdbcTemplate jdbc;

    @Autowired
    public InboundService(ProductDao productDao,
                          InventoryLogDao logDao,
                          InboundOrderDao orderDao,
                          InventoryItemDao itemDao,
                          CodeGenerator codeGenerator,
                          JdbcTemplate jdbc) {
        this.productDao = productDao;
        this.logDao = logDao;
        this.orderDao = orderDao;
        this.itemDao = itemDao;
        this.codeGenerator = codeGenerator;
        this.jdbc = jdbc;
    }

    /** 单条入库,保持老接口签名(内部走批量,只传 1 条明细) */
    public InventoryLog doInbound(int productId, int quantity, String remark, String operator) {
        List<BatchItem> items = new ArrayList<>();
        BatchItem it = new BatchItem();
        it.productId = productId;
        it.quantity = quantity;
        it.rowRemark = remark;
        items.add(it);
        InboundOrder order = doInboundBatch(items, null, remark, operator, null);
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
     *
     * @param orderId 可选:非 null 时把 items 追加到该 DRAFT 单(走 addItemsToDraftInternal),
     *                主表 externalNo/remark/orderNo 不会被覆盖;为 null 时按传统自建单流程
     */
    @Transactional
    public InboundOrder doInboundBatch(List<BatchItem> items,
                                       String externalNo,
                                       String orderRemark,
                                       String operator,
                                       Integer orderId) {
        if (orderId != null) {
            // DRAFT 单追加明细分支
            return addItemsToDraftInternal(orderId, items, operator);
        }

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
        order.setStatus(InboundOrder.STATUS_COMMITTED);  // 老 batch 接口直接 = COMMITTED
        int totalQty = 0;
        for (BatchItem b : merged.values()) totalQty += b.quantity;
        order.setTotalQuantity(totalQty);
        order.setItemCount(merged.size());
        int newOrderId = orderDao.insertOrder(order);
        order.setId(newOrderId);

        // 4. 逐行加库存 + 写明细 + 写流水 + 一物一码生成库存单元
        List<InboundOrderItem> itemEntities = new ArrayList<>();
        for (BatchItem b : merged.values()) {
            List<InventoryItem> codes = processItemBatch(b, operator, newOrderId);
            InboundOrderItem itemEntity = persistOrderItem(b, newOrderId, codes);
            persistInventoryLog(b, operator, newOrderId, itemEntity);
            itemEntities.add(itemEntity);
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
                                          String keyword, String operator, String status,
                                          int page, int pageSize) {
        return orderDao.findOrders(beginDate, endDate, keyword, operator, status, page, pageSize);
    }

    @Transactional(readOnly = true)
    public long countOrders(String beginDate, String endDate,
                            String keyword, String operator, String status) {
        return orderDao.countOrders(beginDate, endDate, keyword, operator, status);
    }

    // ============================================================
    // PDA 入库登记(DRAFT / COMMITTED / CANCELLED)状态机
    // ============================================================

    /**
     * 创建空草稿入库单。返回完整 InboundOrder(id 已生成,status=DRAFT,total_quantity=0,item_count=0)。
     */
    @Transactional
    public InboundOrder createDraft(String externalNo, String remark, String operator) {
        if (operator == null || operator.isBlank()) operator = "操作员";

        InboundOrder order = new InboundOrder();
        order.setOrderNo(genOrderNo());
        order.setExternalNo(blankToNull(externalNo));
        order.setRemark(blankToNull(remark));
        order.setOperator(operator);
        order.setStatus(InboundOrder.STATUS_DRAFT);
        order.setTotalQuantity(0);
        order.setItemCount(0);
        int id = orderDao.insertOrder(order);
        order.setId(id);
        orderDao.updateStatus(id, InboundOrder.STATUS_DRAFT);
        return getOrder(id);
    }

    /**
     * 往 DRAFT 单追加一个明细行(单条)。生成 N 个 inventory_item 24 位码,库存 + 流水立刻生效。
     */
    @Transactional
    public InboundOrder addItemToDraft(int orderId, BatchItem item) {
        if (item == null) throw new IllegalArgumentException("明细不能为空");
        List<BatchItem> list = new ArrayList<>();
        list.add(item);
        return addItemsToDraftInternal(orderId, list, null);
    }

    /**
     * commit:校验 DRAFT + totalQuantity>0,把 status 推到 COMMITTED,返回含 codes 的完整单。
     */
    @Transactional
    public InboundOrder commitDraft(int orderId) {
        InboundOrder order = orderDao.findOrderById(orderId);
        if (order == null) throw new IllegalArgumentException("入库单不存在, id=" + orderId);
        if (!InboundOrder.STATUS_DRAFT.equals(order.getStatus())) {
            throw new IllegalStateException("只有 DRAFT 单可以提交, 当前 status=" + order.getStatus());
        }
        Integer totalQty = order.getTotalQuantity();
        if (totalQty == null || totalQty <= 0) {
            throw new IllegalStateException("空单不能提交,请先添加明细");
        }
        orderDao.updateStatus(orderId, InboundOrder.STATUS_COMMITTED);
        return getOrderWithCodes(orderId);
    }

    /**
     * cancel:校验 DRAFT;回滚 inventory_item + 商品 quantity + 删 inbound_order_item
     *         + 删相关 inventory_log;改 status = CANCELLED(留审计)。
     */
    @Transactional
    public void cancelDraft(int orderId) {
        InboundOrder order = orderDao.findOrderById(orderId);
        if (order == null) throw new IllegalArgumentException("入库单不存在, id=" + orderId);
        if (!InboundOrder.STATUS_DRAFT.equals(order.getStatus())) {
            throw new IllegalStateException("只有 DRAFT 单可以取消, 当前 status=" + order.getStatus());
        }

        // 1) 列出该单所有明细,把商品 quantity 减回去
        List<InboundOrderItem> items = orderDao.findItemsByOrderId(orderId);
        if (items != null) {
            for (InboundOrderItem it : items) {
                if (it.getProductId() == null || it.getQuantity() == null) continue;
                productDao.adjustQuantity(it.getProductId(), -it.getQuantity());
            }
        }

        // 2) 删 inventory_item 关联
        jdbc.update("DELETE FROM inventory_item WHERE inbound_order_id = ?", orderId);

        // 3) 删 inbound_order_item
        orderDao.deleteItemsByOrderId(orderId);

        // 4) 删相关 inventory_log
        jdbc.update("DELETE FROM inventory_log WHERE order_id = ?", orderId);

        // 5) 主表 aggregates 清零 + status 改 CANCELLED
        orderDao.refreshAggregates(orderId);
        orderDao.updateStatus(orderId, InboundOrder.STATUS_CANCELLED);
    }

    /**
     * 内部方法:把 items 追加到指定 DRAFT 单(doInboundBatch 非空 orderId 分支 / addItemToDraft 调)。
     * 注意:同 (productId, supplier) 仍在本批内合并,但不与单据已有明细合并(简化:PDA 单次 add 一次性就好)。
     */
    private InboundOrder addItemsToDraftInternal(int orderId, List<BatchItem> items, String operator) {
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("入库明细不能为空");
        }
        InboundOrder order = orderDao.findOrderById(orderId);
        if (order == null) throw new IllegalArgumentException("入库单不存在, id=" + orderId);
        if (!InboundOrder.STATUS_DRAFT.equals(order.getStatus())) {
            throw new IllegalStateException("只有 DRAFT 单可以追加明细, 当前 status=" + order.getStatus());
        }
        if (operator == null || operator.isBlank()) operator = order.getOperator();
        if (operator == null || operator.isBlank()) operator = "操作员";

        // 校验 + 标准化
        for (BatchItem b : items) {
            if (b.productId == null) throw new IllegalArgumentException("请选择商品");
            if (b.quantity == null || b.quantity <= 0) {
                throw new IllegalArgumentException("入库数量必须大于 0");
            }
            b.supplier = blankToNull(b.supplier);
            b.rowRemark = blankToNull(b.rowRemark);
        }

        // 本批合并
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

        for (BatchItem b : merged.values()) {
            List<InventoryItem> codes = processItemBatch(b, operator, orderId);
            InboundOrderItem itemEntity = persistOrderItem(b, orderId, codes);
            persistInventoryLog(b, operator, orderId, itemEntity);
        }

        // 刷新 aggregates
        orderDao.refreshAggregates(orderId);
        return getOrder(orderId);
    }

    /** 生成 N 个 inventory_item 24 位码并落库。返回所有生成的 InventoryItem 实体 */
    private List<InventoryItem> processItemBatch(BatchItem b, String operator, int orderId) {
        Product p = productDao.findById(b.productId)
                .orElseThrow(() -> new IllegalArgumentException("商品不存在, id=" + b.productId));
        int before = p.getQuantity();
        int rows = productDao.adjustQuantity(b.productId, b.quantity);
        if (rows == 0) throw new IllegalStateException("入库失败,商品状态异常 id=" + b.productId);
        int after = before + b.quantity;
        b._beforeQty = before;
        b._afterQty = after;

        String locCode = blankToNull(p.getLocation()) == null ? "默认库位" : p.getLocation();
        List<InventoryItem> generated = new ArrayList<>();
        for (int unit = 0; unit < b.quantity; unit++) {
            InventoryItem ii = new InventoryItem();
            ii.setProductId(p.getId());
            ii.setSku(p.getSku());
            ii.setProductName(p.getName());
            ii.setLocationCode(locCode);
            ii.setQuantity(1);
            ii.setRemaining(1);
            ii.setStatus(InventoryItem.STATUS_IN_STOCK);
            ii.setInboundOrderId(orderId);
            ii.setCreatedBy(operator);
            // 生成 24 位编码;冲突自动重试(UNIQUE 索引兜底)
            for (int attempt = 0; attempt < 8; attempt++) {
                ii.setCode(codeGenerator.generate(p.getSku()));
                try {
                    itemDao.insert(ii);
                    generated.add(ii);
                    break;
                } catch (DuplicateKeyException dup) {
                    if (attempt == 7) throw new IllegalStateException("唯一编码生成冲突 8 次,请重试");
                }
            }
        }
        return generated;
    }

    /** 写 inbound_order_item 行(并把 codes 列表挂在 entity 上) */
    private InboundOrderItem persistOrderItem(BatchItem b, int orderId, List<InventoryItem> generated) {
        Product p = productDao.findById(b.productId)
                .orElseThrow(() -> new IllegalArgumentException("商品不存在, id=" + b.productId));
        InboundOrderItem it = new InboundOrderItem();
        it.setOrderId(orderId);
        it.setProductId(p.getId());
        it.setSku(p.getSku());
        it.setProductName(p.getName());
        it.setSupplier(b.supplier);
        it.setQuantity(b.quantity);
        it.setUnitPrice(p.getUnitPrice() == null ? BigDecimal.ZERO : p.getUnitPrice());
        it.setBeforeQty(b._beforeQty);
        it.setAfterQty(b._afterQty);
        it.setLocation(blankToNull(p.getLocation()));
        it.setRowRemark(b.rowRemark);
        orderDao.insertItem(it);
        // 填 codes 列表(供 commit 后展示)
        List<String> codes = new ArrayList<>();
        for (InventoryItem ii : generated) codes.add(ii.getCode());
        it.setCodes(codes);
        return it;
    }

    /** 写 inventory_log */
    private void persistInventoryLog(BatchItem b, String operator, int orderId, InboundOrderItem itemEntity) {
        InventoryLog log = new InventoryLog();
        log.setType(InventoryLog.TYPE_IN);
        log.setProductId(itemEntity.getProductId());
        log.setSku(itemEntity.getSku());
        log.setProductName(itemEntity.getProductName());
        log.setQuantity(b.quantity);
        log.setBeforeQty(b._beforeQty);
        log.setAfterQty(b._afterQty);
        log.setRemark(b.rowRemark);
        log.setOperator(operator);
        log.setOrderId(orderId);
        logDao.insert(log);
    }

    /** 取整单(含明细 + 每行的 codes 列表,从 inventory_item 查) */
    @Transactional(readOnly = true)
    public InboundOrder getOrderWithCodes(int orderId) {
        InboundOrder o = orderDao.findOrderById(orderId);
        if (o == null) return null;
        List<InboundOrderItem> items = orderDao.findItemsByOrderId(orderId);
        List<InventoryItem> invItems = itemDao.findByInboundOrder(orderId);
        // 简易分配:每个 itemEntity 按 quantity 顺序从 allCodes 取码(只读 commit 展示场景够用)
        List<String> allCodes = new ArrayList<>();
        for (InventoryItem ii : invItems) allCodes.add(ii.getCode());
        if (items != null) {
            int cursor = 0;
            for (InboundOrderItem it : items) {
                int q = it.getQuantity() == null ? 0 : it.getQuantity();
                List<String> sub = new ArrayList<>();
                for (int i = 0; i < q && cursor < allCodes.size(); i++) {
                    sub.add(allCodes.get(cursor++));
                }
                it.setCodes(sub);
            }
        }
        o.setItems(items);
        return o;
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

        // 内部传递用(由 processItemBatch 设置,供 persistOrderItem/persistInventoryLog 读)
        Integer _beforeQty;
        Integer _afterQty;
    }
}
