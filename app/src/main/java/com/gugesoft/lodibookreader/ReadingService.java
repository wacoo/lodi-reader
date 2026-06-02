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

import java.util.List;

public class ReadingService extends Service {
    private static final String TAG = "ReadingService";
    private static final String CHANNEL_ID = "LodiReadingChannel";
    private static final int NOTIF_ID = 101;

    public static final String ACTION_LOAD = "LODI_ACTION_LOAD";
    public static final String ACTION_PLAY = "LODI_ACTION_PLAY";
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
    private boolean wasPlayingBeforeCall = false;
    private boolean isLoading = false;
    private boolean playAfterLoadRequested = false;
    
    private LodiStepTimer timerManager;
    private VolumeController volumeController;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private AudioFocusRequest audioFocusRequest;

    private final AudioManager.OnAudioFocusChangeListener focusChangeListener = focusChange -> {
        if (focusChange == AudioManager.AUDIOFOCUS_LOSS || focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            pausePlayback();
        }
    };

    private final BroadcastReceiver noisyReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) {
                pausePlayback();
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        settings = new SettingsManager(this);
        createNotificationChannel();

        volumeController = new VolumeController(this);
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

        ttsPlayer = new TTSPlayer(this, new TTSPlayer.OnTTSListener() {
            @Override
            public void onSentenceChanged(int index) {
                updatePlaybackState(PlaybackStateCompat.STATE_PLAYING);
                updateNotification();
                if (currentBookUri != null) settings.setLastReadSentenceIndex(currentBookUri, index);
                
                // Broadcast updates with package name
                sendBroadcast(new Intent(ACTION_PLAY).setPackage(getPackageName()));
                
                Intent intent = new Intent(ACTION_UPDATE_UI);
                intent.putExtra("INDEX", index);
                intent.putExtra("TOTAL", ttsPlayer.getSentences() != null ? ttsPlayer.getSentences().size() : 0);
                intent.setPackage(getPackageName());
                sendBroadcast(intent);
            }
            @Override
            public void onFinished() {
                updatePlaybackState(PlaybackStateCompat.STATE_STOPPED);
                pausePlayback();
            }
        });

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
        Intent mediaButtonIntent = new Intent(Intent.ACTION_MEDIA_BUTTON);
        mediaButtonIntent.setComponent(mbr);
        int piFlags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent pi = PendingIntent.getBroadcast(this, 0, mediaButtonIntent, piFlags);
        mediaSession.setMediaButtonReceiver(pi);

        mediaSession.setActive(true);
        updatePlaybackState(PlaybackStateCompat.STATE_PAUSED);
        
        registerReceiver(noisyReceiver, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
        setupTelephonyListener();
        startServiceForeground();

        currentBookUri = settings.getLastOpenedBookUri();
        if (currentBookUri != null) loadLastBookAsync(false);
    }

    private void startPlayback() {
        if (ttsPlayer == null) return;
        if (ttsPlayer.getSentences() == null || ttsPlayer.getSentences().isEmpty()) {
            playAfterLoadRequested = true;
            loadLastBookAsync(true);
            return;
        }

        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        int result;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AudioAttributes attrs = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
            audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attrs).setOnAudioFocusChangeListener(focusChangeListener).build();
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
    }

    private void forward() { if (ttsPlayer != null) { ttsPlayer.setCurrentIndex(ttsPlayer.getCurrentIndex() + 1); startPlayback(); } }
    private void rewind() { if (ttsPlayer != null) { ttsPlayer.setCurrentIndex(Math.max(0, ttsPlayer.getCurrentIndex() - 1)); startPlayback(); } }

    private void loadLastBookAsync(boolean forcePlay) {
        if (isLoading) { if (forcePlay) playAfterLoadRequested = true; return; }
        currentBookUri = settings.getLastOpenedBookUri();
        if (currentBookUri == null) return;
        isLoading = true;
        new Thread(() -> {
            try {
                BookLoader.BookMetadata meta = new BookLoader().loadBookWithMetadata(this, Uri.parse(currentBookUri));
                if (meta != null && meta.sentences != null && !meta.sentences.isEmpty()) {
                    BookDataHolder.getInstance().replaceData(meta.sentences, meta.toc, meta.title, meta.author);
                    ttsPlayer.loadSentences(meta.sentences);
                    ttsPlayer.setCurrentIndex(settings.getLastReadSentenceIndex(currentBookUri));
                    mediaSession.setMetadata(new MediaMetadataCompat.Builder().putString(MediaMetadataCompat.METADATA_KEY_TITLE, meta.title).putString(MediaMetadataCompat.METADATA_KEY_ARTIST, meta.author).build());
                    if (forcePlay || playAfterLoadRequested) { playAfterLoadRequested = false; mainHandler.post(this::startPlayback); }
                    updateNotification();
                }
            } catch (Exception e) { Log.e(TAG, "Load error", e); } finally { isLoading = false; }
        }).start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if (Intent.ACTION_MEDIA_BUTTON.equals(action)) { MediaButtonReceiver.handleIntent(mediaSession, intent); return START_STICKY; }
            if (ACTION_LOAD.equals(action)) {
                List<Sentence> sentences = BookDataHolder.getInstance().getSentences();
                if (sentences != null && !sentences.isEmpty()) {
                    ttsPlayer.loadSentences(sentences);
                    currentBookUri = settings.getLastOpenedBookUri();
                    mediaSession.setMetadata(new MediaMetadataCompat.Builder().putString(MediaMetadataCompat.METADATA_KEY_TITLE, BookDataHolder.getInstance().getTitle()).putString(MediaMetadataCompat.METADATA_KEY_ARTIST, BookDataHolder.getInstance().getAuthor()).build());
                } else loadLastBookAsync(false);
            }
            if (intent.hasExtra("INDEX")) {
                int index = intent.getIntExtra("INDEX", 0);
                if (ttsPlayer != null) {
                    ttsPlayer.setCurrentIndex(index);
                    if (ttsPlayer.isPlaying()) startPlayback();
                    else {
                        Intent updateIntent = new Intent(ACTION_UPDATE_UI);
                        updateIntent.putExtra("INDEX", index);
                        updateIntent.putExtra("TOTAL", ttsPlayer.getSentences() != null ? ttsPlayer.getSentences().size() : 0);
                        updateIntent.setPackage(getPackageName());
                        sendBroadcast(updateIntent);
                    }
                }
            }
            if (ACTION_PLAY.equals(action)) startPlayback();
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
                    } else {
                        timerManager.stop();
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
                    Intent updateIntent = new Intent(ACTION_UPDATE_UI);
                    updateIntent.putExtra("INDEX", ttsPlayer.getCurrentIndex());
                    updateIntent.putExtra("TOTAL", ttsPlayer.getSentences() != null ? ttsPlayer.getSentences().size() : 0);
                    updateIntent.setPackage(getPackageName());
                    sendBroadcast(updateIntent);
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        else startForeground(NOTIF_ID, notification);
    }

    private void updatePlaybackState(int state) {
        long actions = PlaybackStateCompat.ACTION_PLAY | PlaybackStateCompat.ACTION_PAUSE | PlaybackStateCompat.ACTION_PLAY_PAUSE | PlaybackStateCompat.ACTION_SKIP_TO_NEXT | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS | PlaybackStateCompat.ACTION_STOP;
        mediaSession.setPlaybackState(new PlaybackStateCompat.Builder().setActions(actions).setState(state, ttsPlayer != null ? ttsPlayer.getCurrentIndex() : 0, 1.0f).build());
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
                .setContentTitle(BookDataHolder.getInstance().getTitle())
                .setContentText(isPlaying ? "Reading..." : "Paused")
                .setContentIntent(pMain)
                .setOngoing(isPlaying)
                .setSilent(true)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setStyle(new androidx.media.app.NotificationCompat.MediaStyle().setMediaSession(mediaSession.getSessionToken()).setShowActionsInCompactView(1, 2, 3))
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
                    if (state == TelephonyManager.CALL_STATE_RINGING || state == TelephonyManager.CALL_STATE_OFFHOOK) { if (ttsPlayer != null && ttsPlayer.isPlaying()) { wasPlayingBeforeCall = true; pausePlayback(); } }
                    else if (state == TelephonyManager.CALL_STATE_IDLE) { if (wasPlayingBeforeCall) { wasPlayingBeforeCall = false; startPlayback(); } }
                }
            }, PhoneStateListener.LISTEN_CALL_STATE);
        }
    }

    @Override public void onDestroy() { if (ttsPlayer != null) ttsPlayer.release(); if (mediaSession != null) { mediaSession.setActive(false); mediaSession.release(); } try { unregisterReceiver(noisyReceiver); } catch (Exception ignored) {} super.onDestroy(); }
    @Nullable @Override public IBinder onBind(Intent intent) { return null; }
}
