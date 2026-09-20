package com.wms.controller;

import com.wms.dao.UserDao;
import com.wms.service.BarcodeService;
import com.wms.service.InventoryItemService;
import com.wms.service.JwtService;
import com.wms.service.ProductService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 库存单元两个**写操作**(调拨 / 报废)端点的接线测试。
 *
 * 这些操作有一半是 SQL 行为(调拨真的改了 location_code 且保持 IN_STOCK、报废真的扣了
 * product.quantity 并写了流水),那一半这个切片测试**碰不到** —— 这里 service 是 mock,
 * 全程不连数据库,只能起真栈端到端验证(见计划的验证章节)。
 *
 * 所以这里只钉住切片能管的部分:
 *  - 操作人确实从请求属性传到了 service(这两处以前压根没传,流水里没有"谁干的")
 *  - ADMIN 角色校验还在
 *  - service 抛的异常映射成正确的 HTTP 状态码
 */
@WebMvcTest(InventoryItemController.class)
@AutoConfigureMockMvc(addFilters = false)
class InventoryItemWriteOpsTest {

    @Autowired private MockMvc mvc;

    // WebConfig 会 autowire UserDao;JwtAuthFilter 是 @Component 依赖 JwtService;
    // 控制器本身还依赖 ProductService / BarcodeService。少一个上下文都起不来。
    @MockBean private UserDao userDao;
    @MockBean private JwtService jwtService;
    @MockBean private InventoryItemService service;
    @MockBean private ProductService productService;
    @MockBean private BarcodeService barcodeService;

    private String body(String toLocation) {
        return "{\"toLocation\":\"" + toLocation + "\"}";
    }

    // ==================== 调拨 ====================

    /** 当前用户必须被传到 service(写进 TRANSFER 流水的 createdBy) */
    @Test
    void transfer_passesOperatorFromRequest() throws Exception {
        mvc.perform(post("/api/inventory/items/7/transfer")
                        .requestAttr("role", "ADMIN")
                        .requestAttr("username", "admin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("A-02-01")))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.code").value(0));

        verify(service).transfer(7, "A-02-01", "admin");
    }

    /** 请求里没有 username 时兜底成「操作员」,不能传 null 进去 */
    @Test
    void transfer_withoutUsername_fallsBackToDefault() throws Exception {
        mvc.perform(post("/api/inventory/items/7/transfer")
                        .requestAttr("role", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("A-02-01")))
           .andExpect(status().isOk());

        verify(service).transfer(7, "A-02-01", "操作员");
    }

    /** 体里没带目标库位 → service 收到 null,由它报 400(空值校验在 service 层) */
    @Test
    void transfer_missingToLocation_callsServiceWithNull() throws Exception {
        doThrow(new IllegalArgumentException("目标库位不能为空"))
                .when(service).transfer(eq(7), eq(null), anyString());

        mvc.perform(post("/api/inventory/items/7/transfer")
                        .requestAttr("role", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.code").value(400));
    }

    /** 目标库位不存在 → 400(以前只校验非空,能调出幽灵库位码) */
    @Test
    void transfer_unknownLocation_is400() throws Exception {
        doThrow(new IllegalArgumentException("库位不存在: NOPE"))
                .when(service).transfer(eq(7), eq("NOPE"), anyString());

        mvc.perform(post("/api/inventory/items/7/transfer")
                        .requestAttr("role", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("NOPE")))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.code").value(400));
    }

    /** 非 IN_STOCK 单元(已出库/已报废)→ 409 */
    @Test
    void transfer_notInStock_is409() throws Exception {
        doThrow(new IllegalStateException("仅在库(IN_STOCK)单元可调拨,当前状态: OUT"))
                .when(service).transfer(eq(7), eq("A-02-01"), anyString());

        mvc.perform(post("/api/inventory/items/7/transfer")
                        .requestAttr("role", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("A-02-01")))
           .andExpect(status().isConflict())
           .andExpect(jsonPath("$.code").value(409));
    }

    /** 单元不存在 → 404 */
    @Test
    void transfer_missingItem_is404() throws Exception {
        doThrow(new NotFoundException("库存单元不存在, id=7"))
                .when(service).transfer(eq(7), eq("A-02-01"), anyString());

        mvc.perform(post("/api/inventory/items/7/transfer")
                        .requestAttr("role", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("A-02-01")))
           .andExpect(status().isNotFound())
           .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    void transfer_withoutRole_is401_andNeverReachesService() throws Exception {
        mvc.perform(post("/api/inventory/items/7/transfer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("A-02-01")))
           .andExpect(status().isUnauthorized())
           .andExpect(jsonPath("$.code").value(401));

        verify(service, never()).transfer(any(Integer.class), any(), any());
    }

    // ==================== 报废 ====================

    /**
     * 报废是减库存的动作,流水里必须留"谁干的"。
     * 以前 controller 连操作人都不传,service 也不写 —— SCRAP 流水的 createdBy 一直是空。
     */
    @Test
    void scrap_passesOperatorFromRequest() throws Exception {
        mvc.perform(post("/api/inventory/items/9/scrap")
                        .requestAttr("role", "ADMIN")
                        .requestAttr("username", "admin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"remark\":\"破损\"}"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.code").value(0));

        verify(service).scrap(9, "破损", "admin");
    }

    @Test
    void scrap_withoutUsername_fallsBackToDefault() throws Exception {
        mvc.perform(post("/api/inventory/items/9/scrap")
                        .requestAttr("role", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
           .andExpect(status().isOk());

        verify(service).scrap(9, null, "操作员");
    }

    @Test
    void scrap_notInStock_is409() throws Exception {
        doThrow(new IllegalStateException("仅在库(IN_STOCK)单元可报废,当前状态: OUT"))
                .when(service).scrap(eq(9), any(), anyString());

        mvc.perform(post("/api/inventory/items/9/scrap")
                        .requestAttr("role", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
           .andExpect(status().isConflict())
           .andExpect(jsonPath("$.code").value(409));
    }

    @Test
    void scrap_missingItem_is404() throws Exception {
        doThrow(new NotFoundException("库存单元不存在, id=9"))
                .when(service).scrap(eq(9), any(), anyString());

        mvc.perform(post("/api/inventory/items/9/scrap")
                        .requestAttr("role", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
           .andExpect(status().isNotFound())
           .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    void scrap_withoutRole_is401_andNeverReachesService() throws Exception {
        mvc.perform(post("/api/inventory/items/9/scrap")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
           .andExpect(status().isUnauthorized())
           .andExpect(jsonPath("$.code").value(401));

        verify(service, never()).scrap(any(Integer.class), any(), any());
    }
}
