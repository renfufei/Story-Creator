package com.storycreator.tts.utils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Analyzes WAV (PCM 16-bit) audio files by computing per-window RMS energy.
 * Useful for identifying silence, electrical hum, stuck tones, and other anomalies.
 *
 * <p>Supports proper WAV header parsing (fmt + data chunk discovery), multi-channel audio
 * (downmixes to first channel), and variable sample rates.
 *
 * <p>Also provides monotonic ramp analysis: detects stretches where RMS energy steadily
 * rises or falls (characteristic of electrical noise from TTS model failures).
 *
 * <p>Usage as CLI tool:
 * <pre>
 *   java -cp target/classes com.storycreator.tts.utils.AudioRmsAnalyzer /path/to/file.wav [windowMs] [slidingWindow]
 * </pre>
 *
 * <p>Usage as library:
 * <pre>
 *   var result = AudioRmsAnalyzer.analyze(audioBytes, 100);
 *   result.print();
 *   result.printMonotonic(12, 0.80);
 * </pre>
 */
public class AudioRmsAnalyzer {

    private static final double SILENCE_RMS_THRESHOLD = 50.0;

    public record WindowResult(int windowIndex, double timeSeconds, double rms, int maxAmplitude) {
    }

    /**
     * Parsed WAV format info extracted from the file header.
     */
    public record WavInfo(int sampleRate, int channels, int bitsPerSample, int pcmStart, int pcmLength) {
        public int bytesPerSample() { return bitsPerSample / 8; }
        public int blockAlign() { return channels * bytesPerSample(); }
    }

    /**
     * A monotonic ramp segment found in the RMS profile.
     *
     * @param startWindowIndex first window of the segment
     * @param startTimeSeconds time offset in seconds
     * @param validPairs       number of non-silent adjacent pairs evaluated
     * @param upCount          pairs where RMS increased
     * @param downCount        pairs where RMS decreased
     * @param upFraction       upCount / validPairs
     * @param downFraction     downCount / validPairs
     * @param energyRatio      max RMS / min RMS within the sliding window
     */
    public record MonotonicSegment(int startWindowIndex, double startTimeSeconds,
                                   int validPairs, int upCount, int downCount,
                                   double upFraction, double downFraction, double energyRatio) {
        public String direction() {
            return upFraction > downFraction ? "rising" : "falling";
        }

        @Override
        public String toString() {
            return String.format("i=%d (%.1fs) valid=%d up=%d(%.2f) down=%d(%.2f) ratio=%.2f [%s]",
                    startWindowIndex, startTimeSeconds, validPairs, upCount, upFraction,
                    downCount, downFraction, energyRatio, direction());
        }
    }

