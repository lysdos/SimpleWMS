package com.wms.controller;

import com.wms.model.ApiResponse;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 参数错误 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> badArg(IllegalArgumentException e) {
        // 业务约定:消息以 "码不存在" 或 "出库单不存在, id" 开头 → 资源不存在 → 404
        String msg = e.getMessage() == null ? "" : e.getMessage();
        if (msg.startsWith("码不存在") || msg.startsWith("出库单不存在, id=")
                || msg.startsWith("入库单不存在, id=") || msg.startsWith("用户不存在")) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.fail(404, msg));
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.fail(400, msg));
    }

    /** 业务规则冲突(库存不足等) */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiResponse<Void>> conflict(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.fail(409, e.getMessage()));
    }

    /** 资源不存在 → 404。此前 controller 直接 return ApiResponse.fail(404,..) 会落成 HTTP 200,见 NotFoundException */
    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> notFound(NotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.fail(404, e.getMessage()));
    }

    /** 业务上语义合法但拒绝(例如盘点单中无此码) → 422 */
    @ExceptionHandler(UnprocessableException.class)
    public ResponseEntity<ApiResponse<Void>> unprocessable(UnprocessableException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(ApiResponse.fail(422, e.getMessage()));
    }

    /** 数据库约束冲突(SKU 重复等) */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Void>> dataIntegrity(DataIntegrityViolationException e) {
        String msg = e.getMostSpecificCause() != null
                ? e.getMostSpecificCause().getMessage()
                : e.getMessage();
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.fail(409, "数据冲突: " + (msg == null ? "" : msg)));
    }

    /** 请求体格式错 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> notReadable(HttpMessageNotReadableException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.fail(400, "请求体格式错误"));
    }

    /* ---------------------------------------------------------------
     * 框架级异常:必须显式映射,否则会掉进下面的 catch-all 被伪装成 500,
     * 导致"方法用错 / 路径拼错 / Content-Type 不对"这类客户端问题
     * 在排查时全部表现为"服务器异常"。
     * --------------------------------------------------------------- */

    /** 路径匹配但方法不支持(例如某路径只声明了 PUT/DELETE,却被 GET) → 405 */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> methodNotAllowed(HttpRequestMethodNotSupportedException e) {
        String supported = e.getSupportedHttpMethods() == null ? "" : e.getSupportedHttpMethods().toString();
        String msg = "该接口不支持 " + e.getMethod() + " 方法"
                + (supported.isEmpty() ? "" : ",支持: " + supported);
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(ApiResponse.fail(405, msg));
    }

    /** 无此路径(接口拼错、或静态资源不存在) → 404 */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> noResource(NoResourceFoundException e) {
        // getResourcePath() 不带前导斜杠,补上才像一条路径
        String path = e.getResourcePath();
        String msg = "接口不存在: " + (path.isEmpty() || path.charAt(0) == '/' ? path : "/" + path);
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.fail(404, msg));
    }

    /** Content-Type 不受支持(应为 application/json) → 415 */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> mediaTypeNotSupported(HttpMediaTypeNotSupportedException e) {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(ApiResponse.fail(415, "不支持的 Content-Type: " + e.getContentType()));
    }

    /** 路径变量/查询参数类型不对(例如 /api/users/abc) → 400 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> typeMismatch(MethodArgumentTypeMismatchException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.fail(400, "参数类型错误: " + e.getName()));
    }

    /** 缺少必填查询参数 → 400 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> missingParam(MissingServletRequestParameterException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.fail(400, "缺少必填参数: " + e.getParameterName()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> ex(Exception e) {
        // 服务端留全量堆栈,只对客户端回一句通用提示——避免把 SQL/驱动
        // 细节(表名/列/连接字符串)外漏给低权限或匿名调用方。
        e.printStackTrace();
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.fail(500, "服务器异常,请稍后重试"));
    }
}