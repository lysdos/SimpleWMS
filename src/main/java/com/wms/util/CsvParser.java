package com.wms.util;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * CSV **解析**(导出的反向)。与 {@link Csv#esc(Object)} 对称:那边负责把值写成合法 CSV,
 * 这边负责把 CSV 读回值。
 *
 * 支持 RFC 4180 的全部要点:
 *  - 双引号包裹的字段内可以有逗号、换行;
 *  - 字段内的双引号用 {@code ""} 转义;
 *  - 行尾允许 {@code \r\n} / {@code \n} / {@code \r}。
 *
 * 另外两件现场一定会遇到的事:
 *  - **BOM**:Excel「另存为 CSV UTF-8」会在文件头写 {@code EF BB BF},不剥掉的话
 *    第一个表头会变成 {@code ﻿SKU},按列名匹配永远对不上。
 *  - **GBK**:中文 Windows 上 Excel 默认存成 ANSI(即 GBK)。按 UTF-8 硬解会得到乱码,
 *    表现为"SKU 全部不存在",比直接报错更难查。所以先严格 UTF-8,失败退回 GBK。
 */
public final class CsvParser {

    /** 单个字段的长度上限。超长几乎必然是选错了文件(比如把 xlsx 当 csv 传上来)。 */
    public static final int MAX_FIELD_LENGTH = 512;

    private CsvParser() {}

    /** 一行数据 + 它在文件里的**物理起始行号**(字段内可含换行,所以不等于行下标 + 1) */
    public record Row(int lineNo, List<String> cells) {}

    /** 解析结果 + 实际用的编码(要回给用户看,否则乱码时无从判断文件到底是怎么被解的) */
    public record Parsed(List<Row> rows, String encoding) {}

    /**
     * 字节 → 文本。剥 BOM、严格 UTF-8,失败回退 GBK。
     *
     * 用 {@link CodingErrorAction#REPORT} 而不是 {@code new String(bytes, UTF_8)}:
     * 后者会把无法解码的字节**静默**替换成 {@code �},于是 GBK 文件变成一个
     * 看起来正常、实际全是乱码的字符串,校验阶段只会报"SKU 不存在",误导性极强。
     */
    public static Parsed decode(byte[] raw) {
        if (raw == null || raw.length == 0) {
            throw new IllegalArgumentException("文件为空");
        }
        int offset = 0;
        // UTF-8 BOM
        if (raw.length >= 3 && (raw[0] & 0xFF) == 0xEF && (raw[1] & 0xFF) == 0xBB && (raw[2] & 0xFF) == 0xBF) {
            offset = 3;
        }
        String text = strictDecode(raw, offset, StandardCharsets.UTF_8);
        String encoding = "UTF-8";
        if (text == null) {
            // GBK 在中文 Windows 上是 Excel 的默认输出
            text = strictDecode(raw, offset, Charset.forName("GBK"));
            encoding = "GBK";
        }
        if (text == null) {
            throw new IllegalArgumentException("无法识别文件编码,请把 Excel 另存为「CSV UTF-8(逗号分隔)」后重新上传");
        }
        // UTF-16 的 BOM(FF FE / FE FF)会在这里表现为一堆 NUL 字符,提前挡掉,
        // 免得后面报一句看不懂的"表头缺失"。
        if (!text.isEmpty() && text.indexOf('\u0000') >= 0) {
            throw new IllegalArgumentException("文件不是 UTF-8/GBK 文本编码,请另存为「CSV UTF-8(逗号分隔)」");
        }
        return new Parsed(parse(text), encoding);
    }

    /** 严格解码:有任何非法字节就返回 null(交给调用方换编码重试) */
    private static String strictDecode(byte[] raw, int offset, Charset charset) {
        CharsetDecoder dec = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            CharBuffer cb = dec.decode(ByteBuffer.wrap(raw, offset, raw.length - offset));
            return cb.toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    /**
     * 解析成行 × 列。**保留空行**(由调用方决定是否忽略):行号要对得上文件,
     * 预检报告里的"第 7 行"必须是用户眼睛看到的第 7 行,所以这里不替调用方丢行。
     */
    public static List<Row> parse(String text) {
        List<Row> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        int n = text.length();
        int line = 1;           // 当前物理行(1 起)
        int rowStartLine = 1;   // 当前这一行数据起始的物理行

        for (int i = 0; i < n; i++) {
            char c = text.charAt(i);

            if (inQuotes) {
                if (c == '"') {
                    // "" = 转义的双引号;单个 " = 结束引号段
                    if (i + 1 < n && text.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else if (c == '\r' || c == '\n') {
                    // 引号内的换行属于字段内容(行备注里可能真有);行号照样要往前推
                    if (c == '\r' && i + 1 < n && text.charAt(i + 1) == '\n') i++;
                    line++;
                    field.append('\n');
                } else {
                    field.append(c);
                }
            } else if (c == '"') {
                // 只有在字段起始位置的引号才是"包裹符",否则按普通字符处理
                inQuotes = true;
            } else if (c == ',') {
                row.add(takeField(field));
            } else if (c == '\r' || c == '\n') {
                if (c == '\r' && i + 1 < n && text.charAt(i + 1) == '\n') i++;
                line++;
                row.add(takeField(field));
                rows.add(new Row(rowStartLine, row));
                row = new ArrayList<>();
                rowStartLine = line;
            } else {
                field.append(c);
            }
        }
        // 文件不以换行结尾时,收尾这一行
        if (field.length() > 0 || !row.isEmpty()) {
            row.add(takeField(field));
            rows.add(new Row(rowStartLine, row));
        }
        return rows;
    }

    private static String takeField(StringBuilder field) {
        if (field.length() > MAX_FIELD_LENGTH) {
            throw new IllegalArgumentException("单个单元格过长(超过 " + MAX_FIELD_LENGTH + " 字符),请检查是否选错了文件");
        }
        String s = field.toString();
        field.setLength(0);
        return s;
    }

    /** 整行都是空白(含只有逗号的空行)→ 视为空行 */
    public static boolean isBlankRow(List<String> cells) {
        if (cells == null) return true;
        for (String c : cells) {
            if (c != null && !c.isBlank()) return false;
        }
        return true;
    }
}