    public record AnalysisResult(double totalDurationSeconds, int numWindows,
                                 WavInfo wavInfo, List<WindowResult> windows) {

        public void print() {
            System.out.printf("SampleRate=%d Channels=%d BPS=%d%n",
                    wavInfo.sampleRate, wavInfo.channels, wavInfo.bitsPerSample);
            System.out.printf("Total duration: %.2fs, Windows: %d%n", totalDurationSeconds, numWindows);
            System.out.println("Window# | Time(s) | RMS     | MaxAmp");
            System.out.println("--------|---------|---------|-------");
            for (WindowResult w : windows) {
                System.out.printf("%7d | %7.2f | %7.1f | %5d%n",
                        w.windowIndex, w.timeSeconds, w.rms, w.maxAmplitude);
            }
        }

        /**
         * Perform monotonic ramp analysis on the RMS profile.
         * Scans with a sliding window, reports segments where >= monoThreshold fraction
         * of adjacent pairs are all-up or all-down.
         *
         * @param slidingWindow number of windows per segment (e.g., 12 = 1.2s at 100ms)
         * @param monoThreshold minimum fraction of monotone pairs to flag (e.g., 0.80)
         * @return list of monotonic segments found
         */
        public List<MonotonicSegment> findMonotonicSegments(int slidingWindow, double monoThreshold) {
            List<MonotonicSegment> results = new ArrayList<>();
            if (numWindows < slidingWindow) return results;

            double[] rms = windows.stream().mapToDouble(WindowResult::rms).toArray();
            double windowSec = numWindows > 0 ? windows.get(1).timeSeconds - windows.get(0).timeSeconds : 0.1;

            for (int i = 0; i <= numWindows - slidingWindow; i++) {
                int up = 0, down = 0, valid = 0;
                for (int j = i; j < i + slidingWindow - 1; j++) {
                    if (rms[j] < SILENCE_RMS_THRESHOLD || rms[j + 1] < SILENCE_RMS_THRESHOLD) continue;
                    valid++;
                    if (rms[j + 1] > rms[j]) up++;
                    else if (rms[j + 1] < rms[j]) down++;
                }
                if (valid == 0) continue;
                double upF = (double) up / valid;
                double downF = (double) down / valid;

                if (upF >= monoThreshold || downF >= monoThreshold) {
                    double min = Double.MAX_VALUE, max = 0;
                    for (int j = i; j < i + slidingWindow; j++) {
                        min = Math.min(min, rms[j]);
                        max = Math.max(max, rms[j]);
                    }
                    double ratio = min > 0 ? max / min : 0;
                    results.add(new MonotonicSegment(i, i * windowSec, valid, up, down, upF, downF, ratio));
                }
            }
            return results;
        }

        /**
         * Print monotonic ramp analysis results to stdout.
         */
        public void printMonotonic(int slidingWindow, double monoThreshold) {
            List<MonotonicSegment> segments = findMonotonicSegments(slidingWindow, monoThreshold);
            System.out.printf("%n--- Monotonic analysis (window=%d, threshold=%.2f) ---%n",
                    slidingWindow, monoThreshold);
            if (segments.isEmpty()) {
                System.out.println("No monotonic segments found.");
            } else {
                for (MonotonicSegment s : segments) {
                    System.out.println("  " + s);
                }
            }
        }
    }

    /**
     * Parse WAV header to extract format info and data chunk location.
     * Searches for "fmt " and "data" chunks rather than assuming fixed offsets.
     *
     * @param audioData full WAV file bytes
     * @return parsed format info, or null if invalid
     */
    public static WavInfo parseWavHeader(byte[] audioData) {
        if (audioData.length < 44) return null;

        // Verify RIFF/WAVE signature
        if (audioData[0] != 'R' || audioData[1] != 'I' || audioData[2] != 'F' || audioData[3] != 'F') return null;
        if (audioData[8] != 'W' || audioData[9] != 'A' || audioData[10] != 'V' || audioData[11] != 'E') return null;

        int fmtOffset = findChunk(audioData, "fmt ");
        int dataOffset = findChunk(audioData, "data");
        if (fmtOffset < 0 || dataOffset < 0) return null;

        // fmt chunk: offset+4 = chunk size, offset+8 = format data
        int channels = readUint16LE(audioData, fmtOffset + 8 + 2);
        int sampleRate = readInt32LE(audioData, fmtOffset + 8 + 4);
        int bitsPerSample = readUint16LE(audioData, fmtOffset + 8 + 14);

        if (sampleRate <= 0 || channels <= 0 || bitsPerSample <= 0) return null;

        int pcmStart = dataOffset + 8; // skip "data" tag + chunk size field
        int pcmLength = audioData.length - pcmStart;

        return new WavInfo(sampleRate, channels, bitsPerSample, pcmStart, pcmLength);
    }

    /**
     * Analyze audio with auto-detected format from WAV header.
     * Falls back to the specified sampleRate if header parsing fails.
     *
     * @param audioData full WAV file bytes
     * @param windowMs  analysis window size in milliseconds (e.g., 100)
     * @return analysis result with per-window RMS and max amplitude
     */
    public static AnalysisResult analyze(byte[] audioData, int windowMs) {
        WavInfo info = parseWavHeader(audioData);
        if (info == null) {
            // Fallback: assume standard 24000Hz mono 16-bit, header at offset 44
            info = new WavInfo(24000, 1, 16, 44, audioData.length - 44);
        }
        return analyzeWithInfo(audioData, info, windowMs);
    }

