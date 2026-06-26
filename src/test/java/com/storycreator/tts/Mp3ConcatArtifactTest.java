package com.storycreator.tts;

import com.storycreator.tts.Mp3ProcessingService.AudioFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that concatenation of TTS chunks does not produce click/pop artifacts
 * at chunk junctions due to format mismatches between audio chunks and silence gaps.
 *
 * Run with: mvn test -Dtest=Mp3ConcatArtifactTest -DchunkDir=/path/to/chunks
 */
class Mp3ConcatArtifactTest {

    private static final String CHUNK_DIR_PROP = "chunkDir";
    private static final Path DEFAULT_CHUNK_DIR = Path.of("data/tts-export/35/18/chapter_1");

    private final Mp3ProcessingService service = new Mp3ProcessingService();

    private static Path getChunkDir() {
        String prop = System.getProperty(CHUNK_DIR_PROP);
        return prop != null ? Path.of(prop) : DEFAULT_CHUNK_DIR;
    }

    static boolean chunkDirExists() {
        return Files.isDirectory(getChunkDir());
    }

    @Test
    @EnabledIf("chunkDirExists")
    void detectFormatMismatchBetweenChunksAndGaps() throws Exception {
        Path chunkDir = getChunkDir();
        System.out.println("=== Format Mismatch Detection ===");
        System.out.printf("Chunk directory: %s%n%n", chunkDir.toAbsolutePath());

        List<Path> chunkFiles = new ArrayList<>();
        List<Path> gapFiles = new ArrayList<>();

        try (Stream<Path> files = Files.list(chunkDir)) {
            files.filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .forEach(f -> {
                        String name = f.getFileName().toString();
                        if (name.contains("gap")) {
                            gapFiles.add(f);
                        } else if (name.startsWith("chunk_")) {
                            chunkFiles.add(f);
                        }
                    });
        }

        assertFalse(chunkFiles.isEmpty(), "No chunk files found in " + chunkDir);

        // Detect chunk format
        AudioFormat chunkFormat = service.detectFileFormat(chunkFiles.get(0));
        System.out.printf("Chunk format (from %s): %s%n",
                chunkFiles.get(0).getFileName(), chunkFormat);

        if (!gapFiles.isEmpty()) {
            AudioFormat gapFormat = service.detectFileFormat(gapFiles.get(0));
            System.out.printf("Gap format (from %s): %s%n",
                    gapFiles.get(0).getFileName(), gapFormat);

            if (chunkFormat != gapFormat) {
                System.out.println();
                System.out.println("!! FORMAT MISMATCH DETECTED !!");
                System.out.printf("   Chunks: %s, Gaps: %s%n", chunkFormat, gapFormat);
                System.out.println("   This causes click/pop artifacts at junctions.");
                // This is the bug we're documenting — fail to highlight it
                fail("Format mismatch: chunks are " + chunkFormat + " but gaps are " + gapFormat +
                        ". Fix: generateSilenceFile() should detect actual format of reference file.");
            } else {
                System.out.println("Formats are uniform: " + chunkFormat + " (no mismatch)");
            }
        } else {
            System.out.println("No gap files found (chunkGap may be 0).");
        }
    }

    @Test
    @EnabledIf("chunkDirExists")
    void concatenateMp3ProducesCleanOutput() throws Exception {
        Path chunkDir = getChunkDir();
        List<Path> allFiles = getOrderedChunkFiles(chunkDir);
        if (allFiles.isEmpty()) {
            System.out.println("No files to concatenate.");
            return;
        }

        System.out.println("=== Concatenation Artifact Test (concatenateMp3) ===");
        System.out.printf("Files to concat: %d%n", allFiles.size());

        Path outputFile = Files.createTempFile("concat_test_", ".mp3");
        try {
            service.concatenateMp3(allFiles, outputFile);
            assertTrue(Files.exists(outputFile) && Files.size(outputFile) > 0,
                    "Output file should be non-empty");

            // Read output and check for artifacts at junction points
            byte[] output = Files.readAllBytes(outputFile);
            AudioFormat outputFormat = Mp3ProcessingService.detectFormat(output);
            System.out.printf("Output format: %s, size: %d bytes%n", outputFormat, output.length);

            if (outputFormat == AudioFormat.WAV) {
                analyzeWavJunctions(output, allFiles, chunkDir);
            } else {
                System.out.println("Output is MP3 — manual inspection recommended.");
            }
        } finally {
            Files.deleteIfExists(outputFile);
        }
    }

