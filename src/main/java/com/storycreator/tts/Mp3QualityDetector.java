package com.storycreator.tts;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.zip.CRC32;

@Service
public class Mp3QualityDetector {

    private static final Logger log = LoggerFactory.getLogger(Mp3QualityDetector.class);

    private static final double SILENCE_THRESHOLD_SECONDS = 2.0;
    private static final double REPEAT_THRESHOLD_SECONDS = 1.2;
    private static final double SILENCE_ZERO_RATIO = 0.90;

    // WAV/PCM analysis constants
    private static final double WAV_SILENCE_RMS_THRESHOLD = 50.0;
    private static final double WAV_REPEAT_CORRELATION_THRESHOLD = 0.90;
    private static final double WAV_WINDOW_SECONDS = 0.1; // 100ms windows
    // Sliding window: if average correlation over this many consecutive windows exceeds threshold, it's stuck
    private static final int WAV_REPEAT_SLIDING_WINDOW = 12; // 1.2 seconds at 100ms windows
    private static final double WAV_REPEAT_AVG_THRESHOLD = 0.92;

    // Stuck tone / noise detection via Coefficient of Variation (CV = stddev/mean)
    // Low CV means very stable RMS → indicates stuck tone, electrical hum, or static noise
    // Works regardless of absolute RMS level (catches both low-energy hum and high-energy stuck tones)
    private static final double STUCK_CV_THRESHOLD = 0.05;       // CV below this is considered stuck/noise
    private static final double STUCK_THRESHOLD_SECONDS = 1.2;   // minimum duration to flag
    private static final int STUCK_SLIDING_WINDOW = 12;          // 1.2 seconds at 100ms windows

    private final Mp3ProcessingService mp3ProcessingService;

    public Mp3QualityDetector(Mp3ProcessingService mp3ProcessingService) {
        this.mp3ProcessingService = mp3ProcessingService;
    }

    public record QualityResult(boolean passed, String issue, double issueStartSeconds) {
        public static QualityResult ok() {
            return new QualityResult(true, null, -1);
        }

        public static QualityResult failed(String issue, double startSeconds) {
            return new QualityResult(false, issue, startSeconds);
        }

        public static QualityResult failed(String issue) {
            return new QualityResult(false, issue, -1);
        }
    }

    /**
     * Analyze audio data for quality issues (silence, stuck tone, repeating audio).
     * Supports both MP3 and WAV (RIFF) formats.
     */
    public QualityResult analyze(byte[] audioData) {
        if (audioData == null || audioData.length < 44) {
            return QualityResult.failed("Audio data too small");
        }

        // Detect format: WAV starts with "RIFF", MP3 with ID3 or 0xFF sync
        if (isWavFormat(audioData)) {
            return analyzeWav(audioData);
        }

        return analyzeMp3(audioData);
    }

    private boolean isWavFormat(byte[] data) {
        return data.length >= 12
                && data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F'
                && data[8] == 'W' && data[9] == 'A' && data[10] == 'V' && data[11] == 'E';
    }

    // ===== WAV/PCM Analysis =====

