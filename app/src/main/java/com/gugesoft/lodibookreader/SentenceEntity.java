package com.gugesoft.lodibookreader;

import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;
import androidx.annotation.NonNull;

@Entity(tableName = "sentences",
        foreignKeys = @ForeignKey(entity = BookEntity.class,
                parentColumns = "uri",
                childColumns = "bookUri",
                onDelete = ForeignKey.CASCADE),
        indices = {
                @Index("bookUri"),
                @Index(value = {"bookUri", "chapterIndex"}),
                @Index(value = {"bookUri", "chapterIndex", "globalIndex"}, unique = true)
        })
public class SentenceEntity {
    @PrimaryKey(autoGenerate = true)
    public int id;
    
    @NonNull
    public String bookUri;
    public int chapterIndex;
    public int globalIndex; // Index within chapter
    public String text;
    public String link;
    public String internalId;

    public SentenceEntity(@NonNull String bookUri, int chapterIndex, int globalIndex, String text, String link, String internalId) {
        this.bookUri = bookUri;
        this.chapterIndex = chapterIndex;
        this.globalIndex = globalIndex;
        this.text = text;
        this.link = link;
        this.internalId = internalId;
    }
}
