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
            itemEntities.add(itemEntity);
        }
        order.setItems(itemEntities);

        // 5. 老 batch 接口直接下单 = COMMITTED — 立即"生效":PENDING 单元 → IN_STOCK,
        //    累加 productDao.adjustQuantity,写 inventory_log
        activateItemsAndLog(itemEntities, operator, newOrderId);
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

        // 把该单所有 PENDING 库存单元 → IN_STOCK + remaining=1(每件真正"入"库)
        // + 累加 product.quantity + 写 inventory_log
        List<InboundOrderItem> items = orderDao.findItemsByOrderId(orderId);
        String operator = order.getOperator();
        if (operator == null || operator.isBlank()) operator = "操作员";
        activateItemsAndLog(items, operator, orderId);

        orderDao.updateStatus(orderId, InboundOrder.STATUS_COMMITTED);
        return getOrderWithCodes(orderId);
    }

    /**
     * 把一组 InboundOrderItem 的 PENDING 库存单元升级为 IN_STOCK(remaining=1),
     * 累加 product.quantity,写 inventory_log。
     * commitDraft 与 doInboundBatch 复用。
     */
    private void activateItemsAndLog(List<InboundOrderItem> items, String operator, int orderId) {
        for (InboundOrderItem item : items) {
            if (item.getProductId() == null || item.getQuantity() == null) continue;
            Product p = productDao.findById(item.getProductId())
                    .orElseThrow(() -> new IllegalArgumentException("商品不存在, id=" + item.getProductId()));
            int before = p.getQuantity();
            int rows = productDao.adjustQuantity(p.getId(), item.getQuantity());
            if (rows == 0) throw new IllegalStateException("入库失败,商品状态异常 id=" + p.getId());
            int after = before + item.getQuantity();
            // 把该行所有 PENDING 库存单元升级为 IN_STOCK + remaining=1
            itemDao.activateByInboundItem(item.getId());
            // 写一条 IN 流水
            persistInventoryLog(item, operator, orderId, before, after);
        }
    }

    /**
     * cancel:校验 DRAFT;删 PENDING 状态的 inventory_item(未生效);删 inbound_order_item;
     *         不动 product.quantity(DRAFT 期本来就没加过);改 status = CANCELLED(留审计)。
     */
    @Transactional
    public void cancelDraft(int orderId) {
        InboundOrder order = orderDao.findOrderById(orderId);
        if (order == null) throw new IllegalArgumentException("入库单不存在, id=" + orderId);
        if (!InboundOrder.STATUS_DRAFT.equals(order.getStatus())) {
            throw new IllegalStateException("只有 DRAFT 单可以取消, 当前 status=" + order.getStatus());
        }

        // 1) 删 PENDING 状态的库存单元占位(DRAFT 期未生效,不需要回滚 product.quantity)
        itemDao.deletePendingByInboundOrder(orderId);

        // 2) 删 inbound_order_item
        orderDao.deleteItemsByOrderId(orderId);

        // 3) 主表 aggregates 清零 + status 改 CANCELLED
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
            // 不写 inventory_log,不调整 product.quantity — commit 才生效
        }

        // 刷新 aggregates
        orderDao.refreshAggregates(orderId);
        return getOrder(orderId);
    }

    /** 生成 N 个 inventory_item 24 位码并落库(仅作码占位)。返回所有生成的 InventoryItem 实体
     *  注意:此阶段 inventory_item 状态为 PENDING + remaining=0,不入库总库存、不调 productDao.adjustQuantity、
     *  不写 inventory_log。真正的"生效"在 commitDraft() 里完成。*/
    private List<InventoryItem> processItemBatch(BatchItem b, String operator, int orderId) {
        Product p = productDao.findById(b.productId)
                .orElseThrow(() -> new IllegalArgumentException("商品不存在, id=" + b.productId));
        b._beforeQty = p.getQuantity();        // 提交前快照;commit 时真正做 adjustQuantity
        b._afterQty = p.getQuantity();

        String locCode = blankToNull(p.getLocation()) == null ? "默认库位" : p.getLocation();
        List<InventoryItem> generated = new ArrayList<>();
        for (int unit = 0; unit < b.quantity; unit++) {
            InventoryItem ii = new InventoryItem();
            ii.setProductId(p.getId());
            ii.setSku(p.getSku());
            ii.setProductName(p.getName());
            ii.setLocationCode(locCode);
            ii.setQuantity(1);
            ii.setRemaining(0);               // 占位期剩余 0
            ii.setStatus(InventoryItem.STATUS_PENDING);  // 待入库
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
        // 回填 inventory_item.inbound_item_id(每件实物→它所属的明细行)
        // 这样 getOrderWithCodes 可按 inbound_item_id 精确分配码,不再"按 quantity 均分"
        if (it.getId() != null && generated != null) {
            for (InventoryItem ii : generated) {
                if (ii != null && ii.getId() != null) {
                    itemDao.updateInboundItemId(it.getId(), ii.getId());
                }
            }
        }
        // 填 codes 列表(供 commit 后展示)
        List<String> codes = new ArrayList<>();
        for (InventoryItem ii : generated) codes.add(ii.getCode());
        it.setCodes(codes);
        return it;
    }

    /** 写一条入库流水(IN)。commitDraft 时调用,before/after 来自 productDao.adjustQuantity 前后值 */
    private void persistInventoryLog(InboundOrderItem itemEntity, String operator, int orderId,
                                    int before, int after) {
        InventoryLog log = new InventoryLog();
        log.setType(InventoryLog.TYPE_IN);
        log.setProductId(itemEntity.getProductId());
        log.setSku(itemEntity.getSku());
        log.setProductName(itemEntity.getProductName());
        log.setQuantity(itemEntity.getQuantity());
        log.setBeforeQty(before);
        log.setAfterQty(after);
        log.setRemark(itemEntity.getRowRemark());
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
        // 按 inbound_item_id 精确查每行对应码(依赖 processItemBatch → persistOrderItem
        // 已回填 inventory_item.inbound_item_id)。跨 SKU 多次 addItem 也能一一对应。
        if (items != null) {
            for (InboundOrderItem it : items) {
                if (it.getId() == null) { it.setCodes(new ArrayList<>()); continue; }
                List<InventoryItem> rowUnits = itemDao.findByInboundItem(it.getId());
                List<String> codes = new ArrayList<>();
                for (InventoryItem ii : rowUnits) codes.add(ii.getCode());
                it.setCodes(codes);
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
