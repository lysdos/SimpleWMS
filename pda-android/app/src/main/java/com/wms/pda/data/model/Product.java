package com.wms.pda.data.model;

/** 商品(/api/products,添加明细选商品用) */
public class Product {
    public Integer id;
    public String sku;
    public String name;
    public String unit;
    public Integer quantity;
    public Integer lowStock;
    public String defaultSupplier;
    public Integer defaultSupplierId;
}
