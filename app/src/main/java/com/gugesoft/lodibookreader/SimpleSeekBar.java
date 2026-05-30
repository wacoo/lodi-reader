package com.gugesoft.lodibookreader;

import android.widget.SeekBar;

public class SimpleSeekBar implements SeekBar.OnSeekBarChangeListener {

    public interface OnChange {
        void onChanged(int value);
    }

    private OnChange callback;

    public SimpleSeekBar(OnChange callback) {
        this.callback = callback;
    }

    @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
        callback.onChanged(progress);
    }

    @Override public void onStartTrackingTouch(SeekBar seekBar) {}
    @Override public void onStopTrackingTouch(SeekBar seekBar) {}
}