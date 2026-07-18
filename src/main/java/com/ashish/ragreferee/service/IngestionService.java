package com.ashish.ragreferee.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.ashish.ragreferee.model.Chunk;

/**
 * Turns an uploaded rulebook PDF into a list of embedded, citable Chunks.
 *
 * Chunking strategy: try several heading-detection patterns, in priority
 * order, since rulebooks format section titles very differently (numbered
 * like "4.2 The Robber", ALL CAPS like "OVERVIEW", Title Case, Roman
 * numerals, etc). Each candidate split is sanity-checked before being
 * accepted — if a pattern only matches a few headings and dumps most of
 * the document into one oversized leftover chunk, it's rejected in favor
 * of the next strategy.
 *
 * Falls back to fixed-size chunking with overlap if no strategy produces
 * a plausible sectioning, so it still works on rulebooks with unusual
 * or absent heading formatting.
 */
@Service
public class IngestionService {

    // Tried in order. First one that produces a "plausible" sectioning wins.
    private static final List<Pattern> HEADING_STRATEGIES = List.of(
            // Numbered: "4.2 The Robber", "Section 3: Trading"
            Pattern.compile("(?m)^(?:Section\\s+)?\\d+(?:\\.\\d+)*[\\.:]?\\s+[A-Z][^\\n]{2,60}$"),

            // ALL CAPS standalone line: "OVERVIEW", "VETO POWER", "GAME CONTENTS"
            Pattern.compile("(?m)^[A-Z][A-Z\\s&'\\-]{2,44}[A-Z]\\s*$"),

            // Roman numerals: "IV. Combat", "II - Setup"
            Pattern.compile("(?m)^[IVXLC]+[\\.\\-:]\\s+[A-Z][^\\n]{2,60}$"),

            // Title Case short standalone line acting as a header, e.g. "Presidential Powers"
            // Kept last since it's the loosest / most likely to false-positive.
            Pattern.compile("(?m)^(?:[A-Z][a-z]+\\s?){1,6}$")
    );

    private static final int FALLBACK_CHUNK_SIZE = 1200; // characters
    private static final int FALLBACK_OVERLAP = 150;      // characters

    // Plausibility thresholds for validating a candidate heading strategy.
    private static final int MIN_SECTIONS = 3;
    private static final int MAX_SECTIONS = 80;
    private static final double MAX_SINGLE_SECTION_SHARE = 0.4; // no one section > 40% of doc

    private final EmbeddingService embeddingService;

    public IngestionService(EmbeddingService embeddingService) {
        this.embeddingService = embeddingService;
    }

    public List<Chunk> ingest(MultipartFile pdfFile) throws IOException {
        String rawText = extractText(pdfFile);
        List<String[]> sections = splitBySections(rawText); // [label, text]

        // Filter blank sections first, then embed everything in one batched
        // request instead of one request per section — keeps a whole rulebook
        // upload to a single API call regardless of how many sections it has,
        // which matters on rate-limited free tiers (e.g. 3 requests/minute).
        List<String[]> nonBlankSections = new ArrayList<>();
        List<String> textsToEmbed = new ArrayList<>();
        for (String[] section : sections) {
            String text = section[1].trim();
            if (text.isBlank()) continue;
            nonBlankSections.add(section);
            textsToEmbed.add(text);
        }

        List<float[]> embeddings = embeddingService.embedBatch(textsToEmbed);

        List<Chunk> chunks = new ArrayList<>();
        for (int i = 0; i < nonBlankSections.size(); i++) {
            String label = nonBlankSections.get(i)[0];
            String text = textsToEmbed.get(i);
            chunks.add(new Chunk(UUID.randomUUID().toString(), label, text, embeddings.get(i)));
        }
        return chunks;
    }

    private String extractText(MultipartFile pdfFile) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdfFile.getBytes())) {
            PDFTextStripper stripper = new PDFTextStripper();
            return stripper.getText(document);
        }
    }

    /**
     * Tries each heading strategy in order and returns the first one whose
     * output looks like a real sectioning of the document. Falls back to
     * fixed-size overlapping chunks if none of them do.
     */
    private List<String[]> splitBySections(String rawText) {
        for (Pattern pattern : HEADING_STRATEGIES) {
            List<String[]> candidate = splitByPattern(rawText, pattern);
            if (isPlausibleSectioning(candidate, rawText)) {
                return candidate;
            }
        }
        return fallbackChunk(rawText);
    }

    /** Splits raw text into (label, body) pairs using a single heading pattern. */
    private List<String[]> splitByPattern(String rawText, Pattern pattern) {
        List<String[]> sections = new ArrayList<>();
        Matcher matcher = pattern.matcher(rawText);

        List<Integer> headingStarts = new ArrayList<>();
        List<String> headingTitles = new ArrayList<>();
        while (matcher.find()) {
            headingStarts.add(matcher.start());
            headingTitles.add(matcher.group().trim());
        }

        if (headingStarts.isEmpty()) {
            return sections; // empty -> caller's plausibility check will reject it
        }

        for (int i = 0; i < headingStarts.size(); i++) {
            int start = headingStarts.get(i);
            int end = (i + 1 < headingStarts.size()) ? headingStarts.get(i + 1) : rawText.length();
            String body = rawText.substring(start, end);
            sections.add(new String[]{headingTitles.get(i), body});
        }
        return sections;
    }

    /**
     * A sectioning is "plausible" if it found a reasonable number of sections
     * (not zero, not hundreds of false positives) and no single section
     * swallowed most of the document — which is exactly what happened with
     * the Secret Hitler PDF when only numbered sub-steps were matched and
     * everything after the last one got lumped into one giant leftover chunk.
     */
    private boolean isPlausibleSectioning(List<String[]> sections, String rawText) {
        if (sections.size() < MIN_SECTIONS || sections.size() > MAX_SECTIONS) {
            return false;
        }
        int docLength = Math.max(rawText.length(), 1);
        for (String[] section : sections) {
            double share = section[1].length() / (double) docLength;
            if (share > MAX_SINGLE_SECTION_SHARE) {
                return false;
            }
        }
        return true;
    }

    private List<String[]> fallbackChunk(String rawText) {
        List<String[]> chunks = new ArrayList<>();
        int pos = 0;
        int chunkNumber = 1;
        while (pos < rawText.length()) {
            int end = Math.min(pos + FALLBACK_CHUNK_SIZE, rawText.length());
            String body = rawText.substring(pos, end);
            chunks.add(new String[]{"Chunk " + chunkNumber, body});
            chunkNumber++;
            pos = end - FALLBACK_OVERLAP;
            if (pos <= 0 || end == rawText.length()) break;
        }
        return chunks;
    }
}