package com.storycreator.tts;

import com.storycreator.tts.Mp3QualityDetector.QualityResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
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

    // ===== Ramp Detection Tests =====

    private static final String SAMPLE_RAMP_FILE = "data/tts-export/35/20/chapter_1/chunk_149.mp3";

    static boolean rampFileExists() {
        return Files.exists(Path.of(SAMPLE_RAMP_FILE));
    }

    @Test
    @EnabledIf("rampFileExists")
    void detectRampInChunk149() throws Exception {
        byte[] data = Files.readAllBytes(Path.of(SAMPLE_RAMP_FILE));
        System.out.println("Analyzing ramp file: " + SAMPLE_RAMP_FILE + " (" + data.length + " bytes)");

        QualityResult result = detector.analyze(data);

        System.out.println("Passed: " + result.passed());
        System.out.println("Issue: " + result.issue());

        assertFalse(result.passed(), "Expected quality check to FAIL for electrical noise ramp");
        assertNotNull(result.issue());
        assertTrue(result.issue().toLowerCase().contains("noise") || result.issue().contains("ramp") || result.issue().contains("Stuck"),
                "Issue should mention noise/ramp/stuck: " + result.issue());
        System.out.println("SUCCESS: Detector correctly identified: " + result.issue());
    }

    @Test
    void syntheticRampDetected() {
        // Synthesize WAV: 20 windows of normal speech + 15 windows of monotonically rising RMS
        int sampleRate = 16000;
        int windowSamples = (int) (sampleRate * 0.1); // 100ms
        int totalWindows = 35; // 20 speech + 15 ramp
        int totalSamples = windowSamples * totalWindows;

        short[] pcm = new short[totalSamples];

        // First 20 windows: oscillating amplitude simulating speech (RMS ~200-800, up/down)
        for (int w = 0; w < 20; w++) {
            // Alternate between high and low amplitude
            double amplitude = (w % 2 == 0) ? 600 : 200;
            fillWindowWithNoise(pcm, w * windowSamples, windowSamples, amplitude);
        }

        // Next 15 windows: monotonically rising RMS from 300 to 3600
        for (int w = 0; w < 15; w++) {
            double amplitude = 300 + (3300.0 * w / 14);
            fillWindowWithNoise(pcm, (20 + w) * windowSamples, windowSamples, amplitude);
        }

        byte[] wav = createWav(pcm, sampleRate);
        QualityResult result = detector.analyze(wav);

        System.out.println("Synthetic ramp result: passed=" + result.passed() + ", issue=" + result.issue());
        assertFalse(result.passed(), "Expected synthetic ramp to be detected");
        assertNotNull(result.issue());
        assertTrue(result.issue().contains("ramp"), "Issue should mention ramp: " + result.issue());
    }

    @Test
    void normalSpeechNotFlaggedAsRamp() {
        // Synthesize WAV: 40 windows of oscillating RMS simulating normal speech
        int sampleRate = 16000;
        int windowSamples = (int) (sampleRate * 0.1);
        int totalWindows = 40;
        int totalSamples = windowSamples * totalWindows;

        short[] pcm = new short[totalSamples];

        // Oscillating amplitude: up-down-up-down pattern (typical speech)
        for (int w = 0; w < totalWindows; w++) {
            // Irregular oscillation: 200, 800, 400, 900, 150, 700, ...
            double amplitude = 200 + 600 * Math.abs(Math.sin(w * 1.7));
            fillWindowWithNoise(pcm, w * windowSamples, windowSamples, amplitude);
        }

        byte[] wav = createWav(pcm, sampleRate);
        QualityResult result = detector.analyze(wav);

        System.out.println("Normal speech result: passed=" + result.passed() + ", issue=" + result.issue());
        assertTrue(result.passed(), "Normal speech should pass: " + result.issue());
    }

    // ===== Helper methods for WAV synthesis =====

    private void fillWindowWithNoise(short[] pcm, int offset, int length, double targetRms) {
        // Fill with pseudo-random noise at target RMS level
        // Use simple deterministic pattern (not truly random but has varying sample values)
        for (int i = 0; i < length && (offset + i) < pcm.length; i++) {
            // Simple pseudo-random using a hash-like function
            double phase = (offset + i) * 0.1;
            double sample = Math.sin(phase) * 0.5 + Math.sin(phase * 2.7) * 0.3 + Math.sin(phase * 5.1) * 0.2;
            pcm[offset + i] = (short) Math.max(-32768, Math.min(32767, sample * targetRms * Math.sqrt(2)));
        }
    }

    private byte[] createWav(short[] pcm, int sampleRate) {
        int channels = 1;
        int bitsPerSample = 16;
        int dataSize = pcm.length * 2;
        int fileSize = 44 + dataSize;

        ByteBuffer buf = ByteBuffer.allocate(fileSize).order(ByteOrder.LITTLE_ENDIAN);
        // RIFF header
        buf.put("RIFF".getBytes());
        buf.putInt(fileSize - 8);
        buf.put("WAVE".getBytes());
        // fmt chunk
        buf.put("fmt ".getBytes());
        buf.putInt(16); // chunk size
        buf.putShort((short) 1); // PCM format
        buf.putShort((short) channels);
        buf.putInt(sampleRate);
        buf.putInt(sampleRate * channels * bitsPerSample / 8); // byte rate
        buf.putShort((short) (channels * bitsPerSample / 8)); // block align
        buf.putShort((short) bitsPerSample);
        // data chunk
        buf.put("data".getBytes());
        buf.putInt(dataSize);
        for (short s : pcm) {
            buf.putShort(s);
        }

        return buf.array();
    }
}
