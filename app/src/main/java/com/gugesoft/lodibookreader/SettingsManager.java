package com.gugesoft.lodibookreader;

import android.content.Context;
import android.content.SharedPreferences;

public class SettingsManager {

    private static final String PREF_NAME = "app_settings";

    private static final String KEY_TIMER = "timer_ms";
    private static final String KEY_FADE = "fade_ms";
    private static final String KEY_SHAKE = "shake_intensity";
    private static final String KEY_FONT_SIZE = "font_size";
    private static final String KEY_PAPER_COLOR = "paper_color";
    private static final String KEY_FONT_COLOR = "font_color";
    private static final String KEY_FONT_FAMILY = "font_family";
    private static final String KEY_LAST_BOOK = "last_book_uri";
    private static final String KEY_LAST_SENTENCE_PREFIX = "last_sentence_";
    private SharedPreferences prefs;

    public SettingsManager(Context context) {
        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }
    public void setLastOpenedBookUri(String uri) {
        prefs.edit().putString(KEY_LAST_BOOK, uri).apply();
    }

    public String getLastOpenedBookUri() {
        return prefs.getString(KEY_LAST_BOOK, null);
    }
    // Save last read sentence index for a specific book
    public void setLastReadSentenceIndex(String bookUri, int index) {
        prefs.edit().putInt(KEY_LAST_SENTENCE_PREFIX + bookUri, index).apply();
    }

    // Retrieve last read sentence index for a specific book
    public int getLastReadSentenceIndex(String bookUri) {
        return prefs.getInt(KEY_LAST_SENTENCE_PREFIX + bookUri, 0); // default 0
    }
    // ===== TIMER =====
    public long getTimerMs() {
        return prefs.getLong(KEY_TIMER, 60 * 1000);
    }
    public void setTimerMs(long value) {
        prefs.edit().putLong(KEY_TIMER, value).apply();
    }

    // ===== FADE =====
    public long getFadeMs() {
        return prefs.getLong(KEY_FADE, 10 * 1000);
    }
    public void setFadeMs(long value) {
        prefs.edit().putLong(KEY_FADE, value).apply();
    }

    // ===== SHAKE =====
    public float getShakeIntensity() {
        return prefs.getFloat(KEY_SHAKE, 12.0f);
    }
    public void setShakeIntensity(float value) {
        prefs.edit().putFloat(KEY_SHAKE, value).apply();
    }

    // ===== FONT SIZE =====
    public int getFontSize() {
        return prefs.getInt(KEY_FONT_SIZE, 16);
    }
    public void setFontSize(int value) {
        prefs.edit().putInt(KEY_FONT_SIZE, value).apply();
    }

    // ===== PAPER COLOR =====
    public int getPaperColor() {
        return prefs.getInt(KEY_PAPER_COLOR, 0xFFFFFFFF); // default white
    }
    public void setPaperColor(int value) {
        prefs.edit().putInt(KEY_PAPER_COLOR, value).apply();
    }

    // ===== FONT COLOR =====
    public int getFontColor() {
        return prefs.getInt(KEY_FONT_COLOR, 0xFF000000); // default black
    }
    public void setFontColor(int value) {
        prefs.edit().putInt(KEY_FONT_COLOR, value).apply();
    }

    // ===== FONT FAMILY =====
    public String getFontFamily() {
        return prefs.getString(KEY_FONT_FAMILY, "sans-serif");
    }
    public void setFontFamily(String value) {
        prefs.edit().putString(KEY_FONT_FAMILY, value).apply();
    }
}
