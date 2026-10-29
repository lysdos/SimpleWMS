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
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 差异报告 CSV:未盘行的 `差异` 列怎么填。
 *
 * 提交不再给未盘行编实盘数(见 StockCheckService.commit),但表头 `差异数` 和 `盘亏数`
 * 都把它们算了进去。如果行里留空,文件内就对不上账 —— 「盘亏数 3」找不到对应的 3 行。
 * 所以**已提交**的单子把未盘行按 `0 - 账面` 填进差异列(实盘数列仍空),草稿/已取消留空。
 */
@WebMvcTest(StockCheckController.class)
@AutoConfigureMockMvc(addFilters = false)
class StockCheckExportControllerTest {

    private static final int CHECK_ID = 33;

    @Autowired private MockMvc mvc;

    // WebConfig 实现了 WebMvcConfigurer,会被 @WebMvcTest 一并加载,缺这个 mock 起不来
    @MockBean private UserDao userDao;
    @MockBean private StockCheckService stockCheckService;
    @MockBean private JwtService jwtService;

    /** 已提交:未盘行差异 = 0 - 1 = -1,实盘数列仍空 */
    @Test
    void committed_uncountedRow_getsDiffOfMinusSystem() throws Exception {
        when(stockCheckService.get(CHECK_ID)).thenReturn(sheet(StockCheck.STATUS_COMMITTED));

        String csv = export();

        assertTrue(csv.contains("C1,SKU-1,商品1,A-01,1,1,0,一致,"), csv);
        assertTrue(csv.contains("C2,SKU-2,商品2,A-02,1,,-1,未盘,"), csv);
    }

    /** 草稿:差异数本来就不含未盘行,列里也不该凭空出现 -1 */
    @Test
    void draft_uncountedRow_leavesDiffEmpty() throws Exception {
        when(stockCheckService.get(CHECK_ID)).thenReturn(sheet(StockCheck.STATUS_DRAFT));

        String csv = export();

        assertTrue(csv.contains("C2,SKU-2,商品2,A-02,1,,,未盘,"), csv);
    }

    /** 已取消:同草稿口径(取消不重算,差异数仍不含未盘行) */
    @Test
    void cancelled_uncountedRow_leavesDiffEmpty() throws Exception {
        when(stockCheckService.get(CHECK_ID)).thenReturn(sheet(StockCheck.STATUS_CANCELLED));

        String csv = export();

        assertTrue(csv.contains("C2,SKU-2,商品2,A-02,1,,,未盘,"), csv);
    }

    /** 已提交且账面为 0 的行:0-0 不构成差异,与 diffCount 的 SQL 口径保持一致 */
    @Test
    void committed_uncountedRowWithZeroSystem_keepsDiffEmpty() throws Exception {
        StockCheck c = sheet(StockCheck.STATUS_COMMITTED);
        c.getItems().add(item("C3", "A-03", 0, null));
        when(stockCheckService.get(CHECK_ID)).thenReturn(c);

        String csv = export();

        assertTrue(csv.contains("C3,SKU-3,商品3,A-03,0,,,未盘,"), csv);
    }

    // ---- helpers ----

    private String export() throws Exception {
        byte[] body = mvc.perform(get("/api/stock-checks/" + CHECK_ID + "/export.csv"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        return new String(body, StandardCharsets.UTF_8);
    }

    private static StockCheck sheet(String status) {
        StockCheck c = new StockCheck();
        c.setId(CHECK_ID);
        c.setCheckNo("CHK-20261029-001");
        c.setScope(StockCheck.SCOPE_ALL);
        c.setStatus(status);
        c.setCreatedBy("admin");
        c.setTotalCount(2);
        c.setCountedCount(1);
        c.setDiffCount(1);
        c.setItems(new java.util.ArrayList<>(List.of(
                item("C1", "A-01", 1, 1),
                item("C2", "A-02", 1, null))));
        return c;
    }

    private static StockCheckItem item(String code, String loc, int sys, Integer counted) {
        StockCheckItem it = new StockCheckItem();
        it.setCode(code);
        it.setSku("SKU-" + code.substring(1));
        it.setProductName("商品" + code.substring(1));
        it.setLocationCode(loc);
        it.setSystemQty(sys);
        it.setCountedQty(counted);
        it.setDiff(counted == null ? null : counted - sys);
        return it;
    }
}
