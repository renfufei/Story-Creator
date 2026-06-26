package com.storycreator.tts.utils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Analyzes WAV (PCM 16-bit mono) audio files by computing Pearson correlation
 * between consecutive windows and stride-2 windows.
 * Useful for detecting stuck tones, repeating audio, and periodic noise.
 *
 * <p>Usage as CLI tool:
 * <pre>
 *   java -cp target/classes com.storycreator.tts.utils.AudioCorrelationAnalyzer /path/to/file.wav [windowMs]
 * </pre>
 *
 * <p>Usage as library:
 * <pre>
 *   var results = AudioCorrelationAnalyzer.analyze(audioBytes, 24000, 100);
 *   results.windows().forEach(w -> System.out.println(w.timeSeconds() + " corr=" + w.corrPrev()));
 * </pre>
 */
public class AudioCorrelationAnalyzer {

    public record WindowResult(int windowIndex, double timeSeconds, double rms,
                               double corrPrev, double corrStride2) {
    }

    public record AnalysisResult(double totalDurationSeconds, int numWindows, List<WindowResult> windows) {

        public void print() {
            System.out.println("Window# | Time(s) | RMS     | Corr w/ prev | Corr w/ prev-1");
            System.out.println("--------|---------|---------|--------------|---------------");
            for (WindowResult w : windows) {
                System.out.printf("%7d | %7.1f | %7.1f | %12.4f | %12.4f%n",
                        w.windowIndex, w.timeSeconds, w.rms, w.corrPrev, w.corrStride2);
            }
        }

        /**
         * Find the longest consecutive streak where stride-1 or stride-2 correlation exceeds threshold.
         */
        public StreakResult longestStreak(double threshold) {
            int bestStart = -1, bestLen = 0;
            int curStart = -1, curLen = 0;
            for (int i = 0; i < windows.size(); i++) {
                WindowResult w = windows.get(i);
                boolean match = w.corrPrev > threshold || w.corrStride2 > threshold;
                if (match) {
                    if (curStart < 0) curStart = i;
                    curLen++;
                    if (curLen > bestLen) {
                        bestLen = curLen;
                        bestStart = curStart;
                    }
                } else {
                    curStart = -1;
                    curLen = 0;
                }
            }
            return new StreakResult(bestStart, bestLen);
        }
    }

    public record StreakResult(int startIndex, int length) {
        public double durationSeconds(double windowSec) {
            return length * windowSec;
        }
    }

    /**
     * Analyze audio correlation between windows.
     *
     * @param audioData  full WAV file bytes (including 44-byte header)
     * @param sampleRate sample rate in Hz
     * @param windowMs   window size in milliseconds
     * @return analysis result with per-window correlations
     */
    public static AnalysisResult analyze(byte[] audioData, int sampleRate, int windowMs) {
        int pcmStart = 44;
        int bytesPerSample = 2;
        double windowSec = windowMs / 1000.0;
        int windowSamples = (int) (sampleRate * windowSec);
        int windowBytes = windowSamples * bytesPerSample;
        int pcmLength = audioData.length - pcmStart;
        int numWindows = pcmLength / windowBytes;
        double totalDuration = pcmLength / (double) (sampleRate * bytesPerSample);

        // Extract all windows
        double[][] samplesArr = new double[numWindows][];
        double[] rmsValues = new double[numWindows];

        for (int w = 0; w < numWindows; w++) {
            int start = pcmStart + w * windowBytes;
            samplesArr[w] = new double[windowSamples];
            double sumSq = 0;
            for (int i = 0; i < windowSamples; i++) {
                int offset = start + i * bytesPerSample;
                short sample = (short) ((audioData[offset] & 0xFF) | (audioData[offset + 1] << 8));
                samplesArr[w][i] = sample;
                sumSq += (double) sample * sample;
            }
            rmsValues[w] = Math.sqrt(sumSq / windowSamples);
        }

        // Compute correlations
        List<WindowResult> windows = new ArrayList<>(numWindows);
        for (int w = 0; w < numWindows; w++) {
            double corrPrev = (w > 0) ? pearsonCorrelation(samplesArr[w - 1], samplesArr[w]) : 0.0;
            double corrStride2 = (w > 1) ? pearsonCorrelation(samplesArr[w - 2], samplesArr[w]) : 0.0;
            windows.add(new WindowResult(w, w * windowSec, rmsValues[w], corrPrev, corrStride2));
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
     * Compute Pearson correlation coefficient between two sample arrays.
     */
    public static double pearsonCorrelation(double[] a, double[] b) {
        int n = Math.min(a.length, b.length);
        if (n == 0) return 0.0;

        double meanA = 0, meanB = 0;
        for (int i = 0; i < n; i++) {
            meanA += a[i];
            meanB += b[i];
        }
        meanA /= n;
        meanB /= n;

        double num = 0, denA = 0, denB = 0;
        for (int i = 0; i < n; i++) {
            double da = a[i] - meanA;
            double db = b[i] - meanB;
            num += da * db;
            denA += da * da;
            denB += db * db;
        }

        double den = Math.sqrt(denA) * Math.sqrt(denB);
        if (den == 0) return 1.0; // both constant → identical
        return num / den;
    }

    // --- CLI entry point ---

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: AudioCorrelationAnalyzer <wav-file> [windowMs]");
            System.exit(1);
        }
        byte[] data = Files.readAllBytes(Path.of(args[0]));
        int windowMs = args.length > 1 ? Integer.parseInt(args[1]) : 100;
        AnalysisResult result = analyze(data, 24000, windowMs);
        result.print();
    }
}
