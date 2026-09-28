package com.wms.controller;

import com.wms.dao.UserDao;
import com.wms.model.Product;
import com.wms.service.JwtService;
import com.wms.service.ProductService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 商品「数量」不归资料编辑管(2026-09-28)。
 *
 * 起因:create/update 共用一个解析方法,把缺失的 quantity 补成 0,而 DAO 的 UPDATE 又整行写
 * `quantity=?` —— 一个不带 quantity 的 PUT 就能把库存清零,而 inventory_item 一件没动,
 * 仪表盘的总量/总值/低库存全跟着错。现在数量只由出入库/报废的 adjustQuantity 维护,
 * 编辑路径完全不解析它。
 *
 * 这里只钉切片能管的部分:PUT 传进来的 Product.quantity 必须是 null(DAO 那半由
 * ProductDao.update 的 SQL 保证,靠真栈端到端验证)。新建仍可填初始数量。
 */
@WebMvcTest(ProductController.class)
@AutoConfigureMockMvc(addFilters = false)
class ProductQuantityGuardTest {

    @Autowired private MockMvc mvc;

    // WebConfig autowire UserDao、JwtAuthFilter 依赖 JwtService,少一个上下文起不来
    @MockBean private UserDao userDao;
    @MockBean private JwtService jwtService;
    @MockBean private ProductService productService;

    private static final String BASE = "{\"sku\":\"P-1\",\"name\":\"螺丝\",\"location\":\"A-01-01\"}";

    @Test
    void update_ignoresQuantityEvenWhenBodyCarriesIt() throws Exception {
        when(productService.update(any(Product.class))).thenReturn(new Product());

        mvc.perform(put("/api/products/5")
                        .requestAttr("role", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"P-1\",\"name\":\"螺丝\",\"location\":\"A-01-01\",\"quantity\":999}"))
           .andExpect(status().isOk());

        ArgumentCaptor<Product> captor = ArgumentCaptor.forClass(Product.class);
        verify(productService).update(captor.capture());
        // 编辑路径根本不读 quantity —— 传了也当作没传,DAO 那边也不再写这一列
        assertNull(captor.getValue().getQuantity());
    }

    @Test
    void update_withoutQuantity_doesNotDefaultToZero() throws Exception {
        when(productService.update(any(Product.class))).thenReturn(new Product());

        mvc.perform(put("/api/products/5")
                        .requestAttr("role", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BASE))
           .andExpect(status().isOk());

        ArgumentCaptor<Product> captor = ArgumentCaptor.forClass(Product.class);
        verify(productService).update(captor.capture());
        // 以前这里会被补成 0,正是"改个名字把库存清零"的那条路径
        assertNull(captor.getValue().getQuantity());
    }

    @Test
    void create_stillAcceptsInitialQuantity() throws Exception {
        when(productService.create(any(Product.class))).thenReturn(new Product());

        mvc.perform(post("/api/products")
                        .requestAttr("role", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"P-1\",\"name\":\"螺丝\",\"location\":\"A-01-01\",\"quantity\":7}"))
           .andExpect(status().isOk());

        ArgumentCaptor<Product> captor = ArgumentCaptor.forClass(Product.class);
        verify(productService).create(captor.capture());
        assertEquals(7, captor.getValue().getQuantity());
    }
}
