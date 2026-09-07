package com.wms.controller;

import com.wms.model.InventoryItem;
import com.wms.service.InventoryItemService;
import com.wms.service.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(InventoryItemController.class)
@AutoConfigureMockMvc(addFilters = false)
class InventoryItemControllerQueryTest {

    @Autowired private MockMvc mvc;

    @MockBean private InventoryItemService service;
    // JwtAuthFilter 是 @Component 且实现 Filter,@WebMvcTest 仍会加载该 bean,
    // 必须 mock 它依赖的 JwtService 才能完成上下文初始化。
    // 运行时通过 addFilters = false 让 JwtAuthFilter 不参与请求链。
    @MockBean private JwtService jwtService;

    private InventoryItem sampleItem(String code) {
        InventoryItem i = new InventoryItem();
        i.setId(1);
        i.setCode(code);
        i.setProductId(10);
        i.setSku("SP-001");
        i.setProductName("测试商品");
        i.setLocationCode("A-01");
        i.setQuantity(1);
        i.setRemaining(1);
        i.setStatus(InventoryItem.STATUS_IN_STOCK);
        return i;
    }

    @Test
    void byCode_hit() throws Exception {
        when(service.findByCode("ABC123")).thenReturn(sampleItem("ABC123"));

        mvc.perform(get("/api/inventory/items/by-code/ABC123"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.code").value(0))
           .andExpect(jsonPath("$.data.code").value("ABC123"));
    }

    @Test
    void byCode_miss() throws Exception {
        when(service.findByCode("NOPE")).thenReturn(null);

        mvc.perform(get("/api/inventory/items/by-code/NOPE"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    void byEpc_hit() throws Exception {
        when(service.findByCode("EPC999")).thenReturn(sampleItem("EPC999"));

        mvc.perform(get("/api/inventory/items/by-epc/EPC999"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.code").value(0))
           .andExpect(jsonPath("$.data.code").value("EPC999"));
    }
}