package com.gugesoft.lodibookreader;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.util.Log;

public class VolumeController {
    private static final String TAG = "VolumeController";
    private final AudioManager audioManager;
    private final Context context;
    
    // Persistent state across activity instances
    private static int originalVolume = -1;
    private static long lastAutoSetTime = 0;
    private static int lastSetAutoVolume = -1;
    
    private static final long AUTO_CHANGE_COOLDOWN_MS = 2000;
    private static final String PREF_NAME = "VolumeControllerPrefs";
    private static final String KEY_BASELINE = "baseline_volume";

    public VolumeController(Context context) {
        this.context = context.getApplicationContext();
        audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        
        if (originalVolume == -1) {
            SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            originalVolume = prefs.getInt(KEY_BASELINE, -1);
        }
        updateBaselineIfManual();
    }

    private void saveBaseline(int volume) {
        if (volume <= 0) return;
        originalVolume = volume;
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit().putInt(KEY_BASELINE, volume).apply();
    }

    /**
     * Checks if the current volume was changed manually by the user
     * and adopts it as the new baseline if so.
     */
    public synchronized void updateBaselineIfManual() {
        int current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        if (current > 0) {
            // If the current volume differs from our last auto-set volume and we aren't in cooldown, it's manual
            if (current != lastSetAutoVolume && !isAutoChanging()) {
                saveBaseline(current);
                Log.d(TAG, "Manual volume change detected. Updated baseline to: " + current);
            }
        }
    }

    public synchronized void captureBaselineVolume() {
        int current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        if (current > 0) {
            saveBaseline(current);
            Log.d(TAG, "Baseline captured: " + current);
        }
    }

    public synchronized void applyFade(long remainingMs, long totalFadeDurationMs) {
        updateBaselineIfManual();
        
        if (originalVolume <= 0) {
            originalVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) / 2;
        }
        
        int target = (int) Math.ceil((double) originalVolume * remainingMs / totalFadeDurationMs);
        target = Math.max(0, Math.min(target, originalVolume));

        lastAutoSetTime = System.currentTimeMillis();
        lastSetAutoVolume = target;
        
        if (target != audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)) {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0);
        }
    }

    public synchronized void restoreVolume() {
        updateBaselineIfManual();
        if (originalVolume > 0) {
            Log.d(TAG, "Restoring volume to: " + originalVolume);
            lastAutoSetTime = System.currentTimeMillis();
            lastSetAutoVolume = originalVolume;
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, originalVolume, 0);
        }
    }

    public void playBell() {
        try {
            AudioAttributes aa = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build();
            
            MediaPlayer mp = MediaPlayer.create(context, R.raw.bell, aa, audioManager.generateAudioSessionId());
            if (mp != null) {
                mp.setVolume(1.0f, 1.0f);
                mp.start();
                mp.setOnCompletionListener(MediaPlayer::release);
            }
        } catch (Exception e) { 
            Log.e(TAG, "Error playing bell", e);
        }
    }

    public boolean isAutoChanging() {
        return (System.currentTimeMillis() - lastAutoSetTime < AUTO_CHANGE_COOLDOWN_MS);
    }
}
