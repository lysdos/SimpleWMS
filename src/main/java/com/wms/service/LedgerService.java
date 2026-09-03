package com.wms.service;

import com.wms.dao.InventoryLogDao;
import com.wms.model.InventoryLog;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class LedgerService {

    private final InventoryLogDao logDao;

    @Autowired
    public LedgerService(InventoryLogDao logDao) {
        this.logDao = logDao;
    }

    public List<InventoryLog> list(String type, String keyword, Integer limit) {
        return logDao.findLogs(type, keyword, limit);
    }
}