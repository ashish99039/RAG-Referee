package com.ashish.ragreferee.model;

/**
 * A single retrievable unit of the source document: a section of text,
 * a human-readable label for citation (e.g. "Section 4.2: The Robber"),
 * and its embedding vector used for similarity search.
 */
public class Chunk {

    private final String id;
    private final String label;
    private final String text;
    private final float[] embedding;

    public Chunk(String id, String label, String text, float[] embedding) {
        this.id = id;
        this.label = label;
        this.text = text;
        this.embedding = embedding;
    }

    public String getId() {
        return id;
    }

    public String getLabel() {
        return label;
    }

    public String getText() {
        return text;
    }

    public float[] getEmbedding() {
        return embedding;
    }
}
