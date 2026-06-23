package com.storycreator.tts;

import com.storycreator.ai.router.TtsProviderRegistry;
import com.storycreator.core.port.tts.TtsRequest;
import com.storycreator.persistence.entity.ChapterEntity;
import com.storycreator.persistence.repository.ChapterRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Service
public class TtsService {

    private static final Logger log = LoggerFactory.getLogger(TtsService.class);
    private static final int MAX_CHUNK_LENGTH = 200;
    private static final int MIN_CHUNK_LENGTH = 10;
    private static final Pattern SENTENCE_BOUNDARY = Pattern.compile("(?<=[.。!！?？\\n])");
    private static final Pattern MARKDOWN_PATTERN = Pattern.compile("(?m)^#{1,6}\\s+|\\*\\*|\\*|~~|`{1,3}|\\[([^\\]]+)\\]\\([^)]+\\)");

    private final TtsProviderRegistry ttsProviderRegistry;
    private final ChapterRepository chapterRepository;

    public TtsService(TtsProviderRegistry ttsProviderRegistry, ChapterRepository chapterRepository) {
        this.ttsProviderRegistry = ttsProviderRegistry;
        this.chapterRepository = chapterRepository;
    }

    /**
     * Returns the text chunks for a chapter (for chunk-by-chunk playback).
     */
    public List<String> getChapterChunks(Long projectId, int chapterNumber) {
        return getChapterChunks(projectId, chapterNumber, MIN_CHUNK_LENGTH, MAX_CHUNK_LENGTH);
    }

    /**
     * Returns the text chunks for a chapter with configurable min/max chunk lengths.
     */
    public List<String> getChapterChunks(Long projectId, int chapterNumber, int minLen, int maxLen) {
        ChapterEntity chapter = chapterRepository.findByProjectIdAndChapterNumber(projectId, chapterNumber)
                .orElseThrow(() -> new IllegalArgumentException("Chapter not found: " + chapterNumber));

        String text = chapter.getContent();
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Chapter " + chapterNumber + " has no content");
        }

        String cleaned = cleanText(text);
        List<String> chunks = splitIntoChunks(cleaned, minLen, maxLen);

        // Prepend chapter title as the first chunk (e.g., "第13章 琉璃")
        String title = chapter.getTitle();
        String titleChunk;
        if (title != null && !title.isBlank()) {
            titleChunk = "第" + chapterNumber + "章 " + title;
        } else {
            titleChunk = "第" + chapterNumber + "章";
        }
        chunks.add(0, titleChunk);

