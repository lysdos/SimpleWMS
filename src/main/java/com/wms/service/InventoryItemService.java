package com.wms.service;

import com.wms.dao.InventoryItemDao;
import com.wms.model.InventoryItem;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class InventoryItemService {

    private final InventoryItemDao itemDao;

    @Autowired
    public InventoryItemService(InventoryItemDao itemDao) {
        this.itemDao = itemDao;
    }

    @Transactional(readOnly = true)
    public List<InventoryItem> list(String sku, String location, String status, int page, int pageSize) {
        return itemDao.findItems(sku, location, status, page, pageSize);
    }

    @Transactional(readOnly = true)
    public long count(String sku, String location, String status) {
        return itemDao.countItems(sku, location, status);
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

    /** 报废(必须 IN_STOCK) */
    @Transactional
    public void scrap(int id, String remark) {
        InventoryItem it = itemDao.findById(id);
        if (it == null) throw new IllegalArgumentException("库存单元不存在, id=" + id);
        if (!InventoryItem.STATUS_IN_STOCK.equals(it.getStatus())) {
            throw new IllegalStateException("仅在库(IN_STOCK)单元可报废,当前状态: " + it.getStatus());
        }
        int rows = itemDao.scrap(id, remark);
        if (rows == 0) {
            throw new IllegalStateException("报废失败,状态已变更");
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
