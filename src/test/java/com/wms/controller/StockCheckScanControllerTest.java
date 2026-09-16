package com.wms.controller;

import com.wms.model.StockCheck;
import com.wms.model.StockCheckItem;
import com.wms.service.JwtService;
import com.wms.service.StockCheckService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P6:盘点单扫码端点测试 POST /api/stock-checks/{id}/scan。
 *
 * 参照 OutboundDraftControllerTest 写法:@WebMvcTest + @MockBean mock StockCheckService,
 * + @MockBean JwtService 跳过 JwtAuthFilter,addFilters = false。
 *
 * 覆盖 5 个核心场景:
 *   - scanCode_ok                → 命中 IN_STOCK + 已在明细 + counted_qty + 1
 *   - scanCode_notFound_404      → 码不存在
 *   - scanCode_notInStock_409    → 码已 OUT/SCRAPPED/TRANSFERRED
 *   - scanCode_notInCheck_422    → 码在系统中但不在本盘点单明细
 *   - scanCode_alreadyCommitted_409 → 盘点单已 COMMITTED
 */
@WebMvcTest(StockCheckController.class)
@AutoConfigureMockMvc(addFilters = false)
class StockCheckScanControllerTest {

    @Autowired private MockMvc mvc;

    @MockBean private StockCheckService stockCheckService;
    @MockBean private JwtService jwtService;

    private StockCheck sampleAfterScan() {
        StockCheck c = new StockCheck();
        c.setId(33);
        c.setCheckNo("CHK-20260907-789");
        c.setScope(StockCheck.SCOPE_ALL);
        c.setStatus(StockCheck.STATUS_DRAFT);
        c.setCreatedBy("pda-user");
        c.setTotalCount(2);
        c.setCountedCount(1);
        c.setDiffCount(0);
        StockCheckItem it = new StockCheckItem();
        it.setId(101);
        it.setCheckId(33);
        it.setInventoryItemId(501);
        it.setCode("CODE0001CODE0001CODE99");
        it.setSku("SKU-CHK-01");
        it.setProductName("盘点测试商品");
        it.setLocationCode("A-01-01");
        it.setSystemQty(1);
        it.setCountedQty(1);
        it.setDiff(0);
        List<StockCheckItem> items = new ArrayList<>();
        items.add(it);
        c.setItems(items);
        return c;
    }

    @Test
    void scanCode_ok() throws Exception {
        when(stockCheckService.scanCodeToCheck(eq(33), eq("CODE0001CODE0001CODE99")))
                .thenReturn(sampleAfterScan());

        String body = "{\"code\":\"CODE0001CODE0001CODE99\"}";
        mvc.perform(post("/api/stock-checks/33/scan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.code").value(0))
           .andExpect(jsonPath("$.data.id").value(33))
           .andExpect(jsonPath("$.data.countedCount").value(1))
           .andExpect(jsonPath("$.data.items[0].countedQty").value(1))
           .andExpect(jsonPath("$.data.items[0].code").value("CODE0001CODE0001CODE99"));

        verify(stockCheckService).scanCodeToCheck(eq(33), eq("CODE0001CODE0001CODE99"));
    }

    @Test
    void scanCode_notFound_404() throws Exception {
        // brief:码不存在 → IllegalArgumentException("码不存在") → GlobalExceptionHandler 映射 404
        when(stockCheckService.scanCodeToCheck(anyInt(), anyString()))
                .thenThrow(new IllegalArgumentException("码不存在"));

        String body = "{\"code\":\"DOES_NOT_EXIST\"}";
        mvc.perform(post("/api/stock-checks/33/scan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
           .andExpect(status().isNotFound())
           .andExpect(jsonPath("$.code").value(404))
           .andExpect(jsonPath("$.message").value("码不存在"));
    }

    @Test
    void scanCode_notInStock_409() throws Exception {
        // brief:inventory_item.status 非 IN_STOCK → IllegalStateException → 409
        when(stockCheckService.scanCodeToCheck(eq(33), anyString()))
                .thenThrow(new IllegalStateException("该码状态不可盘点: OUT"));

        String body = "{\"code\":\"CODE0001CODE0001CODE99\"}";
        mvc.perform(post("/api/stock-checks/33/scan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
           .andExpect(status().isConflict())
           .andExpect(jsonPath("$.code").value(409))
           .andExpect(jsonPath("$.message").value("该码状态不可盘点: OUT"));
    }

    @Test
    void scanCode_notInCheck_422() throws Exception {
        // brief:码在系统中但不在本盘点单明细 → UnprocessableException → 422
        when(stockCheckService.scanCodeToCheck(eq(33), anyString()))
                .thenThrow(new UnprocessableException("该码不在本盘点单中,需先创建覆盖该范围的盘点单"));

        String body = "{\"code\":\"CODE9999CODE9999CODE99\"}";
        mvc.perform(post("/api/stock-checks/33/scan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
           .andExpect(status().isUnprocessableEntity())
           .andExpect(jsonPath("$.code").value(422))
           .andExpect(jsonPath("$.message").value("该码不在本盘点单中,需先创建覆盖该范围的盘点单"));
    }

    @Test
    void scanCode_alreadyCommitted_409() throws Exception {
        // brief:盘点单已 COMMITTED,不能再扫 → IllegalStateException → 409
        when(stockCheckService.scanCodeToCheck(eq(33), anyString()))
                .thenThrow(new IllegalStateException("仅 DRAFT 状态可扫码,当前: COMMITTED"));

        String body = "{\"code\":\"CODE0001CODE0001CODE99\"}";
        mvc.perform(post("/api/stock-checks/33/scan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
           .andExpect(status().isConflict())
           .andExpect(jsonPath("$.code").value(409))
           .andExpect(jsonPath("$.message").value("仅 DRAFT 状态可扫码,当前: COMMITTED"));
    }
}