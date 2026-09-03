package com.wms.service;

import com.wms.dao.ProductDao;
import com.wms.model.Product;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
public class ProductService {

    private final ProductDao productDao;

    @Autowired
    public ProductService(ProductDao productDao) {
        this.productDao = productDao;
    }

    public List<Product> list(String keyword) {
        return productDao.findAll(keyword);
    }

    public Optional<Product> get(int id) {
        return productDao.findById(id);
    }

    @Transactional
    public Product create(Product p) {
        validate(p, true);
        Optional<Product> exist = productDao.findBySku(p.getSku());
        if (exist.isPresent()) {
            throw new IllegalArgumentException("SKU 已存在: " + p.getSku());
        }
        try {
            int id = productDao.insert(p);
            p.setId(id);
            return p;
        } catch (DuplicateKeyException e) {
            throw new IllegalArgumentException("SKU 已存在: " + p.getSku());
        }
    }

    @Transactional
    public Product update(Product p) {
        if (p.getId() == null) {
            throw new IllegalArgumentException("更新失败:缺少商品 id");
        }
        validate(p, false);
        Product exist = productDao.findById(p.getId())
                .orElseThrow(() -> new IllegalArgumentException("商品不存在, id=" + p.getId()));

        // SKU 唯一性校验
        if (!exist.getSku().equals(p.getSku())) {
            productDao.findBySku(p.getSku()).ifPresent(other -> {
                if (!other.getId().equals(p.getId())) {
                    throw new IllegalArgumentException("SKU 已存在: " + p.getSku());
                }
            });
        }
        productDao.update(p);
        return productDao.findById(p.getId()).orElseThrow();
    }

    @Transactional
    public void delete(int id) {
        if (productDao.findById(id).isEmpty()) {
            throw new IllegalArgumentException("商品不存在, id=" + id);
        }
        productDao.delete(id);
    }

    private void validate(Product p, boolean creating) {
        if (p.getSku() == null || p.getSku().isBlank()) {
            throw new IllegalArgumentException("SKU 不能为空");
        }
        if (p.getName() == null || p.getName().isBlank()) {
            throw new IllegalArgumentException("名称 不能为空");
        }
        if (p.getQuantity() != null && p.getQuantity() < 0) {
            throw new IllegalArgumentException("库存数量 不能小于 0");
        }
        if (p.getUnitPrice() != null && p.getUnitPrice().signum() < 0) {
            throw new IllegalArgumentException("单价 不能小于 0");
        }
        if (p.getLowStock() == null || p.getLowStock() < 0) {
            p.setLowStock(10);
        }
        if (creating && (p.getQuantity() == null || p.getQuantity() < 0)) {
            p.setQuantity(0);
        }
    }
}