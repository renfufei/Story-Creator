package com.storycreator.tts;

import com.storycreator.ai.router.TtsProviderRegistry;
import com.storycreator.core.port.tts.TtsProvider;
import com.storycreator.core.port.tts.TtsRequest;
import com.storycreator.persistence.entity.ChapterEntity;
import com.storycreator.persistence.repository.ChapterRepository;
import com.storycreator.tts.Mp3QualityDetector.QualityResult;
import com.storycreator.tts.template.TtsReplacementRule;
import com.storycreator.tts.template.TtsReplacementTemplateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Service
public class TtsService {

    private static final Logger log = LoggerFactory.getLogger(TtsService.class);
    private static final int MAX_CHUNK_LENGTH = 200;
    private static final int MIN_CHUNK_LENGTH = 10;

    private static final int MAX_QUALITY_RETRIES = 1;
    private static final double DEFAULT_CHUNK_GAP_SECONDS = 0.1;
    private static final double DEFAULT_SKIP_GAP_SECONDS = 0.3;
    private static final double TRIM_SAFETY_MARGIN_SECONDS = 0.1;
    private static final String SPLIT_PUNCTUATION = "。！？，、；：.!?,;:";

    private final TtsProviderRegistry ttsProviderRegistry;
    private final ChapterRepository chapterRepository;
    private final TtsReplacementTemplateService templateService;
    private final Mp3QualityDetector mp3QualityDetector;
    private final Mp3ProcessingService mp3ProcessingService;

    public TtsService(TtsProviderRegistry ttsProviderRegistry, ChapterRepository chapterRepository,
                      TtsReplacementTemplateService templateService,
                      Mp3QualityDetector mp3QualityDetector, Mp3ProcessingService mp3ProcessingService) {
        this.ttsProviderRegistry = ttsProviderRegistry;
        this.chapterRepository = chapterRepository;
        this.templateService = templateService;
        this.mp3QualityDetector = mp3QualityDetector;
        this.mp3ProcessingService = mp3ProcessingService;
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
        return getChapterChunks(projectId, chapterNumber, minLen, maxLen, null);
    }

    /**
     * Returns the text chunks for a chapter with configurable min/max chunk lengths and config-specific rules.
     */
    public List<String> getChapterChunks(Long projectId, int chapterNumber, int minLen, int maxLen, Long configId) {
        ChapterEntity chapter = chapterRepository.findByProjectIdAndChapterNumber(projectId, chapterNumber)
                .orElseThrow(() -> new IllegalArgumentException("Chapter not found: " + chapterNumber));

        String text = chapter.getContent();
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Chapter " + chapterNumber + " has no content");
        }

        String cleaned = cleanText(text, configId);
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

        return generateWithRetry(resolved.provider(), request);
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

        String cleanedText = cleanText(rawText, configId);
        List<String> chunks = splitIntoChunks(cleanedText, MIN_CHUNK_LENGTH, MAX_CHUNK_LENGTH);

        log.info("TTS generating: {} chunks, total {} chars", chunks.size(), cleanedText.length());

        List<byte[]> audioChunks = new ArrayList<>();
        byte[] silenceGap = null;   // lazy-init after first chunk determines format
        byte[] skipGap = null;

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

