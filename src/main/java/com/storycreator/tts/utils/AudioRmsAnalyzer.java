package com.storycreator.tts.utils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Analyzes WAV (PCM 16-bit mono) audio files by computing per-window RMS energy.
 * Useful for identifying silence, electrical hum, stuck tones, and other anomalies.
 *
 * <p>Usage as CLI tool:
 * <pre>
 *   java -cp target/classes com.storycreator.tts.utils.AudioRmsAnalyzer /path/to/file.wav [windowMs]
 * </pre>
 *
 * <p>Usage as library:
 * <pre>
 *   var results = AudioRmsAnalyzer.analyze(audioBytes, 24000, 100);
 *   results.forEach(w -> System.out.println(w.timeSeconds() + " -> RMS " + w.rms()));
 * </pre>
 */
public class AudioRmsAnalyzer {

    public record WindowResult(int windowIndex, double timeSeconds, double rms, int maxAmplitude) {
    }

    public record AnalysisResult(double totalDurationSeconds, int numWindows, List<WindowResult> windows) {

        public void print() {
            System.out.printf("Total duration: %.2fs, Windows: %d%n", totalDurationSeconds, numWindows);
            System.out.println("Window# | Time(s) | RMS     | MaxAmp");
            System.out.println("--------|---------|---------|-------");
            for (WindowResult w : windows) {
                System.out.printf("%7d | %7.1f | %7.1f | %5d%n",
                        w.windowIndex, w.timeSeconds, w.rms, w.maxAmplitude);
            }
        }
    }

    /**
     * Analyze raw PCM audio data (after WAV header) with given sample rate and window size.
     *
     * @param audioData    full WAV file bytes (including 44-byte header)
     * @param sampleRate   sample rate in Hz (e.g., 24000)
     * @param windowMs     analysis window size in milliseconds (e.g., 100)
     * @return analysis result with per-window RMS and max amplitude
     */
    public static AnalysisResult analyze(byte[] audioData, int sampleRate, int windowMs) {
        int pcmStart = 44; // standard WAV header size
        int bytesPerSample = 2; // 16-bit PCM
        double windowSec = windowMs / 1000.0;
        int windowSamples = (int) (sampleRate * windowSec);
        int windowBytes = windowSamples * bytesPerSample;
        int pcmLength = audioData.length - pcmStart;
        int numWindows = pcmLength / windowBytes;
        double totalDuration = pcmLength / (double) (sampleRate * bytesPerSample);

        List<WindowResult> windows = new ArrayList<>(numWindows);

        for (int w = 0; w < numWindows; w++) {
            int start = pcmStart + w * windowBytes;
            double sumSq = 0;
            int maxAmp = 0;
            for (int i = 0; i < windowSamples; i++) {
                int offset = start + i * bytesPerSample;
                short sample = (short) ((audioData[offset] & 0xFF) | (audioData[offset + 1] << 8));
                sumSq += (double) sample * sample;
                maxAmp = Math.max(maxAmp, Math.abs(sample));
            }
            double rms = Math.sqrt(sumSq / windowSamples);
            windows.add(new WindowResult(w, w * windowSec, rms, maxAmp));
        }

        return new AnalysisResult(totalDuration, numWindows, windows);
    }

    /**
     * Convenience overload: analyze with default 24000Hz sample rate, 100ms windows.
     */
    public static AnalysisResult analyze(byte[] audioData) {
        return analyze(audioData, 24000, 100);
    }

    /**
     * Calculate RMS statistics for a range of windows.
     */
    public static RmsStats statsForRange(List<WindowResult> windows, int fromIndex, int toIndex) {
        int count = toIndex - fromIndex;
        if (count <= 0) return new RmsStats(0, 0, 0, 0);

        double sum = 0, min = Double.MAX_VALUE, max = 0;
        for (int i = fromIndex; i < toIndex; i++) {
            double rms = windows.get(i).rms();
            sum += rms;
            min = Math.min(min, rms);
            max = Math.max(max, rms);
        }
        double mean = sum / count;
        double variance = 0;
        for (int i = fromIndex; i < toIndex; i++) {
            double diff = windows.get(i).rms() - mean;
            variance += diff * diff;
        }
        double stddev = Math.sqrt(variance / count);
        return new RmsStats(mean, stddev, min, max);
    }

    public record RmsStats(double mean, double stddev, double min, double max) {
        public double cv() {
            return mean > 0 ? stddev / mean : 0;
        }

        @Override
        public String toString() {
            return String.format("mean=%.1f stddev=%.1f cv=%.4f min=%.1f max=%.1f", mean, stddev, cv(), min, max);
        }
    }

    // --- CLI entry point ---

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: AudioRmsAnalyzer <wav-file> [windowMs]");
            System.exit(1);
        }
        byte[] data = Files.readAllBytes(Path.of(args[0]));
        int windowMs = args.length > 1 ? Integer.parseInt(args[1]) : 100;
        AnalysisResult result = analyze(data, 24000, windowMs);
        result.print();
    }
}
