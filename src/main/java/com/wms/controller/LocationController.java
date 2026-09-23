package com.wms.controller;

import com.wms.annotation.RequireRole;
import com.wms.model.ApiResponse;
import com.wms.model.Location;
import com.wms.service.LocationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/locations")
public class LocationController {

    private final LocationService locationService;

    @Autowired
    public LocationController(LocationService locationService) {
        this.locationService = locationService;
    }

    @GetMapping
    public ApiResponse<List<Location>> list(
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "warehouseId", required = false) Integer warehouseId) {
        return ApiResponse.ok(locationService.list(keyword, status, warehouseId));
    }

    @GetMapping("/{id}")
    public ApiResponse<Location> get(@PathVariable int id) {
        Location l = locationService.get(id);
        if (l == null) throw new NotFoundException("库位不存在");
        return ApiResponse.ok(l);
    }

    @PostMapping
    @RequireRole("ADMIN")
    public ApiResponse<Location> create(@RequestBody Location l) {
        return ApiResponse.ok(locationService.create(l));
    }

    /**
     * 局部更新。收 Map 而不是 Location:需要区分「字段没带」和「字段显式为 null」,
     * 前者要保留原值(否则不带 enabled 会把停用项改回启用、不带 warehouseId 会把库位踢出仓库)。
     */
    @PutMapping("/{id}")
    @RequireRole("ADMIN")
    public ApiResponse<Location> update(@PathVariable int id, @RequestBody Map<String, Object> body) {
        return ApiResponse.ok(locationService.update(id, body));
    }

    @DeleteMapping("/{id}")
    @RequireRole("ADMIN")
    public ApiResponse<Void> delete(@PathVariable int id) {
        locationService.delete(id);
        return ApiResponse.ok();
    }
}
