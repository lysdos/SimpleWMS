package com.wms.controller;

import com.wms.model.ApiResponse;
import com.wms.model.InventoryLog;
import com.wms.service.LedgerService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/ledger")
public class LedgerController {

    private final LedgerService ledgerService;

    @Autowired
    public LedgerController(LedgerService ledgerService) {
        this.ledgerService = ledgerService;
    }

    @GetMapping
    public ApiResponse<List<InventoryLog>> list(
            @RequestParam(value = "type", required = false) String type,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "limit", required = false) Integer limit) {
        return ApiResponse.ok(ledgerService.list(type, keyword, limit));
    }
}