package com.wms.controller;

import com.wms.dao.UserDao;
import com.wms.model.Warehouse;
import com.wms.service.JwtService;
import com.wms.service.WarehouseService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(WarehouseController.class)
@AutoConfigureMockMvc(addFilters = false)
class WarehouseControllerTest {

    @Autowired private MockMvc mvc;

    // WebConfig 实现 WebMvcConfigurer,会被 @WebMvcTest 一并加载并 autowire UserDao;
    // JwtAuthFilter 也是 @Component,依赖 JwtService。缺这两个 mock 上下文起不来。
    @MockBean private UserDao userDao;
    @MockBean private JwtService jwtService;
    @MockBean private WarehouseService service;
    // WarehouseController 现在多了 CSV 导入的依赖,@WebMvcTest 只加载 controller 层
    @MockBean private com.wms.service.WarehouseImportService warehouseImportService;

    private Warehouse sample() {
        Warehouse w = new Warehouse();
        w.setId(1);
        w.setCode("WH1");
        w.setName("上海一号仓");
        w.setEnabled(true);
        w.setLocationCount(3);
        return w;
    }

    @Test
    void list_ok() throws Exception {
        when(service.list(null, null)).thenReturn(List.of(sample()));

        mvc.perform(get("/api/warehouses"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.code").value(0))
           .andExpect(jsonPath("$.data[0].code").value("WH1"))
           .andExpect(jsonPath("$.data[0].locationCount").value(3));
    }

    /** 缺仓库必须是真 404(不能是 200 + body code=404,否则客户端当成功吞掉) */
    @Test
    void get_miss_isReal404() throws Exception {
        when(service.get(99)).thenReturn(null);

        mvc.perform(get("/api/warehouses/99"))
           .andExpect(status().isNotFound())
           .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    void create_blankCode_is400() throws Exception {
        when(service.create(any())).thenThrow(new IllegalArgumentException("仓库编码不能为空"));

        mvc.perform(post("/api/warehouses")
                        .requestAttr("role", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"\",\"name\":\"x\"}"))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.code").value(400));
    }

    /** 名下还有库位 → 409(删除保护) */
    @Test
    void delete_withLocations_is409() throws Exception {
        doThrow(new IllegalStateException("该仓库下还有 3 个库位,不能删除;请先把库位转移到其他仓库,或一并删除"))
                .when(service).delete(1);

        mvc.perform(delete("/api/warehouses/1").requestAttr("role", "ADMIN"))
           .andExpect(status().isConflict())
           .andExpect(jsonPath("$.code").value(409));
    }

    @Test
    void delete_ok() throws Exception {
        mvc.perform(delete("/api/warehouses/2").requestAttr("role", "ADMIN"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.code").value(0));
    }

    /**
     * 写操作带 @RequireRole("ADMIN"),由 RoleInterceptor 读 request attribute "role" 判定:
     * 无 role → 401;role 不是 ADMIN → 403。addFilters=false 只关掉了 JwtAuthFilter,
     * 拦不住这个 interceptor,所以测试里必须显式给 role。
     */
    @Test
    void delete_withoutRole_is401() throws Exception {
        mvc.perform(delete("/api/warehouses/3"))
           .andExpect(status().isUnauthorized())
           .andExpect(jsonPath("$.code").value(401));
    }
}
