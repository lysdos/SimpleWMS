package com.wms.pda.util;

import android.content.Context;
import android.widget.Toast;

/** 统一 Toast,避免页面到处写 Toast.makeText。 */
public class ToastUtil {
    public static void show(Context ctx, String msg) {
        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show();
    }
    public static void error(Context ctx, String msg) {
        Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show();
    }
}