            byte[] audio = generateWithRetry(resolved.provider(), request);
            if (audio != null) {
                // Insert normal gap before this chunk (not the first)
                if (!audioChunks.isEmpty()) {
                    if (silenceGap == null) {
                        silenceGap = mp3ProcessingService.generateSilenceWav(DEFAULT_CHUNK_GAP_SECONDS, audio);
                    }
                    if (silenceGap != null) {
                        audioChunks.add(silenceGap);
                    }
                }
                audioChunks.add(audio);
                log.debug("TTS chunk {}/{} done, {} bytes", i + 1, chunks.size(), audio.length);
            } else {
                // Chunk discarded — insert longer skip gap
                log.warn("Skipping chunk {}/{} due to quality failure", i + 1, chunks.size());
                if (!audioChunks.isEmpty()) {
                    if (skipGap == null) {
                        skipGap = mp3ProcessingService.generateSilenceWav(DEFAULT_SKIP_GAP_SECONDS, audioChunks.get(0));
                    }
                    if (skipGap != null) {
                        audioChunks.add(skipGap);
                    }
                }
            }
        }

        return mp3ProcessingService.concatenateAudioChunksInMemory(audioChunks);
    }

    private String cleanText(String text, Long configId) {
        String cleaned = text;
        // 先执行百分比替换，再执行数字替换（顺序重要：避免数字替换干扰百分比）
        cleaned = ChineseNumberConverter.convertPercentageToChineseText(cleaned);
        cleaned = ChineseNumberConverter.convertNumberToChineseText(cleaned);
        // 然后执行模板规则
        List<TtsReplacementRule> rules = templateService.resolveRulesForConfig(configId);
        for (TtsReplacementRule rule : rules) {
            if (rule.isRegex()) {
                cleaned = Pattern.compile(rule.pattern()).matcher(cleaned).replaceAll(rule.replacement());
            } else {
                cleaned = cleaned.replace(rule.pattern(), rule.replacement());
            }
        }
        return cleaned.trim();
    }

    private byte[] generateWithRetry(TtsProvider provider, TtsRequest request) {
        String format = request.getResponseFormat();
        boolean shouldCheck = format == null || "mp3".equalsIgnoreCase(format) || "wav".equalsIgnoreCase(format);

        byte[] lastAudio = null;
        QualityResult lastResult = null;

        for (int attempt = 1; attempt <= MAX_QUALITY_RETRIES; attempt++) {
            byte[] audio = provider.generateAudio(request);

            if (!shouldCheck) {
                return audio;
            }

            QualityResult result = mp3QualityDetector.analyze(audio);
            if (result.passed()) {
                if (attempt > 1) {
                    log.info("TTS chunk quality passed on attempt {}/{}", attempt, MAX_QUALITY_RETRIES);
                }
                return audio;
            }

            lastAudio = audio;
            lastResult = result;

            if (attempt < MAX_QUALITY_RETRIES) {
                log.warn("TTS chunk quality issue (attempt {}/{}): {} — retrying",
                        attempt, MAX_QUALITY_RETRIES, result.issue());
            }
        }

        // All retries exhausted — try splitting by comma as fallback
        log.error("TTS chunk quality issue persists after {} attempts: {} [text: {}]",
                MAX_QUALITY_RETRIES, lastResult.issue(), request.getInput());
        appendToErrorLog(request.getInput(), lastResult.issue());

        // Fallback: split by punctuation and generate sub-clauses individually
        String inputText = request.getInput();
        if (containsAnySplitPoint(inputText)) {
            byte[] splitResult = generateBySplitting(provider, request);
            if (splitResult != null) {
                log.info("TTS chunk recovered by punctuation-splitting: [{}]", inputText);
                return splitResult;
            }
        }

        if (lastResult.issueStartSeconds() > 1.0) {
            double trimTo = lastResult.issueStartSeconds() - TRIM_SAFETY_MARGIN_SECONDS;
            byte[] trimmed = mp3ProcessingService.trimAudioToSeconds(lastAudio, trimTo);
            if (trimmed != null) {
                log.info("Trimmed audio to {}s before issue (safety margin {}s)",
                        String.format("%.1f", trimTo), TRIM_SAFETY_MARGIN_SECONDS);
                return trimmed;
            }
        }

        // Effective audio too short or trim failed — discard
        log.warn("Discarding chunk: issue at {}s, not enough valid audio",
                String.format("%.1f", lastResult.issueStartSeconds()));
        return null;
    }

    private void appendToErrorLog(String text, String errorType) {
        try (var writer = new FileWriter("error_words.log", true);
             var pw = new PrintWriter(writer)) {
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            pw.printf("[%s] errorType=%s | text=%s%n", timestamp, errorType, text);
        } catch (IOException e) {
            log.warn("Failed to write to error_words.log: {}", e.getMessage());
        }
    }

    /**
     * Split text by punctuation, generate audio for each sub-clause with quality check,
     * then concatenate. Only 1 level deep — no recursion.
     * Sub-clauses shorter than MIN_CHUNK_LENGTH are merged with adjacent ones.
     */
    private byte[] generateBySplitting(TtsProvider provider, TtsRequest originalRequest) {
        String text = originalRequest.getInput();
        String[] parts = text.split("(?<=[。！？，、；：.!?,;:])");

        // Merge short sub-clauses (< MIN_CHUNK_LENGTH) into adjacent
        List<String> clauses = new ArrayList<>();
        for (String part : parts) {
            String p = part.strip();
            if (p.isEmpty()) continue;
            if (!clauses.isEmpty() && p.length() < MIN_CHUNK_LENGTH) {
                clauses.set(clauses.size() - 1, clauses.get(clauses.size() - 1) + p);
            } else {
                clauses.add(p);
            }
        }

        // If splitting didn't produce multiple clauses, give up
        if (clauses.size() <= 1) {
            return null;
        }

        String format = originalRequest.getResponseFormat();
        boolean shouldCheck = format == null || "mp3".equalsIgnoreCase(format) || "wav".equalsIgnoreCase(format);

        // Generate audio for each sub-clause with quality check
        List<byte[]> audioSegments = new ArrayList<>();
        for (String clause : clauses) {
            TtsRequest subRequest = TtsRequest.builder()
                    .model(originalRequest.getModel())
                    .input(clause)
                    .voice(originalRequest.getVoice())
                    .responseFormat(originalRequest.getResponseFormat())
                    .speed(originalRequest.getSpeed())
                    .baseUrl(originalRequest.getBaseUrl())
                    .apiKey(originalRequest.getApiKey())
                    .build();

            byte[] audio = provider.generateAudio(subRequest);
            if (audio == null || audio.length == 0) {
                continue;
            }

            if (!shouldCheck) {
                audioSegments.add(audio);
                continue;
            }

            // Quality check the sub-chunk
            QualityResult result = mp3QualityDetector.analyze(audio);
            if (result.passed()) {
                audioSegments.add(audio);
            } else {
                // Try trim with safety margin
                double trimTo = result.issueStartSeconds() - TRIM_SAFETY_MARGIN_SECONDS;
                if (trimTo > 0.5) {
                    byte[] trimmed = mp3ProcessingService.trimAudioToSeconds(audio, trimTo);
                    if (trimmed != null) {
                        log.info("Sub-chunk trimmed to {}s (clause: [{}])", String.format("%.1f", trimTo), clause);
                        audioSegments.add(trimmed);
                    } else {
                        log.warn("Sub-chunk trim failed, skipping clause: [{}]", clause);
                    }
                } else {
                    log.warn("Sub-chunk issue too early ({}s), skipping clause: [{}]",
                            String.format("%.1f", result.issueStartSeconds()), clause);
                }
            }
        }

        if (audioSegments.isEmpty()) {
            return null;
        }

        return mp3ProcessingService.concatenateAudioChunksInMemory(audioSegments);
    }

    private boolean containsAnySplitPoint(String text) {
        return text.chars().anyMatch(c -> SPLIT_PUNCTUATION.indexOf(c) >= 0);
    }

    private static final int BOUNDARY_ZONE = 5;
    private static final String SECONDARY_PUNCTUATION_PATTERN = "[，、；;：:]+";

    private List<String> splitIntoChunks(String text, int minLen, int maxLen) {
        List<String> chunks = new ArrayList<>();

        // Rule 1: 段落隔离 — 按换行拆分，不跨段落合并
        String[] paragraphs = text.split("\\n+");
        for (String paragraph : paragraphs) {
            String para = paragraph.strip();
            if (para.isEmpty()) continue;

            // Rule 2: 按句号/感叹号/问号拆分
            String[] sentences = para.split("(?<=[。.！!？?])");
            for (String sentence : sentences) {
                String s = sentence.strip();
                if (s.isEmpty()) continue;

                // Rule 3: 超过上限则按次级分隔符回退拆分（优先句末标点）
                chunks.addAll(splitOversizedChunk(s, maxLen));
            }
        }

        // Strip leading punctuation from each chunk (caused by split boundaries)
        chunks.replaceAll(c -> c.replaceAll("^[，、。.！!？?；;：:]+", ""));
        chunks.removeIf(String::isEmpty);

        // Merge chunks shorter than minLen into previous chunk, but don't merge across sentence-final punctuation
        List<String> merged = new ArrayList<>();
        for (String c : chunks) {
            if (!merged.isEmpty() && c.length() < minLen) {
                String prev = merged.get(merged.size() - 1);
                // If previous chunk ends with sentence-final punctuation, it's a complete sentence — don't append
                if (endsWithSentenceFinal(prev)) {
                    merged.add(c);
                } else {
                    merged.set(merged.size() - 1, prev + c);
                }
            } else {
                merged.add(c);
            }
        }

        // Boundary punctuation splitting: split chunks where secondary punctuation appears in first/last 5 chars
        merged = splitAtBoundaryPunctuation(merged, BOUNDARY_ZONE);

        // Remove trailing secondary punctuation (commas, etc.) — they cause TTS silence/stuttering
        merged.replaceAll(c -> c.replaceAll(SECONDARY_PUNCTUATION_PATTERN + "$", ""));

        // Final cleanup
        merged.removeIf(c -> c.isBlank());
        return merged;
    }

    private boolean endsWithSentenceFinal(String s) {
        if (s == null || s.isEmpty()) return false;
        char last = s.charAt(s.length() - 1);
        return last == '。' || last == '！' || last == '？' || last == '.' || last == '!' || last == '?';
    }

    private List<String> splitAtBoundaryPunctuation(List<String> chunks, int boundaryZone) {
        List<String> result = new ArrayList<>();
        for (String chunk : chunks) {
            if (chunk.length() <= boundaryZone * 2) {
                // Chunk too short for boundary splitting — just remove secondary punctuation inline
                result.add(chunk.replaceAll(SECONDARY_PUNCTUATION_PATTERN, ""));
                continue;
            }
            // Check head zone for secondary punctuation
            int headSplit = findSecondaryPunctuationInRange(chunk, 0, Math.min(boundaryZone, chunk.length()));
            if (headSplit >= 0) {
                String before = chunk.substring(0, headSplit).strip();
                String after = chunk.substring(headSplit + 1).strip();
                if (!before.isEmpty()) result.add(before);
                if (!after.isEmpty()) result.add(after);
                continue;
            }
            // Check tail zone for secondary punctuation
            int tailStart = Math.max(0, chunk.length() - boundaryZone);
            int tailSplit = findLastSecondaryPunctuationInRange(chunk, tailStart, chunk.length());
            if (tailSplit >= 0) {
                String before = chunk.substring(0, tailSplit).strip();
                String after = chunk.substring(tailSplit + 1).strip();
                if (!before.isEmpty()) result.add(before);
                if (!after.isEmpty()) result.add(after);
                continue;
            }
            result.add(chunk);
        }
        return result;
    }

    private int findSecondaryPunctuationInRange(String s, int from, int to) {
        for (int i = from; i < to; i++) {
            if (isSecondaryPunctuation(s.charAt(i))) {
                return i;
            }
        }
        return -1;
    }

    private int findLastSecondaryPunctuationInRange(String s, int from, int to) {
        for (int i = to - 1; i >= from; i--) {
            if (isSecondaryPunctuation(s.charAt(i))) {
                return i;
            }
        }
        return -1;
    }

    private boolean isSecondaryPunctuation(char c) {
        return c == '，' || c == '、' || c == '；' || c == ';' || c == '：' || c == ':';
    }

    private List<String> splitOversizedChunk(String chunk, int maxLen) {
        if (chunk.length() <= maxLen) {
            return List.of(chunk);
        }

        int splitPos = -1;

        // Pass 1: prefer sentence-final punctuation (。！？) — search from maxLen backwards, accept if > maxLen/3
        for (int i = maxLen - 1; i >= maxLen / 3; i--) {
            char c = chunk.charAt(i);
            if (c == '。' || c == '！' || c == '？' || c == '.' || c == '!' || c == '?') {
                splitPos = i + 1; // punctuation stays in head
                break;
            }
        }

        // Pass 2 (fallback): find any secondary delimiter from maxLen backwards
        if (splitPos <= 0) {
            for (int i = maxLen - 1; i >= 0; i--) {
                if (isSecondaryDelimiter(chunk.charAt(i))) {
                    splitPos = i + 1;
                    break;
                }
            }
        }

        if (splitPos <= 0) {
            // 没找到分隔符 — 硬切在 maxLen
            splitPos = maxLen;
        }

        String head = chunk.substring(0, splitPos).strip();
        String tail = chunk.substring(splitPos).stripLeading();

        List<String> result = new ArrayList<>();
        if (!head.isEmpty()) result.add(head);
        if (!tail.isEmpty()) result.addAll(splitOversizedChunk(tail, maxLen));
        return result;
    }

    private boolean isSecondaryDelimiter(char c) {
        return c == '，' || c == '、' || c == '；' || c == ';'
            || c == '：' || c == ':' || c == '？' || c == '?'
            || c == '！' || c == '!' || c == '。' || c == '.';
    }
}
