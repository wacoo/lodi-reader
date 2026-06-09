package com.gugesoft.lodibookreader;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.util.Log;

public class VolumeController {
    private static final String TAG = "VolumeController";
    private final AudioManager audioManager;
    private final Context context;
    
    private int baselineVolume = -1;
    private int lastSetAutoVolume = -1;
    private long lastAutoSetTime = 0;
    
    private static final long AUTO_CHANGE_COOLDOWN_MS = 1000; 

    public VolumeController(Context context) {
        this.context = context.getApplicationContext();
        audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
    }

    public synchronized void setBaselineVolume(int volume) {
        if (volume < 0) return;
        this.baselineVolume = volume;
        Log.d(TAG, "Baseline volume (Original) set to: " + volume);
    }

    /**
     * Detects if the volume was changed manually.
     */
    public synchronized void handlePossibleManualChange() {
        int current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        long now = System.currentTimeMillis();
        
        // If we recently set the volume automatically (e.g. during a fade)
        if (now - lastAutoSetTime < AUTO_CHANGE_COOLDOWN_MS) {
            // Check if the current volume differs from what we just set.
            // If it does, the user manually adjusted it.
            if (lastSetAutoVolume != -1 && current != lastSetAutoVolume) {
                setBaselineVolume(current);
                Log.d(TAG, "Manual change detected during fade. New baseline: " + current);
            }
        } else {
            // Outside of automated changes, any difference from our baseline is manual.
            if (baselineVolume != -1 && current != baselineVolume) {
                setBaselineVolume(current);
                Log.d(TAG, "Manual change detected. New baseline: " + current);
            }
        }
    }

    public synchronized void captureInitialVolume() {
        int current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        setBaselineVolume(current);
        lastSetAutoVolume = current;
    }

    public synchronized void applyFade(long remainingMs, long totalFadeDurationMs) {
        if (baselineVolume <= 0) return;
        
        double ratio = (double) remainingMs / totalFadeDurationMs;
        int target = (int) Math.round((double) baselineVolume * ratio);
        target = Math.max(0, Math.min(target, baselineVolume));

        int current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        
        if (target != current) {
            lastSetAutoVolume = target;
            lastAutoSetTime = System.currentTimeMillis();
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0);
        }
    }

    public synchronized void restoreVolume() {
        if (baselineVolume >= 0) {
            lastSetAutoVolume = baselineVolume;
            lastAutoSetTime = System.currentTimeMillis();
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, baselineVolume, 0);
            Log.d(TAG, "Volume restored to baseline: " + baselineVolume);
        }
    }

    public void playBell() {
        try {
            AudioAttributes aa = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();

            MediaPlayer mp = MediaPlayer.create(context, R.raw.bell);
            if (mp != null) {
                mp.setAudioAttributes(aa);
                mp.start();
                mp.setOnCompletionListener(MediaPlayer::release);
            }
        } catch (Exception e) { 
            Log.e(TAG, "Error playing bell", e);
        }
    }
}
