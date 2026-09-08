package com.wms.pda.data.remote;

import com.wms.pda.data.api.dto.Dto;
import com.wms.pda.data.model.Enums;
import com.wms.pda.data.model.InboundOrder;
import com.wms.pda.data.model.InventoryItem;
import com.wms.pda.data.model.OutboundOrder;
import com.wms.pda.data.model.StocktakeOrder;

import java.util.ArrayList;
import java.util.List;

/**
 * 后端 DTO → PDA UI model 翻译。
 * PDA UI 用 `id` 当单号展示(原 Mock 是 "RK1001"),所以把后端 orderNo 映射成 model.id。
 * 后端 `id`(数据库自增)不暴露给 UI。
 */
final class Mappers {
    private Mappers() {}

    static InboundOrder toModel(Dto.InboundOrder o) {
        if (o == null) return null;
        InboundOrder m = new InboundOrder();
        m.id = o.orderNo == null ? ("o-" + o.id) : o.orderNo;
        m.supplier = firstSupplier(o.items);
        m.warehouse = "";   // 后端无此字段
        m.remark = o.remark;
        m.status = mapStatus(o.status);
        m.createdAt = o.createdAt;
        m.lines = new ArrayList<>();
        if (o.items != null) {
            for (Dto.InboundOrderItem it : o.items) m.lines.add(toLine(it));
        }
        return m;
    }

    private static InboundOrder.InboundLine toLine(Dto.InboundOrderItem it) {
        if (it == null) return null;
        InboundOrder.InboundLine l = new InboundOrder.InboundLine();
        l.sku = it.sku;
        l.name = it.productName;
        l.barcode = it.codes != null && !it.codes.isEmpty() ? it.codes.get(0) : "";
        l.qty = it.quantity == null ? 0 : it.quantity;
        l.uom = "件";
        return l;
    }

    static OutboundOrder toModel(Dto.OutboundOrder o) {
        if (o == null) return null;
        OutboundOrder m = new OutboundOrder();
        m.id = o.orderNo == null ? ("o-" + o.id) : o.orderNo;
        m.customer = o.customer;
        m.warehouse = "";
        m.remark = o.remark;
        m.status = mapStatus(o.status);
        m.createdAt = o.createdAt;
        m.lines = new ArrayList<>();
        if (o.items != null) {
            for (Dto.OutboundOrderItem it : o.items) {
                OutboundOrder.Line l = new OutboundOrder.Line();
                l.sku = it.sku;
                l.name = it.productName;
                l.barcode = "";
                l.qty = it.quantity == null ? 0 : it.quantity;
                l.uom = "件";
                m.lines.add(l);
            }
        }
        return m;
    }

    static StocktakeOrder toModel(Dto.StockCheck c) {
        if (c == null) return null;
        StocktakeOrder s = new StocktakeOrder();
        s.id = c.checkNo == null ? ("c-" + c.id) : c.checkNo;
        s.warehouse = c.scopeKeyword == null ? "" : c.scopeKeyword;
        s.mode = mapScope(c.scope);
        s.status = mapStatus(c.status);
        s.createdAt = c.createdAt;
        s.lines = new ArrayList<>();
        if (c.items != null) {
            for (Dto.StockCheckItem it : c.items) {
                StocktakeOrder.Line l = new StocktakeOrder.Line();
                l.sku = it.sku;
                l.name = it.productName;
                l.barcode = it.code;        // 24 位 inventory_item.code
                l.bookQty = it.systemQty == null ? 0 : it.systemQty;
                l.actualQty = it.countedQty == null ? -1 : it.countedQty;
                l.location = it.locationCode;
                s.lines.add(l);
            }
        }
        return s;
    }

    static InventoryItem toModel(Dto.InventoryItem it) {
        if (it == null) return null;
        InventoryItem m = new InventoryItem();
        m.id = it.id;
        m.productId = it.productId;
        m.sku = it.sku;
        m.name = it.productName;
        m.barcode = it.code;
        m.warehouse = "";
        m.location = it.locationCode;
        m.onHand = it.remaining == null ? 0 : it.remaining;
        m.available = it.remaining == null ? 0 : it.remaining;
        m.locked = 0;
        m.uom = "件";
        m.lowStock = false;
        return m;
    }

    private static String firstSupplier(List<Dto.InboundOrderItem> items) {
        if (items == null) return null;
        for (Dto.InboundOrderItem it : items) if (it.supplier != null && !it.supplier.isEmpty()) return it.supplier;
        return null;
    }

    private static Enums.DocStatus mapStatus(String s) {
        if (s == null) return Enums.DocStatus.PENDING;
        switch (s) {
            case "DRAFT":     return Enums.DocStatus.DRAFT;
            case "COMMITTED": return Enums.DocStatus.DONE;
            case "CANCELLED": return Enums.DocStatus.CANCELLED;
            default:          return Enums.DocStatus.PENDING;
        }
    }

    private static StocktakeOrder.Mode mapScope(String s) {
        if (s == null) return StocktakeOrder.Mode.ALL;
        switch (s) {
            case "SKU":      return StocktakeOrder.Mode.SAMPLE;
            case "LOCATION": return StocktakeOrder.Mode.MOVEMENT;
            default:         return StocktakeOrder.Mode.ALL;
        }
    }
}