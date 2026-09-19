package com.wms.service;

import com.wms.controller.NotFoundException;
import com.wms.dao.WarehouseDao;
import com.wms.model.Warehouse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class WarehouseService {

    private final WarehouseDao warehouseDao;

    @Autowired
    public WarehouseService(WarehouseDao warehouseDao) {
        this.warehouseDao = warehouseDao;
    }

    public List<Warehouse> list(String keyword, String status) {
        return warehouseDao.findAll(keyword, status);
    }

    public Warehouse get(int id) {
        return warehouseDao.findById(id);
    }

    @Transactional
    public Warehouse create(Warehouse w) {
        validate(w);
        try {
            int id = warehouseDao.insert(w);
            w.setId(id);
            return warehouseDao.findById(id);
        } catch (DuplicateKeyException e) {
            throw new IllegalArgumentException("仓库编码已存在: " + w.getCode());
        }
    }

    @Transactional
    public Warehouse update(Warehouse w) {
        if (w.getId() == null) throw new IllegalArgumentException("更新失败:缺少 id");
        if (warehouseDao.findById(w.getId()) == null) {
            throw new NotFoundException("仓库不存在, id=" + w.getId());
        }
        validate(w);
        try {
            warehouseDao.update(w);
        } catch (DuplicateKeyException e) {
            throw new IllegalArgumentException("仓库编码已存在: " + w.getCode());
        }
        return warehouseDao.findById(w.getId());
    }

    /**
     * 删除仓库。名下还有库位时拒绝 —— 那些库位会变成"未分配",
     * 库存单元、单据明细里的库位码仍在,但再也追溯不到属于哪个仓库。
     * 口径与"商品还有未终结库存单元时禁止删除"一致(409)。
     */
    @Transactional
    public void delete(int id) {
        Warehouse w = warehouseDao.findById(id);
        if (w == null) throw new NotFoundException("仓库不存在, id=" + id);
        int n = warehouseDao.countLocations(id);
        if (n > 0) {
            throw new IllegalStateException(
                    "该仓库下还有 " + n + " 个库位,不能删除;请先把库位转移到其他仓库,或一并删除");
        }
        warehouseDao.delete(id);
    }

    private void validate(Warehouse w) {
        if (w.getCode() == null || w.getCode().isBlank()) {
            throw new IllegalArgumentException("仓库编码不能为空");
        }
        if (w.getCode().length() > 32) {
            throw new IllegalArgumentException("仓库编码过长(最多 32)");
        }
        if (w.getName() == null || w.getName().isBlank()) {
            throw new IllegalArgumentException("仓库名称不能为空");
        }
        if (w.getName().length() > 64) {
            throw new IllegalArgumentException("仓库名称过长(最多 64)");
        }
        if (w.getRemark() != null && w.getRemark().length() > 255) {
            throw new IllegalArgumentException("备注过长(最多 255)");
        }
    }
}
