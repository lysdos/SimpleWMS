package com.wms.pda.data.model;

/**
 * 单据状态枚举(出入库单 / 盘点单):草稿 / 待执行 / 已完成 / 已取消。
 * 映射见 {@code Mappers.mapStatus}:后端 DRAFT→DRAFT、COMMITTED→DONE、CANCELLED→CANCELLED,
 * 其余(含 null)一律落到 PENDING("待执行")。
 *
 * 注意:这里的 PENDING **不等于**后端库存单元状态里的 PENDING —— 后者是"待入库",
 * 指入库草稿生成的占位单元。两个是不同维度的枚举,只是名字撞了,别按名字对应。
 * 另外 default 的兜底会把任何没识别的后端状态显示成"待执行",不会报错,排查时留意。
 */
public final class Enums {
    private Enums() {}

    public enum DocStatus {
        DRAFT("草稿"),
        PENDING("待执行"),
        DONE("已完成"),
        CANCELLED("已取消");

        public final String label;
        DocStatus(String label) { this.label = label; }
    }
}
