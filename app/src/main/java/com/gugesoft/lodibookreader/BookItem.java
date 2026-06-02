package com.gugesoft.lodibookreader;

import java.util.Objects;

public class BookItem {
    public String uri;
    public String title;
    public String author;
    public String coverUri;
    public int lastSentenceIndex;
    public int totalPages;
    public int currentPage;

    public BookItem(String uri, String title, String author, String coverUri, int lastSentenceIndex, int totalPages, int currentPage) {
        this.uri = uri;
        this.title = (title != null && !title.isEmpty()) ? title : "Unknown Book";
        this.author = (author != null) ? author : "";
        this.coverUri = coverUri;
        this.lastSentenceIndex = lastSentenceIndex;
        this.totalPages = totalPages;
        this.currentPage = currentPage;
    }

    public BookItem(String uri, String title, int lastIndex) {
        this(uri, title, "", null, lastIndex, 0, 0);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        BookItem bookItem = (BookItem) o;
        return Objects.equals(uri, bookItem.uri);
    }

    @Override
    public int hashCode() {
        return Objects.hash(uri);
    }
}
