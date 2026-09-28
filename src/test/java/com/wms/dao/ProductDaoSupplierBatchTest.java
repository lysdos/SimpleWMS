package com.wms.dao;

import com.wms.model.Product;
import com.wms.model.ProductSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 商品列表的供应商回填必须是**一次批量查询**,不是逐行查询(2026-09-28)。
 *
 * 原来是 `for (Product p : list) attachSuppliers(p)`,每行一次 findSuppliersByProduct ——
 * 列表页 200 行就是 201 次查询。商品列表在「新增单据」时也会被拉取,这条路径不罕见。
 *
 * 这里只钉查询次数(DAO 是 mock,不连库):分组回填的正确性靠真栈端到端验证。
 */
@ExtendWith(MockitoExtension.class)
class ProductDaoSupplierBatchTest {

    @Mock private JdbcTemplate jdbc;
    @Mock private SupplierDao supplierDao;

    private ProductDao dao;

    @BeforeEach
    void setUp() {
        dao = new ProductDao(jdbc, supplierDao);
    }

    private Product product(int id) {
        Product p = new Product();
        p.setId(id);
        p.setSku("SKU-" + id);
        p.setName("商品" + id);
        return p;
    }

    private ProductSupplier ref(int productId, int supplierId, String name, boolean isDefault) {
        ProductSupplier ps = new ProductSupplier();
        ps.setProductId(productId);
        ps.setSupplierId(supplierId);
        ps.setSupplierName(name);
        ps.setIsDefault(isDefault);
        return ps;
    }

    @Test
    @SuppressWarnings("unchecked")
    void findAll_usesSingleBatchQuery_andFillsEachProduct() {
        when(jdbc.query(anyString(), ArgumentMatchers.<RowMapper<Product>>any(), any(Object[].class)))
                .thenReturn(List.of(product(1), product(2)));
        when(supplierDao.findSuppliersByProducts(anyList()))
                .thenReturn(List.of(ref(1, 9, "戴尔", true), ref(2, 8, "联想", true)));

        List<Product> out = dao.findAll(null, null);

        // 一次批量查询,且绝不逐行查
        verify(supplierDao, times(1)).findSuppliersByProducts(anyList());
        verify(supplierDao, never()).findSuppliersByProduct(anyInt());

        // 关联确实按 productId 分到了各自那一行
        assertEquals("戴尔", out.get(0).getDefaultSupplier());
        assertEquals(9, out.get(0).getDefaultSupplierId());
        assertEquals("联想", out.get(1).getDefaultSupplier());
        assertEquals(8, out.get(1).getDefaultSupplierId());
    }
}
