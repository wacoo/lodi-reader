package com.gugesoft.lodibookreader;

import android.os.Bundle;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.Button;

import androidx.appcompat.app.AppCompatActivity;

public class SettingsActivity extends AppCompatActivity {

    private SettingsManager settings;
    private TextView previewText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        settings = new SettingsManager(this);

        previewText = findViewById(R.id.previewText);
        applyPreview(); // initial preview

        // ===== TIMER =====
        SeekBar timerSeek = findViewById(R.id.seekTimer);
        TextView timerLabel = findViewById(R.id.timerLabel);

        timerSeek.setMax(120); // 1–120 minutes
        int timerMin = (int) (settings.getTimerMs() / (60 * 1000));
        if (timerMin < 1) timerMin = 1; // enforce minimum
        timerSeek.setProgress(timerMin);
        timerLabel.setText("Timer: " + timerMin + " min");

        timerSeek.setOnSeekBarChangeListener(new SimpleSeekBar(value -> {
            if (value < 1) value = 1; // enforce minimum
            settings.setTimerMs(value * 60 * 1000L); // store minutes in ms
            timerLabel.setText("Timer: " + value + " min");
        }));

        // ===== FADE =====
        SeekBar fadeSeek = findViewById(R.id.seekFade);
        TextView fadeLabel = findViewById(R.id.fadeLabel);

        fadeSeek.setMax(60); // up to 60 seconds
        int fadeSec = (int) (settings.getFadeMs() / 1000);
        fadeSeek.setProgress(fadeSec);
        fadeLabel.setText("Fade: " + fadeSec + " sec");

        fadeSeek.setOnSeekBarChangeListener(new SimpleSeekBar(value -> {
            settings.setFadeMs(value * 1000L);
            fadeLabel.setText("Fade: " + value + " sec");
        }));

        // ===== SHAKE =====
        SeekBar shakeSeek = findViewById(R.id.seekShake);
        TextView shakeLabel = findViewById(R.id.shakeLabel);

        shakeSeek.setMax(30);
        int shakeVal = (int) settings.getShakeIntensity();
        shakeSeek.setProgress(shakeVal);
        shakeLabel.setText("Shake: " + shakeVal);

        shakeSeek.setOnSeekBarChangeListener(new SimpleSeekBar(value -> {
            settings.setShakeIntensity(value);
            shakeLabel.setText("Shake: " + value);
        }));

        // ===== FONT SIZE =====
        SeekBar fontSeek = findViewById(R.id.seekFont);
        TextView fontLabel = findViewById(R.id.fontLabel);

        fontSeek.setMax(40);
        int fontSize = settings.getFontSize();
        fontSeek.setProgress(fontSize);
        fontLabel.setText("Font Size: " + fontSize);

        fontSeek.setOnSeekBarChangeListener(new SimpleSeekBar(value -> {
            settings.setFontSize(value);
            fontLabel.setText("Font Size: " + value);
            applyPreview();
        }));

        // ===== PAPER COLOR =====
        Spinner colorSpinner = findViewById(R.id.colorSpinner);
        TextView colorLabel = findViewById(R.id.colorLabel);

        String[] colors = {"White", "Sepia", "Ivory", "Night"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, colors);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        colorSpinner.setAdapter(adapter);

        int currentColor = settings.getPaperColor();
        int selectedIndex = 0;
        switch (currentColor) {
            case 0xFFF4ECD8: selectedIndex = 1; break; // Sepia
            case 0xFFFFF8E7: selectedIndex = 2; break; // Ivory
            case 0xFF000000: selectedIndex = 3; break; // Night
            default: selectedIndex = 0; // White
        }
        colorSpinner.setSelection(selectedIndex);
        colorLabel.setText("Paper Color: " + colors[selectedIndex]);

        colorSpinner.setOnItemSelectedListener(new SimpleItemSelectedListener(position -> {
            int chosenPaper, chosenFont;
            switch (position) {
                case 1: chosenPaper = 0xFFF4ECD8; chosenFont = 0xFF000000; break; // Sepia
                case 2: chosenPaper = 0xFFFFF8E7; chosenFont = 0xFF000000; break; // Ivory
                case 3: chosenPaper = 0xFF000000; chosenFont = 0xFFFFFFFF; break; // Night
                default: chosenPaper = 0xFFFFFFFF; chosenFont = 0xFF000000; break; // White
            }
            settings.setPaperColor(chosenPaper);
            settings.setFontColor(chosenFont);
            colorLabel.setText("Paper Color: " + colors[position]);
            applyPreview();
        }));

        // ===== RESET BUTTON =====
        Button resetBtn = findViewById(R.id.resetBtn);
        resetBtn.setOnClickListener(v -> {
            settings.setTimerMs(60 * 60 * 1000L); // default 60 minutes
            settings.setFadeMs(10 * 1000);
            settings.setShakeIntensity(12.0f);
            settings.setFontSize(16);
            settings.setPaperColor(0xFFFFFFFF);
            settings.setFontColor(0xFF000000);
            settings.setFontFamily("sans-serif");

            timerSeek.setProgress(60);
            fadeSeek.setProgress(10);
            shakeSeek.setProgress(12);
            fontSeek.setProgress(16);
            colorSpinner.setSelection(0);

            timerLabel.setText("Timer: 60 min");
            fadeLabel.setText("Fade: 10 sec");
            shakeLabel.setText("Shake: 12");
            fontLabel.setText("Font Size: 16");
            colorLabel.setText("Paper Color: White");

            applyPreview();
        });
    }

    private void applyPreview() {
        previewText.setBackgroundColor(settings.getPaperColor());
        previewText.setTextColor(settings.getFontColor());
        previewText.setTextSize(settings.getFontSize());
    }
}
