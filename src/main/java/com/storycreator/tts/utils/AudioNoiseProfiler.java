package com.storycreator.tts.utils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Profiles WAV audio for electrical noise patterns that other analyzers miss:
 * <ul>
 *   <li><b>Monotonic energy ramp</b>: RMS steadily rises or falls over 1+ seconds (CV check misses
 *       these because rising energy has CV > 0.05)</li>
 * </ul>
 *
 * <p>These patterns are characteristic of TTS model failures that produce electrical noise
 * instead of speech. Normal speech has frequent RMS direction changes at syllable boundaries (~100ms).
 *
 * <p>Usage as CLI tool:
 * <pre>
 *   java -cp target/classes com.storycreator.tts.utils.AudioNoiseProfiler /path/to/file.wav [windowMs] [slidingWindow]
 * </pre>
 *
 * <p>Usage as library:
 * <pre>
 *   var result = AudioNoiseProfiler.analyze(audioBytes, 24000, 100, 12);
 *   if (result.hasRamp()) System.out.println("Ramp: " + result.rampResults().get(0));
 * </pre>
 */
public class AudioNoiseProfiler {

    // Default thresholds (matching Mp3QualityDetector)
    private static final double SILENCE_RMS_THRESHOLD = 50.0;
    private static final double RAMP_MONO_FRACTION = 0.90;
    private static final double RAMP_MIN_ENERGY_RATIO = 1.5;

    /**
     * Result of a monotonic ramp detection in a sliding window.
     *
     * @param startWindowIndex index of the first window in the detected segment
     * @param startTimeSeconds time offset in seconds
     * @param direction        "rising" or "falling"
     * @param startRms         RMS at the beginning of the ramp
     * @param endRms           RMS at the end of the ramp
     * @param monoFraction     fraction of pairs that are monotonic (0.0 - 1.0)
     * @param energyRatio      max RMS / min RMS within the window
     */
    public record RampResult(int startWindowIndex, double startTimeSeconds, String direction,
                             double startRms, double endRms, double monoFraction, double energyRatio) {
        @Override
        public String toString() {
            return String.format("start=%d (%.1fs) %s: RMS %.0f→%.0f, mono=%.2f, ratio=%.2f",
                    startWindowIndex, startTimeSeconds, direction, startRms, endRms, monoFraction, energyRatio);
        }
    }

    /**
     * Combined analysis result.
     */
    public record AnalysisResult(double totalDurationSeconds, int numWindows,
                                 double[] rmsValues, double[] correlations,
                                 List<RampResult> rampResults) {

        public boolean hasRamp() {
            return !rampResults.isEmpty();
        }

        public boolean hasAnyIssue() {
            return hasRamp();
        }

        public void print() {
            System.out.printf("Total duration: %.2fs, Windows: %d%n", totalDurationSeconds, numWindows);

            // Per-window RMS
            System.out.println("\n--- Per-window RMS ---");
            System.out.println("Window# | Time(s) | RMS");
            System.out.println("--------|---------|--------");
            for (int i = 0; i < numWindows; i++) {
                System.out.printf("%7d | %7.2f | %7.1f%n", i, i * 0.1, rmsValues[i]);
            }

            // Per-window correlations
            System.out.println("\n--- Adjacent-window correlations ---");
            System.out.println("Pair      | Corr");
            System.out.println("----------|--------");
            for (int i = 0; i < correlations.length; i++) {
                System.out.printf("w%d→w%d | %7.4f%n", i, i + 1, correlations[i]);
            }

            // Ramp detections
            System.out.printf("%n--- Ramp detections: %d ---%n", rampResults.size());
            for (RampResult r : rampResults) {
                System.out.println("  " + r);
            }
        }
    }

