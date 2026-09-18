package com.wms.controller;

/**
 * 资源不存在 → 映射为 HTTP 404 Not Found。
 *
 * 存在的理由:此前 controller 用 {@code return ApiResponse.fail(404, ...)} 表达"不存在",
 * 而 Spring 对普通 @RestController 返回值一律用 HTTP 200 —— 于是这类响应的状态码是 200、
 * 只有 body 里的 code=404。客户端只看 HTTP 状态码(web 的 res.ok / PDA 的 isSuccessful)
 * 就会把它当成成功,拿到 null 静默吞掉。抛异常才能让状态码真正是 404。
 */
public class NotFoundException extends RuntimeException {
    public NotFoundException(String message) {
        super(message);
    }
}
