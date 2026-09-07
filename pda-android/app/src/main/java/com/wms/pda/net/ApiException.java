package com.wms.pda.net;

/** API 调用异常。 */
public class ApiException extends Exception {
    public static final int ERR_NETWORK = -1;
    public static final int ERR_HTTP = -2;
    public static final int ERR_PARSE = -3;
    public static final int ERR_AUTH = 401;

    private final int code;
    public ApiException(int code, String msg) {
        super(msg);
        this.code = code;
    }
    public int getCode() { return code; }
}
