package com.wms.controller;

import com.wms.model.ApiResponse;
import com.wms.model.InboundOrder;
import com.wms.model.InboundOrderItem;
import com.wms.service.InboundService;
import com.wms.service.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PDA 入库登记状态机端点测试(DRAFT / COMMITTED / CANCELLED)。
 *
 * 用 @WebMvcTest + @MockBean mock InboundService,模仿现有 InventoryItemControllerQueryTest 的写法。
 * 通过 @AutoConfigureMockMvc(addFilters = false) 跳过 JwtAuthFilter。
 */
@WebMvcTest(InboundController.class)
@AutoConfigureMockMvc(addFilters = false)
class InboundDraftControllerTest {

    @Autowired private MockMvc mvc;

    @MockBean private InboundService inboundService;
    @MockBean private JwtService jwtService;

    private InboundOrder sampleDraft() {
        InboundOrder o = new InboundOrder();
        o.setId(7);
        o.setOrderNo("IN-20260907-123");
        o.setCreatedBy("pda-user");
        o.setStatus(InboundOrder.STATUS_DRAFT);
        o.setTotalQuantity(0);
        o.setItemCount(0);
        return o;
    }

    private InboundOrder sampleCommitted() {
        InboundOrder o = new InboundOrder();
        o.setId(7);
        o.setOrderNo("IN-20260907-123");
        o.setCreatedBy("pda-user");
        o.setStatus(InboundOrder.STATUS_COMMITTED);
        o.setTotalQuantity(3);
        o.setItemCount(1);
        InboundOrderItem it = new InboundOrderItem();
        it.setId(1);
        it.setOrderId(7);
        it.setProductId(10);
        it.setSku("SP-001");
        it.setProductName("测试商品");
        it.setQuantity(3);
        it.setCodes(Arrays.asList("CODE0001CODE0001CODE01", "CODE0001CODE0001CODE02", "CODE0001CODE0001CODE03"));
        List<InboundOrderItem> items = new ArrayList<>();
        items.add(it);
        o.setItems(items);
        return o;
    }

    @Test
    void listOrders_defaultStatusDraft() throws Exception {
        // mock service 返回一个空列表 + total=0
        when(inboundService.listOrders(any(), any(), any(), any(), eq("DRAFT"), eq(1), eq(20)))
                .thenReturn(new ArrayList<>());
        when(inboundService.countOrders(any(), any(), any(), any(), eq("DRAFT")))
                .thenReturn(0L);

        mvc.perform(get("/api/inbound/orders"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.code").value(0))
           .andExpect(jsonPath("$.data.total").value(0));

        // 关键验证:即使前端没传 status,service 也收到 "DRAFT"
        verify(inboundService).listOrders(any(), any(), any(), any(), eq("DRAFT"), eq(1), eq(20));
        verify(inboundService).countOrders(any(), any(), any(), any(), eq("DRAFT"));
    }

    @Test
    void createDraft_ok() throws Exception {
        when(inboundService.createDraft(any(), any(), any())).thenReturn(sampleDraft());

        String body = "{\"externalNo\":\"EXT-1\",\"remark\":\"PDA 创建的草稿\"}";
        mvc.perform(post("/api/inbound/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.code").value(0))
           .andExpect(jsonPath("$.data.id").value(7))
           .andExpect(jsonPath("$.data.status").value("DRAFT"))
           .andExpect(jsonPath("$.data.totalQuantity").value(0));

        verify(inboundService).createDraft(eq("EXT-1"), eq("PDA 创建的草稿"), anyString());
    }

    @Test
    void addItem_ok() throws Exception {
        InboundOrder committed = sampleCommitted();
        when(inboundService.addItemToDraft(eq(7), any(InboundService.BatchItem.class)))
                .thenReturn(committed);

        String body = "{\"productId\":10,\"quantity\":3,\"supplier\":\"供应商A\",\"rowRemark\":\"备注1\"}";
        mvc.perform(post("/api/inbound/orders/7/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.code").value(0))
           .andExpect(jsonPath("$.data.id").value(7))
           .andExpect(jsonPath("$.data.items[0].codes.length()").value(3));

        verify(inboundService).addItemToDraft(eq(7), any(InboundService.BatchItem.class));
    }

    @Test
    void addItem_notDraft_409() throws Exception {
        // mock service 抛 IllegalStateException(非 DRAFT),GlobalExceptionHandler 映射 409
        when(inboundService.addItemToDraft(eq(7), any(InboundService.BatchItem.class)))
                .thenThrow(new IllegalStateException("只有 DRAFT 单可以追加明细, 当前 status=COMMITTED"));

        String body = "{\"productId\":10,\"quantity\":1}";
        mvc.perform(post("/api/inbound/orders/7/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
           .andExpect(status().isConflict())
           .andExpect(jsonPath("$.code").value(409));
    }

    @Test
    void commitDraft_ok() throws Exception {
        InboundOrder committed = sampleCommitted();
        when(inboundService.commitDraft(7)).thenReturn(committed);

        mvc.perform(post("/api/inbound/orders/7/commit"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.code").value(0))
           .andExpect(jsonPath("$.data.status").value("COMMITTED"))
           .andExpect(jsonPath("$.data.totalQuantity").value(3))
           .andExpect(jsonPath("$.data.items[0].codes[0]").value("CODE0001CODE0001CODE01"));

        verify(inboundService).commitDraft(7);
    }

    @Test
    void cancelDraft_ok() throws Exception {
        // cancelDraft 是 void,不需要 mock 返回
        mvc.perform(post("/api/inbound/orders/7/cancel"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.code").value(0));

        verify(inboundService).cancelDraft(7);
    }
}