    private QualityResult analyzeWav(byte[] data) {
        // Parse WAV header
        int dataOffset = findWavDataChunk(data);
        if (dataOffset < 0) {
            return QualityResult.failed("Invalid WAV: no data chunk found");
        }

        // Parse fmt chunk for sample rate and channels
        int fmtOffset = findWavChunk(data, "fmt ");
        if (fmtOffset < 0) {
            return QualityResult.failed("Invalid WAV: no fmt chunk found");
        }

        int channels = readUint16LE(data, fmtOffset + 8 + 2);
        int sampleRate = readInt32LE(data, fmtOffset + 8 + 4);
        int bitsPerSample = readUint16LE(data, fmtOffset + 8 + 14);

        if (sampleRate <= 0 || channels <= 0 || bitsPerSample <= 0) {
            return QualityResult.failed("Invalid WAV format parameters");
        }

        int bytesPerSample = bitsPerSample / 8;
        int blockAlign = channels * bytesPerSample;
        int pcmStart = dataOffset + 8; // skip "data" + chunk size
        int pcmLength = data.length - pcmStart;

        if (pcmLength < blockAlign * sampleRate) {
            // Less than 1 second of audio, skip analysis
            return QualityResult.ok();
        }

        // Analyze using 100ms windows
        int windowSamples = (int) (sampleRate * WAV_WINDOW_SECONDS);
        int windowBytes = windowSamples * blockAlign;
        int numWindows = pcmLength / windowBytes;

        if (numWindows < 2) {
            return QualityResult.ok();
        }

        // Extract RMS energies and detect silence + repetition
        double consecutiveSilence = 0.0;
        double maxSilence = 0.0;
        int silenceStartWindow = 0;
        int currentSilenceStart = 0;
        double consecutiveRepeat = 0.0;
        double maxRepeat = 0.0;
        int repeatStartWindow = 0;
        int currentRepeatStart = 0;
        // Stride-2 repetition: catches tones whose period is ~1 window (adjacent windows are anti-correlated)
        double consecutiveRepeatStride2 = 0.0;
        double maxRepeatStride2 = 0.0;
        int repeatStride2StartWindow = 0;
        int currentRepeatStride2Start = 0;

        // Sliding window for average correlation detection
        double[] correlationHistory = new double[numWindows];
        int corrCount = 0;

        // Store RMS values for noise detection pass
        double[] rmsHistory = new double[numWindows];

        // Keep last two windows for stride-2 correlation
        double[] prevWindow = null;
        double[] prevPrevWindow = null;

        for (int w = 0; w < numWindows; w++) {
            int windowStart = pcmStart + w * windowBytes;
            double[] samples = extractMonoSamples(data, windowStart, windowSamples, channels, bytesPerSample);

            // Silence detection via RMS
            double rms = computeRms(samples);
            rmsHistory[w] = rms;
            if (rms < WAV_SILENCE_RMS_THRESHOLD) {
                if (consecutiveSilence == 0.0) {
                    currentSilenceStart = w;
                }
                consecutiveSilence += WAV_WINDOW_SECONDS;
                if (consecutiveSilence > maxSilence) {
                    maxSilence = consecutiveSilence;
                    silenceStartWindow = currentSilenceStart;
                }
            } else {
                consecutiveSilence = 0.0;
            }

            // Repetition/stuck-tone detection via Pearson correlation with previous window
            if (prevWindow != null && rms >= WAV_SILENCE_RMS_THRESHOLD) {
                double corr = pearsonCorrelation(prevWindow, samples);
                correlationHistory[corrCount++] = corr;

                if (corr > WAV_REPEAT_CORRELATION_THRESHOLD) {
                    if (consecutiveRepeat == 0.0) {
                        currentRepeatStart = w;
                    }
                    consecutiveRepeat += WAV_WINDOW_SECONDS;
                    if (consecutiveRepeat > maxRepeat) {
                        maxRepeat = consecutiveRepeat;
                        repeatStartWindow = currentRepeatStart;
                    }
                } else {
                    consecutiveRepeat = 0.0;
                }
            } else {
                if (prevWindow != null) {
                    correlationHistory[corrCount++] = 0.0;
                }
                consecutiveRepeat = 0.0;
            }

            // Stride-2 repetition detection: compare with window two steps back
            if (prevPrevWindow != null && rms >= WAV_SILENCE_RMS_THRESHOLD) {
                double corr2 = pearsonCorrelation(prevPrevWindow, samples);
                if (corr2 > WAV_REPEAT_CORRELATION_THRESHOLD) {
                    if (consecutiveRepeatStride2 == 0.0) {
                        currentRepeatStride2Start = w;
                    }
                    consecutiveRepeatStride2 += WAV_WINDOW_SECONDS;
                    if (consecutiveRepeatStride2 > maxRepeatStride2) {
                        maxRepeatStride2 = consecutiveRepeatStride2;
                        repeatStride2StartWindow = currentRepeatStride2Start;
                    }
                } else {
                    consecutiveRepeatStride2 = 0.0;
                }
            } else {
                consecutiveRepeatStride2 = 0.0;
            }

            prevPrevWindow = prevWindow;
            prevWindow = samples;

            // Early exit on silence
            if (maxSilence > SILENCE_THRESHOLD_SECONDS) {
                break;
            }
        }

        if (maxSilence > SILENCE_THRESHOLD_SECONDS) {
            double startSec = (silenceStartWindow) * WAV_WINDOW_SECONDS;
            return QualityResult.failed(String.format("Continuous silence detected: %.1fs (threshold: %.1fs)",
                    maxSilence, SILENCE_THRESHOLD_SECONDS), startSec);
        }

        if (maxRepeat > REPEAT_THRESHOLD_SECONDS) {
            double startSec = (repeatStartWindow) * WAV_WINDOW_SECONDS;
            return QualityResult.failed(String.format("Repeating/stuck audio detected: %.1fs (threshold: %.1fs)",
                    maxRepeat, REPEAT_THRESHOLD_SECONDS), startSec);
        }

        if (maxRepeatStride2 > REPEAT_THRESHOLD_SECONDS) {
            double startSec = (repeatStride2StartWindow) * WAV_WINDOW_SECONDS;
            return QualityResult.failed(String.format("Repeating/stuck audio detected (stride-2): %.1fs (threshold: %.1fs)",
                    maxRepeatStride2, REPEAT_THRESHOLD_SECONDS), startSec);
        }

        // Sliding window average correlation check: catches stuck tones with brief glitches
        if (corrCount >= WAV_REPEAT_SLIDING_WINDOW) {
            double windowSum = 0;
            for (int i = 0; i < WAV_REPEAT_SLIDING_WINDOW; i++) {
                windowSum += correlationHistory[i];
            }
            for (int i = 0; i <= corrCount - WAV_REPEAT_SLIDING_WINDOW; i++) {
                double avg = windowSum / WAV_REPEAT_SLIDING_WINDOW;
                if (avg > WAV_REPEAT_AVG_THRESHOLD) {
                    double duration = WAV_REPEAT_SLIDING_WINDOW * WAV_WINDOW_SECONDS;
                    // correlationHistory starts from window 1, so offset by 1
                    double startSec = (i + 1) * WAV_WINDOW_SECONDS;
                    return QualityResult.failed(String.format(
                            "Sustained repeating/stuck audio detected: avg correlation %.3f over %.1fs (threshold: %.2f)",
                            avg, duration, WAV_REPEAT_AVG_THRESHOLD), startSec);
                }
                if (i + WAV_REPEAT_SLIDING_WINDOW < corrCount) {
                    windowSum -= correlationHistory[i];
                    windowSum += correlationHistory[i + WAV_REPEAT_SLIDING_WINDOW];
                }
            }
        }

        // Stuck tone / noise detection via CV (Coefficient of Variation)
        // Low CV means very stable RMS energy → stuck tone, electrical hum, or static
        if (numWindows >= STUCK_SLIDING_WINDOW) {
            for (int i = 0; i <= numWindows - STUCK_SLIDING_WINDOW; i++) {
                // Skip windows that are silence (already handled above)
                boolean hasSilence = false;
                double sum = 0;
                for (int j = i; j < i + STUCK_SLIDING_WINDOW; j++) {
                    if (rmsHistory[j] < WAV_SILENCE_RMS_THRESHOLD) {
                        hasSilence = true;
                        break;
                    }
                    sum += rmsHistory[j];
                }
                if (hasSilence) continue;

                double mean = sum / STUCK_SLIDING_WINDOW;
                double variance = 0;
                for (int j = i; j < i + STUCK_SLIDING_WINDOW; j++) {
                    double diff = rmsHistory[j] - mean;
                    variance += diff * diff;
                }
                double stddev = Math.sqrt(variance / STUCK_SLIDING_WINDOW);
                double cv = mean > 0 ? stddev / mean : 0;

                if (cv < STUCK_CV_THRESHOLD) {
                    double duration = STUCK_SLIDING_WINDOW * WAV_WINDOW_SECONDS;
                    double startSec = i * WAV_WINDOW_SECONDS;
                    return QualityResult.failed(String.format(
                            "Stuck tone/noise detected (stable RMS): mean=%.1f, CV=%.4f over %.1fs",
                            mean, cv, duration), startSec);
                }
            }
        }

        return QualityResult.ok();
    }

