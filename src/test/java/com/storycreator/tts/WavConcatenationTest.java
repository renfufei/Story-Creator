package com.storycreator.tts;

import com.storycreator.tts.Mp3ProcessingService.AudioFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

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
 * Tests for WAV format detection and concatenation in Mp3ProcessingService.
 * Only runs when the sample directory exists.
 */
class WavConcatenationTest {

    private static final Path SAMPLE_DIR = Path.of("data/tts-export/24/9/01_ok_sample");

    private final Mp3ProcessingService service = new Mp3ProcessingService();

    static boolean sampleDirExists() {
        return Files.isDirectory(SAMPLE_DIR);
    }

    @Test
    @EnabledIf("sampleDirExists")
    void detectFormatsInDirectory() throws Exception {
        List<Path> files = getChunkFiles();
        System.out.println("=== Format Detection Report ===");
        System.out.printf("Directory: %s%n", SAMPLE_DIR.toAbsolutePath());
        System.out.printf("Total chunk files: %d%n%n", files.size());

        int wavCount = 0, mp3Count = 0, unknownCount = 0;

        for (Path file : files) {
            AudioFormat format = service.detectFileFormat(file);
            long size = Files.size(file);
            String status;
            switch (format) {
                case WAV -> { wavCount++; status = "WAV (mislabeled as .mp3)"; }
                case MP3 -> { mp3Count++; status = "MP3 (correct)"; }
                default -> { unknownCount++; status = "UNKNOWN"; }
            }
            System.out.printf("  %-20s %8d bytes  -> %s%n", file.getFileName(), size, status);
        }

        System.out.println();
        System.out.printf("Summary: WAV=%d, MP3=%d, UNKNOWN=%d%n", wavCount, mp3Count, unknownCount);
        System.out.println();

        // All files should be detected as a known format
        assertEquals(0, unknownCount, "Some files have unknown format");
        assertTrue(files.size() > 0, "No chunk files found");
    }

    @Test
    @EnabledIf("sampleDirExists")
    void concatenateWithFileBasedMethod() throws Exception {
        List<Path> files = getChunkFiles();
        Path output = SAMPLE_DIR.resolve("merged_output.wav");

        System.out.println("=== File-based Concatenation Test ===");
        System.out.printf("Input: %d chunks%n", files.size());

        long startTime = System.currentTimeMillis();
        service.concatenateMp3(files, output);
        long elapsed = System.currentTimeMillis() - startTime;

        assertTrue(Files.exists(output), "Output file should exist");
        long outputSize = Files.size(output);
        System.out.printf("Output: %s (%d bytes)%n", output.getFileName(), outputSize);
        System.out.printf("Time: %d ms%n", elapsed);

        // Verify the output is a valid WAV
        byte[] header = new byte[12];
        try (var is = Files.newInputStream(output)) {
            is.read(header);
        }
        AudioFormat outputFormat = Mp3ProcessingService.detectFormat(header);
        assertEquals(AudioFormat.WAV, outputFormat, "Output should be a valid WAV file");

        // Verify WAV header structure
        byte[] outputBytes = Files.readAllBytes(output);
        verifyWavStructure(outputBytes);

        System.out.printf("Verification: PASSED (valid WAV, %d bytes)%n%n", outputSize);
    }

    @Test
    @EnabledIf("sampleDirExists")
    void concatenateWithInMemoryMethod() throws Exception {
        List<Path> files = getChunkFiles();
        System.out.println("=== In-Memory Concatenation Test ===");
        System.out.printf("Input: %d chunks%n", files.size());

        // Load all chunks into memory
        List<byte[]> chunks = new ArrayList<>();
        long totalInputSize = 0;
        for (Path file : files) {
            byte[] data = Files.readAllBytes(file);
            chunks.add(data);
            totalInputSize += data.length;
        }
        System.out.printf("Total input size: %d bytes%n", totalInputSize);

        long startTime = System.currentTimeMillis();
        byte[] result = service.concatenateAudioChunksInMemory(chunks);
        long elapsed = System.currentTimeMillis() - startTime;

        assertNotNull(result);
        assertTrue(result.length > 0, "Result should not be empty");

        // Verify the output is a valid WAV
        AudioFormat format = Mp3ProcessingService.detectFormat(result);
        assertEquals(AudioFormat.WAV, format, "Output should be a valid WAV file");

        // Verify WAV header structure
        verifyWavStructure(result);

        // Write to disk for manual playback verification
        Path output = SAMPLE_DIR.resolve("merged_output_inmemory.wav");
        Files.write(output, result);

        System.out.printf("Output: %s (%d bytes)%n", output.getFileName(), result.length);
        System.out.printf("Time: %d ms%n", elapsed);
        System.out.printf("Verification: PASSED (valid WAV, %d bytes)%n%n", result.length);
    }

