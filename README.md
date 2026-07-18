# RAG Referee

A rules referee for board games (or any rulebook-style document): upload a rulebook PDF,
then ask it about a claimed move. It answers using **only** the actual text of the rulebook,
and cites the exact section it used — so it never bluffs a rule.

## Why this exists

Most "chat with your PDF" demos are thin wrappers around an LLM call. This one is built to
demonstrate a real Retrieval-Augmented Generation (RAG) pipeline:

1. **Ingest** — the rulebook is parsed, split into sections (not arbitrary character chunks —
   real section boundaries, so each chunk is one complete rule), and each section is
   converted into a vector embedding.
2. **Retrieve** — when a question comes in, it's embedded the same way, and compared via
   cosine similarity against every stored section to find the most relevant ones.
3. **Generate** — the LLM is given *only* those retrieved sections and instructed to answer
   from them alone, citing which section it used, and to say clearly if the rulebook doesn't
   cover the situation rather than guess.

That grounding + citation loop is the actual point — it's what separates this from
"paste your question into ChatGPT," and it's a directly transferable skill to any
retrieval-augmented system (internal docs search, legal/compliance Q&A, etc.).

## Architecture

```
PDF upload → IngestionService (PDFBox extraction + section chunking)
                → EmbeddingService (embeds each chunk)
                → RetrievalService (in-memory vector store)

Question → RetrievalService (cosine similarity top-K)
                → LlmService (grounded prompt, cites sections)
                → RulingResponse (verdict + citations)
```

## Running it

1. Set your API keys as environment variables:
   ```
   export EMBEDDING_API_KEY=sk-...       # OpenAI key, for embeddings
   export LLM_API_KEY=sk-ant-...          # Anthropic key, for the grounded answer
   ```
2. Build and run:
   ```
   mvn spring-boot:run
   ```
3. Upload a rulebook:
   ```
   curl -F "file=@catan_rules.pdf" http://localhost:8080/upload
   ```
4. Ask it something:
   ```
   curl -X POST http://localhost:8080/ask \
     -H "Content-Type: application/json" \
     -d '{"question": "Can I trade during the robber phase if I have exactly 7 cards?"}'
   ```

## Notes / things to extend

- The vector store is in-memory and rebuilt on every `/upload` call — fine for a single
  rulebook demo. Swap in a persistent store (Chroma, pgvector) if you want it to hold
  multiple documents across restarts.
- `LlmService` is written against Anthropic's Messages API; swap the base URL, headers,
  and response parsing if you'd rather call OpenAI's chat completions endpoint.
- The section-heading regex in `IngestionService` is tuned for numbered headings like
  "4.2 The Robber" — adjust the pattern if your source document is formatted differently.
  It falls back to fixed-size overlapping chunks automatically if no headings are detected.