    private int findWavChunk(byte[] data, String id) {
        for (int i = 12; i < data.length - 8; i++) {
            if (data[i] == id.charAt(0) && data[i + 1] == id.charAt(1)
                    && data[i + 2] == id.charAt(2) && data[i + 3] == id.charAt(3)) {
                return i;
            }
        }
        return -1;
    }

    private int findWavDataChunk(byte[] data) {
        return findWavChunk(data, "data");
    }

    private double[] extractMonoSamples(byte[] data, int offset, int numSamples, int channels, int bytesPerSample) {
        double[] samples = new double[numSamples];
        int blockAlign = channels * bytesPerSample;

        for (int i = 0; i < numSamples && offset + i * blockAlign + bytesPerSample <= data.length; i++) {
            int sampleOffset = offset + i * blockAlign;
            // Read first channel only (mono mix)
            if (bytesPerSample == 2) {
                samples[i] = (short) ((data[sampleOffset] & 0xFF) | (data[sampleOffset + 1] << 8));
            } else if (bytesPerSample == 1) {
                samples[i] = (data[sampleOffset] & 0xFF) - 128.0;
            } else if (bytesPerSample == 3) {
                int val = (data[sampleOffset] & 0xFF) | ((data[sampleOffset + 1] & 0xFF) << 8) | (data[sampleOffset + 2] << 16);
                samples[i] = val;
            } else {
                samples[i] = 0;
            }
        }
        return samples;
    }

