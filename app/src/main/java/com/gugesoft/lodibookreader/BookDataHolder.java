package com.gugesoft.lodibookreader;

import java.util.ArrayList;
import java.util.List;

/**
 * Singleton to hold book data in memory.
 * This avoids passing large lists via Intent, which causes TransactionTooLargeException.
 */
public class BookDataHolder {
    private static BookDataHolder instance;
    
    private final List<Sentence> sentences = new ArrayList<>();
    private final List<BookLoader.TOCItem> toc = new ArrayList<>();
    private String title = "Lodi Reader";
    private String author = "";

    private BookDataHolder() {}

    public static synchronized BookDataHolder getInstance() {
        if (instance == null) {
            instance = new BookDataHolder();
        }
        return instance;
    }

    public List<Sentence> getSentences() {
        return sentences;
    }

    public List<BookLoader.TOCItem> getToc() {
        return toc;
    }
    
    public String getTitle() {
        return title;
    }
    
    public String getAuthor() {
        return author;
    }

    public void replaceData(List<Sentence> newSentences, List<BookLoader.TOCItem> newToc, String title, String author) {
        sentences.clear();
        if (newSentences != null) sentences.addAll(newSentences);
        
        toc.clear();
        if (newToc != null) toc.addAll(newToc);
        
        this.title = (title != null && !title.isEmpty()) ? title : "Lodi Reader";
        this.author = (author != null) ? author : "";
    }
}
