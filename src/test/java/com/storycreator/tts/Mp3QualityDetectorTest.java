package com.storycreator.tts;

import com.storycreator.tts.Mp3QualityDetector.QualityResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Mp3QualityDetector.
 * The file-based test only runs when the sample MP3 file exists at the specified path.
 */
class Mp3QualityDetectorTest {

    private static final String SAMPLE_REPEAT_FILE = "data/tts-export/24/chunk_5.mp3";

    private final Mp3ProcessingService mp3ProcessingService = new Mp3ProcessingService();
    private final Mp3QualityDetector detector = new Mp3QualityDetector(mp3ProcessingService);

    static boolean sampleFileExists() {
        return Files.exists(Path.of(SAMPLE_REPEAT_FILE));
    }

    @Test
    @EnabledIf("sampleFileExists")
    void detectRepeatInSampleFile() throws Exception {
        byte[] data = Files.readAllBytes(Path.of(SAMPLE_REPEAT_FILE));
        System.out.println("Analyzing file: " + SAMPLE_REPEAT_FILE + " (" + data.length + " bytes)");

        QualityResult result = detector.analyze(data);

        System.out.println("Passed: " + result.passed());
        System.out.println("Issue: " + result.issue());

        assertFalse(result.passed(), "Expected quality check to FAIL for repeat audio sample");
        assertNotNull(result.issue());
        System.out.println("SUCCESS: Detector correctly identified issue: " + result.issue());
    }

    @Test
    void nullDataReturnsFailed() {
        QualityResult result = detector.analyze(null);
        assertFalse(result.passed());
        assertEquals("Audio data too small", result.issue());
    }

    @Test
    void emptyDataReturnsFailed() {
        QualityResult result = detector.analyze(new byte[0]);
        assertFalse(result.passed());
        assertEquals("Audio data too small", result.issue());
    }

    @Test
    void tooSmallDataReturnsFailed() {
        QualityResult result = detector.analyze(new byte[5]);
        assertFalse(result.passed());
    }
}