    private double computeRms(double[] samples) {
        double sum = 0;
        for (double s : samples) {
            sum += s * s;
        }
        return Math.sqrt(sum / samples.length);
    }

    private double pearsonCorrelation(double[] a, double[] b) {
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

    private int readUint16LE(byte[] data, int offset) {
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
    }

    private int readInt32LE(byte[] data, int offset) {
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8)
                | ((data[offset + 2] & 0xFF) << 16) | ((data[offset + 3] & 0xFF) << 24);
    }

    // ===== MP3 Analysis =====

    private QualityResult analyzeMp3(byte[] mp3Data) {
        int offset = mp3ProcessingService.skipId3v2Tag(mp3Data);
        int frameStart = mp3ProcessingService.findValidFrameSync(mp3Data, offset);
        if (frameStart < 0) {
            return QualityResult.failed("No valid MP3 frames found");
        }

        // Skip Xing/VBRI info frame
        int frameSize = mp3ProcessingService.getMp3FrameSize(mp3Data, frameStart);
        if (frameSize > 0 && mp3ProcessingService.isXingOrVbriFrame(mp3Data, frameStart, frameSize)) {
            frameStart += frameSize;
        }

        double consecutiveSilenceDuration = 0.0;
        double maxSilenceDuration = 0.0;
        double silenceStartSec = 0.0;
        double currentSilenceStartSec = 0.0;
        double consecutiveRepeatDuration = 0.0;
        double maxRepeatDuration = 0.0;
        double repeatStartSec = 0.0;
        double currentRepeatStartSec = 0.0;
        double currentTimeSec = 0.0;
        long previousCrc = -1;

        CRC32 crc32 = new CRC32();
        int pos = frameStart;

        while (pos < mp3Data.length - 3) {
            if ((mp3Data[pos] & 0xFF) != 0xFF || (mp3Data[pos + 1] & 0xE0) != 0xE0) {
                pos++;
                continue;
            }

            int versionBits = (mp3Data[pos + 1] >> 3) & 0x03;
            if (versionBits == 0x01) {
                pos++;
                continue;
            }

            int size = mp3ProcessingService.getMp3FrameSize(mp3Data, pos);
            if (size <= 0 || size > 4608 || pos + size > mp3Data.length) {
                pos++;
                continue;
            }

            double frameDuration = getFrameDuration(mp3Data, pos);
            int sideInfoSize = getSideInfoSize(mp3Data, pos);
            int payloadStart = pos + 4 + sideInfoSize;
            int payloadEnd = pos + size;

            if (payloadStart < payloadEnd && payloadStart < mp3Data.length) {
                int actualPayloadEnd = Math.min(payloadEnd, mp3Data.length);
                int payloadLength = actualPayloadEnd - payloadStart;

                // Silence detection: count zero bytes in payload
                int zeroCount = 0;
                for (int i = payloadStart; i < actualPayloadEnd; i++) {
                    if (mp3Data[i] == 0) {
                        zeroCount++;
                    }
                }
                boolean isSilent = payloadLength > 0 && ((double) zeroCount / payloadLength) > SILENCE_ZERO_RATIO;

                if (isSilent) {
                    if (consecutiveSilenceDuration == 0.0) {
                        currentSilenceStartSec = currentTimeSec;
                    }
                    consecutiveSilenceDuration += frameDuration;
                    if (consecutiveSilenceDuration > maxSilenceDuration) {
                        maxSilenceDuration = consecutiveSilenceDuration;
                        silenceStartSec = currentSilenceStartSec;
                    }
                } else {
                    consecutiveSilenceDuration = 0.0;
                }

                // Repeat detection: CRC32 of payload
                crc32.reset();
                crc32.update(mp3Data, payloadStart, payloadLength);
                long currentCrc = crc32.getValue();

                if (previousCrc >= 0 && currentCrc == previousCrc) {
                    if (consecutiveRepeatDuration == 0.0) {
                        currentRepeatStartSec = currentTimeSec;
                    }
                    consecutiveRepeatDuration += frameDuration;
                    if (consecutiveRepeatDuration > maxRepeatDuration) {
                        maxRepeatDuration = consecutiveRepeatDuration;
                        repeatStartSec = currentRepeatStartSec;
                    }
                } else {
                    consecutiveRepeatDuration = 0.0;
                }
                previousCrc = currentCrc;
            }

            currentTimeSec += frameDuration;
            pos += size;

            // Early exit
            if (maxSilenceDuration > SILENCE_THRESHOLD_SECONDS && maxRepeatDuration > REPEAT_THRESHOLD_SECONDS) {
                break;
            }
        }

        if (maxSilenceDuration > SILENCE_THRESHOLD_SECONDS) {
            return QualityResult.failed(String.format("Continuous silence detected: %.1fs (threshold: %.1fs)",
                    maxSilenceDuration, SILENCE_THRESHOLD_SECONDS), silenceStartSec);
        }

        if (maxRepeatDuration > REPEAT_THRESHOLD_SECONDS) {
            return QualityResult.failed(String.format("Repeating audio detected: %.1fs (threshold: %.1fs)",
                    maxRepeatDuration, REPEAT_THRESHOLD_SECONDS), repeatStartSec);
        }

        return QualityResult.ok();
    }

