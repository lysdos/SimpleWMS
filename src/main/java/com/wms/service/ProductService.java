package com.wms.service;

import com.wms.controller.NotFoundException;
import com.wms.dao.InventoryItemDao;
import com.wms.dao.LocationDao;
import com.wms.dao.ProductDao;
import com.wms.dao.SupplierDao;
import com.wms.model.Product;
import com.wms.model.ProductSupplier;
import com.wms.model.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Service
public class ProductService {

    private final ProductDao productDao;
    private final SupplierDao supplierDao;
    private final InventoryItemDao itemDao;
    private final LocationDao locationDao;

    @Autowired
    public ProductService(ProductDao productDao, SupplierDao supplierDao, InventoryItemDao itemDao,
                          LocationDao locationDao) {
        this.productDao = productDao;
        this.supplierDao = supplierDao;
        this.itemDao = itemDao;
        this.locationDao = locationDao;
    }

    /**
     * 商品默认库位校验。
     *
     * 这个值会在入库时被 InboundService.defaultLocationOf 原样当成
     * inventory_item.location_code 写进库存单元 —— 不校验的话,一个错别字
     * (A-01-1 写成 A-01-01)就会造出一批「查不到所属仓库」的幽灵单元:
     * 能出库、计入总量,却在任何按仓筛选和按仓盘点里都看不见。
     *
     * 长度卡 32 而非 64:product.location 是 VARCHAR(64),但最终落点
     * inventory_item.location_code 只有 VARCHAR(32),超了会在入库时报数据库错。
     */
    private void validateLocation(String location) {
        if (location == null || location.isBlank()) return;   // 空 = 交给兜底库位
        String code = location.trim();
        if (code.length() > 32) {
            throw new IllegalArgumentException("默认库位编码过长(最多 32): " + code);
        }
        if (locationDao.findByCode(code) == null) {
            throw new IllegalArgumentException("默认库位不存在: " + code);
        }
    }

    public List<Product> list(String keyword) {
        return productDao.findAll(keyword);
    }

    public List<Product> list(String keyword, String supplier) {
        return productDao.findAll(keyword, supplier);
    }

    public Optional<Product> get(int id) {
        return productDao.findById(id);
    }

    @Transactional
    public Product create(Product p) {
        validate(p, true);
        validateLocation(p.getLocation());
        Optional<Product> exist = productDao.findBySku(p.getSku());
        if (exist.isPresent()) {
            throw new IllegalArgumentException("SKU 已存在: " + p.getSku());
        }
        try {
            int id = productDao.insert(p);
            p.setId(id);
            // 关联供应商
            if (p.getSuppliersAsList() != null) {
                supplierDao.replaceProductSuppliers(id, p.getSuppliersAsList());
            }
            return productDao.findById(id).orElseThrow();
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

        // 只在库位真的被改动时校验:老数据里可能存着已删库位的幽灵码,
        // 改个商品名字不该被历史脏数据卡住(但新填的必须是真的)
        if (!Objects.equals(exist.getLocation(), p.getLocation())) {
            validateLocation(p.getLocation());
        }

        if (!exist.getSku().equals(p.getSku())) {
            productDao.findBySku(p.getSku()).ifPresent(other -> {
                if (!other.getId().equals(p.getId())) {
                    throw new IllegalArgumentException("SKU 已存在: " + p.getSku());
                }
            });
        }
        productDao.update(p);
        // 关联供应商(全量替换)
        if (p.getSuppliersAsList() != null) {
            supplierDao.replaceProductSuppliers(p.getId(), p.getSuppliersAsList());
        }
        return productDao.findById(p.getId()).orElseThrow();
    }

    @Transactional
    public void delete(int id) {
        if (productDao.findById(id).isEmpty()) {
            throw new NotFoundException("商品不存在, id=" + id);
        }
        // 治本:还有未终结的库存单元就不能删商品。原来不校验,删掉商品后它名下的单元会变成
        // "孤儿"(inventory_item.product_id 指向不存在的商品),之后提交含该商品的出库单时
        // commitDraft 的 FIFO 扣减查不到商品,直接失败。
        int active = itemDao.countActiveByProductId(id);
        if (active > 0) {
            throw new IllegalStateException("该商品还有 " + active + " 个库存单元(在库/待入库/已预留),不能删除;"
                    + "请先出库、报废或调拨这些单元,或取消相关草稿单");
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