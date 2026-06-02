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

public class BookshelfActivity extends AppCompatActivity {

    private RecyclerView recyclerView;
    private BookAdapter adapter;
    private BookRepository bookRepo;
    private ExtendedFloatingActionButton deleteButton;
    private final List<BookItem> books = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_bookshelf);

        recyclerView = findViewById(R.id.recyclerViewBooks);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));

        bookRepo = new BookRepository(this);
        
        adapter = new BookAdapter(books, book -> {
            stopCurrentReadingSession();
            Intent intent = new Intent(this, MainActivity.class);
            intent.putExtra("BOOK_URI", book.uri);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(intent);
        });
        recyclerView.setAdapter(adapter);

        deleteButton = findViewById(R.id.buttonDelete);
        deleteButton.setOnClickListener(v -> {
            List<BookItem> selected = adapter.getSelectedBooks();
            for (BookItem b : selected) {
                bookRepo.deleteBook(b);
            }
            books.removeAll(selected);
            adapter.exitSelectionMode();
            deleteButton.setVisibility(View.GONE);
            adapter.notifyDataSetChanged();
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
        books.clear();
        books.addAll(bookRepo.getBooks());
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    private void stopCurrentReadingSession() {
        try {
            Intent serviceIntent = new Intent(this, ReadingService.class);
            stopService(serviceIntent);
        } catch (Exception e) {
            Log.e("Bookshelf", "Error stopping previous session", e);
        }
    }
}
