package com.wms.service;

import com.wms.controller.NotFoundException;
import com.wms.dao.LocationDao;
import com.wms.dao.WarehouseDao;
import com.wms.model.Location;
import com.wms.model.Warehouse;
import com.wms.util.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
public class LocationService {

    private static final Logger log = LoggerFactory.getLogger(LocationService.class);

    private final LocationDao locationDao;
    private final WarehouseDao warehouseDao;

    @Autowired
    public LocationService(LocationDao locationDao, WarehouseDao warehouseDao) {
        this.locationDao = locationDao;
        this.warehouseDao = warehouseDao;
    }

    public List<Location> list(String keyword) {
        return locationDao.findAll(keyword);
    }

    public List<Location> list(String keyword, String status) {
        return locationDao.findAll(keyword, status);
    }

    public List<Location> list(String keyword, String status, Integer warehouseId) {
        return locationDao.findAll(keyword, status, warehouseId);
    }

    /** 按 id 批量取(导出选中项用) */
    public List<Location> listByIds(java.util.Collection<Integer> ids) {
        return locationDao.findByIds(ids);
    }

    public Location get(int id) {
        return locationDao.findById(id);
    }

    @Transactional
    public Location create(Location l) {
        validate(l);
        try {
            int id = locationDao.insert(l);
            l.setId(id);
            return locationDao.findById(id);
        } catch (DuplicateKeyException e) {
            throw new IllegalArgumentException("库位编码已存在: " + l.getCode());
        }
    }

    /**
     * 局部更新:只应用 patch 里**真正出现过**的字段。
     *
     * 为什么不能直接整对象覆盖:JSON 里「没带这个字段」和「显式置为 null」都会被
     * 反序列化成 null,整对象覆盖等于把没带的一律当置空处理 —— 不带 enabled
     * 会把停用项悄悄改回启用,不带 warehouseId 会把库位踢出所属仓库。
     * 所以这里先读原行,再按 patch 的 key 逐字段合并。
     */
    @Transactional
    public Location update(int id, Map<String, Object> patch) {
        Location cur = locationDao.findById(id);
        if (cur == null) throw new NotFoundException("库位不存在, id=" + id);

        String oldCode = cur.getCode();
        if (patch.containsKey("code"))        cur.setCode(Json.asString(patch.get("code")));
        if (patch.containsKey("name"))        cur.setName(Json.asString(patch.get("name")));
        if (patch.containsKey("remark"))      cur.setRemark(Json.asString(patch.get("remark")));
        if (patch.containsKey("warehouseId")) cur.setWarehouseId(Json.asInt(patch.get("warehouseId")));
        if (patch.containsKey("enabled"))     cur.setEnabled(Json.asBool(patch.get("enabled")));

        validate(cur);
        try {
            locationDao.update(cur);
        } catch (DuplicateKeyException e) {
            throw new IllegalArgumentException("库位编码已存在: " + cur.getCode());
        }

        // 改名:把实时引用一起搬走。库位码是裸字符串(无外键),不搬的话
        // 所有库存单元会指向一个不存在的码 —— 从按仓筛选和按仓盘点里整体消失。
        if (!oldCode.equals(cur.getCode())) {
            int units = locationDao.reassignInventoryUnits(oldCode, cur.getCode());
            int prods = locationDao.reassignProducts(oldCode, cur.getCode());
            log.info("库位改名 {} → {}:同步 {} 个库存单元、{} 个商品默认库位",
                    oldCode, cur.getCode(), units, prods);
        }
        return locationDao.findById(id);
    }

    /**
     * 删除库位。还有货或还有商品拿它当默认库位时拒绝 ——
     * 库位码是裸字符串引用,删掉之后那些单元会变成追溯不到仓库的幽灵。
     * 口径与「仓库名下还有库位不能删」一致(409)。
     */
    @Transactional
    public void delete(int id) {
        Location l = locationDao.findById(id);
        if (l == null) throw new NotFoundException("库位不存在, id=" + id);
        if (Location.FALLBACK_CODE.equals(l.getCode())) {
            throw new IllegalStateException(
                    "「" + Location.FALLBACK_CODE + "」是商品没填库位时的系统兜底库位,不能删除");
        }
        int units = locationDao.countInventoryUnits(l.getCode());
        int prods = locationDao.countProductsUsing(l.getCode());
        if (units > 0 || prods > 0) {
            throw new IllegalStateException("该库位还有 " + units + " 个库存单元、"
                    + prods + " 个商品以它为默认库位,不能删除;请先调拨库存并改掉商品默认库位");
        }
        locationDao.delete(id);
    }

    private void validate(Location l) {
        if (l.getCode() == null || l.getCode().isBlank()) {
            throw new IllegalArgumentException("库位编码不能为空");
        }
        l.setCode(l.getCode().trim());
        if (l.getCode().length() > 32) {
            throw new IllegalArgumentException("库位编码过长(最多 32)");
        }
        // DDL 是 name NOT NULL,模型却允许为空 —— 空就退回编码,
        // 别把数据库的 NOT NULL 约束错误直接抛给调用方(编码是现成的可读标识)
        if (l.getName() == null || l.getName().isBlank()) {
            l.setName(l.getCode());
        }
        if (l.getName().length() > 64) {
            throw new IllegalArgumentException("库位名称过长(最多 64)");
        }
        if (l.getRemark() != null && l.getRemark().length() > 255) {
            throw new IllegalArgumentException("备注过长(最多 255)");
        }
        // 归属仓库可选(空 = 未分配),但给了就必须真实存在且处于启用状态,
        // 否则库位会挂到一个不存在(或已停用)的仓库上
        if (l.getWarehouseId() != null) {
            Warehouse w = warehouseDao.findById(l.getWarehouseId());
            if (w == null) {
                throw new IllegalArgumentException("所属仓库不存在, warehouseId=" + l.getWarehouseId());
            }
            // 锁住仓库行:并发的「删仓库」会等本事务提交后再数库位,
            // 于是能看见刚建的这一个而拒绝删除(location.warehouse_id 没有外键)
            warehouseDao.existsForUpdate(l.getWarehouseId());
            if (Boolean.FALSE.equals(w.getEnabled())) {
                throw new IllegalArgumentException("所属仓库已停用,不能再往里挂库位: " + w.getName());
            }
        }
    }
}
