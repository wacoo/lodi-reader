package com.gugesoft.lodibookreader;

import android.content.Context;
import android.media.AudioAttributes;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;

import java.util.List;
import java.util.Locale;

public class TTSPlayer {
    private static final String TAG = "TTSPlayer";
    private TextToSpeech tts;
    private List<Sentence> sentences;
    private int currentIndex = 0;
    private boolean isReady = false;
    private boolean isPlaying = false;
    private boolean playWhenReady = false;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private OnTTSListener listener;
    private PowerManager.WakeLock wakeLock;

    public interface OnTTSListener {
        void onSentenceChanged(int index);
        void onFinished();
    }

    public TTSPlayer(Context context, OnTTSListener listener) {
        this.listener = listener;
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LodiReader:WakeLock");

        tts = new TextToSpeech(context.getApplicationContext(), status -> {
            if (status == TextToSpeech.SUCCESS) {
                tts.setLanguage(Locale.US);
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    AudioAttributes audioAttributes = new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build();
                    tts.setAudioAttributes(audioAttributes);
                }

                isReady = true;
                Log.d(TAG, "TTS Engine initialized successfully");
                if (playWhenReady) {
                    playWhenReady = false;
                    play();
                }
            } else {
                Log.e(TAG, "TTS Engine initialization failed");
            }
        });

        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override
            public void onStart(String utteranceId) {
                mainHandler.post(() -> { if (listener != null) listener.onSentenceChanged(currentIndex); });
            }
            @Override
            public void onDone(String utteranceId) {
                if (!isPlaying) return;
                currentIndex++;
                mainHandler.post(() -> playNext());
            }
            @Override
            public void onError(String utteranceId) { 
                Log.e(TAG, "TTS Utterance error: " + utteranceId);
                isPlaying = false; 
            }
        });
    }

    public boolean isPlaying() {
        return isPlaying;
    }

    public List<Sentence> getSentences() {
        return sentences;
    }

    public void loadSentences(List<Sentence> sentences) {
        this.sentences = sentences;
        Log.d(TAG, "Loaded " + (sentences != null ? sentences.size() : 0) + " sentences");
    }

    public void setCurrentIndex(int index) {
        if (sentences != null && index >= 0 && index < sentences.size()) {
            this.currentIndex = index;
        } else {
            this.currentIndex = 0;
        }
    }
    public int getCurrentIndex() { return currentIndex; }

    public void play() {
        if (!isReady) {
            Log.d(TAG, "TTS not ready, deferring playback");
            playWhenReady = true;
            return;
        }
        if (sentences == null || sentences.isEmpty()) {
            Log.w(TAG, "No sentences to play");
            return;
        }
        
        isPlaying = true;
        if (!wakeLock.isHeld()) {
            wakeLock.acquire(60 * 60 * 1000L); // 1 hour max
        }
        speak(sentences.get(currentIndex));
    }

    private void playNext() {
        if (sentences != null && currentIndex < sentences.size()) {
            speak(sentences.get(currentIndex));
        } else {
            Log.d(TAG, "Playback finished");
            stop();
            if (listener != null) listener.onFinished();
        }
    }

    private void speak(Sentence sentence) {
        if (!isReady || sentence == null) return;
        Bundle params = new Bundle();
        params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, String.valueOf(sentence.id));
        int result = tts.speak(sentence.text, TextToSpeech.QUEUE_FLUSH, params, String.valueOf(sentence.id));
        if (result != TextToSpeech.SUCCESS) {
            Log.e(TAG, "tts.speak failed with code: " + result);
        }
    }

    public void pause() {
        Log.d(TAG, "Pausing playback");
        isPlaying = false;
        playWhenReady = false;
        if (tts != null) tts.stop();
        if (wakeLock.isHeld()) wakeLock.release();
    }

    public void stop() {
        pause();
    }

    public void release() {
        stop();
        if (tts != null) {
            tts.shutdown();
            tts = null;
        }
    }
}
