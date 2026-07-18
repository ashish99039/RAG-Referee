package com.ashish.ragreferee.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Wraps calls to an embeddings API (Voyage AI's voyage-3.5-lite by default)
 * and provides the similarity math used for retrieval.
 *
 * Swap the base URL / model name here if you want to use a different provider.
 */
@Service
public class EmbeddingService {

    private final WebClient webClient;
    private final String model;

    public EmbeddingService(
            @Value("${embedding.api.base-url:https://api.voyageai.com/v1}") String baseUrl,
            @Value("${embedding.api.key}") String apiKey,
            @Value("${embedding.model:voyage-3.5-lite}") String model
    ) {
        this.model = model;
        this.webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    /** Calls the embeddings API for a single piece of text and returns its vector. */
    public float[] embed(String text) {
        return embedBatch(List.of(text)).get(0);
    }

    /**
     * Calls the embeddings API once for a whole batch of texts and returns
     * one vector per input, in the same order. Voyage (like OpenAI) accepts
     * a list under "input", so a whole rulebook's chunks can be embedded in
     * a single request instead of one request per chunk — this matters a
     * lot on rate-limited free tiers, where RPM (requests/minute) can be
     * as low as 3 without a payment method on file. Batching turns an
     * N-chunk upload into 1 request instead of N.
     */
    @SuppressWarnings("unchecked")
    public List<float[]> embedBatch(List<String> texts) {
        if (texts.isEmpty()) {
            return List.of();
        }

        Map<String, Object> requestBody = Map.of(
                "model", model,
                "input", texts,
                "input_type", "document"
        );

        Map<String, Object> response = webClient.post()
                .uri("/embeddings")
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(Map.class)
                .block();

        List<Map<String, Object>> data = (List<Map<String, Object>>) response.get("data");

        // Voyage returns each item's "index" field so results can be matched
        // back to the input order even if the API ever returns them out of order.
        float[][] resultsInOrder = new float[texts.size()][];
        for (Map<String, Object> item : data) {
            int index = ((Number) item.get("index")).intValue();
            List<Double> vector = (List<Double>) item.get("embedding");

            float[] vec = new float[vector.size()];
            for (int i = 0; i < vector.size(); i++) {
                vec[i] = vector.get(i).floatValue();
            }
            resultsInOrder[index] = vec;
        }

        List<float[]> results = new ArrayList<>(texts.size());
        for (float[] vec : resultsInOrder) {
            results.add(vec);
        }
        return results;
    }

    /** Standard cosine similarity between two equal-length vectors. */
    public double cosineSimilarity(float[] a, float[] b) {
        double dot = 0.0, normA = 0.0, normB = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}