package com.wms.service;

import com.wms.controller.NotFoundException;
import com.wms.dao.LocationDao;
import com.wms.model.Location;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class LocationService {

    private final LocationDao locationDao;

    @Autowired
    public LocationService(LocationDao locationDao) {
        this.locationDao = locationDao;
    }

    public List<Location> list(String keyword) {
        return locationDao.findAll(keyword);
    }

    public List<Location> list(String keyword, String status) {
        return locationDao.findAll(keyword, status);
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
            return l;
        } catch (DuplicateKeyException e) {
            throw new IllegalArgumentException("库位编码已存在: " + l.getCode());
        }
    }

    @Transactional
    public Location update(Location l) {
        if (l.getId() == null) throw new IllegalArgumentException("更新失败:缺少 id");
        validate(l);
        locationDao.update(l);
        return locationDao.findById(l.getId());
    }

    @Transactional
    public void delete(int id) {
        Location l = locationDao.findById(id);
        if (l == null) throw new NotFoundException("库位不存在, id=" + id);
        locationDao.delete(id);
    }

    private void validate(Location l) {
        if (l.getCode() == null || l.getCode().isBlank()) {
            throw new IllegalArgumentException("库位编码不能为空");
        }
        if (l.getCode().length() > 32) {
            throw new IllegalArgumentException("库位编码过长(最多 32)");
        }
        if (l.getName() != null && l.getName().length() > 64) {
            throw new IllegalArgumentException("库位名称过长(最多 64)");
        }
    }
}
