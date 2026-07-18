package com.ashish.ragreferee.service;

import com.ashish.ragreferee.model.Chunk;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * Holds ingested chunks in memory and retrieves the top-matching ones
 * for a given question via cosine similarity.
 *
 * In-memory is enough at this scale (one rulebook = tens of chunks).
 * A real vector DB (Chroma, pgvector, etc.) would only be worth the
 * added complexity if you were indexing many large documents at once.
 */
@Service
public class RetrievalService {

    private final EmbeddingService embeddingService;
    private final List<Chunk> store = new CopyOnWriteArrayList<>();

    public RetrievalService(EmbeddingService embeddingService) {
        this.embeddingService = embeddingService;
    }

    public void indexAll(List<Chunk> chunks) {
        store.clear();
        store.addAll(chunks);
    }

    public boolean isEmpty() {
        return store.isEmpty();
    }

    /** Returns the top-K chunks most semantically similar to the question. */
    public List<Chunk> retrieveTopK(String question, int k) {
        float[] questionEmbedding = embeddingService.embed(question);

        return store.stream()
                .sorted(Comparator.comparingDouble(
                        (Chunk c) -> embeddingService.cosineSimilarity(questionEmbedding, c.getEmbedding())
                ).reversed())
                .limit(k)
                .collect(Collectors.toList());
    }
}
