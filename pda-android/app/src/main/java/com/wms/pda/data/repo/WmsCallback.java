package com.wms.pda.data.repo;

/**
 * 异步仓库操作的统一回调。
 * 写方法 onSuccess(T) 携带后端返回的实体(更新后的单 / 列表);
 * 读方法 onSuccess(List<T>) 携带完整结果集;
 * onError(code, msg) 用于业务错误(404/409/422)+ 网络异常(-1)。
 */
public interface WmsCallback<T> {
    void onSuccess(T value);
    void onError(int code, String msg);
}