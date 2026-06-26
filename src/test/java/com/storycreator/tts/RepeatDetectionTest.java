package com.storycreator.tts;

import com.storycreator.tts.Mp3QualityDetector.QualityResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that Mp3QualityDetector correctly identifies repeating/stuck audio in samples.
 * Run with: mvn test -Dtest=RepeatDetectionTest -Drepeat.sample.dir=/path/to/dir
 */
class RepeatDetectionTest {

    private static final String SAMPLE_DIR = System.getProperty("repeat.sample.dir");

    private final Mp3ProcessingService mp3ProcessingService = new Mp3ProcessingService();
    private final Mp3QualityDetector detector = new Mp3QualityDetector(mp3ProcessingService);

    static boolean sampleDirConfigured() {
        return SAMPLE_DIR != null && Files.isDirectory(Path.of(SAMPLE_DIR));
    }

    @Test
    @EnabledIf("sampleDirConfigured")
    void allRepeatSamplesDetectedAsFailure() throws IOException {
        Path dir = Path.of(SAMPLE_DIR);
        List<Path> files;
        try (Stream<Path> stream = Files.list(dir)) {
            files = stream
                    .filter(p -> p.getFileName().toString().endsWith(".mp3"))
                    .sorted()
                    .toList();
        }

        System.out.println("Found " + files.size() + " .mp3 files in " + dir);
        assertTrue(files.size() > 0, "No .mp3 files found in " + dir);

        assertAll(files.stream().map(file -> () -> {
            byte[] data = Files.readAllBytes(file);
            QualityResult result = detector.analyze(data);
            String fileName = file.getFileName().toString();
            System.out.printf("  %-35s passed=%s  issue=%s%n", fileName, result.passed(), result.issue());
            assertFalse(result.passed(),
                    "Expected FAIL for repeat sample: " + fileName + " (got passed=true)");
        }));
    }
}
