package com.wms.controller;

import com.wms.dao.UserDao;
import com.wms.model.InventoryItem;
import com.wms.service.BarcodeService;
import com.wms.service.InventoryItemService;
import com.wms.service.JwtService;
import com.wms.service.ProductService;
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

    // WebConfig 实现 WebMvcConfigurer,会被 @WebMvcTest 一并加载,而它 autowire 了
    // UserDao(用于 UserStatusFilter)。缺这个 mock 整个 ApplicationContext 起不来。
    @MockBean private UserDao userDao;
    @MockBean private InventoryItemService service;
    // InventoryItemController 新增 /by-code/{code}/product 后依赖 ProductService,
    // 这个 @WebMvcTest 切片必须补上,否则 ApplicationContext 起不来(整类报错)
    @MockBean private ProductService productService;
    @MockBean private BarcodeService barcodeService;
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

    /**
     * miss 必须是**真 404**:早先 controller 用 return ApiResponse.fail(404,..) 表达,
     * Spring 对 @RestController 返回值一律用 HTTP 200,于是状态码是 200 只有 body 里 code=404,
     * 只看状态码的客户端(web res.ok / PDA isSuccessful)会当成功、拿到 null 静默吞掉。
     */
    @Test
    void byCode_miss() throws Exception {
        when(service.findByCode("NOPE")).thenReturn(null);

        mvc.perform(get("/api/inventory/items/by-code/NOPE"))
           .andExpect(status().isNotFound())
           .andExpect(jsonPath("$.code").value(404))
           .andExpect(jsonPath("$.message").value("编码不存在: NOPE"));
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