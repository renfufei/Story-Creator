package com.storycreator.tools;

import com.storycreator.tts.Mp3QualityDetector;
import com.storycreator.tts.Mp3ProcessingService;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * CLI tool to verify MP3 files contain no noise/silence/stuck-tone artifacts.
 *
 * Usage: java --source 21 --enable-preview --class-path target/classes \
 *        src/main/java/com/storycreator/tools/VerifyNoNoiseTool.java <file1> [file2] ...
 */
public class VerifyNoNoiseTool {

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("Usage: VerifyNoNoiseTool <file1> [file2] ...");
            System.err.println("       VerifyNoNoiseTool data/tts-export/35/20/chapter_73/noise_chunk_*.mp3");
            System.exit(1);
        }

        var detector = new Mp3QualityDetector(new Mp3ProcessingService());
        int passed = 0, failed = 0, skipped = 0;

        for (var file : args) {
            var path = Path.of(file);
            if (!Files.exists(path)) {
                System.out.printf("SKIP  %s (file not found)%n", path.getFileName());
                skipped++;
                continue;
            }
            byte[] data = Files.readAllBytes(path);
            var result = detector.analyze(data);
            if (result.passed()) {
                System.out.printf("PASS  %s (%d bytes)%n", path.getFileName(), data.length);
                passed++;
            } else {
                System.out.printf("FAIL  %s → %s @ %.1fs%n", path.getFileName(), result.issue(), result.issueStartSeconds());
                failed++;
            }
        }

        System.out.println("\n=== SUMMARY ===");
        System.out.printf("Total: %d, Passed: %d, Failed: %d, Skipped: %d%n", args.length, passed, failed, skipped);
        if (failed == 0) {
            System.out.println("ALL FILES PASSED!");
        } else {
            System.out.println("SOME FILES FAILED!");
            System.exit(1);
        }
    }
}
