package com.gugesoft.lodibookreader;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.gson.reflect.TypeToken;
import com.google.gson.Gson;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

public class BookRepository {

    private static final String PREF_NAME = "book_repo";
    private static final String KEY_BOOKS = "books";

    private SharedPreferences prefs;
    private Gson gson = new Gson();

    public BookRepository(Context context) {
        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public List<BookItem> getBooks() {
        String json = prefs.getString(KEY_BOOKS, "[]");
        Type type = new TypeToken<List<BookItem>>() {}.getType();
        return gson.fromJson(json, type);
    }

    public void saveBooks(List<BookItem> books) {
        prefs.edit().putString(KEY_BOOKS, gson.toJson(books)).apply();
    }

    public void saveOrUpdateBook(BookItem item) {
        List<BookItem> books = getBooks();

        for (BookItem b : books) {
            if (b.uri.equals(item.uri)) {
                // Update all fields
                b.title = item.title;
                b.author = item.author;
                b.coverUri = item.coverUri;
                b.lastSentenceIndex = item.lastSentenceIndex;
                saveBooks(books);
                return;
            }
        }

        books.add(item);
        saveBooks(books);
    }

    public BookItem findBook(String uri) {
        for (BookItem b : getBooks()) {
            if (b.uri.equals(uri)) return b;
        }
        return null;
    }
    public void deleteBook(BookItem item) {
        List<BookItem> books = getBooks();
        books.removeIf(b -> b.uri.equals(item.uri));
        saveBooks(books);
    }

}