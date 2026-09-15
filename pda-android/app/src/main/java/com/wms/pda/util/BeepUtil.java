package com.wms.pda.util;

import android.content.Context;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 蜂鸣提示:成功单音 / 失败双音。
 * 所有提示音进队列按"音长+间隔"依次播放,避免连续扫码时提示音互相吞掉。
 */
public class BeepUtil {

    private static ToneGenerator tone;
    private static final Handler main = new Handler(Looper.getMainLooper());
    /** 两个提示音之间的静音间隔 */
    private static final int GAP_MS = 140;
    /** 待播队列,元素 = {toneType, durationMs} */
    private static final Deque<int[]> queue = new ArrayDeque<>();
    private static boolean draining = false;

    public static void init(Context ctx) {
        if (tone == null) {
            try {
                tone = new ToneGenerator(AudioManager.STREAM_MUSIC, 80);
            } catch (RuntimeException ignored) {}
        }
    }

    public static void success() {
        enqueue(ToneGenerator.TONE_PROP_BEEP, 120);
    }

    public static void error() {
        enqueue(ToneGenerator.TONE_PROP_BEEP2, 200);
        enqueue(ToneGenerator.TONE_PROP_BEEP2, 200);
    }

    private static synchronized void enqueue(int type, int durationMs) {
        queue.addLast(new int[]{type, durationMs});
        if (!draining) drain();
    }

    private static void drain() {
        int[] item;
        synchronized (BeepUtil.class) {
            item = queue.pollFirst();
            if (item == null) { draining = false; return; }
            draining = true;
        }
        ToneGenerator t = tone;
        if (t != null) {
            try { t.startTone(item[0], item[1]); } catch (Throwable ignored) {}
        }
        main.postDelayed(BeepUtil::drain, item[1] + GAP_MS);
    }

    public static void release() {
        main.removeCallbacksAndMessages(null);
        synchronized (queue) {
            queue.clear();
            draining = false;
        }
        if (tone != null) { tone.release(); tone = null; }
    }
}
