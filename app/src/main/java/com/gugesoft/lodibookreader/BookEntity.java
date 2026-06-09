package com.gugesoft.lodibookreader;

import androidx.room.Entity;
import androidx.room.PrimaryKey;
import androidx.annotation.NonNull;

@Entity(tableName = "books")
public class BookEntity {
    @PrimaryKey
    @NonNull
    public String uri;
    public String title;
    public String author;
    public String coverUri;
    public int lastSentenceIndex;
    public int totalPages;
    public int currentPage;
    public boolean isFullyLoaded;

    public BookEntity(@NonNull String uri, String title, String author, String coverUri) {
        this.uri = uri;
        this.title = (title != null && !title.isEmpty()) ? title : "Unknown Book";
        this.author = (author != null) ? author : "";
        this.coverUri = coverUri;
        this.isFullyLoaded = false;
    }
}
