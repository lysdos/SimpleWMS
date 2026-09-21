package com.wms.util;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * CSV 导出这类人读场景的时间格式。
 *
 * 用 {@code yyyy-MM-dd HH:mm:ss} 而不是 {@link LocalDateTime#toString()} —— 后者是 ISO 格式,
 * 会吐出 {@code 2026-09-20T16:56:34} 这种带 T 的值,导出到 Excel 里没法直接看。
 */
public final class Dates {

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private Dates() {
    }

    /** null 安全:null 输出空串(CSV 里留空)。 */
    public static String fmt(LocalDateTime t) {
        return t == null ? "" : t.format(DATE_TIME);
    }
}
