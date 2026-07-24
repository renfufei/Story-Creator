package com.storycreator.web;

import com.storycreator.learn.LearnAudioService;
import com.storycreator.learn.MultiplicationFormula;
import com.storycreator.persistence.entity.LearnAudioEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/learn/multiplication")
public class LearnAudioController {

    private final LearnAudioService learnAudioService;

    public LearnAudioController(LearnAudioService learnAudioService) {
        this.learnAudioService = learnAudioService;
    }

    @GetMapping("/audio-status")
    public List<LearnAudioEntity> audioStatus() {
        learnAudioService.ensureFormulaRecordsExist(MultiplicationFormula.MODULE);
        return learnAudioService.listByModule(MultiplicationFormula.MODULE);
    }

    @PostMapping("/batch-generate")
    public ResponseEntity<Map<String, String>> batchGenerate(@RequestBody BatchGenerateRequest request) {
        learnAudioService.startBatchGenerate(
            MultiplicationFormula.MODULE,
            request.configId(),
            request.voice(),
            request.speed() != null ? request.speed() : 1.0,
            request.format() != null ? request.format() : "mp3"
        );
        return ResponseEntity.ok(Map.of("status", "started"));
    }

    @PostMapping("/stop-generate")
    public ResponseEntity<Map<String, String>> stopGenerate() {
        learnAudioService.stopBatchGenerate(MultiplicationFormula.MODULE);
        return ResponseEntity.ok(Map.of("status", "stopping"));
    }

    @PostMapping("/{itemKey}/regenerate")
    public ResponseEntity<Map<String, String>> regenerate(
            @PathVariable String itemKey,
            @RequestBody BatchGenerateRequest request) {
        learnAudioService.regenerateByItemKey(
            MultiplicationFormula.MODULE,
            itemKey,
            request.configId(),
            request.voice(),
            request.speed() != null ? request.speed() : 1.0,
            request.format() != null ? request.format() : "mp3"
        );
        return ResponseEntity.ok(Map.of("status", "generated"));
    }

    @GetMapping("/audio/{itemKey}")
    public ResponseEntity<byte[]> getAudio(@PathVariable String itemKey) throws IOException {
        byte[] data = learnAudioService.getAudioBytes(MultiplicationFormula.MODULE, itemKey);
        if (data == null) {
            return ResponseEntity.notFound().build();
        }
        LearnAudioEntity entity = learnAudioService.listByModule(MultiplicationFormula.MODULE).stream()
            .filter(e -> e.getItemKey().equals(itemKey))
            .findFirst().orElse(null);
        String format = entity != null ? entity.getFormat() : "mp3";
        String contentType = "audio/" + ("mp3".equals(format) ? "mpeg" : format);
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_TYPE, contentType)
            .header(HttpHeaders.CACHE_CONTROL, "public, max-age=86400")
            .body(data);
    }

    @GetMapping("/running")
    public ResponseEntity<Map<String, Boolean>> isRunning() {
        return ResponseEntity.ok(Map.of("running", learnAudioService.isRunning(MultiplicationFormula.MODULE)));
    }

    public record BatchGenerateRequest(Long configId, String voice, Double speed, String format) {}
}
