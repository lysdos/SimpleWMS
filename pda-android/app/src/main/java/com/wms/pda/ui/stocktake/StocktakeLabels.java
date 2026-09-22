package com.wms.pda.ui.stocktake;

import com.wms.pda.R;

/**
 * 盘点单「方式 / 范围」的文案。
 *
 * 后端给的是原始值 ALL / SKU / LOCATION / WAREHOUSE,文案按资源翻。
 * 原先 StocktakeAdapter 与 StocktakeDetailActivity **各抄了一份**中文映射(兜底写法还略有不同),
 * 现在共用这一处 —— 改一处两边都跟上,也不会再出现"一处翻了另一处没翻"。
 *
 * 注意 LOCATION(按库位):创建入口早已移除,这里只为**读历史单**保留,别顺手删。
 */
final class StocktakeLabels {

    private StocktakeLabels() { }

    /** 返回文案资源 id;认不出的范围返回 0,由调用方原样显示后端给的值(别把信息吞掉)。 */
    static int scopeRes(String scope) {
        if ("ALL".equals(scope))       return R.string.stocktake_scope_all;
        if ("SKU".equals(scope))       return R.string.stocktake_scope_sku;
        if ("LOCATION".equals(scope))  return R.string.stocktake_scope_location;
        if ("WAREHOUSE".equals(scope)) return R.string.stocktake_scope_warehouse;
        return 0;
    }
}