    /**
     * Full noise profiling analysis on a WAV file.
     *
     * @param audioData     full WAV file bytes (including 44-byte header)
     * @param sampleRate    sample rate in Hz (e.g., 24000)
     * @param windowMs      per-window size in milliseconds (e.g., 100)
     * @param slidingWindow number of consecutive windows for detection (e.g., 12 = 1.2s)
     * @return combined analysis result
     */
    public static AnalysisResult analyze(byte[] audioData, int sampleRate, int windowMs, int slidingWindow) {
        int pcmStart = 44;
        int bytesPerSample = 2;
        double windowSec = windowMs / 1000.0;
        int windowSamples = (int) (sampleRate * windowSec);
        int windowBytes = windowSamples * bytesPerSample;
        int pcmLength = audioData.length - pcmStart;
        int numWindows = pcmLength / windowBytes;
        double totalDuration = pcmLength / (double) (sampleRate * bytesPerSample);

        // Extract RMS per window
        double[] rmsValues = new double[numWindows];
        double[][] samplesArr = new double[numWindows][];
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

        // Compute adjacent-window correlations
        double[] correlations = new double[numWindows > 0 ? numWindows - 1 : 0];
        for (int w = 0; w < numWindows - 1; w++) {
            if (rmsValues[w] < SILENCE_RMS_THRESHOLD || rmsValues[w + 1] < SILENCE_RMS_THRESHOLD) {
                correlations[w] = 0.0; // skip silent windows
            } else {
                correlations[w] = AudioCorrelationAnalyzer.pearsonCorrelation(samplesArr[w], samplesArr[w + 1]);
            }
        }

        // --- Monotonic ramp detection ---
        List<RampResult> rampResults = new ArrayList<>();
        if (numWindows >= slidingWindow) {
            int upCount = 0, downCount = 0, validPairs = 0;

            // Initialize first window
            for (int j = 0; j < slidingWindow - 1; j++) {
                if (rmsValues[j] < SILENCE_RMS_THRESHOLD || rmsValues[j + 1] < SILENCE_RMS_THRESHOLD) continue;
                validPairs++;
                if (rmsValues[j + 1] > rmsValues[j]) upCount++;
                else if (rmsValues[j + 1] < rmsValues[j]) downCount++;
            }

            for (int i = 0; i <= numWindows - slidingWindow; i++) {
                if (validPairs >= slidingWindow - 2) { // require most pairs valid
                    double upFraction = validPairs > 0 ? (double) upCount / validPairs : 0;
                    double downFraction = validPairs > 0 ? (double) downCount / validPairs : 0;

                    if (upFraction >= RAMP_MONO_FRACTION || downFraction >= RAMP_MONO_FRACTION) {
                        // Check energy ratio guard
                        double minRms = Double.MAX_VALUE, maxRms = 0;
                        boolean allNonSilent = true;
                        for (int j = i; j < i + slidingWindow; j++) {
                            if (rmsValues[j] < SILENCE_RMS_THRESHOLD) { allNonSilent = false; break; }
                            minRms = Math.min(minRms, rmsValues[j]);
                            maxRms = Math.max(maxRms, rmsValues[j]);
                        }

                        if (allNonSilent && minRms > 0 && (maxRms / minRms) >= RAMP_MIN_ENERGY_RATIO) {
                            String direction = upFraction >= RAMP_MONO_FRACTION ? "rising" : "falling";
                            double fraction = Math.max(upFraction, downFraction);
                            rampResults.add(new RampResult(i, i * windowSec, direction,
                                    rmsValues[i], rmsValues[i + slidingWindow - 1], fraction, maxRms / minRms));
                        }
                    }
                }

                // Slide window
                if (i + slidingWindow < numWindows) {
                    int outJ = i;
                    if (rmsValues[outJ] >= SILENCE_RMS_THRESHOLD && rmsValues[outJ + 1] >= SILENCE_RMS_THRESHOLD) {
                        validPairs--;
                        if (rmsValues[outJ + 1] > rmsValues[outJ]) upCount--;
                        else if (rmsValues[outJ + 1] < rmsValues[outJ]) downCount--;
                    }
                    int inJ = i + slidingWindow - 1;
                    if (rmsValues[inJ] >= SILENCE_RMS_THRESHOLD && rmsValues[inJ + 1] >= SILENCE_RMS_THRESHOLD) {
                        validPairs++;
                        if (rmsValues[inJ + 1] > rmsValues[inJ]) upCount++;
                        else if (rmsValues[inJ + 1] < rmsValues[inJ]) downCount++;
                    }
                }
            }
        }

        return new AnalysisResult(totalDuration, numWindows, rmsValues, correlations, rampResults);
    }

    /**
     * Convenience overload: 24000Hz, 100ms windows, 12 sliding window (1.2s).
     */
    public static AnalysisResult analyze(byte[] audioData) {
        return analyze(audioData, 24000, 100, 12);
    }

    // --- CLI entry point ---

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: AudioNoiseProfiler <wav-file> [windowMs] [slidingWindow]");
            System.err.println("  Profiles audio for electrical noise patterns:");
            System.err.println("  - Monotonic energy ramps (steadily rising/falling RMS)");
            System.exit(1);
        }
        byte[] data = Files.readAllBytes(Path.of(args[0]));
        int windowMs = args.length > 1 ? Integer.parseInt(args[1]) : 100;
        int slidingWindow = args.length > 2 ? Integer.parseInt(args[2]) : 12;

        AnalysisResult result = analyze(data, 24000, windowMs, slidingWindow);
        result.print();

        // Summary
        System.out.println("\n=== SUMMARY ===");
        if (result.hasAnyIssue()) {
            System.out.println("NOISE DETECTED:");
            if (result.hasRamp()) {
                System.out.printf("  Ramp segments: %d (first at %.1fs)%n",
                        result.rampResults().size(), result.rampResults().get(0).startTimeSeconds());
            }
        } else {
            System.out.println("No noise patterns detected.");
        }
    }
}
