package com.wms.util;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 前端 i18n 词表的占位符守门测试。
 *
 * 现场踩过:文案里写了 Java 风格的 {@code %1$s},而前端的
 * {@code t(key, params)} 只认 {@code {name}}(见 frontend/js/i18n.js 的
 * {@code str.replace(/\{(\w+)\}/g, ...)})。结果占位符原样输出成
 * 「文件 %1$s 行 → 合并后 %2$s 条明细」;更糟的是「确认导入 %1$s 件」这个按钮
 * 文案坏掉后,和预检按钮长得一模一样,用户点了就直接建单。
 *
 * 这类错误单测发现不了(没有 Java 侧断言能覆盖 JS 渲染),只能静态扫词表。
 */
class I18nPlaceholderTest {

    /** Java 风格的格式化占位符,前端不该出现 */
    private static final Pattern JAVA_STYLE = Pattern.compile("%\\d+\\$[sd]");

    @Test
    void i18n_usesCurlyPlaceholdersOnly() throws IOException {
        Path dict = Paths.get("frontend/js/i18n.js");
        assertTrue(Files.exists(dict), "找不到 i18n.js,测试工作目录应为项目根: " + Paths.get("").toAbsolutePath());

        List<String> offenders = new ArrayList<>();
        List<String> lines = Files.readAllLines(dict, StandardCharsets.UTF_8);
        for (int i = 0; i < lines.size(); i++) {
            Matcher m = JAVA_STYLE.matcher(lines.get(i));
            if (m.find()) {
                offenders.add("第 " + (i + 1) + " 行: " + lines.get(i).trim());
            }
        }

        assertTrue(offenders.isEmpty(),
                "i18n.js 里出现了前端不认的 %N$s 占位符(应用 {name} 形式):\n"
                        + String.join("\n", offenders));
    }

    /**
     * 反向守门:凡是 {@code _fmt} 结尾的键,文案里至少要有一个 {@code {xxx}} ——
     * 否则调用方传的 params 会被静默忽略,显示成一句没有数字的话。
     */
    @Test
    void fmtKeys_haveAtLeastOneCurlyPlaceholder() throws IOException {
        Path dict = Paths.get("frontend/js/i18n.js");
        List<String> offenders = new ArrayList<>();
        try (Stream<String> s = Files.lines(dict, StandardCharsets.UTF_8)) {
            s.filter(line -> line.contains("_fmt'"))
             .forEach(line -> {
                 // 只看 zh-CN 那份;en-US 同步即可
                 String zh = extractZh(line);
                 if (zh != null && !zh.contains("{")
                         && !zh.contains("（") && !zh.contains("(")) {   // 少数 _fmt 只是命名习惯,没有参数
                     offenders.add(line.trim());
                 }
             });
        }
        assertTrue(offenders.isEmpty(),
                "_fmt 键的文案里没有 {占位符},调用方传的 params 会被忽略:\n" + String.join("\n", offenders));
    }

    private static String extractZh(String line) {
        int i = line.indexOf("'zh-CN':");
        if (i < 0) return null;
        int start = line.indexOf('\'', i + 8);
        if (start < 0) return null;
        int end = line.indexOf('\'', start + 1);
        return end < 0 ? null : line.substring(start + 1, end);
    }
}
