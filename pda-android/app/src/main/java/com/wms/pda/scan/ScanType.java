package com.wms.pda.scan;

/** 扫码类型。EPC = 24 位 code,后端统一处理。 */
public enum ScanType {
    BARCODE,    // 光学/激光条码
    RFID_EPC    // UHF RFID(EPC 字符串 = inventory_item.code)
}