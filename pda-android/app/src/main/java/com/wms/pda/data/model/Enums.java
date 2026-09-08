package com.wms.pda.data.model;

/**
 * 通用状态枚举:草稿/待执行/已完成。
 * 映射到 SimpleWMS 后端的状态字段时,只需保持 name 一致。
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