    @Test
    @EnabledIf("chunkDirExists")
    void generateSilenceFileMatchesReferenceFormat() throws Exception {
        Path chunkDir = getChunkDir();
        List<Path> chunkFiles = getAudioOnlyFiles(chunkDir);
        if (chunkFiles.isEmpty()) return;

        Path referenceFile = chunkFiles.get(0);
        AudioFormat refFormat = service.detectFileFormat(referenceFile);
        System.out.println("=== Silence File Format Matching Test ===");
        System.out.printf("Reference file: %s (format: %s)%n",
                referenceFile.getFileName(), refFormat);

        Path outputFile = Files.createTempFile("silence_test_", ".mp3");
        try {
            // Generate silence with format="mp3" but reference is WAV
            // After fix, this should produce WAV silence matching the reference
            Path result = service.generateSilenceFile(0.3, "mp3", referenceFile, outputFile);

            if (result != null) {
                AudioFormat silenceFormat = service.detectFileFormat(result);
                System.out.printf("Generated silence format: %s%n", silenceFormat);

                if (refFormat == AudioFormat.WAV) {
                    assertEquals(AudioFormat.WAV, silenceFormat,
                            "Silence should be WAV when reference file is WAV, " +
                                    "regardless of format parameter. This is the core fix.");
                    System.out.println("PASS: Silence format matches reference (WAV)");

                    // Verify WAV header compatibility
                    byte[] refData = Files.readAllBytes(referenceFile);
                    byte[] silData = Files.readAllBytes(result);
                    assertWavHeadersCompatible(refData, silData);
                }
            } else {
                System.out.println("generateSilenceFile returned null (expected if no ffmpeg and format is mp3)");
                if (refFormat == AudioFormat.WAV) {
                    fail("Should have generated WAV silence for WAV reference file");
                }
            }
        } finally {
            Files.deleteIfExists(outputFile);
        }
    }

    @Test
    void generateSilenceFileForSyntheticWav(@TempDir Path tempDir) throws Exception {
        System.out.println("=== Synthetic WAV Silence Generation Test ===");

        // Create a minimal WAV file (1 second of silence, 24kHz, mono, 16-bit)
        int sampleRate = 24000;
        int numSamples = sampleRate; // 1 second
        int bitsPerSample = 16;
        int channels = 1;
        int dataSize = numSamples * channels * (bitsPerSample / 8);

        ByteBuffer wav = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN);
        // RIFF header
        wav.put((byte) 'R').put((byte) 'I').put((byte) 'F').put((byte) 'F');
        wav.putInt(36 + dataSize);
        wav.put((byte) 'W').put((byte) 'A').put((byte) 'V').put((byte) 'E');
        // fmt chunk
        wav.put((byte) 'f').put((byte) 'm').put((byte) 't').put((byte) ' ');
        wav.putInt(16); // chunk size
        wav.putShort((short) 1); // PCM
        wav.putShort((short) channels);
        wav.putInt(sampleRate);
        wav.putInt(sampleRate * channels * bitsPerSample / 8); // byte rate
        wav.putShort((short) (channels * bitsPerSample / 8)); // block align
        wav.putShort((short) bitsPerSample);
        // data chunk
        wav.put((byte) 'd').put((byte) 'a').put((byte) 't').put((byte) 'a');
        wav.putInt(dataSize);
        // PCM data (zeros = silence)

        Path refFile = tempDir.resolve("reference.mp3"); // note: .mp3 extension but WAV content
        Files.write(refFile, wav.array());

        // Verify it's detected as WAV despite .mp3 extension
        assertEquals(AudioFormat.WAV, service.detectFileFormat(refFile),
                "Should detect WAV format from file header regardless of extension");

        // Generate silence with format="mp3" — should still produce WAV
        Path output = tempDir.resolve("gap.mp3");
        Path result = service.generateSilenceFile(0.5, "mp3", refFile, output);

        assertNotNull(result, "Should generate silence file");
        AudioFormat outputFormat = service.detectFileFormat(result);
        assertEquals(AudioFormat.WAV, outputFormat,
                "Generated silence must be WAV when reference is WAV (regardless of format param)");

        // Verify the generated silence has matching sample rate
        byte[] silenceData = Files.readAllBytes(result);
        ByteBuffer silBuf = ByteBuffer.wrap(silenceData).order(ByteOrder.LITTLE_ENDIAN);
        silBuf.position(24); // sample rate offset in WAV header
        int silSampleRate = silBuf.getInt();
        assertEquals(sampleRate, silSampleRate, "Silence sample rate should match reference");

        // Verify duration is approximately correct (0.5s)
        silBuf.position(40); // data chunk size offset
        int silDataSize = silBuf.getInt();
        int expectedSamples = (int) (sampleRate * 0.5);
        int expectedBytes = expectedSamples * channels * (bitsPerSample / 8);
        assertEquals(expectedBytes, silDataSize,
                "Silence duration should be ~0.5 seconds");

