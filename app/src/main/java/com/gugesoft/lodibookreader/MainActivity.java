package com.gugesoft.lodibookreader;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.*;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final int PICK_BOOK_REQUEST = 1;
    private SettingsManager settings;
    private RecyclerView recyclerView;
    private SentenceAdapter adapter;
    private final List<Sentence> sentences = new ArrayList<>();
    private final List<BookLoader.TOCItem> currentToc = new ArrayList<>();

    private LodiStepTimer timerManager;
    private ShakeDetector shakeDetector;
    private BookRepository bookRepo;
    private String currentBookUri;

    private MaterialButton timerToggleButton;
    private CardView bottomControls;
    private LinearLayout topBar;
    private FloatingActionButton playPauseFab;
    private boolean isTimerEnabled = true;
    private boolean isPlaying = false;

    private final BroadcastReceiver serviceUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action == null) return;

            if (ReadingService.ACTION_UPDATE_UI.equals(action)) {
                int index = intent.getIntExtra("INDEX", -1);
                if (index != -1) {
                    runOnUiThread(() -> {
                        adapter.setHighlighted(index);
                        recyclerView.scrollToPosition(index);
                    });
                }
            } else if (ReadingService.ACTION_PLAY.equals(action)) {
                updatePlayState(true);
            } else if (ReadingService.ACTION_PAUSE.equals(action)) {
                updatePlayState(false);
            } else if (ReadingService.ACTION_CLOSE.equals(action)) {
                // Actually close the app and process
                finishAffinity();
                System.exit(0);
            }
        }
    };

    private void updatePlayState(boolean playing) {
        this.isPlaying = playing;
        if (playPauseFab != null) {
            playPauseFab.setImageResource(playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play);
        }
        if (playing) {
            startTimerWithCurrentSettings();
        } else {
            if (timerManager != null) timerManager.stop();
        }
    }

    private void sendServiceCommand(String action) {
        Intent intent = new Intent(this, ReadingService.class);
        intent.setAction(action);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        settings = new SettingsManager(this);
        bookRepo = new BookRepository(this);

        bottomControls = findViewById(R.id.bottomControls);
        topBar = findViewById(R.id.topBar);
        timerToggleButton = findViewById(R.id.timerToggleButton);
        recyclerView = findViewById(R.id.recyclerView);

        IntentFilter filter = new IntentFilter();
        filter.addAction(ReadingService.ACTION_UPDATE_UI);
        filter.addAction(ReadingService.ACTION_PLAY);
        filter.addAction(ReadingService.ACTION_PAUSE);
        filter.addAction(ReadingService.ACTION_CLOSE);
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(serviceUpdateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(serviceUpdateReceiver, filter);
        }

        requestPermissions();

        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new SentenceAdapter(sentences, new SentenceAdapter.OnSentenceClickListener() {
            @Override
            public void onSentenceClick(int position) {
                Intent intent = new Intent(MainActivity.this, ReadingService.class);
                intent.setAction(ReadingService.ACTION_PLAY);
                intent.putExtra("INDEX", position);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent);
                } else {
                    startService(intent);
                }
            }

            @Override public void onNavigateTo(int position) { recyclerView.scrollToPosition(position); }
            @Override public void onSingleTap() {
                if (topBar.getVisibility() == View.VISIBLE) hideControls();
                else showControls();
            }
        }, settings);
        recyclerView.setAdapter(adapter);

        VolumeController vc = new VolumeController(this);
        timerManager = new LodiStepTimer(vc, () -> {
            sendServiceCommand(ReadingService.ACTION_CLOSE);
        });
        timerManager.setTimerListener(remainingMs -> runOnUiThread(() -> updateTimerButtonText(remainingMs)));

        shakeDetector = new ShakeDetector(this, settings.getShakeIntensity(), () -> {
            if (isTimerEnabled && isPlaying) timerManager.handleShake();
        });

        findViewById(R.id.loadBookBtn).setOnClickListener(v -> pickBook());
        findViewById(R.id.openShelfBtn).setOnClickListener(v -> startActivity(new Intent(this, BookshelfActivity.class)));
        findViewById(R.id.openSettingsBtn).setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));

        timerToggleButton.setOnClickListener(v -> {
            isTimerEnabled = !isTimerEnabled;
            if (!isTimerEnabled) {
                timerManager.stop();
                timerToggleButton.setText("Off");
            } else {
                if (isPlaying) startTimerWithCurrentSettings();
                else timerToggleButton.setText("On");
            }
        });

        playPauseFab = findViewById(R.id.playPauseFab);
        playPauseFab.setOnClickListener(v -> sendServiceCommand(isPlaying ? ReadingService.ACTION_PAUSE : ReadingService.ACTION_PLAY));
        findViewById(R.id.rewindFab).setOnClickListener(v -> sendServiceCommand(ReadingService.ACTION_REWIND));
        findViewById(R.id.forwardFab).setOnClickListener(v -> sendServiceCommand(ReadingService.ACTION_FORWARD));
        findViewById(R.id.closeFab).setOnClickListener(v -> sendServiceCommand(ReadingService.ACTION_CLOSE));

        findViewById(R.id.openTocBtn).setOnClickListener(v -> {
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.rootLayout, new TableOfContentsFragment(currentToc, sentences, index -> {
                        Intent intent = new Intent(MainActivity.this, ReadingService.class);
                        intent.setAction(ReadingService.ACTION_PLAY);
                        intent.putExtra("INDEX", index);
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            startForegroundService(intent);
                        } else {
                            startService(intent);
                        }
                        getSupportFragmentManager().popBackStack();
                    }))
                    .addToBackStack(null).commit();
        });

        String uriFromShelf = getIntent().getStringExtra("BOOK_URI");
        String bookUriToLoad = (uriFromShelf != null) ? uriFromShelf : settings.getLastOpenedBookUri();
        if (bookUriToLoad != null) loadBookFromUri(Uri.parse(bookUriToLoad));

        applyAppearance();
    }

    private void requestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            }
        }

        List<String> permissionsToRequest = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.READ_PHONE_STATE);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS);
            }
        }

        if (!permissionsToRequest.isEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsToRequest.toArray(new String[0]), 100);
        }
    }

    private void loadBookFromUri(Uri uri) {
        Toast.makeText(this, "Loading book...", Toast.LENGTH_SHORT).show();
        settings.setLastOpenedBookUri(uri.toString());
        new Thread(() -> {
            try {
                currentBookUri = uri.toString();
                BookLoader.BookMetadata meta = new BookLoader().loadBookWithMetadata(this, uri);
                runOnUiThread(() -> {
                    if (meta.sentences == null || meta.sentences.isEmpty()) return;
                    sentences.clear();
                    sentences.addAll(meta.sentences);
                    currentToc.clear();
                    currentToc.addAll(meta.toc);
                    adapter.notifyDataSetChanged();

                    int startIndex = settings.getLastReadSentenceIndex(currentBookUri);
                    
                    // SAFE DATA TRANSFER: Use BookDataHolder singleton to avoid Intent size limits
                    BookDataHolder.getInstance().replaceData(sentences, currentToc, meta.title, meta.author);

                    Intent serviceIntent = new Intent(this, ReadingService.class);
                    serviceIntent.setAction(ReadingService.ACTION_LOAD);
                    serviceIntent.putExtra("INDEX", startIndex);
                    
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(serviceIntent);
                    } else {
                        startService(serviceIntent);
                    }

                    adapter.setHighlighted(startIndex);
                    recyclerView.scrollToPosition(startIndex);
                });
            } catch (Exception e) { Log.e("MainActivity", "Error", e); }
        }).start();
    }

    private void startTimerWithCurrentSettings() {
        if (!isTimerEnabled) return;
        timerManager.setResetTimeMs(settings.getTimerMs());
        timerManager.setFadeStartMs(settings.getFadeMs());
        timerManager.start(settings.getTimerMs());
    }

    private void updateTimerButtonText(long remainingMs) {
        int minutes = (int) (remainingMs / 1000) / 60;
        int seconds = (int) (remainingMs / 1000) % 60;
        timerToggleButton.setText(String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds));
    }

    private void showControls() {
        topBar.setVisibility(View.VISIBLE);
        bottomControls.setVisibility(View.VISIBLE);
        timerToggleButton.setVisibility(View.VISIBLE);
    }

    private void hideControls() {
        topBar.setVisibility(View.GONE);
        bottomControls.setVisibility(View.GONE);
        timerToggleButton.setVisibility(View.GONE);
    }

    private void applyAppearance() {
        recyclerView.setBackgroundColor(settings.getPaperColor());
        findViewById(R.id.rootLayout).setBackgroundColor(settings.getPaperColor());
        adapter.notifyDataSetChanged();
    }

    private void pickBook() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"text/plain", "application/epub+zip"});
        startActivityForResult(intent, PICK_BOOK_REQUEST);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_BOOK_REQUEST && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                getContentResolver().takePersistableUriPermission(uri, data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION));
                loadBookFromUri(uri);
            }
        }
    }

    @Override protected void onResume() { 
        super.onResume(); 
        shakeDetector.start(); 
        applyAppearance(); 
        // Force sync status with service on resume
        sendServiceCommand(ReadingService.ACTION_GET_STATUS);
    }
    @Override protected void onPause() { super.onPause(); }
    @Override protected void onDestroy() {
        super.onDestroy();
        try { unregisterReceiver(serviceUpdateReceiver); } catch (Exception ignored) {}
        shakeDetector.stop();
        if (timerManager != null) {
            timerManager.stop();
        }
    }
}
