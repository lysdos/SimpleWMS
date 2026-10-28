package com.wms.util;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * CSV 导出的统一出口。
 *
 * 与 {@link Csv#esc(Object)}(单字段转义)、{@link CsvParser}(反方向的解析)配套。
 *
 * 为什么要有它:导出的样板是「BOM + 表头 + 逐行拼字段 + 编码 + 响应头」,
 * 在此之前已经有 4 个 controller 各抄了一遍。基础管理再加 5 个模块 × 2 种导出,
 * 抄九遍的话改一处漏八处是必然的。
 *
 * ⚠️ **不**替换既有那 4 个导出:入库单/出库单的 CSV 要先输出单头 KV 段再输出明细表,
 * 结构与本类的「表头 + 行」不同。新代码请走这里。
 */
public final class CsvWriter {

    /** UTF-8 BOM。Excel 双击打开 CSV 时靠它判断编码,没有就是中文乱码。 */
    private static final char BOM = '﻿';

    private CsvWriter() {}

    /**
     * 表头 + 行 → CSV 字节(带 BOM)。
     *
     * 每个字段都过 {@link Csv#esc(Object)}:含逗号/引号/换行的会加引号,
     * 以 {@code = + - @} 开头的会加单引号前缀挡公式注入。
     */
    public static byte[] toCsv(String[] headers, List<Object[]> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append(BOM);
        if (headers != null && headers.length > 0) {
            for (int i = 0; i < headers.length; i++) {
                if (i > 0) sb.append(',');
                sb.append(Csv.esc(headers[i]));
            }
            sb.append('\n');
        }
        if (rows != null) {
            for (Object[] row : rows) {
                for (int i = 0; i < row.length; i++) {
                    if (i > 0) sb.append(',');
                    sb.append(Csv.esc(row[i]));
                }
                sb.append('\n');
            }
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 包成「以附件下载」的响应。
     *
     * 文件名要 URL 编码:中文文件名直接塞进 Content-Disposition 在部分浏览器/代理上会乱码。
     * 末尾的 {@code +} → {@code %20} 是必要的 —— {@code URLEncoder} 按 form 编码把空格
     * 编成 {@code +},而 HTTP 头里的文件名要的是 {@code %20},否则下载下来文件名带加号。
     */
    public static ResponseEntity<byte[]> download(String filename, String[] headers, List<Object[]> rows) {
        byte[] body = toCsv(headers, rows);
        String fname = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.parseMediaType("text/csv;charset=UTF-8"));
        h.setContentDispositionFormData("attachment", fname);
        return new ResponseEntity<>(body, h, 200);
    }
}
