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
import android.widget.SeekBar;
import android.widget.TextView;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final int PICK_BOOK_REQUEST = 1;
    private static final int WORDS_PER_PAGE = 250;

    private SettingsManager settings;
    private RecyclerView recyclerView;
    private SentenceAdapter adapter;
    private final List<Sentence> sentences = new ArrayList<>();
    private final List<BookLoader.TOCItem> currentToc = new ArrayList<>();

    private ShakeDetector shakeDetector;
    private BookRepository bookRepo;
    private String currentBookUri;

    private MaterialButton timerToggleButton;
    private CardView bottomControls;
    private CardView seekContainer;
    private SeekBar seekBarProgress;
    private TextView tvPageCounter;
    private LinearLayout topBar;
    private FloatingActionButton playPauseFab;
    private boolean isTimerEnabled = true;
    private boolean isPlaying = false;
    private boolean isUserSeeking = false;

    // Page mapping data
    private int[] sentenceToPage;
    private int[] pageToFirstSentenceIndex;
    private int totalPages = 0;

    private final BroadcastReceiver serviceUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action == null) return;

            switch (action) {
                case ReadingService.ACTION_UPDATE_UI:
                    int index = intent.getIntExtra("INDEX", -1);
                    if (index != -1) {
                        runOnUiThread(() -> {
                            adapter.setHighlighted(index);
                            recyclerView.scrollToPosition(index);
                            updateProgressBar(index);
                        });
                    }
                    break;
                case ReadingService.ACTION_PLAY:
                    updatePlayState(true);
                    break;
                case ReadingService.ACTION_PAUSE:
                    updatePlayState(false);
                    break;
                case ReadingService.ACTION_TIMER_TICK:
                    long remainingMs = intent.getLongExtra("REMAINING", 0);
                    runOnUiThread(() -> updateTimerButtonText(remainingMs));
                    break;
                case ReadingService.ACTION_CLOSE:
                    finishAffinity();
                    System.exit(0);
                    break;
            }
        }
    };

    private void updatePlayState(boolean playing) {
        this.isPlaying = playing;
        if (playPauseFab != null) {
            playPauseFab.setImageResource(playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play);
        }
        if (!playing) {
            timerToggleButton.setText(isTimerEnabled ? "On" : "Off");
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
        isTimerEnabled = settings.isTimerEnabled();

        bottomControls = findViewById(R.id.bottomControls);
        seekContainer = findViewById(R.id.seekContainer);
        seekBarProgress = findViewById(R.id.seekBarProgress);
        tvPageCounter = findViewById(R.id.tvPageCounter);
        topBar = findViewById(R.id.topBar);
        timerToggleButton = findViewById(R.id.timerToggleButton);
        recyclerView = findViewById(R.id.recyclerView);

        timerToggleButton.setText(isTimerEnabled ? "On" : "Off");

        IntentFilter filter = new IntentFilter();
        filter.addAction(ReadingService.ACTION_UPDATE_UI);
        filter.addAction(ReadingService.ACTION_PLAY);
        filter.addAction(ReadingService.ACTION_PAUSE);
        filter.addAction(ReadingService.ACTION_CLOSE);
        filter.addAction(ReadingService.ACTION_TIMER_TICK);
        
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
                playAtPosition(position);
            }

            @Override public void onNavigateTo(int position) { recyclerView.scrollToPosition(position); }
            @Override public void onSingleTap() {
                if (topBar.getVisibility() == View.VISIBLE) hideControls();
                else showControls();
            }
        }, settings);
        recyclerView.setAdapter(adapter);

        shakeDetector = new ShakeDetector(this, settings.getShakeIntensity(), () -> {
            if (isTimerEnabled && isPlaying) sendServiceCommand(ReadingService.ACTION_SHAKE);
        });

        findViewById(R.id.loadBookBtn).setOnClickListener(v -> pickBook());
        findViewById(R.id.openShelfBtn).setOnClickListener(v -> startActivity(new Intent(this, BookshelfActivity.class)));
        findViewById(R.id.openSettingsBtn).setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));

        timerToggleButton.setOnClickListener(v -> {
            isTimerEnabled = !isTimerEnabled;
            settings.setTimerEnabled(isTimerEnabled);
            timerToggleButton.setText(isTimerEnabled ? "On" : "Off");
        });

        playPauseFab = findViewById(R.id.playPauseFab);
        playPauseFab.setOnClickListener(v -> sendServiceCommand(isPlaying ? ReadingService.ACTION_PAUSE : ReadingService.ACTION_PLAY));
        findViewById(R.id.rewindFab).setOnClickListener(v -> sendServiceCommand(ReadingService.ACTION_REWIND));
        findViewById(R.id.forwardFab).setOnClickListener(v -> sendServiceCommand(ReadingService.ACTION_FORWARD));
        findViewById(R.id.closeFab).setOnClickListener(v -> sendServiceCommand(ReadingService.ACTION_CLOSE));

        seekBarProgress.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    tvPageCounter.setText(String.format(Locale.getDefault(), "Page %d / %d", progress + 1, totalPages));
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { isUserSeeking = true; }
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                isUserSeeking = false;
                int page = seekBar.getProgress();
                if (pageToFirstSentenceIndex != null && page < pageToFirstSentenceIndex.length) {
                    playAtPosition(pageToFirstSentenceIndex[page]);
                }
            }
        });

        findViewById(R.id.openTocBtn).setOnClickListener(v -> {
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.rootLayout, new TableOfContentsFragment(currentToc, sentences, index -> {
                        playAtPosition(index);
                        getSupportFragmentManager().popBackStack();
                    }))
                    .addToBackStack(null).commit();
        });

        String uriFromShelf = getIntent().getStringExtra("BOOK_URI");
        String bookUriToLoad = (uriFromShelf != null) ? uriFromShelf : settings.getLastOpenedBookUri();
        if (bookUriToLoad != null) loadBookFromUri(Uri.parse(bookUriToLoad));

        applyAppearance();
    }

    private void playAtPosition(int position) {
        Intent intent = new Intent(MainActivity.this, ReadingService.class);
        intent.setAction(ReadingService.ACTION_PLAY);
        intent.putExtra("INDEX", position);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    private void requestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                try {
                    @SuppressLint("BatteryLife") Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                } catch (Exception ignored) {}
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
                    if (meta == null || meta.sentences == null || meta.sentences.isEmpty()) return;
                    sentences.clear();
                    sentences.addAll(meta.sentences);
                    currentToc.clear();
                    currentToc.addAll(meta.toc);
                    
                    calculatePages();
                    
                    adapter.notifyDataSetChanged();
                    seekBarProgress.setMax(Math.max(0, totalPages - 1));
                    
                    int startIndex = settings.getLastReadSentenceIndex(currentBookUri);
                    updateProgressBar(startIndex);
                    
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

    private void calculatePages() {
        if (sentences.isEmpty()) {
            totalPages = 0;
            sentenceToPage = new int[0];
            pageToFirstSentenceIndex = new int[0];
            return;
        }

        sentenceToPage = new int[sentences.size()];
        List<Integer> firstIndices = new ArrayList<>();
        
        int currentWordCount = 0;
        int currentPage = 0;
        firstIndices.add(0);

        for (int i = 0; i < sentences.size(); i++) {
            String text = sentences.get(i).getText();
            int wordsInSentence = (text == null || text.isEmpty()) ? 0 : text.trim().split("\\s+").length;
            
            if (currentWordCount + wordsInSentence > WORDS_PER_PAGE && currentWordCount > 0) {
                currentPage++;
                firstIndices.add(i);
                currentWordCount = wordsInSentence;
            } else {
                currentWordCount += wordsInSentence;
            }
            sentenceToPage[i] = currentPage;
        }
        
        totalPages = currentPage + 1;
        pageToFirstSentenceIndex = new int[firstIndices.size()];
        for (int i = 0; i < firstIndices.size(); i++) {
            pageToFirstSentenceIndex[i] = firstIndices.get(i);
        }
    }

    private void updateProgressBar(int sentenceIndex) {
        if (!isUserSeeking && sentenceToPage != null && sentenceIndex < sentenceToPage.length) {
            int page = sentenceToPage[sentenceIndex];
            seekBarProgress.setProgress(page);
            tvPageCounter.setText(String.format(Locale.getDefault(), "Page %d / %d", page + 1, totalPages));
        }
    }

    private void updateTimerButtonText(long remainingMs) {
        if (!isTimerEnabled) {
            timerToggleButton.setText("Off");
            return;
        }
        int minutes = (int) (remainingMs / 1000) / 60;
        int seconds = (int) (remainingMs / 1000) % 60;
        timerToggleButton.setText(String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds));
    }

    private void showControls() {
        topBar.setVisibility(View.VISIBLE);
        bottomControls.setVisibility(View.VISIBLE);
        seekContainer.setVisibility(View.VISIBLE);
        timerToggleButton.setVisibility(View.VISIBLE);
    }

    private void hideControls() {
        topBar.setVisibility(View.GONE);
        bottomControls.setVisibility(View.GONE);
        seekContainer.setVisibility(View.GONE);
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
        sendServiceCommand(ReadingService.ACTION_GET_STATUS);
    }
    @Override protected void onPause() { super.onPause(); shakeDetector.stop(); }
    @Override protected void onDestroy() {
        super.onDestroy();
        try { unregisterReceiver(serviceUpdateReceiver); } catch (Exception ignored) {}
        shakeDetector.stop();
    }
}