        return chunks;
    }

    /**
     * Generate audio for a single text chunk.
     */
    public byte[] generateAudioForChunk(Long configId, String text, String voice, String format, double speed) {
        TtsProviderRegistry.ResolvedTtsConfig resolved = ttsProviderRegistry.resolve(configId);
        if (resolved == null) {
            throw new IllegalArgumentException("TTS config not found or inactive: " + configId);
        }

        TtsRequest request = TtsRequest.builder()
                .model(resolved.modelId())
                .input(text)
                .voice(voice != null ? voice : "alloy")
                .responseFormat(format != null ? format : "mp3")
                .speed(speed > 0 ? speed : 1.0)
                .baseUrl(resolved.baseUrl())
                .apiKey(resolved.apiKey())
                .build();

        return resolved.provider().generateAudio(request);
    }

    public byte[] generateChapterAudio(Long projectId, Long configId, int chapterNumber,
                                       String voice, String format, double speed) {
        ChapterEntity chapter = chapterRepository.findByProjectIdAndChapterNumber(projectId, chapterNumber)
                .orElseThrow(() -> new IllegalArgumentException("Chapter not found: " + chapterNumber));

        String text = chapter.getContent();
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Chapter " + chapterNumber + " has no content");
        }

        return generateAudioFromText(configId, text, voice, format, speed);
    }

    public byte[] generateMultiChapterAudio(Long projectId, Long configId, List<Integer> chapterNumbers,
                                            String voice, String format, double speed) {
        List<ChapterEntity> chapters;
        if (chapterNumbers == null || chapterNumbers.isEmpty()) {
            chapters = chapterRepository.findByProjectIdOrderByChapterNumber(projectId);
        } else {
            chapters = new ArrayList<>();
            for (int num : chapterNumbers) {
                chapterRepository.findByProjectIdAndChapterNumber(projectId, num)
                        .ifPresent(chapters::add);
            }
        }

        if (chapters.isEmpty()) {
            throw new IllegalArgumentException("No chapters found");
        }

        StringBuilder fullText = new StringBuilder();
        for (ChapterEntity chapter : chapters) {
            if (chapter.getContent() != null && !chapter.getContent().isBlank()) {
                if (!fullText.isEmpty()) {
                    fullText.append("\n\n");
                }
                String title = chapter.getTitle();
                if (title != null && !title.isBlank()) {
                    fullText.append("第").append(chapter.getChapterNumber()).append("章 ").append(title).append("\n\n");
                }
                fullText.append(chapter.getContent());
            }
        }

        if (fullText.isEmpty()) {
            throw new IllegalArgumentException("No chapter content found");
        }

        return generateAudioFromText(configId, fullText.toString(), voice, format, speed);
    }

    private byte[] generateAudioFromText(Long configId, String rawText, String voice, String format, double speed) {
        TtsProviderRegistry.ResolvedTtsConfig resolved = ttsProviderRegistry.resolve(configId);
        if (resolved == null) {
            throw new IllegalArgumentException("TTS config not found or inactive: " + configId);
        }

        String cleanedText = cleanText(rawText);
        List<String> chunks = splitIntoChunks(cleanedText, MIN_CHUNK_LENGTH, MAX_CHUNK_LENGTH);

        log.info("TTS generating: {} chunks, total {} chars", chunks.size(), cleanedText.length());

        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            for (int i = 0; i < chunks.size(); i++) {
                TtsRequest request = TtsRequest.builder()
                        .model(resolved.modelId())
                        .input(chunks.get(i))
                        .voice(voice != null ? voice : "alloy")
                        .responseFormat(format != null ? format : "mp3")
                        .speed(speed > 0 ? speed : 1.0)
                        .baseUrl(resolved.baseUrl())
                        .apiKey(resolved.apiKey())
                        .build();

                byte[] audio = resolved.provider().generateAudio(request);
                output.write(audio);

                if (i < chunks.size() - 1) {
                    log.debug("TTS chunk {}/{} done, {} bytes", i + 1, chunks.size(), audio.length);
                }
            }
            return output.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to concatenate audio chunks", e);
        }
    }

    private String cleanText(String text) {
        // 1. Remove markdown formatting
        String cleaned = MARKDOWN_PATTERN.matcher(text).replaceAll("$1");
        // 2. Remove separator lines (---, ***, ===, etc.)
        cleaned = cleaned.replaceAll("(?m)^\\s*[-*=─━—]{3,}\\s*$", "");
        // 3. Remove quotation marks (CN/EN/JP)
        cleaned = cleaned.replaceAll("[\"'\\u2018\\u2019\\u201C\\u201D\\u300C\\u300D\\u300E\\u300F]", "");
        // 4. Replace brackets with space (keep content)
        cleaned = cleaned.replaceAll("[\\u3010\\u3011\\u3016\\u3017\\uFF08\\uFF09()\\[\\]\\u3008\\u3009]", " ");
        // 5. Remove book title marks (keep content)
        cleaned = cleaned.replaceAll("[\\u300A\\u300B]", "");
        // 6. Remove dashes, ellipsis, decorative symbols
        cleaned = cleaned.replaceAll("[\\u2014\\u2013\\u2026\\uFF5E~]", "");
        cleaned = cleaned.replaceAll("[※★☆◆◇■□▲△▼▽○●\\u2192\\u2190\\u2191\\u2193\\uFF0A*\\uFE4F_\\u2502\\u2503\\u00B7\\u2022]", "");
        // 7. Normalize whitespace
        cleaned = cleaned.replaceAll("[ \\t]+", " ");
        cleaned = cleaned.replaceAll("\\n{3,}", "\n\n");
        return cleaned.trim();
    }

    private List<String> splitIntoChunks(String text, int minLen, int maxLen) {
        List<String> chunks = new ArrayList<>();

        // Split entire text by sentence boundaries to produce small chunks
        String[] sentences = SENTENCE_BOUNDARY.split(text);
        StringBuilder current = new StringBuilder();

        for (String sentence : sentences) {
            String trimmed = sentence.strip();
            if (trimmed.isEmpty()) continue;

            // Would exceed maxLen, flush buffer first
            if (current.length() + trimmed.length() > maxLen && !current.isEmpty()) {
                chunks.add(current.toString().trim());
                current.setLength(0);
            }

            // Single sentence exceeds maxLen, hard-split it
            if (trimmed.length() > maxLen) {
                for (int i = 0; i < trimmed.length(); i += maxLen) {
                    chunks.add(trimmed.substring(i, Math.min(i + maxLen, trimmed.length())));
                }
                continue;
            }

            current.append(trimmed);

            // Flush as soon as we reach minLen — prefer small chunks
            if (current.length() >= minLen) {
                chunks.add(current.toString().trim());
                current.setLength(0);
            }
        }

        if (!current.isEmpty()) {
            chunks.add(current.toString().trim());
        }

        return chunks;
    }
}
