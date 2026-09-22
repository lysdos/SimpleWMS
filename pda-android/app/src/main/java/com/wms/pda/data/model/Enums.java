package com.wms.pda.data.model;

/**
 * 单据状态枚举(出入库单 / 盘点单)。映射见 {@code Mappers.mapStatus}:
 * 后端 DRAFT→DRAFT、COMMITTED→DONE、CANCELLED→CANCELLED,其余(含 null)一律落到
 * PENDING(界面上显示"待执行")。
 *
 * 注意:这里的 PENDING **不等于**后端库存单元状态里的 PENDING —— 后者是"待入库",
 * 指入库草稿生成的占位单元。两个是不同维度的枚举,只是名字撞了,别按名字对应。
 * 另外 default 的兜底会把任何没识别的后端状态落到 PENDING,不会报错,排查时留意。
 *
 * 枚举里**不再带界面文案**(原先每个值挂了一个中文 label)。文案由状态胶囊
 * {@code ui.common.StatusPill} 按资源翻 —— 模型层持有文案,英文就永远翻不到。
 */
public final class Enums {
    private Enums() {}

    public enum DocStatus {
        DRAFT,
        PENDING,
        DONE,
        CANCELLED
    }
}
