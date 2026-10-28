package com.wms.service;

import com.wms.controller.NotFoundException;
import com.wms.dao.WarehouseDao;
import com.wms.model.Warehouse;
import com.wms.util.Json;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

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

    /** 按 id 批量取(导出选中项用) */
    public List<Warehouse> listByIds(java.util.Collection<Integer> ids) {
        return warehouseDao.findByIds(ids);
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

    /**
     * 局部更新:只应用 patch 里**真正出现过**的字段。
     * 整对象覆盖会把「没带 enabled」当置空,于是停用的仓库被静默重启 ——
     * 所以先读原行再按 key 合并。详见 LocationService.update 的同款注释。
     */
    @Transactional
    public Warehouse update(int id, Map<String, Object> patch) {
        Warehouse cur = warehouseDao.findById(id);
        if (cur == null) throw new NotFoundException("仓库不存在, id=" + id);

        if (patch.containsKey("code"))    cur.setCode(Json.asString(patch.get("code")));
        if (patch.containsKey("name"))    cur.setName(Json.asString(patch.get("name")));
        if (patch.containsKey("remark"))  cur.setRemark(Json.asString(patch.get("remark")));
        if (patch.containsKey("enabled")) cur.setEnabled(Json.asBool(patch.get("enabled")));

        validate(cur);
        try {
            warehouseDao.update(cur);
        } catch (DuplicateKeyException e) {
            throw new IllegalArgumentException("仓库编码已存在: " + cur.getCode());
        }
        return warehouseDao.findById(id);
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
        // 锁定读:普通 COUNT 走事务快照,看不见期间刚提交的新库位,会留下孤儿库位
        int n = warehouseDao.countLocationsLocking(id);
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
