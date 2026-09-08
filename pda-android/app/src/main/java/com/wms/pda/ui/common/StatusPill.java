package com.wms.pda.ui.common;

import android.view.View;
import android.widget.TextView;

import com.wms.pda.R;
import com.wms.pda.data.model.Enums;

/**
 * 状态药丸的背景色选择器(简单按枚举挑 drawable)。
 */
public final class StatusPill {
    private StatusPill() {}

    public static void apply(TextView tv, Enums.DocStatus status) {
        if (status == null) return;
        tv.setText(status.label);
        int bg;
        switch (status) {
            case PENDING:   bg = R.drawable.bg_pill_warning; break;
            case DONE:      bg = R.drawable.bg_pill_success; break;
            case DRAFT:     bg = R.drawable.bg_pill_draft;   break;
            case CANCELLED: bg = R.drawable.bg_pill_draft;   break;
            default:        bg = R.drawable.bg_pill;         break;
        }
        tv.setBackgroundResource(bg);
        tv.setVisibility(View.VISIBLE);
    }
}
