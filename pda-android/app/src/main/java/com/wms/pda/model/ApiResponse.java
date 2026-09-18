package com.wms.pda.model;

/** 后端 ApiResponse 镜像。 */
public class ApiResponse<T> {
    public int code;
    public String message;
    public T data;
}
