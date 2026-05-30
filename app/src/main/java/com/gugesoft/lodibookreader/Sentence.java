package com.gugesoft.lodibookreader;

import java.io.Serializable;

public class Sentence implements Serializable {
    public int id;
    public String text;
    public String link; // Outgoing link (http or internal)
    public String internalId; // Target ID for incoming links

    public Sentence(int id, String text, String link) {
        this.id = id;
        this.text = text;
        this.link = link;
    }

    public Sentence(int id, String text, String link, String internalId) {
        this.id = id;
        this.text = text;
        this.link = link;
        this.internalId = internalId;
    }

    public int getId() { return id; }
    public String getText() { return text; }
    public String getLink() { return link; }
    public String getInternalId() { return internalId; }
}