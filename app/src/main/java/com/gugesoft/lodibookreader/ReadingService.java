package com.gugesoft.lodibookreader;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.view.KeyEvent;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.media.session.MediaButtonReceiver;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ReadingService extends Service {
    private static final String TAG = "ReadingService";
    private static final String CHANNEL_ID = "LodiReadingChannel";
    private static final int NOTIF_ID = 101;

    public static final String ACTION_LOAD = "LODI_ACTION_LOAD";
    public static final String ACTION_PLAY = "LODI_ACTION_PLAY";
    public static final String ACTION_PLAY_AT = "LODI_ACTION_PLAY_AT";
    public static final String ACTION_PAUSE = "LODI_ACTION_PAUSE";
    public static final String ACTION_REWIND = "LODI_ACTION_REWIND";
    public static final String ACTION_FORWARD = "LODI_ACTION_FORWARD";
    public static final String ACTION_CLOSE = "LODI_ACTION_CLOSE";
    public static final String ACTION_UPDATE_UI = "LODI_ACTION_UPDATE_UI";
    public static final String ACTION_GET_STATUS = "LODI_ACTION_GET_STATUS";
    public static final String ACTION_TIMER_TICK = "LODI_ACTION_TIMER_TICK";
    public static final String ACTION_SHAKE = "LODI_ACTION_SHAKE";
    public static final String ACTION_TOGGLE_TIMER = "LODI_ACTION_TOGGLE_TIMER";

    private MediaSessionCompat mediaSession;
    private TTSPlayer ttsPlayer;
    private SettingsManager settings;
    private String currentBookUri;
    private int currentChapterIndex = -1;
    private int totalChapters = 0;
    private boolean wasPlayingBeforeCall = false;
    
    private final Set<String> loadingChapters = Collections.synchronizedSet(new HashSet<>());
    private String backgroundLoadingBookUri = null;

    private LodiStepTimer timerManager;
    private ShakeDetector shakeDetector;
    private VolumeController volumeController;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private AudioFocusRequest audioFocusRequest;
    private AppDatabase db;

    private final AudioManager.OnAudioFocusChangeListener focusChangeListener = focusChange -> {
        if (focusChange == AudioManager.AUDIOFOCUS_LOSS || focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            pausePlayback();
        } else if (focusChange == AudioManager.AUDIOFOCUS_GAIN) {
            // Gains focus
        }
    };

    private final BroadcastReceiver audioReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(action)) {
                pausePlayback();
            } else if ("android.media.VOLUME_CHANGED_ACTION".equals(action)) {
                int streamType = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1);
                if (streamType == AudioManager.STREAM_MUSIC) {
                    if (volumeController != null) volumeController.handlePossibleManualChange();
                }
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        settings = new SettingsManager(this);
        db = AppDatabase.getInstance(this);
        createNotificationChannel();

        volumeController = new VolumeController(this);
        volumeController.captureInitialVolume();

        timerManager = new LodiStepTimer(volumeController, () -> {
            pausePlayback();
            sendBroadcast(new Intent(ACTION_CLOSE).setPackage(getPackageName()));
        });
        timerManager.setTimerListener(remainingMs -> {
            Intent tickIntent = new Intent(ACTION_TIMER_TICK);
            tickIntent.putExtra("REMAINING", remainingMs);
            tickIntent.setPackage(getPackageName());
            sendBroadcast(tickIntent);
        });

        shakeDetector = new ShakeDetector(this, settings.getShakeIntensity(), () -> {
            if (ttsPlayer != null && ttsPlayer.isPlaying() && settings.isTimerEnabled()) {
                mainHandler.post(() -> {
                    if (timerManager != null) timerManager.handleShake();
                });
            }
        });

        ttsPlayer = new TTSPlayer(this, new TTSPlayer.OnTTSListener() {
            @Override
            public void onSentenceChanged(int index) {
                updatePlaybackState(PlaybackStateCompat.STATE_PLAYING);
                updateNotification();
                if (currentBookUri != null) {
                    settings.setLastReadSentenceIndex(currentBookUri, index);
                    settings.setLastReadChapterIndex(currentBookUri, currentChapterIndex);
                }
                sendUpdateUiBroadcast(index);
            }
            @Override
            public void onFinished() {
                loadNextChapter();
            }
        });

        setupMediaSession();
        
        IntentFilter audioFilter = new IntentFilter();
        audioFilter.addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        audioFilter.addAction("android.media.VOLUME_CHANGED_ACTION");
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(audioReceiver, audioFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(audioReceiver, audioFilter);
        }
        
        setupTelephonyListener();
        startServiceForeground();

        currentBookUri = settings.getLastOpenedBookUri();
        if (currentBookUri != null) {
            executor.execute(() -> {
                try {
                    List<ChapterEntity> chapters = db.bookDao().getChaptersForBook(currentBookUri);
                    totalChapters = chapters.size();
                    if (totalChapters > 0) {
                        int lastChapter = settings.getLastReadChapterIndex(currentBookUri);
                        int lastSentence = settings.getLastReadSentenceIndex(currentBookUri);
                        mainHandler.post(() -> loadChapterAsync(currentBookUri, lastChapter, false, lastSentence));
                    }
                    startBackgroundLoading(currentBookUri);
                } catch (Exception e) {
                    Log.e(TAG, "Init load error", e);
                }
            });
        }
    }

    private void sendUpdateUiBroadcast(int sentenceIndex) {
        Intent intent = new Intent(ACTION_UPDATE_UI);
        intent.putExtra("INDEX", sentenceIndex);
        intent.putExtra("CHAPTER_INDEX", currentChapterIndex);
        intent.putExtra("TOTAL_CHAPTERS", totalChapters);
        intent.setPackage(getPackageName());
        sendBroadcast(intent);
    }

    private void setupMediaSession() {
        ComponentName mbr = new ComponentName(this, MediaButtonReceiver.class);
        mediaSession = new MediaSessionCompat(this, "LodiReaderSession", mbr, null);
        mediaSession.setCallback(new MediaSessionCompat.Callback() {
            @Override public void onPlay() { startPlayback(); }
            @Override public void onPause() { pausePlayback(); }
            @Override public void onSkipToNext() { forward(); }
            @Override public void onSkipToPrevious() { rewind(); }
            @Override public void onStop() { pausePlayback(); stopSelf(); }
            @Override
            public boolean onMediaButtonEvent(Intent mediaButtonEvent) {
                KeyEvent event = mediaButtonEvent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
                if (event != null && event.getAction() == KeyEvent.ACTION_DOWN) {
                    int keyCode = event.getKeyCode();
                    if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || keyCode == KeyEvent.KEYCODE_HEADSETHOOK) {
                        if (ttsPlayer != null && ttsPlayer.isPlaying()) pausePlayback();
                        else startPlayback();
                        return true;
                    }
                }
                return super.onMediaButtonEvent(mediaButtonEvent);
            }
        }, mainHandler);

        mediaSession.setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS | MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS);
        mediaSession.setActive(true);
    }

    private void startPlayback() {
        if (ttsPlayer == null) return;
        if (ttsPlayer.getSentences() == null || ttsPlayer.getSentences().isEmpty()) {
            return;
        }

        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        int result;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AudioAttributes attrs = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
            audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(attrs)
                    .setOnAudioFocusChangeListener(focusChangeListener)
                    .build();
            result = am.requestAudioFocus(audioFocusRequest);
        } else {
            result = am.requestAudioFocus(focusChangeListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        }

        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            mediaSession.setActive(true);
            ttsPlayer.play();
            updatePlaybackState(PlaybackStateCompat.STATE_PLAYING);
            updateNotification();
            sendBroadcast(new Intent(ACTION_PLAY).setPackage(getPackageName()));
            
            if (settings.isTimerEnabled()) {
                timerManager.setResetTimeMs(settings.getTimerMs());
                timerManager.setFadeStartMs(settings.getFadeMs());
                timerManager.start(settings.getTimerMs());
            }
            shakeDetector.start();
        }
    }

    private void pausePlayback() {
        if (ttsPlayer != null) {
            ttsPlayer.pause();
            updatePlaybackState(PlaybackStateCompat.STATE_PAUSED);
            updateNotification();
            sendBroadcast(new Intent(ACTION_PAUSE).setPackage(getPackageName()));
        }
        if (timerManager != null) timerManager.stop();
        if (shakeDetector != null) shakeDetector.stop();
    }

    private void forward() {
        if (ttsPlayer == null) return;
        int next = ttsPlayer.getCurrentIndex() + 1;
        if (next < ttsPlayer.getSentences().size()) {
            ttsPlayer.setCurrentIndex(next);
            startPlayback();
        } else {
            loadNextChapter();
        }
    }

    private void rewind() {
        if (ttsPlayer == null) return;
        int prev = ttsPlayer.getCurrentIndex() - 1;
        if (prev >= 0) {
            ttsPlayer.setCurrentIndex(prev);
            startPlayback();
        } else {
            loadPreviousChapter();
        }
    }

    private void loadNextChapter() {
        if (currentBookUri != null && currentChapterIndex < totalChapters - 1) {
            loadChapterAsync(currentBookUri, currentChapterIndex + 1, true, 0);
        } else {
            pausePlayback();
        }
    }

    private void loadPreviousChapter() {
        if (currentBookUri != null && currentChapterIndex > 0) {
            loadChapterAsync(currentBookUri, currentChapterIndex - 1, true, -1);
        }
    }

    private void loadChapterAsync(String uri, int chapterIndex, boolean playImmediately, int startSentenceIndex) {
        String key = uri + "_" + chapterIndex;
        if (loadingChapters.contains(key)) return;
        loadingChapters.add(key);

        executor.execute(() -> {
            try {
                BookEntity book = db.bookDao().getBookByUri(uri);
                if (book == null) return;

                List<ChapterEntity> allChapters = db.bookDao().getChaptersForBook(uri);
                totalChapters = allChapters.size();
                
                // Persist progress to DB for bookshelf immediately
                db.runInTransaction(() -> {
                    db.bookDao().updateProgress(uri, chapterIndex);
                    db.bookDao().updateTotalPages(uri, totalChapters);
                });

                List<SentenceEntity> entities = db.bookDao().getSentencesForChapter(uri, chapterIndex);
                List<Sentence> sentences = new ArrayList<>();
                
                if (entities.isEmpty()) {
                    List<Sentence> loaded = new BookLoader().loadChapter(this, Uri.parse(uri), chapterIndex);
                    if (loaded != null) {
                        List<SentenceEntity> toSave = new ArrayList<>();
                        for (int i = 0; i < loaded.size(); i++) {
                            Sentence s = loaded.get(i);
                            toSave.add(new SentenceEntity(uri, chapterIndex, i, s.text, s.link, s.internalId));
                        }
                        db.runInTransaction(() -> {
                            db.bookDao().deleteSentencesForChapter(uri, chapterIndex);
                            db.bookDao().insertSentences(toSave);
                            db.bookDao().updateChapterLoadStatus(uri, chapterIndex, true, loaded.size());
                        });
                        sentences.addAll(loaded);
                    }
                } else {
                    for (SentenceEntity e : entities) {
                        sentences.add(new Sentence(e.globalIndex, e.text, e.link, e.internalId));
                    }
                }

                if (!sentences.isEmpty()) {
                    mainHandler.post(() -> {
                        currentBookUri = uri;
                        currentChapterIndex = chapterIndex;
                        ttsPlayer.loadSentences(sentences);
                        int finalIdx = startSentenceIndex;
                        if (finalIdx == -1) finalIdx = sentences.size() - 1;
                        if (finalIdx >= sentences.size()) finalIdx = 0;
                        ttsPlayer.setCurrentIndex(finalIdx);
                        
                        mediaSession.setMetadata(new MediaMetadataCompat.Builder()
                                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, book.title)
                                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, book.author)
                                .build());
                        
                        if (playImmediately) startPlayback();
                        updateNotification();
                        sendUpdateUiBroadcast(finalIdx);
                    });
                }
            } catch (Exception e) {
                Log.e(TAG, "Load chapter error", e);
            } finally {
                loadingChapters.remove(key);
            }
        });
    }

    private void startBackgroundLoading(String uri) {
        if (uri == null || uri.equals(backgroundLoadingBookUri)) return;
        backgroundLoadingBookUri = uri;

        executor.execute(() -> {
            try {
                List<ChapterEntity> chapters = db.bookDao().getChaptersForBook(uri);
                for (ChapterEntity chapter : chapters) {
                    if (!uri.equals(backgroundLoadingBookUri)) break;
                    
                    if (!chapter.isLoaded) {
                        String key = uri + "_" + chapter.index;
                        if (loadingChapters.contains(key)) continue;
                        loadingChapters.add(key);
                        
                        try {
                            List<Sentence> loaded = new BookLoader().loadChapter(this, Uri.parse(uri), chapter.index);
                            if (loaded != null) {
                                List<SentenceEntity> toSave = new ArrayList<>();
                                for (int i = 0; i < loaded.size(); i++) {
                                    Sentence s = loaded.get(i);
                                    toSave.add(new SentenceEntity(uri, chapter.index, i, s.text, s.link, s.internalId));
                                }
                                db.runInTransaction(() -> {
                                    db.bookDao().deleteSentencesForChapter(uri, chapter.index);
                                    db.bookDao().insertSentences(toSave);
                                    db.bookDao().updateChapterLoadStatus(uri, chapter.index, true, loaded.size());
                                });
                            }
                        } finally {
                            loadingChapters.remove(key);
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Background loading error", e);
            } finally {
                if (uri.equals(backgroundLoadingBookUri)) backgroundLoadingBookUri = null;
            }
        });
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if (Intent.ACTION_MEDIA_BUTTON.equals(action)) {
                MediaButtonReceiver.handleIntent(mediaSession, intent);
                return START_STICKY;
            }
            if (ACTION_LOAD.equals(action)) {
                String uri = intent.getStringExtra("URI");
                int chapter = intent.getIntExtra("CHAPTER_INDEX", 0);
                int sentence = intent.getIntExtra("SENTENCE_INDEX", 0);
                if (uri != null) {
                    loadChapterAsync(uri, chapter, true, sentence);
                    startBackgroundLoading(uri);
                }
            }
            else if (ACTION_PLAY.equals(action)) startPlayback();
            else if (ACTION_PLAY_AT.equals(action)) {
                int index = intent.getIntExtra("INDEX", 0);
                if (ttsPlayer != null) {
                    ttsPlayer.setCurrentIndex(index);
                    startPlayback();
                }
            }
            else if (ACTION_PAUSE.equals(action)) pausePlayback();
            else if (ACTION_FORWARD.equals(action)) forward();
            else if (ACTION_REWIND.equals(action)) rewind();
            else if (ACTION_SHAKE.equals(action)) { if (timerManager != null) timerManager.handleShake(); }
            else if (ACTION_TOGGLE_TIMER.equals(action)) {
                if (ttsPlayer != null && ttsPlayer.isPlaying()) {
                    if (settings.isTimerEnabled()) {
                        timerManager.setResetTimeMs(settings.getTimerMs());
                        timerManager.setFadeStartMs(settings.getFadeMs());
                        timerManager.start(settings.getTimerMs());
                        shakeDetector.start();
                    } else {
                        timerManager.stop();
                        shakeDetector.stop();
                    }
                }
            }
            else if (ACTION_GET_STATUS.equals(action)) {
                boolean isPlaying = ttsPlayer != null && ttsPlayer.isPlaying();
                sendBroadcast(new Intent(isPlaying ? ACTION_PLAY : ACTION_PAUSE).setPackage(getPackageName()));
                if (isPlaying && timerManager != null && timerManager.isRunning()) {
                    Intent tickIntent = new Intent(ACTION_TIMER_TICK);
                    tickIntent.putExtra("REMAINING", timerManager.getRemainingTimeMs());
                    tickIntent.setPackage(getPackageName());
                    sendBroadcast(tickIntent);
                }
                if (ttsPlayer != null) {
                    sendUpdateUiBroadcast(ttsPlayer.getCurrentIndex());
                }
            }
            else if (ACTION_CLOSE.equals(action)) {
                pausePlayback(); mediaSession.setActive(false); stopForeground(true);
                sendBroadcast(new Intent(ACTION_CLOSE).setPackage(getPackageName()));
                stopSelf(); return START_NOT_STICKY;
            }
        }
        startServiceForeground();
        return START_STICKY;
    }

    private void startServiceForeground() {
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) 
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        else startForeground(NOTIF_ID, notification);
    }

    private void updatePlaybackState(int state) {
        long actions = PlaybackStateCompat.ACTION_PLAY | PlaybackStateCompat.ACTION_PAUSE | PlaybackStateCompat.ACTION_PLAY_PAUSE | 
                       PlaybackStateCompat.ACTION_SKIP_TO_NEXT | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS | PlaybackStateCompat.ACTION_STOP;
        mediaSession.setPlaybackState(new PlaybackStateCompat.Builder()
                .setActions(actions)
                .setState(state, ttsPlayer != null ? ttsPlayer.getCurrentIndex() : 0, 1.0f)
                .build());
    }

    private void updateNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIF_ID, buildNotification());
    }

    private Notification buildNotification() {
        boolean isPlaying = ttsPlayer != null && ttsPlayer.isPlaying();
        Intent mainIntent = new Intent(this, MainActivity.class);
        int piFlags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? (PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT) : PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent pMain = PendingIntent.getActivity(this, 0, mainIntent, piFlags);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(mediaSession.getController().getMetadata() != null ? mediaSession.getController().getMetadata().getDescription().getTitle() : "Lodi Reader")
                .setContentText(isPlaying ? "Reading..." : "Paused")
                .setContentIntent(pMain)
                .setOngoing(isPlaying)
                .setSilent(true)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setStyle(new androidx.media.app.NotificationCompat.MediaStyle()
                        .setMediaSession(mediaSession.getSessionToken())
                        .setShowActionsInCompactView(1, 2, 3))
                .addAction(android.R.drawable.ic_media_rew, "Rewind", PendingIntent.getService(this, 1, new Intent(this, ReadingService.class).setAction(ACTION_REWIND), piFlags))
                .addAction(isPlaying ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play, isPlaying ? "Pause" : "Play", PendingIntent.getService(this, 2, new Intent(this, ReadingService.class).setAction(isPlaying ? ACTION_PAUSE : ACTION_PLAY), piFlags))
                .addAction(android.R.drawable.ic_media_ff, "Forward", PendingIntent.getService(this, 3, new Intent(this, ReadingService.class).setAction(ACTION_FORWARD), piFlags))
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Close", PendingIntent.getService(this, 4, new Intent(this, ReadingService.class).setAction(ACTION_CLOSE), piFlags))
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Lodi Playback", NotificationManager.IMPORTANCE_LOW);
            channel.setShowBadge(false);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private void setupTelephonyListener() {
        TelephonyManager tm = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
        if (tm != null) {
            tm.listen(new PhoneStateListener() {
                @Override public void onCallStateChanged(int state, String phoneNumber) {
                    if (state == TelephonyManager.CALL_STATE_RINGING || state == TelephonyManager.CALL_STATE_OFFHOOK) { 
                        if (ttsPlayer != null && ttsPlayer.isPlaying()) { wasPlayingBeforeCall = true; pausePlayback(); } 
                    } else if (state == TelephonyManager.CALL_STATE_IDLE) { 
                        if (wasPlayingBeforeCall) { wasPlayingBeforeCall = false; startPlayback(); } 
                    }
                }
            }, PhoneStateListener.LISTEN_CALL_STATE);
        }
    }

    @Override public void onDestroy() { 
        if (ttsPlayer != null) ttsPlayer.release(); 
        if (mediaSession != null) { mediaSession.setActive(false); mediaSession.release(); } 
        try { unregisterReceiver(audioReceiver); } catch (Exception ignored) {} 
        if (shakeDetector != null) shakeDetector.stop();
        executor.shutdown();
        super.onDestroy(); 
    }
    @Nullable @Override public IBinder onBind(Intent intent) { return null; }
}
