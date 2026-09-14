package com.wms.pda.data.api.dto;

import java.util.List;

/**
 * 后端 JSON 镜像(1:1 对应 src/main/java/com/wms/model/* 字段)。
 * 字段 public,Gson 直读直写。
 * PDA 端 RemoteWmsRepository 收到 DTO 后通过 Mappers 翻译成 data/model/* 给 UI 用。
 */
public class Dto {

    // ---- 仪表盘 ----
    public static class DashboardStats {
        public Long productCount;
        public Long totalQuantity;
        public String totalValue;     // BigDecimal → 字符串
        public Long lowStockCount;
    }

    // ---- 入库 ----
    public static class InboundOrder {
        public Integer id;
        public String orderNo;
        public String externalNo;
        public String remark;
        public String operator;
        public Integer totalQuantity;
        public Integer itemCount;
        public String createdAt;     // ISO 字符串
        public String status;        // DRAFT / COMMITTED / CANCELLED
        public List<InboundOrderItem> items;
    }

    public static class InboundOrderItem {
        public Integer id;
        public Integer orderId;
        public Integer productId;
        public String sku;
        public String productName;
        public String supplier;
        public Integer quantity;
        public String unitPrice;     // BigDecimal 序列化为字符串
        public Integer beforeQty;
        public Integer afterQty;
        public String location;
        public String rowRemark;
        public String createdAt;
        public List<String> codes;   // commit 后填充的 24 位码
    }

    // ---- 出库 ----
    public static class OutboundOrder {
        public Integer id;
        public String orderNo;
        public String customer;
        public String externalNo;
        public String remark;
        public String operator;
        public Integer totalQuantity;
        public Integer itemCount;
        public String createdAt;
        public String status;
        public List<OutboundOrderItem> items;
    }

    public static class OutboundOrderItem {
        public Integer id;
        public Integer orderId;
        public Integer productId;
        public String sku;
        public String productName;
        public Integer quantity;
        public String unitPrice;
        public Integer beforeQty;
        public Integer afterQty;
        public String location;
        public String rowRemark;
        public String createdAt;
        public List<String> codes;   // 每个库存单元的编码(逐件显示用)
    }

    // ---- 盘点 ----
    public static class StockCheck {
        public Integer id;
        public String checkNo;
        public String scope;          // ALL / SKU / LOCATION
        public String scopeKeyword;
        public String remark;
        public String operator;
        public String status;
        public Integer totalCount;
        public Integer countedCount;
        public Integer diffCount;
        public String committedAt;
        public String createdAt;
        public String updatedAt;
        public List<StockCheckItem> items;
    }

    public static class StockCheckItem {
        public Integer id;
        public Integer checkId;
        public Integer inventoryItemId;
        public String code;
        public String sku;
        public String productName;
        public String locationCode;
        public Integer systemQty;
        public Integer countedQty;
        public Integer diff;
        public String rowRemark;
        public String createdAt;
        public String updatedAt;
    }

    // ---- 库存单元 ----
    public static class InventoryItem {
        public Integer id;
        public String code;
        public Integer productId;
        public String sku;
        public String productName;
        public String locationCode;
        public Integer quantity;
        public Integer remaining;
        public String status;         // IN_STOCK / OUT / SCRAPPED / TRANSFERRED
        public Integer inboundOrderId;
        public Integer outboundOrderId;
        public String createdBy;
        public String createdAt;
        public String updatedAt;
    }

    // ---- 登录响应 ----
    public static class LoginResponse {
        public String token;
        public String username;
        public String role;
        public Boolean mustChangePw;
    }
}