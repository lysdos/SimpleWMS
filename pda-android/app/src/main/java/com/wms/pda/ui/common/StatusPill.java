package com.wms.pda.ui.common;

import android.view.View;
import android.widget.TextView;

import com.wms.pda.R;
import com.wms.pda.data.model.Enums;

/**
 * 单据状态胶囊:文案 + 背景色。
 *
 * 文案在这里按资源翻(**不是**从枚举上取)—— 原先 Enums.DocStatus 每个值挂着一个中文
 * label,模型层持有界面文案,切英文时这里永远是中文。现在枚举只留状态值,文案归这儿。
 */
public final class StatusPill {
    private StatusPill() {}

    public static void apply(TextView tv, Enums.DocStatus status) {
        if (status == null) return;
        int textRes;
        int bg;
        switch (status) {
            case PENDING:   textRes = R.string.doc_pending;   bg = R.drawable.bg_pill_warning; break;
            case DONE:      textRes = R.string.doc_done;      bg = R.drawable.bg_pill_success; break;
            case DRAFT:     textRes = R.string.doc_draft;     bg = R.drawable.bg_pill_draft;   break;
            case CANCELLED: textRes = R.string.doc_cancelled; bg = R.drawable.bg_pill_draft;   break;
            default:        textRes = 0;                      bg = R.drawable.bg_pill;         break;
        }
        // 兜底(四个值都列全了,正常走不到):显示原始枚举名,别把信息吞掉
        tv.setText(textRes == 0 ? status.name() : tv.getContext().getString(textRes));
        tv.setBackgroundResource(bg);
        tv.setVisibility(View.VISIBLE);
    }
}
