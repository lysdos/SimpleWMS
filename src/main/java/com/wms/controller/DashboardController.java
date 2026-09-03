package com.wms.controller;

import com.wms.model.ApiResponse;
import com.wms.model.DashboardStats;
import com.wms.service.DashboardService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
}