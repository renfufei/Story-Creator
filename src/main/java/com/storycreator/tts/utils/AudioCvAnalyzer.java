package com.storycreator.tts.utils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Analyzes WAV (PCM 16-bit mono) audio files using a sliding window approach
 * to compute the Coefficient of Variation (CV = stddev/mean) of RMS energy.
 * Low CV indicates sustained constant-level audio (stuck tones, electrical hum, static noise).
 *
 * <p>Usage as CLI tool:
 * <pre>
 *   java -cp target/classes com.storycreator.tts.utils.AudioCvAnalyzer /path/to/file.wav [windowMs] [slidingWindowSize] [cvThreshold]
 * </pre>
 *
 * <p>Usage as library:
 * <pre>
 *   var results = AudioCvAnalyzer.analyze(audioBytes, 24000, 100, 30, 0.10);
 *   results.forEach(r -> System.out.println(r));
 * </pre>
 */
public class AudioCvAnalyzer {

    public record CvWindowResult(int startWindowIndex, double startTimeSeconds,
                                 double meanRms, double stddev, double cv) {
        @Override
        public String toString() {
            return String.format("start=%d (%.1fs) meanRMS=%.1f stddev=%.1f cv=%.4f",
                    startWindowIndex, startTimeSeconds, meanRms, stddev, cv);
        }
    }

    public record AnalysisResult(double totalDurationSeconds, int numWindows,
                                 List<CvWindowResult> lowCvWindows, List<CvWindowResult> allWindows) {

        /**
         * Print only the low-CV windows (below threshold).
         */
        public void printLowCv() {
            System.out.printf("Total duration: %.2fs, Windows: %d, Low-CV segments: %d%n",
                    totalDurationSeconds, numWindows, lowCvWindows.size());
            if (lowCvWindows.isEmpty()) {
                System.out.println("No low-CV segments found.");
                return;
            }
            System.out.println("StartWin | Time  | Mean RMS | StdDev | CV");
            System.out.println("---------|-------|----------|--------|------");
            for (CvWindowResult w : lowCvWindows) {
                System.out.printf("%8d | %5.1f | %8.1f | %6.1f | %.4f%n",
                        w.startWindowIndex, w.startTimeSeconds, w.meanRms, w.stddev, w.cv);
            }
        }

        /**
         * Print all sliding window CV values.
         */
        public void printAll() {
            System.out.printf("Total duration: %.2fs, Windows: %d%n", totalDurationSeconds, numWindows);
            System.out.println("StartWin | Time  | Mean RMS | StdDev | CV");
            System.out.println("---------|-------|----------|--------|------");
            for (CvWindowResult w : allWindows) {
                System.out.printf("%8d | %5.1f | %8.1f | %6.1f | %.4f%n",
                        w.startWindowIndex, w.startTimeSeconds, w.meanRms, w.stddev, w.cv);
            }
        }

        /**
         * Find the longest consecutive stretch of low-CV windows.
         */
        public int longestLowCvStreak() {
            if (lowCvWindows.isEmpty()) return 0;
            int best = 1, current = 1;
            for (int i = 1; i < lowCvWindows.size(); i++) {
                if (lowCvWindows.get(i).startWindowIndex == lowCvWindows.get(i - 1).startWindowIndex + 1) {
                    current++;
                    best = Math.max(best, current);
                } else {
                    current = 1;
                }
            }
            return best;
        }
    }

    /**
     * Analyze audio CV using sliding windows over RMS values.
     *
     * @param audioData         full WAV file bytes (including 44-byte header)
     * @param sampleRate        sample rate in Hz (e.g., 24000)
     * @param windowMs          per-window size in milliseconds (e.g., 100)
     * @param slidingWindowSize number of consecutive windows to compute CV over (e.g., 30 = 3s)
     * @param cvThreshold       CV values below this are flagged as "low" (e.g., 0.10)
     * @return analysis result
     */
    public static AnalysisResult analyze(byte[] audioData, int sampleRate, int windowMs,
                                         int slidingWindowSize, double cvThreshold) {
        int pcmStart = 44;
        int bytesPerSample = 2;
        double windowSec = windowMs / 1000.0;
        int windowSamples = (int) (sampleRate * windowSec);
        int windowBytes = windowSamples * bytesPerSample;
        int pcmLength = audioData.length - pcmStart;
        int numWindows = pcmLength / windowBytes;
        double totalDuration = pcmLength / (double) (sampleRate * bytesPerSample);

        // Compute RMS per window
        double[] rms = new double[numWindows];
        for (int w = 0; w < numWindows; w++) {
            int start = pcmStart + w * windowBytes;
            double sumSq = 0;
            for (int i = 0; i < windowSamples; i++) {
                int offset = start + i * bytesPerSample;
                short sample = (short) ((audioData[offset] & 0xFF) | (audioData[offset + 1] << 8));
                sumSq += (double) sample * sample;
            }
            rms[w] = Math.sqrt(sumSq / windowSamples);
        }

        // Sliding window CV computation
        List<CvWindowResult> allWindows = new ArrayList<>();
        List<CvWindowResult> lowCvWindows = new ArrayList<>();

        for (int i = 0; i <= numWindows - slidingWindowSize; i++) {
            double sum = 0;
            for (int j = i; j < i + slidingWindowSize; j++) {
                sum += rms[j];
            }
            double mean = sum / slidingWindowSize;
            double variance = 0;
            for (int j = i; j < i + slidingWindowSize; j++) {
                double diff = rms[j] - mean;
                variance += diff * diff;
            }
            double stddev = Math.sqrt(variance / slidingWindowSize);
            double cv = mean > 0 ? stddev / mean : 0;

            CvWindowResult result = new CvWindowResult(i, i * windowSec, mean, stddev, cv);
            allWindows.add(result);
            if (cv < cvThreshold) {
                lowCvWindows.add(result);
            }
        }

        return new AnalysisResult(totalDuration, numWindows, lowCvWindows, allWindows);
    }

    /**
     * Convenience overload with defaults: 24000Hz, 100ms windows, 30 sliding window (3s), 0.10 CV threshold.
     */
    public static AnalysisResult analyze(byte[] audioData) {
        return analyze(audioData, 24000, 100, 30, 0.10);
    }

    // --- CLI entry point ---

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: AudioCvAnalyzer <wav-file> [windowMs] [slidingWindowSize] [cvThreshold]");
            System.exit(1);
        }
        byte[] data = Files.readAllBytes(Path.of(args[0]));
        int windowMs = args.length > 1 ? Integer.parseInt(args[1]) : 100;
        int slidingWindowSize = args.length > 2 ? Integer.parseInt(args[2]) : 30;
        double cvThreshold = args.length > 3 ? Double.parseDouble(args[3]) : 0.10;

        AnalysisResult result = analyze(data, 24000, windowMs, slidingWindowSize, cvThreshold);
        result.printLowCv();
    }
}
