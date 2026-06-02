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
    
    // Static state to persist across service restarts within the same process
    private static int originalVolume = -1;
    private static int lastSetAutoVolume = -1;
    private static long lastAutoSetTime = 0;
    
    private static final long AUTO_CHANGE_COOLDOWN_MS = 5000; // 5 seconds to be safe from system lag
    private static final String PREF_NAME = "VolumeControllerPrefs";
    private static final String KEY_BASELINE = "baseline_volume";

    public VolumeController(Context context) {
        this.context = context.getApplicationContext();
        audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        
        if (originalVolume == -1) {
            SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            originalVolume = prefs.getInt(KEY_BASELINE, -1);
            Log.d(TAG, "Loaded baseline from prefs: " + originalVolume);
        }
        
        // Ensure we don't immediately adopt current volume on cold boot if we have a saved one
        if (lastAutoSetTime == 0) {
            lastAutoSetTime = System.currentTimeMillis();
        }
    }

    private void saveBaseline(int volume) {
        if (volume <= 1) return; // Never adopt near-zero as a baseline
        
        // If we already have a baseline, only adopt a new one if it's significantly different 
        // OR if we haven't set an auto volume recently.
        originalVolume = volume;
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit().putInt(KEY_BASELINE, volume).apply();
        Log.d(TAG, "New baseline volume saved: " + volume);
    }

    /**
     * Adopts the current system volume as the new baseline if it was adjusted manually.
     */
    public synchronized void updateBaselineIfManual() {
        int current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        if (current <= 1) return;

        long now = System.currentTimeMillis();
        long timeSinceAuto = now - lastAutoSetTime;
        
        if (timeSinceAuto < AUTO_CHANGE_COOLDOWN_MS) {
            // During auto-period, only adopt if user moved it significantly AWAY from our target.
            // If they moved it TOWARDS zero, we might be fading, so be careful.
            if (lastSetAutoVolume != -1 && Math.abs(current - lastSetAutoVolume) > 2) {
                saveBaseline(current);
                Log.d(TAG, "Manual override detected during auto-period. New baseline: " + current);
            }
        } else {
            // Idle state. Adopting change as baseline if it's different.
            if (originalVolume == -1 || current != originalVolume) {
                saveBaseline(current);
            }
        }
    }

    public synchronized void captureBaselineVolume() {
        int current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        long timeSinceAuto = System.currentTimeMillis() - lastAutoSetTime;
        
        // Only capture if we are not currently faded (timeSinceAuto > cooldown)
        // OR if current volume is higher than our recorded baseline.
        if (current > 1) {
            if (timeSinceAuto > AUTO_CHANGE_COOLDOWN_MS || current > originalVolume || originalVolume == -1) {
                saveBaseline(current);
                Log.d(TAG, "Captured baseline volume: " + current);
            }
        }
    }

    public synchronized void applyFade(long remainingMs, long totalFadeDurationMs) {
        // Check for manual changes before applying next fade step
        updateBaselineIfManual();
        
        if (originalVolume <= 0) {
            originalVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) / 2;
        }
        
        double ratio = (double) remainingMs / totalFadeDurationMs;
        int target = (int) Math.ceil((double) originalVolume * ratio);
        target = Math.max(0, Math.min(target, originalVolume));

        int current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        
        // Update intent tracking
        lastSetAutoVolume = target;
        lastAutoSetTime = System.currentTimeMillis();

        if (target != current) {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0);
        }
    }

    public synchronized void restoreVolume() {
        // IMPORTANT: In handleShake, we call restoreVolume.
        // We must ensure originalVolume is actually a valid "normal" volume.
        
        if (originalVolume > 0) {
            int current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
            
            Log.d(TAG, "Restoring volume to baseline: " + originalVolume + " (current: " + current + ")");
            
            // Set first, update tracking last
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, originalVolume, 0);
            
            lastSetAutoVolume = originalVolume;
            lastAutoSetTime = System.currentTimeMillis();
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
}
