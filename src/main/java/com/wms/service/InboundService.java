package com.wms.service;

import com.wms.controller.NotFoundException;
import com.wms.dao.InboundOrderDao;
import com.wms.dao.InventoryItemDao;
import com.wms.dao.InventoryLogDao;
import com.wms.dao.LocationDao;
import com.wms.dao.ProductDao;
import com.wms.model.InboundOrder;
import com.wms.model.InboundOrderItem;
import com.wms.model.InventoryItem;
import com.wms.model.InventoryLog;
import com.wms.model.Location;
import com.wms.model.Product;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;
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
    private final LocationDao locationDao;
    private final CodeGenerator codeGenerator;
    private final JdbcTemplate jdbc;

    @Autowired
    public InboundService(ProductDao productDao,
                          InventoryLogDao logDao,
                          InboundOrderDao orderDao,
                          InventoryItemDao itemDao,
                          LocationDao locationDao,
                          CodeGenerator codeGenerator,
                          JdbcTemplate jdbc) {
        this.productDao = productDao;
        this.logDao = logDao;
        this.orderDao = orderDao;
        this.itemDao = itemDao;
        this.locationDao = locationDao;
        this.codeGenerator = codeGenerator;
        this.jdbc = jdbc;
    }

    // ==================== 库位解析 ====================

    /** 商品默认库位;商品也没设就用兜底库位 */
    private String defaultLocationOf(Product p) {
        String dflt = blankToNull(p.getLocation());
        return dflt == null ? Location.FALLBACK_CODE : dflt;
    }

    /**
     * 校验**用户显式指定**的库位:必须存在且启用,否则 400。
     * 只校验用户输入的那一个 —— 行上沿用的老库位不校验,否则库位一停用,
     * 连"只改数量"的编辑都会失败。
     */
    private String requireValidLocation(String code) {
        Location loc = locationDao.findByCode(code);
        if (loc == null) throw new IllegalArgumentException("库位不存在: " + code);
        if (Boolean.FALSE.equals(loc.getEnabled())) {
            throw new IllegalArgumentException("库位已停用: " + code);
        }
        return code;
    }

    /**
     * 一条入库明细最终落在哪个库位码。
     *
     * 解析顺序:行上显式指定 > 商品默认库位 > 兜底库位。
     * 允许逐行覆盖是因为"商品只有一个库位"这个旧假设已经不成立 ——
     * 一批货主仓库位放不下时,剩下的要能指定到副仓库位。
     *
     * ⚠️ **这里不校验**。校验(存在 + 启用)只做在用户输入进来的那一处
     * ({@code addItemsToDraftInternal} / {@code updateDraftItem}),因为往后传的值
     * 里有两种**不是用户现在选的、不该拦**的东西:商品默认库位、行上沿用的老库位 ——
     * 库位能被删掉/停用(LocationService.delete 没有"在用"保护),
     * 对这些值再校验一次,会让"商品默认库位事后被停用"变成入库/改单直接 400。
     */
    private String resolveLocationCode(String requested, Product p) {
        String code = blankToNull(requested);
        return code == null ? defaultLocationOf(p) : code;
    }

    /**
     * 批量入库(老 batch 接口):统一改为"DRAFT + 提交"语义,确保未正式提交不影响库存
     *   1) 校验 + 合并同 (productId, supplier) 的数量
     *   2) 写主表 status=DRAFT
     *   3) 调 addItemsToDraftInternal 写明细 + 生成 PENDING 单元(不调 adjustQuantity、不写流水)
     * 真实"生效"在 commitDraft() 里完成(PENDING 单元 → IN_STOCK + adjustQuantity + 写 inventory_log)
     *
     * 只负责**建新单**;往已有草稿单追加明细走 addItemToDraft。
     * (原先这里有个 orderId 参数 + "非 null 就追加到该草稿单"的分支,2026-09-24 删除:
     *  两个调用方——controller 与被删掉的 doInbound——传的都是 null,分支从来不会执行。)
     */
    @Transactional
    public InboundOrder doInboundBatch(List<BatchItem> items,
                                       String externalNo,
                                       String orderRemark,
                                       String createdBy) {
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("入库明细不能为空");
        }
        if (createdBy == null || createdBy.isBlank()) createdBy = "操作员";

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
        order.setCreatedBy(createdBy);
        order.setStatus(InboundOrder.STATUS_DRAFT);
        order.setTotalQuantity(0);
        order.setItemCount(0);
        int newOrderId = insertOrderWithRetry(order);
        order.setId(newOrderId);

        // 3. 写明细 + 生成 PENDING 单元(不调 adjustQuantity、不写 inventory_log)
        return addItemsToDraftInternal(newOrderId, items, createdBy);
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
                                          String keyword, String createdBy, String status,
                                          int page, int pageSize) {
        return orderDao.findOrders(beginDate, endDate, keyword, createdBy, status, page, pageSize);
    }

    @Transactional(readOnly = true)
    public long countOrders(String beginDate, String endDate,
                            String keyword, String createdBy, String status) {
        return orderDao.countOrders(beginDate, endDate, keyword, createdBy, status);
    }

    // ============================================================
    // PDA 入库登记(DRAFT / COMMITTED / CANCELLED)状态机
    // ============================================================

    /**
     * 创建空草稿入库单。返回完整 InboundOrder(id 已生成,status=DRAFT,total_quantity=0,item_count=0)。
     */
    @Transactional
    public InboundOrder createDraft(String externalNo, String remark, String createdBy) {
        if (createdBy == null || createdBy.isBlank()) createdBy = "操作员";

        InboundOrder order = new InboundOrder();
        order.setOrderNo(genOrderNo());
        order.setExternalNo(blankToNull(externalNo));
        order.setRemark(blankToNull(remark));
        order.setCreatedBy(createdBy);
        order.setStatus(InboundOrder.STATUS_DRAFT);
        order.setTotalQuantity(0);
        order.setItemCount(0);
        int id = insertOrderWithRetry(order);
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
     * 修改 DRAFT 单头(外部单号/备注),仅 DRAFT 可改。返回最新单。
     */
    @Transactional
    public InboundOrder updateDraftHeader(int orderId, String externalNo, String remark) {
        requireDraftOrder(orderId);
        if (orderDao.updateHeader(orderId, blankToNull(externalNo), blankToNull(remark)) == 0) {
            throw new IllegalStateException("入库单已被并发处理,放弃本次编辑");
        }
        return getOrder(orderId);
    }

    /**
     * 修改 DRAFT 单明细行(商品/数量/供应商/行备注),仅 DRAFT 可改。
     * 数量增减同步增删 PENDING 占位单元(未生效,不动库存/流水);
     * 换商品时清空该行全部旧占位码,按新商品+新数量重建,并同步行上的
     * sku/名称/单价/库位快照;commit 时按行 quantity 统一激活。
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
            throw new NotFoundException("明细行不存在或不属于该单, itemId=" + itemId);
        }
        int oldQty = item.getQuantity() == null ? 0 : item.getQuantity();
        int newQty = patch.quantity;
        String operator = order.getCreatedBy();
        if (operator == null || operator.isBlank()) operator = "操作员";

        boolean productChanged = patch.productId != null
                && !patch.productId.equals(item.getProductId());
        Product product = productDao.findById(
                        productChanged ? patch.productId : item.getProductId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "商品不存在, id=" + (productChanged ? patch.productId : item.getProductId())));

        // 批次效期:patch 未提供时沿用行上现值(避免只改数量/供应商就把效期抹掉)
        java.time.LocalDate effProdDate = patch.productionDate != null
                ? patch.productionDate : item.getProductionDate();
        Integer effShelfDays = patch.shelfLifeDays != null
                ? patch.shelfLifeDays : item.getShelfLifeDays();

        // 库位:显式传了就用传的;没传时 —— 换商品则跟随新商品的默认库位,
        // 否则沿用行上现值(只改数量/备注不该把货挪走)。
        // 注意沿用现值时**不校验**:库位事后被停用/删除的话,连改个数量都会失败。
        String requestedLoc = blankToNull(patch.location);
        String effLocation;
        if (requestedLoc != null) {
            effLocation = requireValidLocation(requestedLoc);
        } else if (productChanged) {
            effLocation = defaultLocationOf(product);
        } else {
            effLocation = item.getLocation() != null
                    ? item.getLocation() : defaultLocationOf(product);
        }

        // 供应商/行备注:patch 里**没带**(null)就沿用原值。
        // 以前是裸赋值,于是 PDA 上「只改数量」也会把供应商和行备注悄悄清空
        // (供应商下拉落在「不指定」或原供应商已被改名时,发上来的就是 null)。
        // 想真的清空就显式发空串 —— 空串会被 blankToNull 归一成 null。
        String effSupplier = patch.supplier != null ? blankToNull(patch.supplier) : item.getSupplier();
        String effRowRemark = patch.rowRemark != null ? blankToNull(patch.rowRemark) : item.getRowRemark();

        // 占位单元必须与行快照一致:商品/数量/库位/生产日期/保质期**任一项**变了,
        // 就把该行的 PENDING 单元整体作废重建。
        //
        // 为什么整体重建而不是分别打补丁:占位单元的库位与效期只在创建那一刻写入,
        // 提交(activateByInboundItem)只改 status/remaining,不会回头读行快照。
        // 以前按「换商品 / 加数量 / 减数量」分三支处理,于是「只改库位不改数量」
        // 一个分支都不进 —— 行上写着 B-02、单元还在 A-01,提交后库存落错库位;
        // 只改效期同理(出库拣货和临期预警读的是单元上的旧到期日)。
        // 改成一条路径重建,这几类字段就一起跟上了。
        boolean unitsNeedRebuild = productChanged
                || newQty != oldQty
                || !Objects.equals(item.getLocation(), effLocation)
                || !Objects.equals(item.getProductionDate(), effProdDate)
                || !Objects.equals(item.getShelfLifeDays(), effShelfDays);

        if (unitsNeedRebuild) {
            itemDao.deletePendingByInboundItem(itemId, Integer.MAX_VALUE);
            BatchItem regen = new BatchItem();
            regen.productId = product.getId();
            regen.quantity = newQty;
            regen.supplier = effSupplier;
            regen.rowRemark = effRowRemark;
            regen.productionDate = effProdDate;
            regen.shelfLifeDays = effShelfDays;
            regen.location = effLocation;
            List<InventoryItem> generated = processItemBatch(regen, operator, orderId);
            for (InventoryItem u : generated) {
                itemDao.updateInboundItemId(itemId, u.getId());
            }
        }

        // 行快照同步(换商品时 sku/名称/单价/库位一并更新)
        item.setProductId(product.getId());
        item.setSku(product.getSku());
        item.setProductName(product.getName());
        item.setUnitPrice(product.getUnitPrice() == null ? BigDecimal.ZERO : product.getUnitPrice());
        item.setLocation(effLocation);
        item.setQuantity(newQty);
        item.setSupplier(effSupplier);
        item.setProductionDate(effProdDate);
        item.setShelfLifeDays(effShelfDays);
        item.setRowRemark(effRowRemark);
        orderDao.updateItem(item);
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
            throw new NotFoundException("明细行不存在或不属于该单, itemId=" + itemId);
        }
        itemDao.deletePendingByInboundItem(itemId, Integer.MAX_VALUE);
        orderDao.deleteItem(itemId);
        orderDao.refreshAggregates(orderId);
        return getOrder(orderId);
    }

    /**
     * 取草稿入库单,**加行锁**(FOR UPDATE)。改明细的三个入口(updateDraftHeader /
     * updateDraftItem / deleteDraftItem)都以它开头,锁在事务第一条读上拿到,
     * 于是"改明细"与"提交/取消"在同一张单上串行 —— 详见
     * {@link com.wms.dao.InboundOrderDao#findInboundOrderByIdForUpdate(int)}。
     */
    private InboundOrder requireDraftOrder(int orderId) {
        InboundOrder order = orderDao.findInboundOrderByIdForUpdate(orderId);
        if (order == null) throw new NotFoundException("入库单不存在, id=" + orderId);
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
        // 加锁读(事务第一条读):与"追加/修改明细"互斥,否则提交完的单还会被追加明细,
        // 那些行的 PENDING 占位单元永远不会被激活(见 findInboundOrderByIdForUpdate)
        InboundOrder order = orderDao.findInboundOrderByIdForUpdate(orderId);
        if (order == null) throw new NotFoundException("入库单不存在, id=" + orderId);
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
        String operator = order.getCreatedBy();
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
    private void activateItemsAndLog(List<InboundOrderItem> items, String createdBy, int orderId) {
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
            persistInventoryLog(item, createdBy, orderId, before, after);
        }
    }

    /**
     * cancel:校验 DRAFT;删 PENDING 状态的 inventory_item(未生效);删 inbound_order_item;
     *         不动 product.quantity(DRAFT 期本来就没加过);改 status = CANCELLED(留审计)。
     */
    @Transactional
    public void cancelDraft(int orderId) {
        InboundOrder order = orderDao.findInboundOrderByIdForUpdate(orderId);
        if (order == null) throw new NotFoundException("入库单不存在, id=" + orderId);
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
     * 内部方法:把 items 追加到指定 DRAFT 单(addItemToDraft 调;新建单时由 doInboundBatch 走另一条路)。
     * 注意:同 (productId, supplier) 仍在本批内合并,但不与单据已有明细合并(简化:PDA 单次 add 一次性就好)。
     */
    private InboundOrder addItemsToDraftInternal(int orderId, List<BatchItem> items, String createdBy) {
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("入库明细不能为空");
        }
        // 加锁读:追加明细与提交/取消互斥(新建单时 doInboundBatch 会走到这里,
        // 那行是自己事务刚插入的,拿自己的锁无副作用)
        InboundOrder order = orderDao.findInboundOrderByIdForUpdate(orderId);
        if (order == null) throw new NotFoundException("入库单不存在, id=" + orderId);
        if (!InboundOrder.STATUS_DRAFT.equals(order.getStatus())) {
            throw new IllegalStateException("只有 DRAFT 单可以追加明细, 当前 status=" + order.getStatus());
        }
        if (createdBy == null || createdBy.isBlank()) createdBy = order.getCreatedBy();
        if (createdBy == null || createdBy.isBlank()) createdBy = "操作员";

        // 校验 + 标准化
        for (BatchItem b : items) {
            if (b.productId == null) throw new IllegalArgumentException("请选择商品");
            if (b.quantity == null || b.quantity <= 0) {
                throw new IllegalArgumentException("入库数量必须大于 0");
            }
            b.supplier = blankToNull(b.supplier);
            b.rowRemark = blankToNull(b.rowRemark);
            // 库位也归一化(空串→null),因为它进了下面的合并 key。
            // 顺带在这里做**唯一一次**校验:这是用户指定的库位进入系统的入口,
            // 再往后(resolveLocationCode)就不再校验了,免得把商品默认库位、
            // 行上沿用的老库位也拦下来。
            b.location = blankToNull(b.location);
            if (b.location != null) requireValidLocation(b.location);
        }

        // 本批合并。key 必须含批次字段(生产日期/保质期):同 SKU 同供应商但
        // 生产日期不同的两行是两个批次,合并会把另一批的日期抹掉。
        // 库位同理:同一批次拆到主仓/副仓两行时,合并会把这批的库位抹成第一行的,
        // 另一边的货凭空跑到主仓去,且不报任何错。
        Map<String, BatchItem> merged = new LinkedHashMap<>();
        for (BatchItem raw : items) {
            String key = raw.productId + "|" + (raw.supplier == null ? "" : raw.supplier)
                    + "|" + (raw.productionDate == null ? "" : raw.productionDate)
                    + "|" + (raw.shelfLifeDays == null ? "" : raw.shelfLifeDays)
                    + "|" + (raw.location == null ? "" : raw.location);
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
            List<InventoryItem> codes = processItemBatch(b, createdBy, orderId);
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
    private List<InventoryItem> processItemBatch(BatchItem b, String createdBy, int orderId) {
        Product p = productDao.findById(b.productId)
                .orElseThrow(() -> new IllegalArgumentException("商品不存在, id=" + b.productId));
        b._beforeQty = p.getQuantity();        // 提交前快照;commit 时真正做 adjustQuantity
        b._afterQty = p.getQuantity();

        // 批次效期:生产日期+保质期天数都有才推得到期日;任一缺失则不参与效期预警
        java.time.LocalDate expiry = (b.productionDate != null && b.shelfLifeDays != null && b.shelfLifeDays > 0)
                ? b.productionDate.plusDays(b.shelfLifeDays) : null;

        String locCode = resolveLocationCode(b.location, p);
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
            ii.setProductionDate(b.productionDate);
            ii.setShelfLifeDays(b.shelfLifeDays);
            ii.setExpiryDate(expiry);
            ii.setCreatedBy(createdBy);
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
        it.setLocation(resolveLocationCode(b.location, p));
        it.setProductionDate(b.productionDate);
        it.setShelfLifeDays(b.shelfLifeDays);
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
    private void persistInventoryLog(InboundOrderItem itemEntity, String createdBy, int orderId,
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
        log.setCreatedBy(createdBy);
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

    /**
     * 建单头:订单号撞 {@code uk(orders.order_no)} 时换个号再试一次。
     *
     * 单号 = 秒级时间戳 + 3 位随机(1/900),同一秒内两处同时建单确有概率撞上,而
     * order_no 是 UNIQUE —— 不重试的话用户看到的是一句原始驱动报错(Duplicate entry ...),
     * 再点一次多半就好了,但现场只会以为系统坏了。口径与
     * {@link StockCheckService#create} 的重试一致(盘点单号早就这么做了,订单侧一直没有)。
     */
    private int insertOrderWithRetry(InboundOrder order) {
        try {
            return orderDao.insertOrder(order);
        } catch (DuplicateKeyException e) {
            order.setOrderNo(genOrderNo());
            return orderDao.insertOrder(order);
        }
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
        public java.time.LocalDate productionDate;  // 批次生产日期(可选)
        public Integer shelfLifeDays;               // 批次保质期天数(可选)
        public String location;                     // 目标库位码(可选;空=用商品默认库位)

        // 内部传递用(由 processItemBatch 设置,供 persistOrderItem/persistInventoryLog 读)
        Integer _beforeQty;
        Integer _afterQty;
    }
}
