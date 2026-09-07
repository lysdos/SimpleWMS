package com.wms.pda.util;

import android.content.Context;
import android.media.AudioManager;
import android.media.ToneGenerator;

/** 蜂鸣提示:成功/失败两音。 */
public class BeepUtil {

    private static ToneGenerator tone;

    public static void init(Context ctx) {
        if (tone == null) {
            try {
                tone = new ToneGenerator(AudioManager.STREAM_MUSIC, 80);
            } catch (RuntimeException ignored) {}
        }
    }

    public static void success() {
        if (tone != null) tone.startTone(ToneGenerator.TONE_PROP_BEEP, 120);
    }

    public static void error() {
        if (tone != null) {
            tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 200);
            try { Thread.sleep(220); } catch (InterruptedException ignored) {}
            tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 200);
        }
    }

    public static void release() {
        if (tone != null) { tone.release(); tone = null; }
    }
}
