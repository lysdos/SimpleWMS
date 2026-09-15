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

    /**
     * 单条入库(老接口):改为 DRAFT 语义,不再即时写 inventory_log
     * 调用方(controller)负责后续 commit。返回创建出来的 DRAFT InboundOrder
     */
    public InboundOrder doInbound(int productId, int quantity, String remark, String operator) {
        List<BatchItem> items = new ArrayList<>();
        BatchItem it = new BatchItem();
        it.productId = productId;
        it.quantity = quantity;
        it.rowRemark = remark;
        items.add(it);
        return doInboundBatch(items, null, remark, operator, null);
    }

    /**
     * 批量入库(老 batch 接口):统一改为"DRAFT + 提交"语义,确保未正式提交不影响库存
     *   1) 校验 + 合并同 (productId, supplier) 的数量
     *   2) 写主表 status=DRAFT
     *   3) 调 addItemsToDraftInternal 写明细 + 生成 PENDING 单元(不调 adjustQuantity、不写流水)
     * 真实"生效"在 commitDraft() 里完成(PENDING 单元 → IN_STOCK + adjustQuantity + 写 inventory_log)
     *
     * @param orderId 可选:非 null 时把 items 追加到该 DRAFT 单,主表 externalNo/remark/orderNo 不会被覆盖
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

        // 2. 写主表 status=DRAFT(INSERT SQL 现在写入 status 列)
        InboundOrder order = new InboundOrder();
        order.setOrderNo(genOrderNo());
        order.setExternalNo(blankToNull(externalNo));
        order.setRemark(blankToNull(orderRemark));
        order.setOperator(operator);
        order.setStatus(InboundOrder.STATUS_DRAFT);
        order.setTotalQuantity(0);
        order.setItemCount(0);
        int newOrderId = orderDao.insertOrder(order);
        order.setId(newOrderId);

        // 3. 写明细 + 生成 PENDING 单元(不调 adjustQuantity、不写 inventory_log)
        return addItemsToDraftInternal(newOrderId, items, operator);
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
     * 修改 DRAFT 单明细行(数量/供应商/行备注),仅 DRAFT 可改,商品不可换。
     * 数量增减同步增删 PENDING 占位单元(未生效,不动库存/流水);
     * commit 时按行 quantity 统一激活,数量差值在占位阶段即可平衡。
     */
    @Transactional
    public InboundOrder updateDraftItem(int orderId, int itemId, BatchItem patch) {
        InboundOrder order = requireDraftOrder(orderId);
        if (patch == null) throw new IllegalArgumentException("明细不能为空");
        if (patch.quantity == null || patch.quantity <= 0) {
            throw new IllegalArgumentException("入库数量必须大于 0");
        }
        InboundOrderItem item = orderDao.findItemById(itemId);
        if (item == null || item.getOrderId() == null || item.getOrderId() != orderId) {
            throw new IllegalArgumentException("明细行不存在或不属于该单, itemId=" + itemId);
        }
        int oldQty = item.getQuantity() == null ? 0 : item.getQuantity();
        int newQty = patch.quantity;
        if (newQty > oldQty) {
            // 补占位单元
            BatchItem extra = new BatchItem();
            extra.productId = item.getProductId();
            extra.quantity = newQty - oldQty;
            extra.supplier = blankToNull(patch.supplier);
            extra.rowRemark = blankToNull(patch.rowRemark);
            String operator = order.getOperator();
            if (operator == null || operator.isBlank()) operator = "操作员";
            List<InventoryItem> generated = processItemBatch(extra, operator, orderId);
            for (InventoryItem u : generated) {
                itemDao.updateInboundItemId(itemId, u.getId());
            }
        } else if (newQty < oldQty) {
            itemDao.deletePendingByInboundItem(itemId, oldQty - newQty);
        }
        orderDao.updateItem(itemId, newQty, blankToNull(patch.supplier), blankToNull(patch.rowRemark));
        orderDao.refreshAggregates(orderId);
        return getOrder(orderId);
    }

    /**
     * 删除 DRAFT 单明细行(连带清理其 PENDING 占位单元),仅 DRAFT 可删。
     */
    @Transactional
    public InboundOrder deleteDraftItem(int orderId, int itemId) {
        requireDraftOrder(orderId);
        InboundOrderItem item = orderDao.findItemById(itemId);
        if (item == null || item.getOrderId() == null || item.getOrderId() != orderId) {
            throw new IllegalArgumentException("明细行不存在或不属于该单, itemId=" + itemId);
        }
        itemDao.deletePendingByInboundItem(itemId, Integer.MAX_VALUE);
        orderDao.deleteItem(itemId);
        orderDao.refreshAggregates(orderId);
        return getOrder(orderId);
    }

    private InboundOrder requireDraftOrder(int orderId) {
        InboundOrder order = orderDao.findOrderById(orderId);
        if (order == null) throw new IllegalArgumentException("入库单不存在, id=" + orderId);
        if (!InboundOrder.STATUS_DRAFT.equals(order.getStatus())) {
            throw new IllegalStateException("只有 DRAFT 单可以修改明细, 当前 status=" + order.getStatus());
        }
        return order;
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

        // 关键:状态推进带 WHERE status='DRAFT' 守卫,影响 0 行=并发竞争,事务整体回滚
        if (orderDao.updateStatusFromDraft(orderId, InboundOrder.STATUS_COMMITTED) == 0) {
            throw new IllegalStateException("入库单已被并发处理,放弃本次提交");
        }
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
        if (orderDao.updateStatusFromDraft(orderId, InboundOrder.STATUS_CANCELLED) == 0) {
            throw new IllegalStateException("入库单已被并发处理,放弃本次取消");
        }
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
