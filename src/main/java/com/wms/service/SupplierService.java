package com.wms.service;

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

    public Supplier get(int id) {
        return supplierDao.findById(id);
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
        supplierDao.update(s);
        return supplierDao.findById(s.getId());
    }

    @Transactional
    public void delete(int id) {
        Supplier s = supplierDao.findById(id);
        if (s == null) throw new IllegalArgumentException("供应商不存在, id=" + id);
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
