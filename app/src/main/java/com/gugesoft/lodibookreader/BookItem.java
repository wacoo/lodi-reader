package com.gugesoft.lodibookreader;

import java.util.Objects;

public class BookItem {
    public String uri;
    public String title;
    public String author;
    public String coverUri;
    public int lastSentenceIndex;

    public BookItem(String uri, String title, String author, String coverUri, int lastSentenceIndex) {
        this.uri = uri;
        this.title = (title != null && !title.isEmpty()) ? title : "Unknown Book";
        this.author = (author != null) ? author : "";
        this.coverUri = coverUri;
        this.lastSentenceIndex = lastSentenceIndex;
    }

    public BookItem(String uri, String title, int lastIndex) {
        this(uri, title, "", null, lastIndex);
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
