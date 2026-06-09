package com.gugesoft.lodibookreader;

import android.os.Handler;
import android.os.Looper;

public class LodiStepTimer {

    public interface TimerListener {
        void onTick(long remainingMs);
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TimerListener listener;

    private long remainingTimeMs;
    private boolean isRunning = false;
    private long resetTimeMs = 60 * 1000; // Default 1 minute
    private final VolumeController volumeController;
    private final Runnable onFinish;

    // Fade duration
    private long fadeDurationMs = 10 * 1000;

    public LodiStepTimer(VolumeController vc, Runnable finish) {
        this.volumeController = vc;
        this.onFinish = finish;
    }

    public void setTimerListener(TimerListener listener) {
        this.listener = listener;
    }

    private final Runnable timerRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isRunning) return;

            remainingTimeMs -= 1000;

            if (listener != null) {
                listener.onTick(remainingTimeMs);
            }

            // Smooth fade during the fade duration
            if (remainingTimeMs <= fadeDurationMs && remainingTimeMs >= 0) {
                volumeController.applyFade(remainingTimeMs, fadeDurationMs);
            }

            if (remainingTimeMs <= 0) {
                isRunning = false;
                // Ensure volume is restored to baseline when timer finishes
                volumeController.restoreVolume();
                if (onFinish != null) {
                    onFinish.run();
                }
                return;
            }

            handler.postDelayed(this, 1000);
        }
    };

    public void start(long durationMs) {
        stop();
        this.remainingTimeMs = durationMs;
        this.isRunning = true;
        // We no longer capture baseline here, it's managed by VolumeController
        // based on app start and manual changes.
        handler.post(timerRunnable);
    }

    public void handleShake() {
        if (!isRunning) return;

        boolean wasInFade = remainingTimeMs <= fadeDurationMs;

        // Reset timer to the configured reset time
        remainingTimeMs = resetTimeMs;

        if (listener != null) {
            listener.onTick(remainingTimeMs);
        }

        if (wasInFade) {
            // Restore volume and play bell ONLY if we were in the fade period
            volumeController.restoreVolume();
            volumeController.playBell();
        }
    }

    public void stop() {
        if (isRunning) {
            isRunning = false;
            handler.removeCallbacksAndMessages(null);
            // Ensure volume is back to baseline when timer is stopped
            volumeController.restoreVolume();
        }
    }

    public boolean isRunning() {
        return isRunning;
    }

    public long getRemainingTimeMs() {
        return remainingTimeMs;
    }

    public void setResetTimeMs(long resetTimeMs) {
        this.resetTimeMs = resetTimeMs;
    }

    public void setFadeStartMs(long fadeDurationMs) {
        this.fadeDurationMs = fadeDurationMs;
    }
}
