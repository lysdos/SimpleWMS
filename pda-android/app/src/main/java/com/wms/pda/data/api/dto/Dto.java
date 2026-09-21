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
        public Long expiringSoonCount; // 临期批次预警数(未过期)
        public Long expiredCount;      // 已过期在库批次数
    }

    // ---- 入库 ----
    public static class InboundOrder {
        public Integer id;
        public String orderNo;
        public String externalNo;
        public String remark;
        public String createdBy;
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
        public String productionDate; // yyyy-MM-dd(可空)
        public Integer shelfLifeDays; // 保质期天数(可空)
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
        public String createdBy;
        public Integer totalQuantity;
        public Integer itemCount;
        public String createdAt;
        public String status;
        public List<String> scannedCodes; // 已扫码确认的库存单元编码(详情页回看用)
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
        public String location;      // 取货库位;空 = 自动(跨库位找货),实际分布看 picks
        public String rowRemark;
        public String createdAt;
        public List<String> codes;   // 每个库存单元的编码(逐件显示用)
        public java.util.Map<String, Integer> picks; // 库位码 → 件数(该行实际从各库位取几件)
        // 库位码 → 该库位取到的码列表。与 picks 同源同序(keySet 恒等),按仓库分组显示靠它
        public java.util.Map<String, List<String>> codesByLocation;
    }

    // ---- 盘点 ----
    public static class StockCheck {
        public Integer id;
        public String checkNo;
        public String scope;          // ALL / SKU / LOCATION
        public String scopeKeyword;
        public String remark;
        public String createdBy;
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
        public String warehouseName;  // 库位所属仓库名(后端 join location→warehouse 带出,可空)
        public Integer quantity;
        public Integer remaining;
        public String status;         // IN_STOCK / OUT / SCRAPPED / TRANSFERRED
        public String expiryDate;     // 到期日 yyyy-MM-dd(入库时按生产日期+保质期算出,可空)
        public Integer expiryWarnDays; // 该商品的临期预警天数(后端联 product 带出,可空=按 30 天)
        public Integer inboundOrderId;
        public Integer outboundOrderId;
        public String createdBy;
        public String createdAt;
        public String updatedAt;
    }

    // ---- 商品(/api/products,添加明细选商品用) ----
    public static class Product {
        public Integer id;
        public String sku;
        public String name;
        public String unit;
        public Integer quantity;
        public Integer lowStock;
        public String defaultSupplier;
        public Integer defaultSupplierId;
        public Integer defaultShelfLifeDays; // 默认保质期天数(可空)
    }

    // ---- 供应商(/api/suppliers,下拉选项) ----
    public static class Supplier {
        public Integer id;
        public String name;
        public Boolean enabled;
    }

    // ---- 库位(/api/locations,调拨目标 / 盘点「按库位」用) ----
    // warehouseId / warehouseName 由后端 JOIN warehouse 带出;为空 = 未分配仓库。
    // 多仓库后下拉要按仓库分组(见 ui.common.LocationPicker)。
    public static class Location {
        public Integer id;
        public String code;
        public String name;
        public Integer warehouseId;
        public String warehouseName;
        public Boolean enabled;
    }

    // ---- 库存流水(最近操作用) ----
    public static class InventoryLog {
        public Integer id;
        public String type;          // IN / OUT / CHECK
        public Integer productId;
        public String sku;
        public String productName;
        public Integer quantity;
        public Integer beforeQty;
        public Integer afterQty;
        public String remark;
        public String createdBy;
        public Integer orderId;
        public String createdAt;
    }

    // ---- 撤销扫码确认(POST /orders/{id}/reset-scans 响应) ----
    public static class ResetScansResponse {
        public Integer orderId;
        public Integer deleted;
    }

    // ---- UHF EPC 扫码结果(后端 /orders/{id}/scan-epc 返回) ----
    public static class EpcScanResult {
        public Boolean matched;          // true = 扫码确认成功(幂等的重复扫也走 true)
        public Boolean alreadyScanned;   // true = 本单此前已确认过该 EPC(非错误)
        public String reason;            // matched=false 时的失败原因
        public String epc;
        public String sku;
        public Integer scannedQty;       // 本单(该 SKU)已确认件数
        public Integer reservedCount;    // 本单(该 SKU)锁定总件数
        public Integer stillNeeded;      // 还差几件
    }
}