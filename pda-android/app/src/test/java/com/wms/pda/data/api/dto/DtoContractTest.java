package com.wms.pda.data.api.dto;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.wms.pda.model.ApiResponse;

import org.junit.Test;

import java.lang.reflect.Type;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/**
 * 后端 JSON ↔ PDA DTO 的**字段契约**测试。
 *
 * 为什么需要它:DTO 的字段名就是 Gson 的 JSON key。字段名和后端对不上**不会编译报错**,
 * Gson 只会静默给 null,一路飘到 UI 变成空白/空字符串,只有到现场才发现。
 * 这类 bug 真出过(PDA 曾把后端的 `message` 当成 `msg`,错误提示全是 null)。
 *
 * 注意它**不**覆盖编译器已经管的部分:改 Java 字段名会连带编译失败(ApiClient 在用),
 * 那层不用测。它管的是编译器看不到的**线路名**问题 —— 后端换了 JSON key、
 * 或有人给字段加了个错误的 @SerializedName(编译照样通过,接线却断了)。
 *
 * fixture 取自**真实后端响应**(每个用例注了来源端点)。为可读性删掉了与断言无关的字段、
 * 或把长数组截短 —— 但**字段名一个没改**,这才是被断言的东西。
 * 个别没有抓包条件的(scan-epc)按后端模型的字段名写,已在用例上单独标注。
 */
public class DtoContractTest {

    private final Gson gson = new Gson();

    private <T> T parse(String json, Type type) {
        return gson.fromJson(json, type);
    }

    private static <T> Type resp(Type data) {
        return TypeToken.getParameterized(ApiResponse.class, data).getType();
    }

    // ==================== 响应壳 ====================

    /**
     * 字段必须叫 `message`(后端就是发 `message`)。
     *
     * 反向那条断言是关键:喂 `{"msg":...}` 进来 message 必须为 null ——
     * 它挡住的是"把线路名改回去"这类改动(光把 Java 字段改回 msg 会编译失败,
     * 但配合 @SerializedName 或后端改名就能悄悄溜过去)。
     */
    @Test
    public void apiResponse_fieldIsMessage_notMsg() {
        ApiResponse<Object> ok = parse("{\"code\":0,\"message\":\"ok\",\"data\":null}", resp(Object.class));
        assertEquals(0, ok.code);
        assertEquals("ok", ok.message);

        ApiResponse<Object> err = parse(
                "{\"code\":404,\"message\":\"码不存在: INVALID\",\"data\":null}", resp(Object.class));
        assertEquals(404, err.code);
        assertEquals("码不存在: INVALID", err.message);

        // 旧字段名 msg 不再被识别(保护已修复的 bug 不回退)
        ApiResponse<Object> legacy = parse("{\"code\":1,\"msg\":\"旧字段\",\"data\":null}", resp(Object.class));
        assertNull(legacy.message);
    }

    // ==================== 各业务 DTO ====================

    /** 来源:GET /api/inbound/orders/1 */
    @Test
    public void inboundOrder_withItems_andCodes() {
        String json = "{\"id\":1,\"orderNo\":\"IN-20260916141117-332\",\"externalNo\":\"4553647577\","
                + "\"remark\":\"测试\",\"createdBy\":\"admin\",\"totalQuantity\":10,\"itemCount\":1,"
                + "\"createdAt\":\"2026-09-16T14:11:17\",\"status\":\"COMMITTED\",\"items\":["
                + "{\"id\":1,\"orderId\":1,\"productId\":1,\"sku\":\"SP-001\",\"productName\":\"新冠疫苗\","
                + "\"supplier\":\"生物科技\",\"quantity\":10,\"unitPrice\":10.00,\"beforeQty\":0,\"afterQty\":0,"
                + "\"location\":\"A-01-01\",\"productionDate\":\"2026-07-01\",\"shelfLifeDays\":90,"
                + "\"rowRemark\":\"测试\",\"createdAt\":\"2026-09-16T14:11:17\","
                + "\"codes\":[\"000000003500302029039176\",\"000000003500302039039206\"]}]}";

        Dto.InboundOrder o = parse(json, Dto.InboundOrder.class);
        assertEquals(Integer.valueOf(1), o.id);
        assertEquals("IN-20260916141117-332", o.orderNo);
        assertEquals("4553647577", o.externalNo);
        assertEquals("COMMITTED", o.status);
        assertEquals("admin", o.createdBy);
        assertEquals(1, o.items.size());

        Dto.InboundOrderItem it = o.items.get(0);
        assertEquals("SP-001", it.sku);
        assertEquals("生物科技", it.supplier);
        assertEquals("A-01-01", it.location);
        assertEquals("2026-07-01", it.productionDate);
        assertEquals(Integer.valueOf(90), it.shelfLifeDays);
        assertEquals(2, it.codes.size());
        assertEquals("000000003500302029039176", it.codes.get(0));
    }

