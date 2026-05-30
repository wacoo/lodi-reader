package com.gugesoft.lodibookreader;

import android.os.SystemClock;
import android.view.View;

public abstract class DoubleClickListener implements View.OnClickListener {
    private static final long DOUBLE_CLICK_TIME_DELTA = 300;
    private long lastClickTime = 0;

    @Override
    public void onClick(View v) {
        long clickTime = SystemClock.elapsedRealtime();
        if (clickTime - lastClickTime < DOUBLE_CLICK_TIME_DELTA) {
            onDoubleClick(v);
        } else {
            onSingleClick(v);
        }
        lastClickTime = clickTime;
    }

    public abstract void onDoubleClick(View v);

    public void onSingleClick(View v) {
    }
}