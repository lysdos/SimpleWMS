package com.wms.pda.model;

import java.util.List;

public class StockCheckDto {
    public Integer id;
    public String checkNo;
    public String scope;
    public String scopeKeyword;
    public String remark;
    public String status;
    public Integer totalCount;
    public Integer countedCount;
    public Integer diffCount;
    public List<Item> items;

    public static class Item {
        public Integer id;
        public Integer inventoryItemId;
        public String code;
        public String sku;
        public String productName;
        public String locationCode;
        public Integer systemQty;
        public Integer countedQty;
        public Integer diff;
        public String rowRemark;
    }
}
