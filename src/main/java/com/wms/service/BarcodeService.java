package com.wms.service;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * 条码生成 — 用 ZXing 生成 CODE_128 PNG(支持数字 + 字母 + 特殊字符)。
 *
 * 24 位编码含字母,CODE_128 是最合适的选择(QR 码字符密度太高不便扫描,
 * CODE_39 字符集受限)。条码下方再叠一行文字编码方便人眼读取。
 */
@Service
public class BarcodeService {

    public byte[] renderCode128Png(String code, int width, int height) throws Exception {
        Map<EncodeHintType, Object> hints = new HashMap<>();
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        hints.put(EncodeHintType.MARGIN, 1);

        BitMatrix matrix = new MultiFormatWriter().encode(
                code, BarcodeFormat.CODE_128, width, height, hints);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        MatrixToImageWriter.writeToStream(matrix, "PNG", out);
        return out.toByteArray();
    }

    /** 默认 320×100 的条码 PNG,适合 24 位编码 */
    public byte[] renderCode128Png(String code) throws Exception {
        return renderCode128Png(code, 320, 100);
    }
}
