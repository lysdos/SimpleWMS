package com.wms.controller;

import com.wms.dao.UserDao;
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

import static org.mockito.ArgumentMatchers.any;
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
 * 覆盖 7 个核心场景:
 *   - scanCode_ok                → 命中 IN_STOCK + 已在明细 + counted_qty + 1
 *   - scanCode_notFound_404      → 码不存在
 *   - scanCode_notInStock_409    → 码已 OUT/SCRAPPED/TRANSFERRED
 *   - scanCode_notInCheck_422    → 码在系统中但不在本盘点单明细
 *   - scanCode_alreadyCommitted_409 → 盘点单已 COMMITTED
 *   - create_withOperatorRole_isAllowed / commit_withOperatorRole_isAllowed
 *     → 盘点整条线不限角色(2026-09-24 决策):不动库存,作业员自己发起、盘完、提交
 */
@WebMvcTest(StockCheckController.class)
@AutoConfigureMockMvc(addFilters = false)
class StockCheckScanControllerTest {

    @Autowired private MockMvc mvc;

    // WebConfig 实现 WebMvcConfigurer,会被 @WebMvcTest 一并加载,而它 autowire 了
    // UserDao(用于 UserStatusFilter)。缺这个 mock 整个 ApplicationContext 起不来。
    @MockBean private UserDao userDao;
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
        // brief:码不存在 → NotFoundException("码不存在") → GlobalExceptionHandler 映射 404
        // (此前靠消息前缀分流,那段已删除,异常类型必须跟着换)
        when(stockCheckService.scanCodeToCheck(anyInt(), anyString()))
                .thenThrow(new NotFoundException("码不存在"));

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

    // ==================== 角色门槛:整条线不限(2026-09-24 起的决策) ====================

    /**
     * 盘点**故意不设角色门槛**:它不动库存(提交只固化差异记录),作业员自己发起、盘完、提交即可。
     * 带 OPERATOR 角色必须能建单 —— 这条是那个决策的钉子,不是遗漏。
     * 与 InventoryItemWriteOpsTest 里"报废仍限 ADMIN"那两条合起来看,才是完整的权限口径。
     */
    @Test
    void create_withOperatorRole_isAllowed() throws Exception {
        when(stockCheckService.create(anyString(), any(), any(), any(), anyString()))
                .thenReturn(sampleAfterScan());

        mvc.perform(post("/api/stock-checks")
                        .requestAttr("role", "OPERATOR")
                        .requestAttr("username", "cs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scope\":\"ALL\"}"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.data.id").value(33));

        verify(stockCheckService).create(eq("ALL"), any(), any(), any(), eq("cs"));
    }

    /** 同理:操作员必须能提交(否则现场盘完卡在最后一步) */
    @Test
    void commit_withOperatorRole_isAllowed() throws Exception {
        when(stockCheckService.commit(33)).thenReturn(sampleAfterScan());

        mvc.perform(post("/api/stock-checks/33/commit")
                        .requestAttr("role", "OPERATOR")
                        .requestAttr("username", "cs"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.code").value(0));

        verify(stockCheckService).commit(33);
    }
}
