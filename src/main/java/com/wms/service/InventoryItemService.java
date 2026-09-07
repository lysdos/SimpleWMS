package com.wms.service;

import com.wms.dao.InventoryItemDao;
import com.wms.model.InventoryItem;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InventoryItemService {

    private final InventoryItemDao itemDao;

    @Autowired
    public InventoryItemService(InventoryItemDao itemDao) {
        this.itemDao = itemDao;
    }

    @Transactional(readOnly = true)
    public InventoryItem findByCode(String code) {
        if (code == null || code.isBlank()) return null;
        return itemDao.findByCode(code.trim());
    }
}
