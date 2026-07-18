package com.ashish.ragreferee.controller;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.ashish.ragreferee.model.AskRequest;
import com.ashish.ragreferee.model.Chunk;
import com.ashish.ragreferee.model.RulingResponse;
import com.ashish.ragreferee.service.IngestionService;
import com.ashish.ragreferee.service.LlmService;
import com.ashish.ragreferee.service.RetrievalService;

@RestController
public class RefereeController {

    private static final int TOP_K = 3;

    private final IngestionService ingestionService;
    private final RetrievalService retrievalService;
    private final LlmService llmService;

    public RefereeController(IngestionService ingestionService,
                              RetrievalService retrievalService,
                              LlmService llmService) {
        this.ingestionService = ingestionService;
        this.retrievalService = retrievalService;
        this.llmService = llmService;
    }

    /** Upload a rulebook PDF once. Chunks + embeds it and holds it in memory. */
    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> upload(@RequestParam("file") MultipartFile file) throws IOException {
        List<Chunk> chunks = ingestionService.ingest(file);
        retrievalService.indexAll(chunks);

        return ResponseEntity.ok(Map.of(
                "message", "Rulebook ingested successfully",
                "chunkCount", chunks.size(),
                "sections", chunks.stream().map(Chunk::getLabel).collect(Collectors.toList())
        ));
    }   

    /** Ask a rules question / put a claimed move to the referee. */
    @PostMapping("/ask")
    public ResponseEntity<?> ask(@RequestBody AskRequest request) {
        if (retrievalService.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "No rulebook has been uploaded yet. POST a PDF to /upload first."
            ));
        }

        List<Chunk> topChunks = retrievalService.retrieveTopK(request.getQuestion(), TOP_K);
        String verdict = llmService.answerGrounded(request.getQuestion(), topChunks);
        List<String> citedSections = topChunks.stream().map(Chunk::getLabel).collect(Collectors.toList());

        return ResponseEntity.ok(new RulingResponse(verdict, citedSections));
    }
}