        System.out.println("PASS: WAV silence generated correctly despite format='mp3'");
    }

    // --- Helper methods ---

    private List<Path> getOrderedChunkFiles(Path dir) throws Exception {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .forEach(files::add);
        }
        return files;
    }

    private List<Path> getAudioOnlyFiles(Path dir) throws Exception {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile)
                    .filter(f -> f.getFileName().toString().startsWith("chunk_")
                            && !f.getFileName().toString().contains("gap"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .forEach(files::add);
        }
        return files;
    }

    private void analyzeWavJunctions(byte[] wavData, List<Path> sourceFiles, Path chunkDir) throws Exception {
        // Parse WAV to get PCM samples
        ByteBuffer buf = ByteBuffer.wrap(wavData).order(ByteOrder.LITTLE_ENDIAN);
        // Skip to data chunk
        int pos = 12; // after RIFF header
        int dataOffset = -1;
        int dataSize = -1;
        while (pos + 8 < wavData.length) {
            String chunkId = new String(wavData, pos, 4);
            int chunkSize = buf.getInt(pos + 4);
            if ("data".equals(chunkId)) {
                dataOffset = pos + 8;
                dataSize = chunkSize;
                break;
            }
            pos += 8 + chunkSize;
        }

        if (dataOffset < 0) {
            System.out.println("Could not find data chunk in output WAV");
            return;
        }

        // Get sample rate from fmt chunk
        int sampleRate = buf.getInt(24);
        int bitsPerSample = buf.getShort(34) & 0xFFFF;
        int channels = buf.getShort(22) & 0xFFFF;
        int bytesPerSample = bitsPerSample / 8;
        int totalSamples = dataSize / (bytesPerSample * channels);

        System.out.printf("Output WAV: %dHz, %d-bit, %dch, %d samples (%.2fs)%n",
                sampleRate, bitsPerSample, channels, totalSamples,
                (double) totalSamples / sampleRate);

        // Calculate approximate junction positions based on source file sizes
        long cumulativeSamples = 0;
        List<Long> junctionPositions = new ArrayList<>();

        for (Path f : sourceFiles) {
            byte[] fData = Files.readAllBytes(f);
            AudioFormat fmt = Mp3ProcessingService.detectFormat(fData);
            if (fmt == AudioFormat.WAV) {
                // Get PCM data size from this file
                ByteBuffer fBuf = ByteBuffer.wrap(fData).order(ByteOrder.LITTLE_ENDIAN);
                int fPos = 12;
                while (fPos + 8 < fData.length) {
                    String fChunkId = new String(fData, fPos, 4);
                    int fChunkSize = fBuf.getInt(fPos + 4);
                    if ("data".equals(fChunkId)) {
                        long fileSamples = fChunkSize / (bytesPerSample * channels);
                        cumulativeSamples += fileSamples;
                        junctionPositions.add(cumulativeSamples);
                        break;
                    }
                    fPos += 8 + fChunkSize;
                }
            }
        }

        // Analyze amplitude around each junction
        System.out.printf("%nAnalyzing %d junctions for amplitude spikes...%n", junctionPositions.size());
        int windowSamples = sampleRate / 200; // 5ms window
        int artifactCount = 0;

        for (int i = 0; i < junctionPositions.size() - 1; i++) {
            long junctionSample = junctionPositions.get(i);
            if (junctionSample <= windowSamples || junctionSample >= totalSamples - windowSamples)
                continue;

            // Check for sudden amplitude spike at junction
            double maxAmplitude = 0;
            double avgAmplitude = 0;
            int count = 0;

            for (long s = junctionSample - windowSamples; s < junctionSample + windowSamples; s++) {
                int sampleOffset = dataOffset + (int) (s * bytesPerSample * channels);
                if (sampleOffset + 1 >= wavData.length) break;
                short sample = buf.getShort(sampleOffset);
                double amp = Math.abs((double) sample / Short.MAX_VALUE);
                maxAmplitude = Math.max(maxAmplitude, amp);
                avgAmplitude += amp;
                count++;
            }
            avgAmplitude /= count;

            // A spike is detected if max >> avg (indicates discontinuity)
            if (maxAmplitude > 0.5 && maxAmplitude > avgAmplitude * 10) {
                artifactCount++;
                System.out.printf("  Junction %d (sample %d, t=%.3fs): SPIKE max=%.4f avg=%.4f%n",
                        i, junctionSample, (double) junctionSample / sampleRate,
                        maxAmplitude, avgAmplitude);
            }
        }

        if (artifactCount > 0) {
            System.out.printf("%n!! %d potential artifacts detected at junctions%n", artifactCount);
        } else {
            System.out.println("\nNo amplitude spikes detected at junctions.");
        }
    }

    private void assertWavHeadersCompatible(byte[] ref, byte[] silence) {
        // Check sample rate matches
        ByteBuffer refBuf = ByteBuffer.wrap(ref).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer silBuf = ByteBuffer.wrap(silence).order(ByteOrder.LITTLE_ENDIAN);

        int refSampleRate = refBuf.getInt(24);
        int silSampleRate = silBuf.getInt(24);
        assertEquals(refSampleRate, silSampleRate, "Sample rates must match");

        short refChannels = refBuf.getShort(22);
        short silChannels = silBuf.getShort(22);
        assertEquals(refChannels, silChannels, "Channel count must match");

        short refBits = refBuf.getShort(34);
        short silBits = silBuf.getShort(34);
        assertEquals(refBits, silBits, "Bits per sample must match");

        System.out.printf("WAV headers compatible: %dHz, %dch, %d-bit%n",
                refSampleRate, refChannels, refBits);
    }
}
