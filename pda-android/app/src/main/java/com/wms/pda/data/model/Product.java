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
    public Integer defaultShelfLifeDays; // 默认保质期天数(入库预填,可空)
    /** 关联供应商名(后端已按"默认在前"排好);入库明细的供应商候选靠它置顶,可空 */
    public java.util.List<String> suppliers;
}
