package com.ashish.ragreferee.service;

import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import com.ashish.ragreferee.model.Chunk;

/**
 * Calls the LLM with a prompt that is explicitly restricted to the
 * retrieved passages. This is the "grounding" step that stops the
 * model from guessing rules from general training knowledge — if the
 * answer isn't in the passages it's told to say so.
 *
 * Written against Google's Gemini API (generateContent), which has a
 * genuinely free tier — no payment method required. Swap the base URL,
 * headers, and request/response parsing below if you'd rather use
 * Anthropic's or OpenAI's chat APIs instead; both have a different
 * request/response shape from Gemini's.
 */
@Service
public class LlmService {

    private final WebClient webClient;
    private final String model;

    public LlmService(
            @Value("${llm.api.base-url:https://generativelanguage.googleapis.com/v1beta}") String baseUrl,
            @Value("${llm.api.key}") String apiKey,
            @Value("${llm.model:gemini-3.5-flash}") String model
    ) {
        this.model = model;
        this.webClient = WebClient.builder()
                .baseUrl(baseUrl)
                // Gemini authenticates via this header rather than Anthropic's
                // x-api-key / anthropic-version pair.
                .defaultHeader("x-goog-api-key", apiKey)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @SuppressWarnings("unchecked")
    public String answerGrounded(String question, List<Chunk> contextChunks) {
        StringBuilder passages = new StringBuilder();
        for (Chunk chunk : contextChunks) {
            passages.append("[").append(chunk.getLabel()).append("]\n")
                    .append(chunk.getText()).append("\n\n");
        }

        String prompt = """
                You are a rules referee. Answer the question using ONLY the passages below.
                Cite the section label(s) you used. If the passages don't contain the
                answer, say clearly that the rulebook doesn't cover this — do not guess.

                Passages:
                %s

                Question: %s
                """.formatted(passages, question);

        // Gemini's request shape: a "contents" array of turns, each with a
        // "parts" array containing the actual text. There's no top-level
        // "model" or "max_tokens" field in the body — the model is in the
        // URL path, and output length limits go in an optional
        // "generationConfig" object (omitted here; Gemini's default is
        // generous enough for this use case).
        Map<String, Object> requestBody = Map.of(
                "contents", List.of(
                        Map.of("parts", List.of(Map.of("text", prompt)))
                )
        );

        Map<String, Object> response;
        try {
            response = webClient.post()
                    // Gemini's URI includes the model name and action directly,
                    // unlike Anthropic's fixed "/messages" path.
                    .uri("/models/{model}:generateContent", model)
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();
        } catch (WebClientResponseException e) {
            System.err.println("Gemini API error " + e.getStatusCode() + ": " + e.getResponseBodyAsString());
            throw e;
        }

        // Gemini's response shape: { "candidates": [ { "content": { "parts": [ { "text": "..." } ] } } ] }
        List<Map<String, Object>> candidates = (List<Map<String, Object>>) response.get("candidates");
        Map<String, Object> content = (Map<String, Object>) candidates.get(0).get("content");
        List<Map<String, Object>> parts = (List<Map<String, Object>>) content.get("parts");
        return (String) parts.get(0).get("text");
    }
}