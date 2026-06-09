package com.gugesoft.lodibookreader;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.*;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private static final int PICK_REQUEST = 1;

    private SettingsManager settings;
    private RecyclerView recyclerView;
    private SentenceAdapter adapter;
    private final List<Sentence> currentSentences = new ArrayList<>();
    private final List<BookLoader.TOCItem> currentToc = new ArrayList<>();

    private ShakeDetector shakeDetector;
    private String currentBookUri;
    private int currentChapterIndex = -1;

    private MaterialButton timerToggleButton;
    private CardView bottomControls;
    private CardView seekContainer;
    private SeekBar seekBarProgress;
    private TextView tvPageCounter;
    private LinearLayout topBar;
    private FloatingActionButton playPauseFab;
    
    private View loadingOverlay;
    private ProgressBar progressBarLoading;
    private TextView tvLoadingMessage;

    private boolean isTimerEnabled = true;
    private boolean isPlaying = false;
    private AppDatabase db;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private final BroadcastReceiver serviceUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action == null) return;

            switch (action) {
                case ReadingService.ACTION_UPDATE_UI:
                    int index = intent.getIntExtra("INDEX", -1);
                    int chapterIdx = intent.getIntExtra("CHAPTER_INDEX", -1);
                    int totalChapters = intent.getIntExtra("TOTAL_CHAPTERS", 0);
                    
                    runOnUiThread(() -> {
                        if (totalChapters > 0) {
                            tvPageCounter.setText(String.format(Locale.getDefault(), "Chapter %d / %d", chapterIdx + 1, totalChapters));
                        }
                    });

                    if (chapterIdx != -1 && (chapterIdx != currentChapterIndex || currentSentences.isEmpty())) {
                        loadChapterFromDb(chapterIdx, index);
                    } else if (index != -1) {
                        runOnUiThread(() -> {
                            adapter.setHighlighted(index);
                            recyclerView.scrollToPosition(index);
                            hideLoading();
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        if (!isTaskRoot() && getIntent().hasCategory(Intent.CATEGORY_LAUNCHER) && Intent.ACTION_MAIN.equals(getIntent().getAction())) {
            finish();
            return;
        }

        setContentView(R.layout.activity_main);
        settings = new SettingsManager(this);
        db = AppDatabase.getInstance(this);
        isTimerEnabled = settings.isTimerEnabled();

        initUi();
        setupReceiver();
        requestPermissions();

        shakeDetector = new ShakeDetector(this, settings.getShakeIntensity(), () -> {
            if (isTimerEnabled && isPlaying) sendServiceCommand(ReadingService.ACTION_SHAKE);
        });

        handleIntent(getIntent());
        applyAppearance();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void initUi() {
        bottomControls = findViewById(R.id.bottomControls);
        seekContainer = findViewById(R.id.seekContainer);
        seekBarProgress = findViewById(R.id.seekBarProgress);
        tvPageCounter = findViewById(R.id.tvPageCounter);
        topBar = findViewById(R.id.topBar);
        timerToggleButton = findViewById(R.id.timerToggleButton);
        recyclerView = findViewById(R.id.recyclerView);
        loadingOverlay = findViewById(R.id.loadingOverlay);
        progressBarLoading = findViewById(R.id.progressBarLoading);
        tvLoadingMessage = findViewById(R.id.tvLoadingMessage);

        timerToggleButton.setText(isTimerEnabled ? "On" : "Off");
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new SentenceAdapter(currentSentences, new SentenceAdapter.OnSentenceClickListener() {
            @Override public void onSentenceClick(int position) { playAtPosition(position); }
            @Override public void onNavigateTo(int position) { recyclerView.scrollToPosition(position); }
            @Override public void onSingleTap() {
                if (topBar.getVisibility() == View.VISIBLE) hideControls(); else showControls();
            }
        }, settings);
        recyclerView.setAdapter(adapter);

        findViewById(R.id.loadBookBtn).setOnClickListener(v -> pickBook());
        findViewById(R.id.openShelfBtn).setOnClickListener(v -> startActivity(new Intent(this, BookshelfActivity.class)));
        findViewById(R.id.openSettingsBtn).setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        timerToggleButton.setOnClickListener(v -> {
            isTimerEnabled = !isTimerEnabled;
            settings.setTimerEnabled(isTimerEnabled);
            timerToggleButton.setText(isTimerEnabled ? "On" : "Off");
            sendServiceCommand(ReadingService.ACTION_TOGGLE_TIMER);
        });

        playPauseFab = findViewById(R.id.playPauseFab);
        playPauseFab.setOnClickListener(v -> sendServiceCommand(isPlaying ? ReadingService.ACTION_PAUSE : ReadingService.ACTION_PLAY));
        findViewById(R.id.rewindFab).setOnClickListener(v -> sendServiceCommand(ReadingService.ACTION_REWIND));
        findViewById(R.id.forwardFab).setOnClickListener(v -> sendServiceCommand(ReadingService.ACTION_FORWARD));
        findViewById(R.id.closeFab).setOnClickListener(v -> sendServiceCommand(ReadingService.ACTION_CLOSE));

        findViewById(R.id.openTocBtn).setOnClickListener(v -> {
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.rootLayout, new TableOfContentsFragment(currentToc, chapterIndex -> {
                        loadChapterAndPlay(chapterIndex, 0);
                        getSupportFragmentManager().popBackStack();
                    }))
                    .addToBackStack(null).commit();
        });
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void setupReceiver() {
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
    }

    private void handleIntent(Intent intent) {
        if (intent == null) return;
        Uri uri = intent.getData();
        if (uri == null) {
            String uriStr = intent.getStringExtra("BOOK_URI");
            if (uriStr == null) uriStr = settings.getLastOpenedBookUri();
            if (uriStr != null) uri = Uri.parse(uriStr);
        }
        if (uri != null) loadBookMetadata(uri);
    }

    private void loadBookMetadata(Uri uri) {
        currentBookUri = uri.toString();
        settings.setLastOpenedBookUri(currentBookUri);
        showLoading("Loading book structure...");

        executor.execute(() -> {
            try {
                BookEntity existing = db.bookDao().getBookByUri(currentBookUri);
                if (existing == null) {
                    BookLoader.BookInfo info = new BookLoader().extractBookInfo(this, uri);
                    if (info != null) {
                        BookEntity entity = new BookEntity(currentBookUri, info.title, info.author, info.coverUri);
                        entity.totalPages = info.toc.size();
                        db.bookDao().insertBook(entity);
                        
                        List<ChapterEntity> chapters = new ArrayList<>();
                        for (int i = 0; i < info.toc.size(); i++) {
                            BookLoader.TOCItem item = info.toc.get(i);
                            chapters.add(new ChapterEntity(currentBookUri, item.chapterIndex, item.title, item.href));
                        }
                        db.bookDao().insertChapters(chapters);
                        
                        runOnUiThread(() -> {
                            currentToc.clear();
                            currentToc.addAll(info.toc);
                            tvPageCounter.setText(String.format(Locale.getDefault(), "Chapter 1 / %d", info.toc.size()));
                            loadChapterFromDb(0, 0);
                        });
                    } else {
                        runOnUiThread(() -> {
                            hideLoading();
                            Toast.makeText(this, "Failed to load book structure", Toast.LENGTH_SHORT).show();
                        });
                    }
                } else {
                    List<ChapterEntity> chapters = db.bookDao().getChaptersForBook(currentBookUri);
                    if (existing.totalPages == 0 && !chapters.isEmpty()) {
                        existing.totalPages = chapters.size();
                        db.bookDao().updateBook(existing);
                    }
                    runOnUiThread(() -> {
                        currentToc.clear();
                        for (ChapterEntity c : chapters) {
                            currentToc.add(new BookLoader.TOCItem(c.title, c.href, c.index));
                        }
                        int lastChapter = settings.getLastReadChapterIndex(currentBookUri);
                        int lastSent = settings.getLastReadSentenceIndex(currentBookUri);
                        tvPageCounter.setText(String.format(Locale.getDefault(), "Chapter %d / %d", lastChapter + 1, chapters.size()));
                        loadChapterFromDb(lastChapter, lastSent);
                    });
                }
            } catch (Exception e) {
                Log.e(TAG, "Error loading metadata", e);
                runOnUiThread(this::hideLoading);
            }
        });
    }

    private void loadChapterFromDb(int chapterIndex, int sentenceIndex) {
        executor.execute(() -> {
            try {
                List<SentenceEntity> entities = db.bookDao().getSentencesForChapter(currentBookUri, chapterIndex);
                if (entities.isEmpty()) {
                    runOnUiThread(() -> showLoading("Loading chapter contents..."));
                    Intent intent = new Intent(this, ReadingService.class);
                    intent.setAction(ReadingService.ACTION_LOAD);
                    intent.putExtra("URI", currentBookUri);
                    intent.putExtra("CHAPTER_INDEX", chapterIndex);
                    intent.putExtra("SENTENCE_INDEX", sentenceIndex);
                    startServiceCommand(intent);
                } else {
                    runOnUiThread(() -> {
                        currentChapterIndex = chapterIndex;
                        currentSentences.clear();
                        for (SentenceEntity e : entities) {
                            currentSentences.add(new Sentence(e.globalIndex, e.text, e.link, e.internalId));
                        }
                        adapter.notifyDataSetChanged();
                        adapter.setHighlighted(sentenceIndex);
                        recyclerView.scrollToPosition(sentenceIndex);
                        hideLoading();
                    });
                }
            } catch (Exception e) {
                Log.e(TAG, "DB error loading chapter", e);
                runOnUiThread(this::hideLoading);
            }
        });
    }

    private void updatePlayState(boolean playing) {
        this.isPlaying = playing;
        runOnUiThread(() -> {
            playPauseFab.setImageResource(playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play);
        });
    }

    private void updateTimerButtonText(long remainingMs) {
        if (remainingMs <= 0) {
            timerToggleButton.setText(isTimerEnabled ? "On" : "Off");
        } else {
            long seconds = (remainingMs / 1000) % 60;
            long minutes = (remainingMs / (1000 * 60)) % 60;
            timerToggleButton.setText(String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds));
        }
    }

    private void showControls() {
        topBar.setVisibility(View.VISIBLE);
        bottomControls.setVisibility(View.VISIBLE);
        seekContainer.setVisibility(View.VISIBLE);
    }

    private void hideControls() {
        topBar.setVisibility(View.GONE);
        bottomControls.setVisibility(View.GONE);
        seekContainer.setVisibility(View.GONE);
    }

    private void pickBook() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, PICK_REQUEST);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_REQUEST && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                loadBookMetadata(uri);
            }
        }
    }

    private void loadChapterAndPlay(int chapterIndex, int sentenceIndex) {
        showLoading("Loading chapter...");
        Intent intent = new Intent(this, ReadingService.class);
        intent.setAction(ReadingService.ACTION_LOAD);
        intent.putExtra("URI", currentBookUri);
        intent.putExtra("CHAPTER_INDEX", chapterIndex);
        intent.putExtra("SENTENCE_INDEX", sentenceIndex);
        startServiceCommand(intent);
    }

    private void playAtPosition(int position) {
        Intent intent = new Intent(this, ReadingService.class);
        intent.setAction(ReadingService.ACTION_PLAY_AT);
        intent.putExtra("INDEX", position);
        startServiceCommand(intent);
    }

    private void sendServiceCommand(String action) {
        Intent intent = new Intent(this, ReadingService.class);
        intent.setAction(action);
        startServiceCommand(intent);
    }

    private void startServiceCommand(Intent intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    private void requestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, 101);
            }
        }
    }

    private void applyAppearance() {
        // App background, text sizes etc are managed via settings and styles
    }

    private void showLoading(String message) {
        tvLoadingMessage.setText(message);
        loadingOverlay.setVisibility(View.VISIBLE);
    }

    private void hideLoading() {
        loadingOverlay.setVisibility(View.GONE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (shakeDetector != null) shakeDetector.start();
        sendServiceCommand(ReadingService.ACTION_GET_STATUS);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (shakeDetector != null) shakeDetector.stop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try { unregisterReceiver(serviceUpdateReceiver); } catch (Exception ignored) {}
        executor.shutdown();
    }
}
