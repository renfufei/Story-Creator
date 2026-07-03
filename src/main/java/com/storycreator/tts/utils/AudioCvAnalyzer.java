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
 * <p>CV detection is best suited for identifying <b>steady-state noise</b> — signals whose
 * amplitude remains nearly constant over time (e.g., 50/60Hz electrical hum, stuck oscillator
 * tones, constant white noise floor). A low CV value (e.g., &lt; 0.05) means the energy level
 * barely fluctuates across the sliding window, which is characteristic of these noise types.
 *
 * <p>Windows containing silence (RMS &lt; 50) are automatically skipped to prevent silence
 * regions from artificially producing low CV values.
 *
 * <h3>噪音类型诊断指南</h3>
 * <ul>
 *   <li>低 CV（&lt; 0.05）→ 固定音调/电力哼声 → 使用 AudioCvAnalyzer</li>
 *   <li>低相关性（avg|corr| &lt; 0.10）→ 随机宽带噪音 → 使用 AudioCorrelationAnalyzer</li>
 *   <li>单调递增 RMS → 渐变噪音 → 使用 AudioRmsAnalyzer / AudioNoiseProfiler</li>
 *   <li>综合分析 → 使用 AudioNoiseProfiler（整合以上三种检测）</li>
 * </ul>
 *
 * <p>Usage as CLI tool:
 * <pre>
 *   java -cp target/classes com.storycreator.tts.utils.AudioCvAnalyzer /path/to/file.wav [windowMs] [slidingWindowSize] [cvThreshold]
 * </pre>
 *
 * <p>Usage as library:
 * <pre>
 *   var results = AudioCvAnalyzer.analyze(audioBytes, 24000, 100, 30, 0.10);
 *   results.nonSilentWindows().forEach(r -> System.out.println(r));
 * </pre>
 *
 * @see AudioCorrelationAnalyzer
 * @see AudioNoiseProfiler
 * @see Mp3QualityDetector
 */
public class AudioCvAnalyzer {

    private static final double SILENCE_RMS_THRESHOLD = 50.0;

    public record CvWindowResult(int startWindowIndex, double startTimeSeconds,
                                 double meanRms, double stddev, double cv, boolean hasSilence) {
        @Override
        public String toString() {
            if (hasSilence) {
                return String.format("start=%d (%.1fs) (has silence)", startWindowIndex, startTimeSeconds);
            }
            return String.format("start=%d (%.1fs) meanRMS=%.1f stddev=%.1f cv=%.4f",
                    startWindowIndex, startTimeSeconds, meanRms, stddev, cv);
        }
    }

    public record AnalysisResult(double totalDurationSeconds, int numWindows,
                                 List<CvWindowResult> lowCvWindows, List<CvWindowResult> allWindows) {

        /**
         * Returns only non-silent windows from the analysis.
         */
        public List<CvWindowResult> nonSilentWindows() {
            return allWindows.stream().filter(w -> !w.hasSilence).toList();
        }

        /**
         * Print only the low-CV windows (below threshold), excluding silence.
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
         * Print all sliding window CV values, including silence markers.
         */
        public void printAll() {
            System.out.printf("Total duration: %.2fs, Windows: %d%n", totalDurationSeconds, numWindows);
            System.out.println("StartWin | Time  | Mean RMS | StdDev | CV");
            System.out.println("---------|-------|----------|--------|------");
            for (CvWindowResult w : allWindows) {
                if (w.hasSilence) {
                    System.out.printf("%8d | %5.1f | (has silence)%n",
                            w.startWindowIndex, w.startTimeSeconds);
                } else {
                    System.out.printf("%8d | %5.1f | %8.1f | %6.1f | %.4f%n",
                            w.startWindowIndex, w.startTimeSeconds, w.meanRms, w.stddev, w.cv);
                }
            }
        }

        /**
         * Find the longest consecutive stretch of low-CV windows (excluding silence).
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
     * Uses proper WAV header parsing to locate PCM data.
     * Windows containing silence (RMS &lt; 50) are skipped.
     *
     * @param audioData         full WAV file bytes (including header)
     * @param sampleRate        sample rate in Hz (e.g., 24000) — used as fallback if header parsing fails
     * @param windowMs          per-window size in milliseconds (e.g., 100)
     * @param slidingWindowSize number of consecutive windows to compute CV over (e.g., 30 = 3s)
     * @param cvThreshold       CV values below this are flagged as "low" (e.g., 0.10)
     * @return analysis result
     */
    public static AnalysisResult analyze(byte[] audioData, int sampleRate, int windowMs,
                                         int slidingWindowSize, double cvThreshold) {
        AudioRmsAnalyzer.WavInfo info = AudioRmsAnalyzer.parseWavHeader(audioData);
        if (info == null) {
            info = new AudioRmsAnalyzer.WavInfo(sampleRate, 1, 16, 44, audioData.length - 44);
        }

        int pcmStart = info.pcmStart();
        int effectiveSampleRate = info.sampleRate();
        int bytesPerSample = 2; // 16-bit
        double windowSec = windowMs / 1000.0;
        int windowSamples = (int) (effectiveSampleRate * windowSec);
        int windowBytes = windowSamples * bytesPerSample;
        int pcmLength = info.pcmLength();
        int numWindows = pcmLength / windowBytes;
        double totalDuration = pcmLength / (double) (effectiveSampleRate * bytesPerSample);

        // Compute RMS per window
        double[] rms = new double[numWindows];
        for (int w = 0; w < numWindows; w++) {
            int start = pcmStart + w * windowBytes;
            double sumSq = 0;
            for (int i = 0; i < windowSamples; i++) {
                int offset = start + i * bytesPerSample;
                if (offset + 1 >= audioData.length) break;
                short sample = (short) ((audioData[offset] & 0xFF) | (audioData[offset + 1] << 8));
                sumSq += (double) sample * sample;
            }
            rms[w] = Math.sqrt(sumSq / windowSamples);
        }

        // Sliding window CV computation with silence skipping
        List<CvWindowResult> allWindows = new ArrayList<>();
        List<CvWindowResult> lowCvWindows = new ArrayList<>();

        for (int i = 0; i <= numWindows - slidingWindowSize; i++) {
            // Check for silence within the sliding window
            boolean hasSilence = false;
            double sum = 0;
            for (int j = i; j < i + slidingWindowSize; j++) {
                if (rms[j] < SILENCE_RMS_THRESHOLD) {
                    hasSilence = true;
                    break;
                }
                sum += rms[j];
            }

            if (hasSilence) {
                allWindows.add(new CvWindowResult(i, i * windowSec, 0, 0, 0, true));
                continue;
            }

            double mean = sum / slidingWindowSize;
            double variance = 0;
            for (int j = i; j < i + slidingWindowSize; j++) {
                double diff = rms[j] - mean;
                variance += diff * diff;
            }
            double stddev = Math.sqrt(variance / slidingWindowSize);
            double cv = mean > 0 ? stddev / mean : 0;

            CvWindowResult result = new CvWindowResult(i, i * windowSec, mean, stddev, cv, false);
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
        result.printAll();
    }
}
