package com.gugesoft.lodibookreader;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

@Dao
public interface BookDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertBook(BookEntity book);

    @Update
    void updateBook(BookEntity book);

    @Query("SELECT * FROM books WHERE uri = :uri")
    BookEntity getBookByUri(String uri);

    @Query("SELECT * FROM books")
    List<BookEntity> getAllBooks();

    @Query("DELETE FROM books WHERE uri = :uri")
    void deleteBookByUri(String uri);

    @Query("UPDATE books SET currentPage = :page WHERE uri = :uri")
    void updateProgress(String uri, int page);

    @Query("UPDATE books SET totalPages = :total WHERE uri = :uri")
    void updateTotalPages(String uri, int total);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertChapters(List<ChapterEntity> chapters);

    @Query("SELECT * FROM chapters WHERE bookUri = :bookUri ORDER BY `index` ASC")
    List<ChapterEntity> getChaptersForBook(String bookUri);

    @Query("UPDATE chapters SET isLoaded = :isLoaded, sentenceCount = :sentenceCount WHERE bookUri = :bookUri AND `index` = :chapterIndex")
    void updateChapterLoadStatus(String bookUri, int chapterIndex, boolean isLoaded, int sentenceCount);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertSentences(List<SentenceEntity> sentences);

    @Query("SELECT * FROM sentences WHERE bookUri = :bookUri AND chapterIndex = :chapterIndex ORDER BY globalIndex ASC")
    List<SentenceEntity> getSentencesForChapter(String bookUri, int chapterIndex);

    @Query("DELETE FROM sentences WHERE bookUri = :bookUri AND chapterIndex = :chapterIndex")
    void deleteSentencesForChapter(String bookUri, int chapterIndex);
    
    @Query("SELECT COUNT(*) FROM sentences WHERE bookUri = :bookUri")
    int getSentenceCountForBook(String bookUri);
}
