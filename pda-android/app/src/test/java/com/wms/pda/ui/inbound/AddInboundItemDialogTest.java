package com.wms.pda.ui.inbound;

import com.wms.pda.data.model.Product;
import com.wms.pda.data.model.Supplier;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;

/**
 * 入库明细「供应商候选」的顺序规则(纯逻辑,不管界面)。
 *
 * 规则:首项永远是"不指定";该商品关联过的供应商按后端给的顺序(默认那个第一)排后面;
 * 其余供应商兜底跟在最后。两头都不能错 —— 关联的没置顶,用户就会以为多选白填了
 * (这次修的就是它);兜底的丢了,现场临时来的新供应商当场录不进来。
 */
@RunWith(RobolectricTestRunner.class)
// 28:本机已缓存 API 28 的 android-all 运行时,离线也能跑(与 LocationPickerTest 同因)
@Config(sdk = 28)
public class AddInboundItemDialogTest {

    private static final String NONE = "(不指定)";

    /** related 按后端给数组的顺序传入(默认供应商本来就在第一位,这里不额外排) */
    private static Product productWith(String... related) {
        Product p = new Product();
        p.suppliers = related.length == 0 ? null : new ArrayList<>(Arrays.asList(related));
        return p;
    }

    private static List<Supplier> allWith(String... names) {
        List<Supplier> list = new ArrayList<>();
        for (String n : names) {
            Supplier s = new Supplier();
            s.name = n;
            s.enabled = true;
            list.add(s);
        }
        return list;
    }

    @Test
    public void relatedSuppliersGoFirst_thenTheRest() {
        List<String> labels = AddInboundItemDialog.supplierLabelsFor(
                productWith("戴尔", "生物科技"), allWith("戴尔", "生物科技", "华为"), NONE);
        assertEquals(Arrays.asList(NONE, "戴尔", "生物科技", "华为"), labels);
    }

    @Test
    public void relatedSupplierMissingFromAll_isNotDuplicatedOrDropped() {
        // 关联里留着的供应商没被 /api/suppliers 返回(理论上不会)时,也不该出现两次
        List<String> labels = AddInboundItemDialog.supplierLabelsFor(
                productWith("戴尔", "生物科技"), allWith("戴尔", "华为"), NONE);
        assertEquals(Arrays.asList(NONE, "戴尔", "生物科技", "华为"), labels);
    }

    @Test
    public void noRelatedSuppliers_fallsBackToFlatList() {
        List<String> labels = AddInboundItemDialog.supplierLabelsFor(
                productWith(), allWith("戴尔", "生物科技"), NONE);
        assertEquals(Arrays.asList(NONE, "戴尔", "生物科技"), labels);
    }

    @Test
    public void nullProduct_fallsBackToFlatList() {
        // 商品列表拉取失败时会插一个兜底商品当首项,它没有关联供应商
        assertEquals(Arrays.asList(NONE, "戴尔"),
                AddInboundItemDialog.supplierLabelsFor(null, allWith("戴尔"), NONE));
    }

    @Test
    public void noSuppliersAtAll_onlyThePlaceholder() {
        assertEquals(Arrays.asList(NONE),
                AddInboundItemDialog.supplierLabelsFor(productWith(), new ArrayList<>(), NONE));
    }
}
