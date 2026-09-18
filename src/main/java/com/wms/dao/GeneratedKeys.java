package com.wms.dao;

import org.springframework.jdbc.support.KeyHolder;

import java.util.Map;

/** 统一从 KeyHolder 取自增主键。
 *  H2 的 getGeneratedKeys() 返回 {id, created_at, updated_at} 多列(且getKey()遇多键抛异常),
 *  MySQL Connector/J 返回单列但列名是 GENERATED_KEY —— 两者的键名不同,
 *  必须兼容,否则一边拿到 null 一边拿到 null,insert 结果 id=0 造成脏数据。 */
final class GeneratedKeys {

    private GeneratedKeys() {}

    static int id(KeyHolder kh) {
        Map<String, Object> keys = kh.getKeys();
        if (keys == null || keys.isEmpty()) return 0;
        Number k = (Number) keys.get("id");
        if (k == null) k = (Number) keys.get("GENERATED_KEY");
        if (k == null) {
            // 兜底:驱动键名再变也取第一个数字值(自增主键必然在 keys 里)
            for (Object v : keys.values()) {
                if (v instanceof Number) { k = (Number) v; break; }
            }
        }
        return k == null ? 0 : k.intValue();
    }
}
