package com.wms.service;

import com.wms.dao.InventoryItemDao;
import com.wms.dao.InventoryLogDao;
import com.wms.dao.ProductDao;
import com.wms.model.InventoryItem;
import com.wms.model.InventoryLog;
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

    @Autowired
    public InventoryItemService(InventoryItemDao itemDao, ProductDao productDao, InventoryLogDao logDao) {
        this.itemDao = itemDao;
        this.productDao = productDao;
        this.logDao = logDao;
    }

    @Transactional(readOnly = true)
    public List<InventoryItem> list(String sku, String location, String status,
                                    String expiryStatus, String expiryFrom, String expiryTo,
                                    int page, int pageSize) {
        return itemDao.findItems(sku, location, status, expiryStatus, expiryFrom, expiryTo, page, pageSize);
    }

    @Transactional(readOnly = true)
    public long count(String sku, String location, String status,
                      String expiryStatus, String expiryFrom, String expiryTo) {
        return itemDao.countItems(sku, location, status, expiryStatus, expiryFrom, expiryTo);
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
    public void scrap(int id, String remark) {
        InventoryItem it = itemDao.findById(id);
        if (it == null) throw new IllegalArgumentException("库存单元不存在, id=" + id);
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
            logDao.insert(log);
        }
    }

    /** 调拨(必须 IN_STOCK) */
    @Transactional
    public void transfer(int id, String toLocation) {
        if (toLocation == null || toLocation.isBlank()) {
            throw new IllegalArgumentException("目标库位不能为空");
        }
        InventoryItem it = itemDao.findById(id);
        if (it == null) throw new IllegalArgumentException("库存单元不存在, id=" + id);
        if (!InventoryItem.STATUS_IN_STOCK.equals(it.getStatus())) {
            throw new IllegalStateException("仅在库(IN_STOCK)单元可调拨,当前状态: " + it.getStatus());
        }
        if (toLocation.equalsIgnoreCase(it.getLocationCode())) {
            throw new IllegalArgumentException("目标库位与当前库位相同");
        }
        int rows = itemDao.transfer(id, toLocation.trim());
        if (rows == 0) {
            throw new IllegalStateException("调拨失败,状态已变更");
        }
    }
}
