package com.wms.util;

/**
 * 从 JSON 请求体({@code Map<String,Object>})取值的小工具。
 *
 * 为什么需要:PUT 是局部更新,必须区分「字段没带」和「字段显式为 null」——
 * 前者要保留原值,后者才是真的置空。控制器把 body 收成 Map 后,
 * 由调用方用 {@code patch.containsKey(...)} 判存在,再用这里的静态方法做类型转换。
 * 转换失败抛 {@link IllegalArgumentException},由 GlobalExceptionHandler 统一转 400。
 *
 * 注:几个既有控制器里各有一份私有的 asString/asInt,尚未统一到这里。
 */
public final class Json {

    private Json() {
    }

    /** 取值并去首尾空白;空白串按 null 处理(前端输入框清空后发的是空串)。 */
    public static String asString(Object o) {
        if (o == null) return null;
        String s = o.toString().trim();
        return s.isEmpty() ? null : s;
    }

    /** 数值/数字字符串 → Integer;空串 → null。 */
    public static Integer asInt(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.intValue();
        String s = o.toString().trim();
        if (s.isEmpty()) return null;
        try {
            return Integer.valueOf(s);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("不是合法的整数: " + s);
        }
    }

    /** 布尔/「true|false|1|0」→ Boolean;空串 → null。 */
    public static Boolean asBool(Object o) {
        if (o == null) return null;
        if (o instanceof Boolean b) return b;
        String s = o.toString().trim();
        if (s.isEmpty()) return null;
        if ("true".equalsIgnoreCase(s) || "1".equals(s)) return Boolean.TRUE;
        if ("false".equalsIgnoreCase(s) || "0".equals(s)) return Boolean.FALSE;
        throw new IllegalArgumentException("不是合法的布尔值: " + s);
    }
}