    /**
     * 后端把 BigDecimal 的 unitPrice 发成 **JSON 数字**(原文 `"unitPrice":10.00`,没有引号),
     * 而 DTO 声明的是 String。Gson 会**宽松地**把数字读成字符串,所以现在能用;
     * 这条测试把这个隐含依赖钉住 —— 一旦 Gson 行为或字段类型变了,这里先红。
     * 不断言字面量("10.00" / "10.0" 取决于 Gson 的数字读取路径),只断言数值可还原。
     */
    @Test
    public void inboundOrderItem_unitPrice_isJsonNumber_coercedToString() {
        String json = "{\"sku\":\"SP-001\",\"quantity\":10,\"unitPrice\":10.00}";

        Dto.InboundOrderItem it = parse(json, Dto.InboundOrderItem.class);
        assertNotNull(it.unitPrice);
        assertEquals(10.0, Double.parseDouble(it.unitPrice), 0.0001);
    }

    /** 来源:GET /api/inventory/items?pageSize=1(后端返回的字段比 DTO 多,多出来的必须被忽略而不是报错) */
    @Test
    public void inventoryItem_readsSubset_ignoresExtraFields() {
        String json = "{\"id\":35,\"code\":\"202609161503204987915335\",\"productId\":1,\"sku\":\"SP-001\","
                + "\"productName\":\"新冠疫苗\",\"locationCode\":\"A-01-01\",\"quantity\":1,\"remaining\":0,"
                + "\"status\":\"PENDING\",\"inboundOrderId\":3,\"inboundItemId\":4,\"outboundOrderId\":null,"
                + "\"scrappedAt\":null,\"scrappedRemark\":null,\"transferredAt\":null,\"transferredFrom\":null,"
                + "\"transferredTo\":null,\"productionDate\":\"2026-09-01\",\"shelfLifeDays\":90,"
                + "\"expiryDate\":\"2026-11-30\",\"expiryWarnDays\":30,\"createdBy\":\"admin\","
                + "\"createdAt\":\"2026-09-16T15:03:20\",\"updatedAt\":\"2026-09-16T15:03:20\"}";

        Dto.InventoryItem it = parse(json, Dto.InventoryItem.class);
        assertEquals("202609161503204987915335", it.code);
        assertEquals("A-01-01", it.locationCode);
        assertEquals("PENDING", it.status);
        assertEquals(Integer.valueOf(3), it.inboundOrderId);
        assertNull(it.outboundOrderId);
        assertEquals("admin", it.createdBy);
    }

    /**
     * 来源:GET /api/locations(多仓库改造后的新响应)。
     * 未分配仓库时 warehouseId / warehouseName 都是 null —— LocationPicker 靠这个判断
     * 该把库位归到「(未分配)」组,所以这两个字段的 null 语义必须锁住。
     */
    @Test
    public void location_carriesWarehouse_andUnassignedIsNull() {
        String unassigned = "{\"id\":1,\"code\":\"A-01-01\",\"name\":\"A区01排01位\",\"warehouseId\":null,"
                + "\"warehouseName\":null,\"remark\":\"测试\",\"enabled\":true,"
                + "\"createdAt\":\"2026-09-16T14:05:46\",\"displayName\":\"A区01排01位\"}";
        Dto.Location a = parse(unassigned, Dto.Location.class);
        assertEquals("A-01-01", a.code);
        assertNull(a.warehouseId);
        assertNull(a.warehouseName);
        assertEquals(Boolean.TRUE, a.enabled);

        String assigned = "{\"id\":4,\"code\":\"默认库位\",\"name\":\"默认库位\",\"warehouseId\":1,"
                + "\"warehouseName\":\"默认仓库\",\"remark\":\"\",\"enabled\":true}";
        Dto.Location b = parse(assigned, Dto.Location.class);
        assertEquals(Integer.valueOf(1), b.warehouseId);
        assertEquals("默认仓库", b.warehouseName);
    }

    /** 来源:GET /api/stock-checks?pageSize=1 */
    @Test
    public void stockCheck_fields() {
        String json = "{\"id\":11,\"checkNo\":\"CHK-20260917164533-447\",\"scope\":\"LOCATION\","
                + "\"scopeKeyword\":\"A-01-01\",\"remark\":\"测试\",\"createdBy\":\"admin\",\"status\":\"CANCELLED\","
                + "\"totalCount\":12,\"countedCount\":12,\"diffCount\":12,\"committedAt\":null,"
                + "\"createdAt\":\"2026-09-17T16:45:33\",\"updatedAt\":\"2026-09-18T11:11:51\",\"items\":null}";

        Dto.StockCheck c = parse(json, Dto.StockCheck.class);
        assertEquals("CHK-20260917164533-447", c.checkNo);
        assertEquals("LOCATION", c.scope);
        assertEquals("A-01-01", c.scopeKeyword);
        assertEquals(Integer.valueOf(12), c.diffCount);
        assertNull(c.committedAt);
    }

