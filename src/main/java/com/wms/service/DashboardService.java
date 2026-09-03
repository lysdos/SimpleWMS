package com.wms.service;

import com.wms.dao.ProductDao;
import com.wms.model.DashboardStats;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class DashboardService {

    private final ProductDao productDao;

    @Autowired
    public DashboardService(ProductDao productDao) {
        this.productDao = productDao;
    }

    public DashboardStats stats() {
        DashboardStats s = new DashboardStats();
        s.setProductCount(productDao.count());
        s.setTotalQuantity(productDao.sumQuantity());
        s.setTotalValue(productDao.sumValue());
        s.setLowStockCount(productDao.countLowStock());
        return s;
    }
}