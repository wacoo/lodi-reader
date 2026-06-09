package com.gugesoft.lodibookreader;

import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;
import androidx.annotation.NonNull;

@Entity(tableName = "chapters",
        foreignKeys = @ForeignKey(entity = BookEntity.class,
                parentColumns = "uri",
                childColumns = "bookUri",
                onDelete = ForeignKey.CASCADE),
        indices = {@Index(value = {"bookUri", "index"}, unique = true)})
public class ChapterEntity {
    @PrimaryKey(autoGenerate = true)
    public int id;
    @NonNull
    public String bookUri;
    public int index;
    public String title;
    public String href;
    public boolean isLoaded;
    public int sentenceCount;

    public ChapterEntity(@NonNull String bookUri, int index, String title, String href) {
        this.bookUri = bookUri;
        this.index = index;
        this.title = title;
        this.href = href;
        this.isLoaded = false;
        this.sentenceCount = 0;
    }
}
