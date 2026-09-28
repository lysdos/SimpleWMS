package com.wms.controller;

/**
 * 请求过于频繁(当前唯一用途:登录失败次数超限) → 由
 * {@link GlobalExceptionHandler} 映射成 HTTP 429。
 *
 * 为什么不复用现成的两个异常:
 *  - {@code IllegalArgumentException} → 400,而登录接口对 401 有客户端硬编码文案
 *    (web auth.js 把 401 一律显示成「用户名或密码错误」),用 401 会把限流提示盖掉;
 *  - {@code IllegalStateException} → {@code AuthController.login} 在本地把它映射成 **403**
 *    (账号禁用走的就是那条),语义也不对。
 * 单独一个 429 才说得清"你现在被限流了,稍后再试"。
 */
public class TooManyRequestsException extends RuntimeException {
    public TooManyRequestsException(String message) {
        super(message);
    }
}
