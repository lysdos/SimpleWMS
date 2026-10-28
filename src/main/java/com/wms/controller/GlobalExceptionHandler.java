package com.wms.controller;

import com.wms.model.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 参数错误 → 400。
     * 注意:"资源不存在"**不要**走这里 —— 抛 {@link NotFoundException}(→404)。
     * 这里曾用消息文本前缀({"码不存在" / "出库单不存在, id=" / ...})把部分 400 分流成 404,
     * 那等于让状态码取决于提示语文案:改一句话就可能悄悄改掉响应码,而不在清单里的
     * "xx不存在" 又会落到 400。2026-09-18 已把那些抛出点统一改抛 NotFoundException。
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> badArg(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.fail(400, e.getMessage()));
    }

    /** 业务规则冲突(库存不足等) */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiResponse<Void>> conflict(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.fail(409, e.getMessage()));
    }

    /** 请求过于频繁(登录失败次数超限) → 429。单独一个状态码,才不会被客户端当成"密码错"或"账号禁用" */
    @ExceptionHandler(TooManyRequestsException.class)
    public ResponseEntity<ApiResponse<Void>> tooMany(TooManyRequestsException e) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(ApiResponse.fail(429, e.getMessage()));
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

    /**
     * 数据库约束冲突(SKU 重复、字段超长等)。
     *
     * 只回一句通用文案:驱动原文形如 `Data too long for column 'sku' at row 1` /
     * `Duplicate entry 'X' for key 'uk_product_sku'`,带着表名、列名、约束名 ——
     * 那是给运维看的,不该出现在任何调用方能拿到的响应里(导出类接口此前还是匿名可读)。
     * 原文记日志。
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Void>> dataIntegrity(DataIntegrityViolationException e) {
        log.warn("数据约束冲突: {}", e.getMostSpecificCause() != null
                ? e.getMostSpecificCause().getMessage() : e.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.fail(409, "数据冲突,请检查字段长度或唯一性约束"));
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

    /**
     * 上传文件超过大小限制 → 413。
     *
     * **必须显式映射**:没有这条时它会掉进下面的 catch-all,变成 500「服务器异常」——
     * 用户传了个大文件却被告知服务器坏了,没人会想到是文件太大。
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> tooLarge(MaxUploadSizeExceededException e) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(ApiResponse.fail(413, "文件过大,请拆分后重试(单个文件上限 10MB)"));
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