    /** 来源:GET /api/outbound/orders/1(scannedCodes 是后端真实字段) */
    @Test
    public void outboundOrder_scannedCodes() {
        String json = "{\"id\":1,\"orderNo\":\"OUT-20260916141700-158\",\"customer\":\"项目部\","
                + "\"totalQuantity\":3,\"itemCount\":1,\"status\":\"COMMITTED\","
                + "\"items\":[{\"id\":1,\"orderId\":1,\"productId\":1,\"sku\":\"SP-001\","
                + "\"productName\":\"新冠疫苗\",\"quantity\":3,\"unitPrice\":10.0,\"beforeQty\":15,\"afterQty\":15,"
                + "\"location\":\"A-01-01\",\"rowRemark\":\"测试\",\"createdAt\":\"2026-09-16T14:17:00\","
                + "\"codes\":[\"000000003500302029039176\"]}],"
                + "\"scannedCodes\":[\"000000003500302029039176\",\"000000003500302039039206\"],"
                + "\"uhfDispatchedAt\":null,\"uhfDeviceCode\":null}";

        Dto.OutboundOrder o = parse(json, Dto.OutboundOrder.class);
        assertEquals("项目部", o.customer);
        assertEquals(2, o.scannedCodes.size());
        assertEquals(1, o.items.size());
        assertEquals("A-01-01", o.items.get(0).location);
    }

    /** 来源:GET /api/products(默认供应商/默认保质期是入库选商品要用的字段) */
    @Test
    public void product_defaultSupplierAndShelfLife() {
        String json = "[{\"id\":2,\"sku\":\"SP-002\",\"name\":\"手机\",\"unit\":\"件\",\"quantity\":0,"
                + "\"lowStock\":10,\"defaultShelfLifeDays\":null,\"defaultSupplier\":\"华为\","
                + "\"defaultSupplierId\":3,\"suppliers\":[{\"id\":3,\"name\":\"华为\",\"isDefault\":true}],"
                + "\"stockValue\":0.00}]";

        List<Dto.Product> list = parse(json, new TypeToken<List<Dto.Product>>(){}.getType());
        assertEquals(1, list.size());
        Dto.Product p = list.get(0);
        assertEquals("SP-002", p.sku);
        assertEquals("华为", p.defaultSupplier);
        assertEquals(Integer.valueOf(3), p.defaultSupplierId);
        assertNull(p.defaultShelfLifeDays);
        assertEquals(Integer.valueOf(0), p.quantity);
    }

    /** 来源:GET /api/suppliers(DTO 只取 3 个字段,其余忽略) */
    @Test
    public void supplier_readsSubset() {
        String json = "[{\"id\":3,\"name\":\"华为\",\"contact\":\"\",\"phone\":\"\",\"email\":\"\","
                + "\"address\":\"\",\"remark\":\"\",\"enabled\":true,\"createdAt\":\"2026-09-16T15:02:21\"}]";

        List<Dto.Supplier> list = parse(json, new TypeToken<List<Dto.Supplier>>(){}.getType());
        assertEquals(1, list.size());
        assertEquals("华为", list.get(0).name);
        assertEquals(Boolean.TRUE, list.get(0).enabled);
    }

    /**
     * 来源:POST /api/outbound/orders/{id}/scan-epc 的响应。
     * ⚠️ 这条不是逐字抓包,而是照后端 ScanResult 模型的字段名写的
     * (matched / reason / sku / epc / scannedQty / reservedCount / stillNeeded / alreadyScanned);
     * PDA 侧只声明了其中子集,itemId / orderId 被忽略。
     */
    @Test
    public void epcScanResult_fields() {
        String json = "{\"matched\":true,\"alreadyScanned\":false,\"reason\":null,\"epc\":\"E2803821000000000000ABCD\","
                + "\"sku\":\"SP-001\",\"scannedQty\":1,\"reservedCount\":3,\"stillNeeded\":2}";

        Dto.EpcScanResult r = parse(json, Dto.EpcScanResult.class);
        assertEquals(Boolean.TRUE, r.matched);
        assertEquals(Boolean.FALSE, r.alreadyScanned);
        assertEquals("SP-001", r.sku);
        assertEquals(Integer.valueOf(2), r.stillNeeded);
    }

    /** 来源:GET /api/auth/me */
    @Test
    public void authMe_fields() {
        String json = "{\"code\":0,\"message\":\"ok\","
                + "\"data\":{\"role\":\"ADMIN\",\"userId\":1,\"username\":\"admin\"}}";
        ApiResponse<com.google.gson.JsonObject> r = parse(json, resp(com.google.gson.JsonObject.class));
        assertEquals(0, r.code);
        assertEquals("ADMIN", r.data.get("role").getAsString());
        assertEquals("admin", r.data.get("username").getAsString());
    }
}
