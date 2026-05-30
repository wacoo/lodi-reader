package com.gugesoft.lodibookreader;

import android.view.View;
import android.widget.AdapterView;

public class SimpleItemSelectedListener implements AdapterView.OnItemSelectedListener {

    public interface Callback {
        void onItemSelected(int position);
    }

    private final Callback callback;

    public SimpleItemSelectedListener(Callback callback) {
        this.callback = callback;
    }

    @Override
    public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
        callback.onItemSelected(position);
    }

    @Override
    public void onNothingSelected(AdapterView<?> parent) {
        // no-op
    }
}
