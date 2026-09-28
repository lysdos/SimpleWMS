package com.wms.service;

import com.wms.controller.NotFoundException;
import com.wms.dao.InventoryItemDao;
import com.wms.dao.InventoryLogDao;
import com.wms.dao.LocationDao;
import com.wms.dao.ProductDao;
import com.wms.model.InventoryItem;
import com.wms.model.InventoryLog;
import com.wms.model.Location;
import com.wms.model.Product;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class InventoryItemService {

    private final InventoryItemDao itemDao;
    private final ProductDao productDao;
    private final InventoryLogDao logDao;
    private final LocationDao locationDao;

    @Autowired
    public InventoryItemService(InventoryItemDao itemDao, ProductDao productDao,
                                InventoryLogDao logDao, LocationDao locationDao) {
        this.itemDao = itemDao;
        this.productDao = productDao;
        this.logDao = logDao;
        this.locationDao = locationDao;
    }

    @Transactional(readOnly = true)
    public List<InventoryItem> list(String sku, String location, String status,
                                    String expiryStatus, String expiryFrom, String expiryTo,
                                    Integer warehouseId, int page, int pageSize) {
        return itemDao.findItems(sku, location, status, expiryStatus, expiryFrom, expiryTo,
                warehouseId, page, pageSize);
    }

    @Transactional(readOnly = true)
    public long count(String sku, String location, String status,
                      String expiryStatus, String expiryFrom, String expiryTo,
                      Integer warehouseId) {
        return itemDao.countItems(sku, location, status, expiryStatus, expiryFrom, expiryTo, warehouseId);
    }

    @Transactional(readOnly = true)
    public InventoryItem get(int id) {
        return itemDao.findById(id);
    }

    @Transactional(readOnly = true)
    public List<InventoryItem> listByInboundOrder(int orderId) {
        return itemDao.findByInboundOrder(orderId);
    }

    @Transactional(readOnly = true)
    public List<InventoryItem> listByOutboundOrder(int orderId) {
        return itemDao.findByOutboundOrder(orderId);
    }

    @Transactional(readOnly = true)
    public InventoryItem findByCode(String code) {
        if (code == null || code.isBlank()) return null;
        return itemDao.findByCode(code.trim());
    }

    /** 报废(必须 IN_STOCK),同步扣减 product.quantity 并写 SCRAP 流水留痕 */
    @Transactional
    public void scrap(int id, String remark, String createdBy) {
        InventoryItem it = itemDao.findById(id);
        if (it == null) throw new NotFoundException("库存单元不存在, id=" + id);
        if (!InventoryItem.STATUS_IN_STOCK.equals(it.getStatus())) {
            throw new IllegalStateException("仅在库(IN_STOCK)单元可报废,当前状态: " + it.getStatus());
        }
        int qty = it.getRemaining() == null ? 1 : it.getRemaining();
        int rows = itemDao.scrap(id, remark);
        if (rows == 0) {
            throw new IllegalStateException("报废失败,状态已变更");
        }
        if (qty > 0) {
            int before = 0;
            Product p = productDao.findById(it.getProductId()).orElse(null);
            if (p != null && p.getQuantity() != null) before = p.getQuantity();
            int adj = productDao.adjustQuantity(it.getProductId(), -qty);
            if (adj == 0) {
                // 商品已被删除,或扣减会导致负库存:整体回滚(报废状态变更+流水一起撤销)
                throw new IllegalStateException("商品不存在或账面库存不足,报废已回滚, productId=" + it.getProductId());
            }
            InventoryLog log = new InventoryLog();
            log.setType("SCRAP");
            log.setProductId(it.getProductId());
            log.setSku(it.getSku());
            log.setProductName(it.getProductName());
            log.setQuantity(-qty);
            log.setBeforeQty(before);
            log.setAfterQty(before - qty);
            log.setRemark(remark);
            // 报废是减库存的动作,必须留"谁干的" —— 以前这里没写,流水里 createdBy 一直是空
            log.setCreatedBy(createdBy == null || createdBy.isBlank() ? "操作员" : createdBy);
            logDao.insert(log);
        }
    }

    /**
     * 调拨:把在库单元挪到另一个库位(必须 IN_STOCK)。
     *
     * 只改 location_code、状态保持 IN_STOCK —— 实物还是那一件,只是换了个位置,
     * 所以总量、出库锁定、盘点照常认它。另写一条 TRANSFER 流水,记下谁把它从哪挪到哪。
     */
    @Transactional
    public void transfer(int id, String toLocation, String createdBy) {
        if (toLocation == null || toLocation.isBlank()) {
            throw new IllegalArgumentException("目标库位不能为空");
        }
        String target = toLocation.trim();
        InventoryItem it = itemDao.findById(id);
        if (it == null) throw new NotFoundException("库存单元不存在, id=" + id);
        if (!InventoryItem.STATUS_IN_STOCK.equals(it.getStatus())) {
            throw new IllegalStateException("仅在库(IN_STOCK)单元可调拨,当前状态: " + it.getStatus());
        }
        if (target.equalsIgnoreCase(it.getLocationCode())) {
            throw new IllegalArgumentException("目标库位与当前库位相同");
        }
        // 目标库位必须真实存在。以前只校验非空,能调出一个 location 表里没有的
        // "幽灵库位码",之后出库锁定、按仓筛选、盘点范围都再也对不上它。
        Location loc = locationDao.findByCode(target);
        if (loc == null) throw new IllegalArgumentException("库位不存在: " + target);
        if (Boolean.FALSE.equals(loc.getEnabled())) {
            throw new IllegalArgumentException("库位已停用: " + target);
        }
        String from = it.getLocationCode();
        int rows = itemDao.transfer(id, from, target);
        if (rows == 0) {
            // DAO 的 WHERE 同时守着 status 与 location_code:两种情况都说明这条读已经过期
            // (并发调拨/出库/报废),此时不能写流水,否则 transferred_from 会是假值
            throw new IllegalStateException("调拨失败:单元状态或库位已被改动,请刷新后重试");
        }
        // 留痕:调拨不改变全局总量,所以 quantity=0(照 SCAN 标记的先例),
        // 挪到哪儿写在 remark 里
        InventoryLog log = new InventoryLog();
        log.setType("TRANSFER");
        log.setProductId(it.getProductId());
        log.setSku(it.getSku());
        log.setProductName(it.getProductName());
        log.setQuantity(0);
        log.setBeforeQty(0);
        log.setAfterQty(0);
        log.setRemark("调拨: " + (from == null ? "-" : from) + " → " + target);
        log.setCreatedBy(createdBy == null || createdBy.isBlank() ? "操作员" : createdBy);
        logDao.insert(log);
    }
}
