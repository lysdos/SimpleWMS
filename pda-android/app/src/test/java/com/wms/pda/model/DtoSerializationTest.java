package com.wms.pda.model;

import com.google.gson.Gson;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class DtoSerializationTest {

    private final Gson gson = new Gson();

    @Test
    public void loginRequest() {
        String json = gson.toJson(new LoginRequest("admin", "change-me-now"));
        assertEquals("{\"username\":\"admin\",\"password\":\"change-me-now\"}", json);
    }

    @Test
    public void inventoryItemDto() {
        String src = "{\"id\":1,\"code\":\"ABC123\",\"sku\":\"SP-001\",\"status\":\"IN_STOCK\"}";
        InventoryItemDto it = gson.fromJson(src, InventoryItemDto.class);
        assertEquals(1, it.id.intValue());
        assertEquals("ABC123", it.code);
        assertEquals("IN_STOCK", it.status);
    }

    @Test
    public void inboundRequest_nested() {
        InboundRequest.Item it = new InboundRequest.Item();
        it.productId = 10;
        it.quantity = 5;
        InboundRequest r = new InboundRequest();
        r.remark = "test";
        r.items = Arrays.asList(it);
        String json = gson.toJson(r);
        assertNotNull(json);
        assertEquals(true, json.contains("\"productId\":10"));
        assertEquals(true, json.contains("\"quantity\":5"));
    }

    @Test
    public void stockCheckItem() {
        String src = "{\"id\":1,\"code\":\"X1\",\"systemQty\":3,\"countedQty\":2,\"diff\":-1}";
        StockCheckDto.Item it = gson.fromJson(src, StockCheckDto.Item.class);
        assertEquals(-1, it.diff.intValue());
    }
}
