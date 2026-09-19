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
 * PDA UI 用 `id` 当单号展示,把后端 orderNo 映射成 model.id。
 * 后端 `id`(数据库自增)不暴露给 UI。
 */
final class Mappers {
    private Mappers() {}

    static InboundOrder toModel(Dto.InboundOrder o) {
        if (o == null) return null;
        InboundOrder m = new InboundOrder();
        m.dbId = o.id;
        m.id = o.orderNo == null ? ("o-" + o.id) : o.orderNo;
        m.externalNo = o.externalNo;
        m.createdBy = o.createdBy;
        m.totalQuantity = o.totalQuantity;
        m.itemCount = o.itemCount;
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
        l.id = it.id;
        l.productId = it.productId;
        l.sku = it.sku;
        l.name = it.productName;
        l.supplier = it.supplier;
        l.unitPrice = it.unitPrice;
        l.beforeQty = it.beforeQty;
        l.afterQty = it.afterQty;
        l.location = it.location;
        l.rowRemark = it.rowRemark;
        l.productionDate = it.productionDate;
        l.shelfLifeDays = it.shelfLifeDays;
        l.qty = it.quantity == null ? 0 : it.quantity;
        l.uom = "件";  // 后端未持久化单位,PDA 端占位
        l.codes = it.codes;
        return l;
    }

    static OutboundOrder toModel(Dto.OutboundOrder o) {
        if (o == null) return null;
        OutboundOrder m = new OutboundOrder();
        m.dbId = o.id;
        m.id = o.orderNo == null ? ("o-" + o.id) : o.orderNo;
        m.customer = o.customer;
        m.externalNo = o.externalNo;
        m.createdBy = o.createdBy;
        m.totalQuantity = o.totalQuantity;
        m.itemCount = o.itemCount;
        m.remark = o.remark;
        m.status = mapStatus(o.status);
        m.createdAt = o.createdAt;
        m.scannedCodes = o.scannedCodes;
        m.lines = new ArrayList<>();
        if (o.items != null) {
            for (Dto.OutboundOrderItem it : o.items) {
                OutboundOrder.Line l = new OutboundOrder.Line();
                l.id = it.id;
                l.productId = it.productId;
                l.sku = it.sku;
                l.name = it.productName;
                l.unitPrice = it.unitPrice;
                l.beforeQty = it.beforeQty;
                l.afterQty = it.afterQty;
                l.location = it.location;
                l.rowRemark = it.rowRemark;
                l.qty = it.quantity == null ? 0 : it.quantity;
                l.uom = "件";
                l.codes = it.codes;
                m.lines.add(l);
            }
        }
        return m;
    }

    static StocktakeOrder toModel(Dto.StockCheck c) {
        if (c == null) return null;
        StocktakeOrder s = new StocktakeOrder();
        s.dbId = c.id;
        s.id = c.checkNo == null ? ("c-" + c.id) : c.checkNo;
        s.scope = c.scope;
        s.scopeKeyword = c.scopeKeyword;
        s.createdBy = c.createdBy;
        s.totalCount = c.totalCount;
        s.countedCount = c.countedCount;
        s.diffCount = c.diffCount;
        s.remark = c.remark;
        s.status = mapStatus(c.status);
        s.createdAt = c.createdAt;
        s.lines = new ArrayList<>();
        if (c.items != null) {
            for (Dto.StockCheckItem it : c.items) {
                StocktakeOrder.Line l = new StocktakeOrder.Line();
                l.id = it.id;
                l.inventoryItemId = it.inventoryItemId;
                l.code = it.code;
                l.sku = it.sku;
                l.productName = it.productName;
                l.locationCode = it.locationCode;
                l.systemQty = it.systemQty;
                l.countedQty = it.countedQty;
                l.diff = it.diff;
                l.rowRemark = it.rowRemark;
                s.lines.add(l);
            }
        }
        return s;
    }

    static InventoryItem toModel(Dto.InventoryItem it) {
        if (it == null) return null;
        InventoryItem m = new InventoryItem();
        m.id = it.id;
        m.code = it.code;
        m.productId = it.productId;
        m.sku = it.sku;
        m.productName = it.productName;
        m.locationCode = it.locationCode;
        m.quantity = it.quantity;
        m.remaining = it.remaining;
        m.status = it.status;
        m.inboundOrderId = it.inboundOrderId;
        m.outboundOrderId = it.outboundOrderId;
        m.createdBy = it.createdBy;
        m.createdAt = it.createdAt;
        return m;
    }

    static com.wms.pda.data.model.EpcScanResult toModel(Dto.EpcScanResult r) {
        if (r == null) return null;
        com.wms.pda.data.model.EpcScanResult m = new com.wms.pda.data.model.EpcScanResult();
        m.matched = r.matched != null && r.matched;
        m.alreadyScanned = r.alreadyScanned != null && r.alreadyScanned;
        m.reason = r.reason;
        m.epc = r.epc;
        m.sku = r.sku;
        m.scannedQty = r.scannedQty;
        m.reservedCount = r.reservedCount;
        m.stillNeeded = r.stillNeeded;
        return m;
    }

    static com.wms.pda.data.model.Product toModel(Dto.Product p) {
        if (p == null) return null;
        com.wms.pda.data.model.Product m = new com.wms.pda.data.model.Product();
        m.id = p.id;
        m.sku = p.sku;
        m.name = p.name;
        m.unit = p.unit;
        m.quantity = p.quantity;
        m.lowStock = p.lowStock;
        m.defaultSupplier = p.defaultSupplier;
        m.defaultSupplierId = p.defaultSupplierId;
        m.defaultShelfLifeDays = p.defaultShelfLifeDays;
        return m;
    }

    static com.wms.pda.data.model.Supplier toModel(Dto.Supplier s) {
        if (s == null) return null;
        com.wms.pda.data.model.Supplier m = new com.wms.pda.data.model.Supplier();
        m.id = s.id;
        m.name = s.name;
        m.enabled = s.enabled == null || s.enabled;
        return m;
    }

    static com.wms.pda.data.model.Location toModel(Dto.Location l) {
        if (l == null) return null;
        com.wms.pda.data.model.Location m = new com.wms.pda.data.model.Location();
        m.id = l.id;
        m.code = l.code;
        m.name = l.name;
        m.warehouseId = l.warehouseId;
        m.warehouseName = l.warehouseName;
        // 后端 enabled 为空按启用处理(与 supplier 一致)
        m.enabled = l.enabled == null || l.enabled;
        return m;
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
}