    /**
     * Analyze with explicit sample rate (legacy API, ignores WAV header sample rate).
     *
     * @param audioData  full WAV file bytes (including header)
     * @param sampleRate sample rate in Hz (e.g., 24000)
     * @param windowMs   analysis window size in milliseconds (e.g., 100)
     * @return analysis result with per-window RMS and max amplitude
     */
    public static AnalysisResult analyze(byte[] audioData, int sampleRate, int windowMs) {
        WavInfo info = parseWavHeader(audioData);
        if (info == null) {
            info = new WavInfo(sampleRate, 1, 16, 44, audioData.length - 44);
        } else {
            // Override sample rate but use parsed pcmStart/channels
            info = new WavInfo(sampleRate, info.channels(), info.bitsPerSample(), info.pcmStart(), info.pcmLength());
        }
        return analyzeWithInfo(audioData, info, windowMs);
    }

    private static AnalysisResult analyzeWithInfo(byte[] audioData, WavInfo info, int windowMs) {
        double windowSec = windowMs / 1000.0;
        int windowSamples = (int) (info.sampleRate() * windowSec);
        int windowBytes = windowSamples * info.blockAlign();
        int numWindows = info.pcmLength() / windowBytes;
        double totalDuration = info.pcmLength() / (double) (info.sampleRate() * info.blockAlign());

        List<WindowResult> windows = new ArrayList<>(numWindows);

        for (int w = 0; w < numWindows; w++) {
            int start = info.pcmStart() + w * windowBytes;
            double sumSq = 0;
            int maxAmp = 0;
            for (int i = 0; i < windowSamples; i++) {
                int offset = start + i * info.blockAlign();
                if (offset + info.bytesPerSample() > audioData.length) break;
                // Read first channel only (mono downmix)
                short sample;
                if (info.bytesPerSample() == 2) {
                    sample = (short) ((audioData[offset] & 0xFF) | (audioData[offset + 1] << 8));
                } else if (info.bytesPerSample() == 1) {
                    sample = (short) (((audioData[offset] & 0xFF) - 128) * 256);
                } else {
                    sample = 0;
                }
                sumSq += (double) sample * sample;
                maxAmp = Math.max(maxAmp, Math.abs(sample));
            }
            double rms = Math.sqrt(sumSq / windowSamples);
            windows.add(new WindowResult(w, w * windowSec, rms, maxAmp));
        }

        return new AnalysisResult(totalDuration, numWindows, info, windows);
    }

    /**
     * Convenience overload: analyze with auto-detected format, 100ms windows.
     */
    public static AnalysisResult analyze(byte[] audioData) {
        return analyze(audioData, 100);
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

    // --- WAV chunk parsing helpers ---

    private static int findChunk(byte[] data, String id) {
        for (int i = 12; i < data.length - 8; i++) {
            if (data[i] == id.charAt(0) && data[i + 1] == id.charAt(1)
                    && data[i + 2] == id.charAt(2) && data[i + 3] == id.charAt(3)) {
                return i;
            }
        }
        return -1;
    }

    private static int readUint16LE(byte[] d, int o) {
        return (d[o] & 0xFF) | ((d[o + 1] & 0xFF) << 8);
    }

    private static int readInt32LE(byte[] d, int o) {
        return (d[o] & 0xFF) | ((d[o + 1] & 0xFF) << 8)
                | ((d[o + 2] & 0xFF) << 16) | ((d[o + 3] & 0xFF) << 24);
    }

    // --- CLI entry point ---

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: AudioRmsAnalyzer <wav-file> [windowMs] [slidingWindow]");
            System.err.println("  Outputs per-window RMS + monotonic ramp analysis.");
            System.err.println("  windowMs: analysis window in ms (default: 100)");
            System.err.println("  slidingWindow: windows for monotonic check (default: 12)");
            System.exit(1);
        }
        byte[] data = Files.readAllBytes(Path.of(args[0]));
        int windowMs = args.length > 1 ? Integer.parseInt(args[1]) : 100;
        int slidingWindow = args.length > 2 ? Integer.parseInt(args[2]) : 12;

        AnalysisResult result = analyze(data, windowMs);
        result.print();
        result.printMonotonic(slidingWindow, 0.80);
    }
}
