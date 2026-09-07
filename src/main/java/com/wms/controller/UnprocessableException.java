package com.wms.controller;

/**
 * 业务上"语义可处理但拒绝"的异常 → 映射为 HTTP 422 Unprocessable Entity。
 *
 * 与 IllegalStateException(状态机)不同:这里指请求格式合法,但业务逻辑不允许操作
 * (例如:扫到的码不在当前盘点单的明细快照中)。
 */
public class UnprocessableException extends RuntimeException {
    public UnprocessableException(String message) {
        super(message);
    }
}