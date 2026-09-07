package com.wms.pda.model;

import java.util.List;

public class OutboundRequest {
    public String customer;
    public String externalNo;
    public String remark;
    public List<Item> items;

    public static class Item {
        public Integer productId;
        public Integer quantity;
        public String rowRemark;
    }
}
