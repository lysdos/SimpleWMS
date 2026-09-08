package com.wms.controller;

import com.wms.annotation.RequireRole;
import com.wms.model.ApiResponse;
import com.wms.model.Location;
import com.wms.service.LocationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/locations")
public class LocationController {

    private final LocationService locationService;

    @Autowired
    public LocationController(LocationService locationService) {
        this.locationService = locationService;
    }

    @GetMapping
    public ApiResponse<List<Location>> list(@RequestParam(value = "keyword", required = false) String keyword) {
        return ApiResponse.ok(locationService.list(keyword));
    }

    @GetMapping("/{id}")
    public ApiResponse<Location> get(@PathVariable int id) {
        Location l = locationService.get(id);
        if (l == null) return ApiResponse.fail(404, "库位不存在");
        return ApiResponse.ok(l);
    }

    @PostMapping
    @RequireRole("ADMIN")
    public ApiResponse<Location> create(@RequestBody Location l) {
        return ApiResponse.ok(locationService.create(l));
    }

    @PutMapping("/{id}")
    @RequireRole("ADMIN")
    public ApiResponse<Location> update(@PathVariable int id, @RequestBody Location l) {
        l.setId(id);
        return ApiResponse.ok(locationService.update(l));
    }

    @DeleteMapping("/{id}")
    @RequireRole("ADMIN")
    public ApiResponse<Void> delete(@PathVariable int id) {
        locationService.delete(id);
        return ApiResponse.ok();
    }
}
