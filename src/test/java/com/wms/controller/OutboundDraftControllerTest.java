package com.wms.controller;

import com.wms.dao.UserDao;
import com.wms.model.OutboundOrder;
import com.wms.model.OutboundOrderItem;
import com.wms.service.JwtService;
import com.wms.service.OutboundService;
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
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PDA 出库登记状态机端点测试(DRAFT / COMMITTED / CANCELLED)。
 *
 * 参照 B1 InboundDraftControllerTest:
 *   @WebMvcTest + @MockBean mock OutboundService,@MockBean JwtService 跳过 JwtAuthFilter。
 */
@WebMvcTest(OutboundController.class)
@AutoConfigureMockMvc(addFilters = false)
class OutboundDraftControllerTest {

    @Autowired private MockMvc mvc;

    // WebConfig 实现 WebMvcConfigurer,会被 @WebMvcTest 一并加载,而它 autowire 了
    // UserDao(用于 UserStatusFilter)。缺这个 mock 整个 ApplicationContext 起不来。
    @MockBean private UserDao userDao;
    @MockBean private OutboundService outboundService;
    @MockBean private JwtService jwtService;

    private OutboundOrder sampleDraft() {
        OutboundOrder o = new OutboundOrder();
        o.setId(11);
        o.setOrderNo("OUT-20260907-456");
        o.setCreatedBy("pda-user");
        o.setStatus(OutboundOrder.STATUS_DRAFT);
        o.setTotalQuantity(0);
        o.setItemCount(0);
        return o;
    }

    private OutboundOrder sampleAfterScan() {
        OutboundOrder o = sampleDraft();
        o.setTotalQuantity(2);
        o.setItemCount(1);
        OutboundOrderItem it = new OutboundOrderItem();
        it.setId(1);
        it.setOrderId(11);
        it.setProductId(20);
        it.setSku("SKU-OUT-01");
        it.setProductName("出库测试商品");
        it.setQuantity(2);
        List<OutboundOrderItem> items = new ArrayList<>();
        items.add(it);
        o.setItems(items);
        return o;
    }

    private OutboundOrder sampleCommitted() {
        OutboundOrder o = sampleAfterScan();
        o.setStatus(OutboundOrder.STATUS_COMMITTED);
        return o;
    }

    /** 不传 status = 不过滤(= 全部),语义同 InboundDraftControllerTest 的同名用例 */
    @Test
    void listOrders_noStatus_meansAll() throws Exception {
        when(outboundService.listOrders(any(), any(), any(), any(), isNull(), eq(1), eq(20)))
                .thenReturn(new ArrayList<>());
        when(outboundService.countOrders(any(), any(), any(), any(), isNull()))
                .thenReturn(0L);

        mvc.perform(get("/api/outbound/orders"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.code").value(0))
           .andExpect(jsonPath("$.data.total").value(0));

        // 关键验证:没传 status 时原样透传 null,由 DAO 决定"不过滤"
        verify(outboundService).listOrders(any(), any(), any(), any(), isNull(), eq(1), eq(20));
        verify(outboundService).countOrders(any(), any(), any(), any(), isNull());
    }

    @Test
    void createDraft_ok() throws Exception {
        when(outboundService.createDraft(any(), any(), any(), anyString())).thenReturn(sampleDraft());

        String body = "{\"customer\":\"客户A\",\"externalNo\":\"EXT-OUT-1\",\"remark\":\"PDA 创建的草稿\"}";
        mvc.perform(post("/api/outbound/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.code").value(0))
           .andExpect(jsonPath("$.data.id").value(11))
           .andExpect(jsonPath("$.data.status").value("DRAFT"))
           .andExpect(jsonPath("$.data.totalQuantity").value(0));

        verify(outboundService).createDraft(eq("客户A"), eq("EXT-OUT-1"), eq("PDA 创建的草稿"), anyString());
    }

    @Test
    void scanCode_ok() throws Exception {
        when(outboundService.scanCodeToDraft(eq(11), eq("CODE0001CODE0001CODE99")))
                .thenReturn(sampleAfterScan());

        String body = "{\"code\":\"CODE0001CODE0001CODE99\"}";
        mvc.perform(post("/api/outbound/orders/11/scan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.code").value(0))
           .andExpect(jsonPath("$.data.id").value(11))
           .andExpect(jsonPath("$.data.totalQuantity").value(2))
           .andExpect(jsonPath("$.data.items[0].productId").value(20));

        verify(outboundService).scanCodeToDraft(eq(11), eq("CODE0001CODE0001CODE99"));
    }

    @Test
    void scanCode_notFound_404() throws Exception {
        // brief:不存在 → throw IllegalArgumentException("码不存在") → 404
        when(outboundService.scanCodeToDraft(eq(11), anyString()))
                .thenThrow(new IllegalArgumentException("码不存在"));

        String body = "{\"code\":\"DOES_NOT_EXIST\"}";
        mvc.perform(post("/api/outbound/orders/11/scan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
           .andExpect(status().isNotFound())
           .andExpect(jsonPath("$.code").value(404))
           .andExpect(jsonPath("$.message").value("码不存在"));
    }

    @Test
    void scanCode_alreadyOut_409() throws Exception {
        // brief:status=OUT → throw IllegalStateException → 409
        when(outboundService.scanCodeToDraft(eq(11), anyString()))
                .thenThrow(new IllegalStateException("已出库"));

        String body = "{\"code\":\"CODE0001CODE0001CODE99\"}";
        mvc.perform(post("/api/outbound/orders/11/scan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
           .andExpect(status().isConflict())
           .andExpect(jsonPath("$.code").value(409))
           .andExpect(jsonPath("$.message").value("已出库"));
    }

    @Test
    void commitDraft_ok() throws Exception {
        when(outboundService.commitDraft(11)).thenReturn(sampleCommitted());

        mvc.perform(post("/api/outbound/orders/11/commit"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.code").value(0))
           .andExpect(jsonPath("$.data.status").value("COMMITTED"))
           .andExpect(jsonPath("$.data.totalQuantity").value(2));

        verify(outboundService).commitDraft(11);
    }
}
