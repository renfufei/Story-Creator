package com.storycreator.learn;

import com.storycreator.persistence.entity.LearnAudioEntity;
import com.storycreator.persistence.repository.LearnAudioRepository;
import com.storycreator.tts.TtsPlaybackSettings;
import com.storycreator.tts.TtsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class LearnAudioService {

    private static final Logger log = LoggerFactory.getLogger(LearnAudioService.class);
    private static final String AUDIO_BASE_DIR = "data/learn-audio";

    /** TTS pronunciation fix replacements (applied before sending to TTS, not saved to DB) */
    private static final Map<String, String> TTS_REPLACEMENTS = new LinkedHashMap<>();
    static {
        TTS_REPLACEMENTS.put("一一得一", "一衣得一");
        TTS_REPLACEMENTS.put("一六得六", "1六得六");
        TTS_REPLACEMENTS.put("一九得九", "依九得九");
        TTS_REPLACEMENTS.put("二五一十", "贰五衣实");
        TTS_REPLACEMENTS.put("四四十六", "四肆十六");
        TTS_REPLACEMENTS.put("四八三十二", "肆八三十二");
        TTS_REPLACEMENTS.put("五五二十五", "吴五二十五");
        TTS_REPLACEMENTS.put("六六三十六", "六流三十六");
        TTS_REPLACEMENTS.put("七七四十九", "七期四十九");
        TTS_REPLACEMENTS.put("七八五十六", "七捌五十六");
        TTS_REPLACEMENTS.put("八八六十四", "巴八六十四");
        TTS_REPLACEMENTS.put("八九七十二", "捌九七十二");
        TTS_REPLACEMENTS.put("六的乘法口诀", "刘得乘法口诀");
    }

    private final LearnAudioRepository repository;
    private final TtsService ttsService;
    private final ConcurrentHashMap<String, Boolean> stopSignals = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> runningTasks = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public LearnAudioService(LearnAudioRepository repository, TtsService ttsService) {
        this.repository = repository;
        this.ttsService = ttsService;
    }

    @Transactional
    public void ensureFormulaRecordsExist(String module) {
        List<LearnAudioEntity> existing = repository.findByModuleOrderByItemKey(module);
        if (existing.isEmpty()) {
            // First-time seed: prefixes + formulas
            for (MultiplicationFormula.PrefixEntry prefix : MultiplicationFormula.PREFIXES) {
                LearnAudioEntity entity = new LearnAudioEntity();
                entity.setModule(module);
                entity.setItemKey(prefix.itemKey());
                entity.setTextContent(prefix.chineseText());
                repository.save(entity);
            }
            for (MultiplicationFormula formula : MultiplicationFormula.FORMULAS) {
                LearnAudioEntity entity = new LearnAudioEntity();
                entity.setModule(module);
                entity.setItemKey(formula.itemKey());
                entity.setTextContent(formula.chineseText());
                repository.save(entity);
            }
        } else {
            // Ensure prefix records exist (for existing DBs that only have formula records)
            for (MultiplicationFormula.PrefixEntry prefix : MultiplicationFormula.PREFIXES) {
                if (existing.stream().noneMatch(e -> e.getItemKey().equals(prefix.itemKey()))) {
                    LearnAudioEntity entity = new LearnAudioEntity();
                    entity.setModule(module);
                    entity.setItemKey(prefix.itemKey());
                    entity.setTextContent(prefix.chineseText());
                    repository.save(entity);
                }
            }
        }
    }

    public List<LearnAudioEntity> listByModule(String module) {
        return repository.findByModuleOrderByItemKey(module);
    }

    public boolean isRunning(String module) {
        return Boolean.TRUE.equals(runningTasks.get(module));
    }

    public void startBatchGenerate(String module, Long configId, String voice, double speed, String format) {
        if (Boolean.TRUE.equals(runningTasks.get(module))) {
            throw new IllegalStateException("Batch generation already running for module: " + module);
        }
        runningTasks.put(module, true);
        stopSignals.remove(module);

        executor.submit(() -> {
            try {
                executeBatch(module, configId, voice, speed, format);
            } catch (Exception e) {
                log.error("Batch generation failed for module {}", module, e);
            } finally {
                runningTasks.remove(module);
                stopSignals.remove(module);
            }
        });
    }

    public void stopBatchGenerate(String module) {
        stopSignals.put(module, true);
    }

    @Transactional
    public void generateSingle(Long recordId, Long configId, String voice, double speed, String format) {
        LearnAudioEntity entity = repository.findById(recordId)
            .orElseThrow(() -> new IllegalArgumentException("Record not found: " + recordId));
        generateAudioForRecord(entity, configId, voice, speed, format);
    }

    @Transactional
    public void regenerateByItemKey(String module, String itemKey, Long configId, String voice, double speed, String format) {
        LearnAudioEntity entity = repository.findByModuleAndItemKey(module, itemKey)
            .orElseThrow(() -> new IllegalArgumentException("Record not found: " + module + "/" + itemKey));
        generateAudioForRecord(entity, configId, voice, speed, format);
    }

    public byte[] getAudioBytes(String module, String itemKey) throws IOException {
        LearnAudioEntity entity = repository.findByModuleAndItemKey(module, itemKey)
            .orElseThrow(() -> new IllegalArgumentException("Record not found: " + module + "/" + itemKey));
        if (entity.getFilePath() == null || !"READY".equals(entity.getStatus())) {
            return null;
        }
        Path path = Paths.get(entity.getFilePath());
        if (!Files.exists(path)) {
            return null;
        }
        return Files.readAllBytes(path);
    }

    private void executeBatch(String module, Long configId, String voice, double speed, String format) {
        List<LearnAudioEntity> records = repository.findByModuleOrderByItemKey(module);
        for (LearnAudioEntity record : records) {
            if (Boolean.TRUE.equals(stopSignals.get(module))) {
                log.info("Batch generation stopped for module: {}", module);
                return;
            }
            try {
                generateAudioForRecord(record, configId, voice, speed, format);
            } catch (Exception e) {
                log.error("Failed to generate audio for {}/{}", module, record.getItemKey(), e);
                record.setStatus("ERROR");
                record.setErrorMessage(e.getMessage() != null ? e.getMessage().substring(0, Math.min(e.getMessage().length(), 500)) : "Unknown error");
                repository.save(record);
            }
        }
    }

    private String applyTtsReplacements(String text) {
        if (text == null) return text;
        for (Map.Entry<String, String> entry : TTS_REPLACEMENTS.entrySet()) {
            text = text.replace(entry.getKey(), entry.getValue());
        }
        return text;
    }

    private void generateAudioForRecord(LearnAudioEntity entity, Long configId, String voice, double speed, String format) {
        // Delete existing audio file before regenerating
        if (entity.getFilePath() != null) {
            try {
                Path oldFile = Paths.get(entity.getFilePath());
                Files.deleteIfExists(oldFile);
            } catch (IOException e) {
                log.warn("Failed to delete old audio file: {}", entity.getFilePath(), e);
            }
        }

        entity.setStatus("GENERATING");
        entity.setConfigId(configId);
        entity.setVoice(voice);
        entity.setSpeed(speed);
        entity.setFormat(format);
        entity.setErrorMessage(null);
        entity.setFilePath(null);
        repository.save(entity);

        try {
            String ttsText = applyTtsReplacements(entity.getTextContent());
            TtsPlaybackSettings settings = new TtsPlaybackSettings(configId, voice, format, speed);
            byte[] audioData = ttsService.generateAudioForChunk(ttsText, settings);

            Path dir = Paths.get(AUDIO_BASE_DIR, entity.getModule());
            Files.createDirectories(dir);
            String fileName = entity.getItemKey() + "." + format;
            Path filePath = dir.resolve(fileName);
            Files.write(filePath, audioData);

            entity.setFilePath(filePath.toString());
            entity.setStatus("READY");
            repository.save(entity);
        } catch (Exception e) {
            entity.setStatus("ERROR");
            String msg = e.getMessage() != null ? e.getMessage() : "Unknown error";
            entity.setErrorMessage(msg.substring(0, Math.min(msg.length(), 500)));
            repository.save(entity);
            throw new RuntimeException("Audio generation failed for " + entity.getItemKey(), e);
        }
    }
}
