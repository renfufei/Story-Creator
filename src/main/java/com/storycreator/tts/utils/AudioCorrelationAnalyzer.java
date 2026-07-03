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
 * <p><b>Low correlation between adjacent windows indicates random/uncorrelated noise</b>
 * (white noise, broadband static, random electrical interference). In such cases,
 * consecutive windows of audio are essentially independent random signals. A sustained
 * stretch of low |correlation| (e.g., avg &lt; 0.10) strongly suggests random wideband noise
 * rather than speech, music, or tonal artifacts.
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
 *   java -cp target/classes com.storycreator.tts.utils.AudioCorrelationAnalyzer /path/to/file.wav [windowMs]
 * </pre>
 *
 * <p>Usage as library:
 * <pre>
 *   var results = AudioCorrelationAnalyzer.analyze(audioBytes, 24000, 100);
 *   results.windows().forEach(w -> System.out.println(w.timeSeconds() + " corr=" + w.corrPrev()));
 *   var streak = results.lowCorrelationStreak(0.10);
 *   System.out.println("Longest low-corr streak: " + streak.length() + " windows");
 * </pre>
 *
 * @see AudioCvAnalyzer
 * @see AudioNoiseProfiler
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

        /**
         * Find the longest consecutive streak where |corrPrev| is below maxThreshold.
         * Useful for detecting random noise regions where adjacent windows are uncorrelated.
         *
         * @param maxThreshold absolute correlation threshold (e.g., 0.10)
         * @return streak result describing the longest low-correlation segment
         */
        public StreakResult lowCorrelationStreak(double maxThreshold) {
            int bestStart = -1, bestLen = 0;
            int curStart = -1, curLen = 0;
            for (int i = 1; i < windows.size(); i++) { // start from 1 since window 0 has no corrPrev
                WindowResult w = windows.get(i);
                boolean lowCorr = Math.abs(w.corrPrev) < maxThreshold;
                if (lowCorr) {
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

        /**
         * Compute average absolute correlation (|corrPrev|) for a window range.
         *
         * @param fromWindow start window index (inclusive)
         * @param toWindow   end window index (exclusive)
         * @return average |corrPrev| in the range, or 0 if range is invalid
         */
        public double avgAbsCorrelation(int fromWindow, int toWindow) {
            if (fromWindow < 0) fromWindow = 0;
            if (toWindow > windows.size()) toWindow = windows.size();
            if (fromWindow >= toWindow) return 0.0;

            double sum = 0;
            int count = 0;
            for (int i = fromWindow; i < toWindow; i++) {
                WindowResult w = windows.get(i);
                if (i > 0) { // window 0 has corrPrev=0 by definition
                    sum += Math.abs(w.corrPrev);
                    count++;
                }
            }
            return count > 0 ? sum / count : 0.0;
        }

        /**
         * Print low-correlation analysis summary.
         */
        public void printLowCorrelationSummary(double threshold) {
            StreakResult streak = lowCorrelationStreak(threshold);
            double avgCorr = avgAbsCorrelation(0, windows.size());
            System.out.printf("%n--- Low-correlation summary (threshold=%.2f) ---%n", threshold);
            System.out.printf("Overall avg |corrPrev|: %.4f%n", avgCorr);
            if (streak.length > 0) {
                double windowSec = windows.size() > 1
                        ? windows.get(1).timeSeconds - windows.get(0).timeSeconds : 0.1;
                System.out.printf("Longest low-corr streak: %d windows (%.1fs) starting at window %d (%.1fs)%n",
                        streak.length, streak.durationSeconds(windowSec),
                        streak.startIndex, streak.startIndex * windowSec);
                double streakAvg = avgAbsCorrelation(streak.startIndex, streak.startIndex + streak.length);
                System.out.printf("Streak avg |corrPrev|: %.4f%n", streakAvg);
            } else {
                System.out.println("No low-correlation streaks found.");
            }
        }
    }

    public record StreakResult(int startIndex, int length) {
        public double durationSeconds(double windowSec) {
            return length * windowSec;
        }
    }

    /**
     * Analyze audio correlation between windows.
     * Uses proper WAV header parsing to locate PCM data.
     *
     * @param audioData  full WAV file bytes (including header)
     * @param sampleRate sample rate in Hz — used as fallback if header parsing fails
     * @param windowMs   window size in milliseconds
     * @return analysis result with per-window correlations
     */
    public static AnalysisResult analyze(byte[] audioData, int sampleRate, int windowMs) {
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

        // Extract all windows
        double[][] samplesArr = new double[numWindows][];
        double[] rmsValues = new double[numWindows];

        for (int w = 0; w < numWindows; w++) {
            int start = pcmStart + w * windowBytes;
            samplesArr[w] = new double[windowSamples];
            double sumSq = 0;
            for (int i = 0; i < windowSamples; i++) {
                int offset = start + i * bytesPerSample;
                if (offset + 1 >= audioData.length) break;
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
        result.printLowCorrelationSummary(0.10);
    }
}
