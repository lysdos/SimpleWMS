package com.wms.service;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 24 位商品编码生成器。
 *
 * 格式:
 *   YYYYMMDD(8) + HHmmss(6) + SKU_HASH(4) + SERIAL(6) = 24 位
 *
 * - 时间戳:生成时刻的本地时间
 * - SKU_HASH:SHA-256(sku) 取前 2 字节转 4 位大写 hex(0-9 A-F),最多 65536 个槽
 * - SERIAL:6 位 0-9 数字。生成时先按 SKU+时间随机一个值,DB UNIQUE 约束兜底防冲突。
 *
 * 注:不依赖外部递增计数器,做到无状态、可水平扩展。
 */
@Component
public class CodeGenerator {

    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /**
     * 生成候选编码(可被 DB UNIQUE 约束拒绝,调用方应捕获 DuplicateKeyException 重新生成)。
     */
    public String generate(String sku) {
        String ts = LocalDateTime.now().format(TS_FMT);     // 14 位
        String skuHash = hashSku4(sku);                    //  4 位 hex
        String serial = randomSerial6();                   //  6 位数字
        return ts + skuHash + serial;                      // 24 位
    }

    /** SHA-256 → 前 2 字节 → 4 位大写 hex */
    private static String hashSku4(String sku) {
        if (sku == null || sku.isEmpty()) return "0000";
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(sku.getBytes(StandardCharsets.UTF_8));
            int v = ((h[0] & 0xFF) << 8) | (h[1] & 0xFF);
            return String.format("%04X", v);
        } catch (Exception e) {
            return "0000";
        }
    }

    /** 6 位 0-9 数字,首位允许 0 */
    private static String randomSerial6() {
        int v = ThreadLocalRandom.current().nextInt(0, 1_000_000);
        return String.format("%06d", v);
    }

    /** 校验码格式(24 位数字 + 大写字母) */
    public static boolean isValid(String code) {
        if (code == null || code.length() != 24) return false;
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'A' && c <= 'Z'))) return false;
        }
        return true;
    }
}