    private double getFrameDuration(byte[] data, int frameStart) {
        int versionBits = (data[frameStart + 1] >> 3) & 0x03;
        int layerBits = (data[frameStart + 1] >> 1) & 0x03;
        int sampleRateIndex = (data[frameStart + 2] >> 2) & 0x03;

        int sampleRate = getSampleRate(versionBits, sampleRateIndex);
        int samplesPerFrame = getSamplesPerFrame(versionBits, layerBits);

        if (sampleRate <= 0) return 0.026; // fallback ~26ms
        return (double) samplesPerFrame / sampleRate;
    }

    private int getSamplesPerFrame(int versionBits, int layerBits) {
        if (layerBits == 0x03) { // Layer I
            return 384;
        } else if (layerBits == 0x02) { // Layer II
            return 1152;
        } else { // Layer III
            return (versionBits == 0x03) ? 1152 : 576;
        }
    }

    private int getSampleRate(int versionBits, int sampleRateIndex) {
        int[][] sampleRates = {
                {11025, 12000, 8000},  // MPEG2.5 (versionBits=0x00)
                {0, 0, 0},            // reserved (versionBits=0x01)
                {22050, 24000, 16000}, // MPEG2 (versionBits=0x02)
                {44100, 48000, 32000}  // MPEG1 (versionBits=0x03)
        };
        if (sampleRateIndex >= 3) return 0;
        return sampleRates[versionBits][sampleRateIndex];
    }

    private int getSideInfoSize(byte[] data, int frameStart) {
        int versionBits = (data[frameStart + 1] >> 3) & 0x03;
        int channelMode = (data[frameStart + 3] >> 6) & 0x03;

        if (versionBits == 0x03) { // MPEG1
            return (channelMode == 0x03) ? 17 : 32; // mono : stereo
        } else { // MPEG2/2.5
            return (channelMode == 0x03) ? 9 : 17;
        }
    }
}
