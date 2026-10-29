package com.wms.service;

import com.wms.controller.NotFoundException;
import com.wms.dao.SupplierDao;
import com.wms.model.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class SupplierService {

    private final SupplierDao supplierDao;

    @Autowired
    public SupplierService(SupplierDao supplierDao) {
        this.supplierDao = supplierDao;
    }

    public List<Supplier> list(String keyword) {
        return supplierDao.findAll(keyword);
    }

    public List<Supplier> list(String keyword, String status) {
        return supplierDao.findAll(keyword, status);
    }

    public Supplier get(int id) {
        return supplierDao.findById(id);
    }

    /** 按 id 批量取(导出选中项用) */
    public List<Supplier> listByIds(java.util.Collection<Integer> ids) {
        return supplierDao.findByIds(ids);
    }

    /**
     * 分页列表(Web 管理页用)。与全量的 {@link #list} 并存 ——
     * 全量版本仍被商品表单的供应商多选与 PDA 使用。
     */
    public com.wms.model.PageResult<Supplier> listPage(String keyword, String status,
                                                       int page, int pageSize) {
        int ps = pageSize <= 0 || pageSize > 200 ? 20 : pageSize;
        long total = supplierDao.count(keyword, status);
        int p = com.wms.model.PageResult.clampPage(page, ps, total);
        List<Supplier> list = supplierDao.findPage(keyword, status, (p - 1) * ps, ps);
        return new com.wms.model.PageResult<>(list, total, p, ps);
    }

    @Transactional
    public Supplier create(Supplier s) {
        validate(s);
        try {
            int id = supplierDao.insert(s);
            s.setId(id);
            return s;
        } catch (DuplicateKeyException e) {
            throw new IllegalArgumentException("供应商名称已存在: " + s.getName());
        }
    }

    @Transactional
    public Supplier update(Supplier s) {
        if (s.getId() == null) throw new IllegalArgumentException("更新失败:缺少 id");
        validate(s);
        try {
            supplierDao.update(s);
        } catch (DuplicateKeyException e) {
            // 改名撞上已有名称 —— 与新建同一个口径。不处理的话冒到 catch-all 变成
            // 500「服务器异常」,用户不知道是重名。
            throw new IllegalArgumentException("供应商名称已存在: " + s.getName());
        }
        return supplierDao.findById(s.getId());
    }

    @Transactional
    public void delete(int id) {
        Supplier s = supplierDao.findById(id);
        if (s == null) throw new NotFoundException("供应商不存在, id=" + id);
        supplierDao.delete(id);
    }

    private void validate(Supplier s) {
        if (s.getName() == null || s.getName().isBlank()) {
            throw new IllegalArgumentException("供应商名称不能为空");
        }
        if (s.getName().length() > 128) {
            throw new IllegalArgumentException("供应商名称过长(最多 128)");
        }
    }
}
