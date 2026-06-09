package com.gugesoft.lodibookreader;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class BookshelfActivity extends AppCompatActivity {

    private RecyclerView recyclerView;
    private BookAdapter adapter;
    private AppDatabase db;
    private ExtendedFloatingActionButton deleteButton;
    private final List<BookItem> books = new ArrayList<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_bookshelf);

        recyclerView = findViewById(R.id.recyclerViewBooks);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));

        db = AppDatabase.getInstance(this);
        
        adapter = new BookAdapter(books, book -> {
            Intent intent = new Intent(this, MainActivity.class);
            intent.putExtra("BOOK_URI", book.uri);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(intent);
        });
        recyclerView.setAdapter(adapter);

        deleteButton = findViewById(R.id.buttonDelete);
        deleteButton.setOnClickListener(v -> {
            List<BookItem> selected = adapter.getSelectedBooks();
            executor.execute(() -> {
                for (BookItem b : selected) {
                    db.bookDao().deleteBookByUri(b.uri);
                }
                runOnUiThread(() -> {
                    books.removeAll(selected);
                    adapter.exitSelectionMode();
                    deleteButton.setVisibility(View.GONE);
                    adapter.notifyDataSetChanged();
                });
            });
        });

        adapter.setOnSelectionModeChange(active -> {
            if (active) {
                deleteButton.show();
            } else {
                deleteButton.hide();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshBooks();
    }

    private void refreshBooks() {
        executor.execute(() -> {
            List<BookEntity> entities = db.bookDao().getAllBooks();
            List<BookItem> items = new ArrayList<>();
            for (BookEntity e : entities) {
                items.add(new BookItem(e.uri, e.title, e.author, e.coverUri, e.lastSentenceIndex, e.totalPages, e.currentPage));
            }
            runOnUiThread(() -> {
                books.clear();
                books.addAll(items);
                adapter.notifyDataSetChanged();
            });
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
    }
}