    @Test
    @EnabledIf("sampleDirExists")
    void verifyIndividualChunkPcmExtraction() throws Exception {
        List<Path> files = getChunkFiles();
        System.out.println("=== Individual Chunk PCM Extraction ===");

        long totalPcmBytes = 0;
        int validChunks = 0;

        for (Path file : files) {
            byte[] data = Files.readAllBytes(file);
            AudioFormat format = Mp3ProcessingService.detectFormat(data);
            if (format != AudioFormat.WAV) {
                System.out.printf("  %-20s SKIPPED (not WAV)%n", file.getFileName());
                continue;
            }

            // Verify we can parse the WAV structure
            ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            // Skip RIFF header (12 bytes)
            buf.position(12);

            boolean foundFmt = false, foundData = false;
            int dataSize = 0;

            while (buf.remaining() >= 8) {
                byte[] id = new byte[4];
                buf.get(id);
                int chunkSize = buf.getInt();
                String chunkId = new String(id);

                if ("fmt ".equals(chunkId)) {
                    foundFmt = true;
                    buf.position(buf.position() + chunkSize);
                } else if ("data".equals(chunkId)) {
                    foundData = true;
                    dataSize = chunkSize;
                    break;
                } else {
                    buf.position(buf.position() + chunkSize + (chunkSize % 2));
                }
            }

            assertTrue(foundFmt, "WAV file should have fmt chunk: " + file.getFileName());
            assertTrue(foundData, "WAV file should have data chunk: " + file.getFileName());
            assertTrue(dataSize > 0, "data chunk should have non-zero size: " + file.getFileName());

            totalPcmBytes += dataSize;
            validChunks++;

            System.out.printf("  %-20s fmt=OK data=%d bytes%n", file.getFileName(), dataSize);
        }

        System.out.printf("%nValid chunks: %d, Total PCM: %d bytes (%.1f MB)%n%n",
                validChunks, totalPcmBytes, totalPcmBytes / 1024.0 / 1024.0);
        assertTrue(validChunks > 0, "Should have at least one valid WAV chunk");
    }

    private List<Path> getChunkFiles() throws Exception {
        try (Stream<Path> stream = Files.list(SAMPLE_DIR)) {
            return stream
                    .filter(p -> p.getFileName().toString().startsWith("chunk_") && p.getFileName().toString().endsWith(".mp3"))
                    .sorted(Comparator.comparingInt(this::extractChunkNumber))
                    .toList();
        }
    }

    private int extractChunkNumber(Path path) {
        String name = path.getFileName().toString();
        // chunk_0.mp3 -> 0
        String num = name.replace("chunk_", "").replace(".mp3", "");
        return Integer.parseInt(num);
    }

    private void verifyWavStructure(byte[] data) {
        assertTrue(data.length >= 44, "WAV file should be at least 44 bytes");

        // RIFF header
        assertEquals('R', (char) data[0]);
        assertEquals('I', (char) data[1]);
        assertEquals('F', (char) data[2]);
        assertEquals('F', (char) data[3]);

        // File size field
        int riffSize = ByteBuffer.wrap(data, 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
        assertEquals(data.length - 8, riffSize, "RIFF size should be fileSize - 8");

        // WAVE format
        assertEquals('W', (char) data[8]);
        assertEquals('A', (char) data[9]);
        assertEquals('V', (char) data[10]);
        assertEquals('E', (char) data[11]);

        // Find data chunk and verify its size
        int pos = 12;
        boolean foundData = false;
        while (pos + 8 <= data.length) {
            String chunkId = new String(data, pos, 4);
            int chunkSize = ByteBuffer.wrap(data, pos + 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
            if ("data".equals(chunkId)) {
                foundData = true;
                int expectedDataEnd = pos + 8 + chunkSize;
                assertEquals(data.length, expectedDataEnd, "data chunk size should match remaining file length");
                break;
            }
            pos += 8 + chunkSize;
            if (chunkSize % 2 != 0) pos++;
        }
        assertTrue(foundData, "WAV file should contain a 'data' chunk");
    }
}
