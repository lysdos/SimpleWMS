package com.wms.controller;

import com.wms.model.ApiResponse;
import com.wms.model.DashboardStats;
import com.wms.service.DashboardService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final DashboardService dashboardService;

    @Autowired
    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @GetMapping("/stats")
    public ApiResponse<DashboardStats> stats() {
        return ApiResponse.ok(dashboardService.stats());
    }

    /** 库存趋势(累计在库单元数 / 天) */
    @GetMapping("/inventory-trend")
    public ApiResponse<List<Map<String, Object>>> inventoryTrend(
            @RequestParam(defaultValue = "30") int days) {
        return ApiResponse.ok(dashboardService.inventoryTrend(days));
    }

    /** 出库趋势(每天出库件数) */
    @GetMapping("/outbound-trend")
    public ApiResponse<List<Map<String, Object>>> outboundTrend(
            @RequestParam(defaultValue = "30") int days) {
        return ApiResponse.ok(dashboardService.outboundTrend(days));
    }
}