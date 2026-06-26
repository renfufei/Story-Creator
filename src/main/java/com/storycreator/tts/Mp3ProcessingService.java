package com.storycreator.tts;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@Service
public class Mp3ProcessingService {

    private static final Logger log = LoggerFactory.getLogger(Mp3ProcessingService.class);

    enum AudioFormat { MP3, WAV, UNKNOWN }

    private volatile Boolean ffmpegAvailable;

    public boolean isFfmpegAvailable() {
        if (ffmpegAvailable == null) {
            synchronized (this) {
                if (ffmpegAvailable == null) {
                    ffmpegAvailable = checkFfmpeg();
                }
            }
        }
        return ffmpegAvailable;
    }

    private boolean checkFfmpeg() {
        try {
            Process process = new ProcessBuilder("ffmpeg", "-version")
                    .redirectErrorStream(true)
                    .start();
            int exitCode = process.waitFor();
            return exitCode == 0;
        } catch (Exception e) {
            log.info("ffmpeg not available: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Detect audio format from file header bytes.
     */
    AudioFormat detectFileFormat(Path file) throws IOException {
        byte[] header = new byte[12];
        try (var is = Files.newInputStream(file)) {
            int read = is.read(header);
            if (read < 12) return AudioFormat.UNKNOWN;
        }
        return detectFormat(header);
    }

    /**
     * Detect audio format from in-memory data.
     */
    static AudioFormat detectFormat(byte[] data) {
        if (data == null || data.length < 12) return AudioFormat.UNKNOWN;
        if (isWavHeader(data)) return AudioFormat.WAV;
        if (isMp3Header(data)) return AudioFormat.MP3;
        return AudioFormat.UNKNOWN;
    }

    private static boolean isWavHeader(byte[] data) {
        // RIFF....WAVE
        return data.length >= 12
                && data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F'
                && data[8] == 'W' && data[9] == 'A' && data[10] == 'V' && data[11] == 'E';
    }

    private static boolean isMp3Header(byte[] data) {
        if (data.length < 2) return false;
        // ID3v2 tag
        if (data.length >= 3 && data[0] == 'I' && data[1] == 'D' && data[2] == '3') return true;
        // MP3 frame sync
        return (data[0] & 0xFF) == 0xFF && (data[1] & 0xE0) == 0xE0;
    }

    /**
     * Concatenate WAV files by extracting raw PCM data and rebuilding the WAV header.
     */
    private void concatenateWavFiles(List<Path> wavChunks, Path output) throws IOException {
        byte[] fmtChunk = null;
        List<byte[]> pcmDataList = new java.util.ArrayList<>();
        long totalPcmSize = 0;

        for (Path chunk : wavChunks) {
            byte[] data = Files.readAllBytes(chunk);
            if (data.length < 44) {
                log.warn("WAV file too small, skipping: {}", chunk);
                continue;
            }

            // Parse fmt chunk from first valid file
            byte[] pcm = extractWavPcmData(data);
            if (pcm == null) {
                log.warn("Could not extract PCM data from: {}", chunk);
                continue;
            }

            if (fmtChunk == null) {
                fmtChunk = extractWavFmtChunk(data);
            }

            pcmDataList.add(pcm);
            totalPcmSize += pcm.length;
        }

        if (fmtChunk == null || pcmDataList.isEmpty()) {
            throw new IOException("No valid WAV data found in chunks");
        }

        // Write combined WAV file
        try (OutputStream out = Files.newOutputStream(output)) {
            int fmtChunkSize = fmtChunk.length;
            // RIFF header: "RIFF" + fileSize + "WAVE"
            // fileSize = 4(WAVE) + 8(fmt header) + fmtChunkSize + 8(data header) + totalPcmSize
            long fileSize = 4 + 8 + fmtChunkSize + 8 + totalPcmSize;
            ByteBuffer header = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
            header.put((byte) 'R').put((byte) 'I').put((byte) 'F').put((byte) 'F');
            header.putInt((int) fileSize);
            header.put((byte) 'W').put((byte) 'A').put((byte) 'V').put((byte) 'E');
            out.write(header.array());

            // fmt sub-chunk: "fmt " + size + data
            ByteBuffer fmtHeader = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
            fmtHeader.put((byte) 'f').put((byte) 'm').put((byte) 't').put((byte) ' ');
            fmtHeader.putInt(fmtChunkSize);
            out.write(fmtHeader.array());
            out.write(fmtChunk);

            // data sub-chunk: "data" + size + PCM data
            ByteBuffer dataHeader = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
            dataHeader.put((byte) 'd').put((byte) 'a').put((byte) 't').put((byte) 'a');
            dataHeader.putInt((int) totalPcmSize);
            out.write(dataHeader.array());

            for (byte[] pcm : pcmDataList) {
                out.write(pcm);
            }
        }
        log.info("WAV concatenation complete: {} chunks, {} bytes PCM", pcmDataList.size(), totalPcmSize);
    }

    /**
     * Extract the fmt chunk data (without "fmt " tag and size) from WAV file bytes.
     */
    private byte[] extractWavFmtChunk(byte[] data) {
        int pos = 12; // skip RIFF header
        while (pos + 8 <= data.length) {
            String chunkId = new String(data, pos, 4, java.nio.charset.StandardCharsets.US_ASCII);
            int chunkSize = ByteBuffer.wrap(data, pos + 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
            if ("fmt ".equals(chunkId)) {
                if (pos + 8 + chunkSize <= data.length) {
                    byte[] fmt = new byte[chunkSize];
                    System.arraycopy(data, pos + 8, fmt, 0, chunkSize);
                    return fmt;
                }
            }
            pos += 8 + chunkSize;
            // WAV chunks are word-aligned
            if (chunkSize % 2 != 0) pos++;
        }
        return null;
    }

    /**
     * Extract raw PCM data from the "data" chunk of a WAV file.
     */
    private byte[] extractWavPcmData(byte[] data) {
        int pos = 12; // skip RIFF header
        while (pos + 8 <= data.length) {
            String chunkId = new String(data, pos, 4, java.nio.charset.StandardCharsets.US_ASCII);
            int chunkSize = ByteBuffer.wrap(data, pos + 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
            if ("data".equals(chunkId)) {
                int dataStart = pos + 8;
                int dataEnd = Math.min(dataStart + chunkSize, data.length);
                byte[] pcm = new byte[dataEnd - dataStart];
                System.arraycopy(data, dataStart, pcm, 0, pcm.length);
                return pcm;
            }
            pos += 8 + chunkSize;
            if (chunkSize % 2 != 0) pos++;
        }
        return null;
    }

    /**
     * Concatenate audio chunks in memory with format-aware handling.
     * Detects whether chunks are WAV or MP3 and handles accordingly.
     */
    public byte[] concatenateAudioChunksInMemory(List<byte[]> chunks) {
        if (chunks == null || chunks.isEmpty()) return new byte[0];
        if (chunks.size() == 1) return chunks.get(0);

        AudioFormat format = detectFormat(chunks.get(0));
        if (format == AudioFormat.WAV) {
            return concatenateWavChunksInMemory(chunks);
        }
        // MP3 or unknown: direct byte concatenation (existing behavior)
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (byte[] chunk : chunks) {
                out.write(chunk);
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to concatenate audio chunks", e);
        }
    }

    private byte[] concatenateWavChunksInMemory(List<byte[]> chunks) {
        byte[] fmtChunk = null;
        List<byte[]> pcmDataList = new java.util.ArrayList<>();
        long totalPcmSize = 0;

        for (byte[] data : chunks) {
            if (data.length < 44) continue;
            byte[] pcm = extractWavPcmData(data);
            if (pcm == null) continue;
            if (fmtChunk == null) {
                fmtChunk = extractWavFmtChunk(data);
            }
            pcmDataList.add(pcm);
            totalPcmSize += pcm.length;
        }

        if (fmtChunk == null || pcmDataList.isEmpty()) {
            // Fallback: just concatenate raw bytes
            try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                for (byte[] chunk : chunks) {
                    out.write(chunk);
                }
                return out.toByteArray();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        int fmtChunkSize = fmtChunk.length;
        long fileSize = 4 + 8 + fmtChunkSize + 8 + totalPcmSize;
        int totalSize = 12 + 8 + fmtChunkSize + 8 + (int) totalPcmSize;

        ByteBuffer buf = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN);
        // RIFF header
        buf.put((byte) 'R').put((byte) 'I').put((byte) 'F').put((byte) 'F');
        buf.putInt((int) fileSize);
        buf.put((byte) 'W').put((byte) 'A').put((byte) 'V').put((byte) 'E');
        // fmt chunk
        buf.put((byte) 'f').put((byte) 'm').put((byte) 't').put((byte) ' ');
        buf.putInt(fmtChunkSize);
        buf.put(fmtChunk);
        // data chunk
        buf.put((byte) 'd').put((byte) 'a').put((byte) 't').put((byte) 'a');
        buf.putInt((int) totalPcmSize);
        for (byte[] pcm : pcmDataList) {
            buf.put(pcm);
        }

        return buf.array();
    }

    /**
     * Concatenate MP3 files with proper header handling.
     * If ffmpeg is available, uses concat demuxer for correct duration metadata.
     * Otherwise, falls back to byte concatenation with ID3v2 tag stripping.
     */
    public void concatenateMp3(List<Path> chunkFiles, Path output) throws IOException {
        if (chunkFiles.size() <= 1) {
            // Single file or empty: just copy directly
            if (!chunkFiles.isEmpty()) {
                Files.copy(chunkFiles.get(0), output, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            return;
        }

        if (isFfmpegAvailable()) {
            try {
                concatenateMp3WithFfmpeg(chunkFiles, output);
                return;
            } catch (IOException e) {
                log.warn("ffmpeg concat failed, falling back to manual concatenation: {}", e.getMessage());
            }
        }

        concatenateMp3Manual(chunkFiles, output);
    }

    /**
     * Use ffmpeg concat demuxer to properly merge MP3 files.
     * Uses -write_xing 0 to suppress the Xing/VBRI VBR header that would otherwise
     * report only the first chunk's frame count, causing incorrect duration display.
     */
    private void concatenateMp3WithFfmpeg(List<Path> chunkFiles, Path output) throws IOException {
        Path fileList = output.resolveSibling("ffmpeg_concat_list_" + System.currentTimeMillis() + ".txt");
        try {
            // Write file list for concat demuxer
            try (BufferedWriter writer = Files.newBufferedWriter(fileList)) {
                for (Path chunk : chunkFiles) {
                    writer.write("file '" + chunk.toAbsolutePath().toString().replace("'", "'\\''") + "'");
                    writer.newLine();
                }
            }

            ProcessBuilder pb = new ProcessBuilder(
                    "ffmpeg",
                    "-f", "concat",
                    "-safe", "0",
                    "-i", fileList.toString(),
                    "-c", "copy",
                    "-write_xing", "0",
                    "-y",
                    output.toString()
            );
            pb.redirectErrorStream(true);
            Process process = pb.start();
            process.getInputStream().readAllBytes();
            int exitCode = process.waitFor();

            if (exitCode != 0) {
                throw new IOException("ffmpeg concat exited with code " + exitCode);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("ffmpeg concat interrupted", e);
        } finally {
            Files.deleteIfExists(fileList);
        }
    }

    /**
     * Manual MP3 concatenation with proper CBR Info header.
     * Two-pass approach:
     * 1. Count total frames and bytes across all chunks (stripping ID3v2 + Xing/VBRI)
     * 2. Write Info header + stripped frame data from all chunks
     *
     * If chunks are actually WAV format, delegates to concatenateWavFiles().
     */
    private void concatenateMp3Manual(List<Path> chunkFiles, Path output) throws IOException {
        // Detect actual format from first chunk
        AudioFormat format = detectFileFormat(chunkFiles.get(0));
        if (format == AudioFormat.WAV) {
            log.info("Detected WAV format in chunks, using WAV concatenation");
            concatenateWavFiles(chunkFiles, output);
            return;
        }

        // Pass 1: count frames and total bytes, capture first frame's header for Info frame
        long totalFrames = 0;
        long totalDataBytes = 0;
        byte[] firstFrameHeader = null; // 4 bytes of the first audio frame header
        int firstFrameSize = 0;

        for (Path chunk : chunkFiles) {
            byte[] data = Files.readAllBytes(chunk);
            int offset = skipId3v2Tag(data);
            int frameStart = findValidFrameSync(data, offset);
            if (frameStart < 0) continue;

            int frameSize = getMp3FrameSize(data, frameStart);
            // Skip Xing/VBRI info frame
            int audioStart = frameStart;
            if (frameSize > 0 && isXingOrVbriFrame(data, frameStart, frameSize)) {
                audioStart = frameStart + frameSize;
            }

            if (firstFrameHeader == null) {
                // Find the first real audio frame (after possible Xing skip)
                int realFrame = findValidFrameSync(data, audioStart);
                if (realFrame >= 0 && realFrame + 4 <= data.length) {
                    firstFrameHeader = new byte[4];
                    System.arraycopy(data, realFrame, firstFrameHeader, 0, 4);
                    firstFrameSize = getMp3FrameSize(data, realFrame);
                }
            }

            // Count frames from audioStart
            FrameCount fc = countFrames(data, audioStart);
            totalFrames += fc.frameCount;
            totalDataBytes += fc.byteCount;
        }

        // Pass 2: write output
        try (OutputStream out = Files.newOutputStream(output)) {
            // Write CBR Info header if we have valid frame info
            if (firstFrameHeader != null && firstFrameSize > 0) {
                byte[] infoHeader = buildCbrInfoHeader(firstFrameHeader, firstFrameSize, totalFrames, totalDataBytes);
                if (infoHeader != null) {
                    out.write(infoHeader);
                }
            }

            // Write stripped frame data from each chunk
            for (Path chunk : chunkFiles) {
                writeStrippingHeaders(chunk, out);
            }
        }
    }

    /**
     * Skip ID3v2 tag and return offset to first byte after it.
     */
    int skipId3v2Tag(byte[] data) {
        if (data.length >= 10 && data[0] == 'I' && data[1] == 'D' && data[2] == '3') {
            int size = ((data[6] & 0x7F) << 21)
                     | ((data[7] & 0x7F) << 14)
                     | ((data[8] & 0x7F) << 7)
                     | (data[9] & 0x7F);
            int offset = 10 + size;
            return Math.min(offset, data.length);
        }
        return 0;
    }

    /**
     * Count MP3 frames and total byte count from offset to end of data.
     */
    private FrameCount countFrames(byte[] data, int offset) {
        long frames = 0;
        long bytes = 0;
        int pos = offset;
        while (pos < data.length - 3) {
            if ((data[pos] & 0xFF) == 0xFF && (data[pos + 1] & 0xE0) == 0xE0) {
                int versionBits = (data[pos + 1] >> 3) & 0x03;
                if (versionBits == 0x01) { pos++; continue; }
                int size = getMp3FrameSize(data, pos);
                if (size > 0 && size <= 4608) {
                    frames++;
                    bytes += size;
                    pos += size;
                    continue;
                }
            }
            pos++;
        }
        return new FrameCount(frames, bytes);
    }

    private record FrameCount(long frameCount, long byteCount) {}

    /**
     * Build a CBR Info header frame that declares total frame count and byte count.
     * This allows players to instantly compute correct duration without scanning the file.
     */
    private byte[] buildCbrInfoHeader(byte[] frameHeader, int frameSize, long totalFrames, long totalDataBytes) {
        if (frameSize <= 0) return null;

        byte[] frame = new byte[frameSize];

        // Copy the 4-byte frame header
        System.arraycopy(frameHeader, 0, frame, 0, 4);

        // Determine side info size based on MPEG version and channel mode
        int versionBits = (frameHeader[1] >> 3) & 0x03;
        int channelMode = (frameHeader[3] >> 6) & 0x03;
        int sideInfoSize;
        if (versionBits == 0x03) { // MPEG1
            sideInfoSize = (channelMode == 0x03) ? 17 : 32; // mono : stereo
        } else { // MPEG2/2.5
            sideInfoSize = (channelMode == 0x03) ? 9 : 17;
        }

        // Info tag starts after header (4 bytes) + side info
        int infoOffset = 4 + sideInfoSize;
        if (infoOffset + 120 > frameSize) return null; // frame too small

        // Write "Info" tag (signals CBR)
        frame[infoOffset]     = 'I';
        frame[infoOffset + 1] = 'n';
        frame[infoOffset + 2] = 'f';
        frame[infoOffset + 3] = 'o';

        // Flags: 0x07 = frames + bytes + TOC present
        int flagsOffset = infoOffset + 4;
        frame[flagsOffset]     = 0;
        frame[flagsOffset + 1] = 0;
        frame[flagsOffset + 2] = 0;
        frame[flagsOffset + 3] = 0x07;

        // Total frames (4 bytes big-endian)
        int framesOffset = flagsOffset + 4;
        frame[framesOffset]     = (byte) ((totalFrames >> 24) & 0xFF);
        frame[framesOffset + 1] = (byte) ((totalFrames >> 16) & 0xFF);
        frame[framesOffset + 2] = (byte) ((totalFrames >> 8) & 0xFF);
        frame[framesOffset + 3] = (byte) (totalFrames & 0xFF);

        // Total bytes (4 bytes big-endian) — includes Info header frame itself
        long totalBytes = totalDataBytes + frameSize;
        int bytesOffset = framesOffset + 4;
        frame[bytesOffset]     = (byte) ((totalBytes >> 24) & 0xFF);
        frame[bytesOffset + 1] = (byte) ((totalBytes >> 16) & 0xFF);
        frame[bytesOffset + 2] = (byte) ((totalBytes >> 8) & 0xFF);
        frame[bytesOffset + 3] = (byte) (totalBytes & 0xFF);

        // 100-byte TOC (linear for CBR: byte[i] = i * 256 / 100)
        int tocOffset = bytesOffset + 4;
        for (int i = 0; i < 100; i++) {
            frame[tocOffset + i] = (byte) (i * 256 / 100);
        }

        // Rest of frame stays zero-filled (silence)
        return frame;
    }

    /**
     * Write MP3 file content to output stream, skipping any ID3v2 tag and Xing/VBRI VBR header frame.
     * Stripping the Xing/VBRI header ensures players don't read stale duration info from a single chunk.
     */
    private void writeStrippingHeaders(Path file, OutputStream out) throws IOException {
        byte[] data = Files.readAllBytes(file);
        int offset = skipId3v2Tag(data);

        // Find the first valid MP3 frame sync (with frame-chaining validation)
        int frameStart = findValidFrameSync(data, offset);
        if (frameStart < 0) {
            // No valid MP3 frame found, write from after ID3 tag
            out.write(data, offset, data.length - offset);
            return;
        }

        // Check if first frame is a Xing/VBRI info frame and skip it
        int frameSize = getMp3FrameSize(data, frameStart);
        if (frameSize > 0 && isXingOrVbriFrame(data, frameStart, frameSize)) {
            // Skip the Xing/VBRI info frame, write everything after it
            int afterXing = frameStart + frameSize;
            if (afterXing < data.length) {
                out.write(data, afterXing, data.length - afterXing);
            }
        } else {
            // No Xing/VBRI header, write from first frame
            out.write(data, frameStart, data.length - frameStart);
        }
    }

    /**
     * Find the first valid MP3 frame sync using frame-chaining validation.
     * A candidate sync is only accepted if the next frame (at offset + frameSize) also
     * starts with a valid sync pattern. This eliminates false positives from garbage data.
     */
    int findValidFrameSync(byte[] data, int offset) {
        for (int i = offset; i < data.length - 3; i++) {
            if ((data[i] & 0xFF) == 0xFF && (data[i + 1] & 0xE0) == 0xE0) {
                int versionBits = (data[i + 1] >> 3) & 0x03;
                if (versionBits == 0x01) continue; // reserved version
                int size = getMp3FrameSize(data, i);
                if (size <= 0 || size > 4608) continue; // invalid or absurd frame size
                int next = i + size;
                if (next >= data.length - 1) return i; // last frame, accept
                // Validate next frame also has sync
                if ((data[next] & 0xFF) == 0xFF && (data[next + 1] & 0xE0) == 0xE0) {
                    int nextVersion = (data[next + 1] >> 3) & 0x03;
                    if (nextVersion != 0x01) return i;
                }
            }
        }
        return -1;
    }

    /**
     * Calculate MP3 frame size from frame header.
     * Supports Layer I, Layer II, and Layer III for all MPEG versions.
     * Returns -1 if header is invalid.
     */
    int getMp3FrameSize(byte[] data, int frameStart) {
        if (frameStart + 4 > data.length) return -1;

        int h = ((data[frameStart] & 0xFF) << 24)
              | ((data[frameStart + 1] & 0xFF) << 16)
              | ((data[frameStart + 2] & 0xFF) << 8)
              | (data[frameStart + 3] & 0xFF);

        int versionBits = (h >> 19) & 0x03;
        int layerBits = (h >> 17) & 0x03;
        int bitrateIndex = (h >> 12) & 0x0F;
        int sampleRateIndex = (h >> 10) & 0x03;
        int paddingBit = (h >> 9) & 0x01;

        if (layerBits == 0x00) return -1; // reserved layer
        if (bitrateIndex == 0 || bitrateIndex == 0x0F) return -1;
        if (sampleRateIndex == 0x03) return -1;

        // Bitrate tables (kbps) indexed by bitrateIndex [1..14]
        // MPEG1 Layer I
        int[] bitratesV1L1 = {0, 32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448, 0};
        // MPEG1 Layer II
        int[] bitratesV1L2 = {0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384, 0};
        // MPEG1 Layer III
        int[] bitratesV1L3 = {0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0};
        // MPEG2/2.5 Layer I
        int[] bitratesV2L1 = {0, 32, 48, 56, 64, 80, 96, 112, 128, 144, 160, 176, 192, 224, 256, 0};
        // MPEG2/2.5 Layer II & III
        int[] bitratesV2L23 = {0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0};

        // Sample rate tables
        int[] sampleRatesV1 = {44100, 48000, 32000};
        int[] sampleRatesV2 = {22050, 24000, 16000};
        int[] sampleRatesV25 = {11025, 12000, 8000};

        int bitrate;
        int sampleRate;

        // layerBits: 11=Layer I, 10=Layer II, 01=Layer III
        if (versionBits == 0x03) { // MPEG1
            sampleRate = sampleRatesV1[sampleRateIndex];
            if (layerBits == 0x03) { // Layer I
                bitrate = bitratesV1L1[bitrateIndex] * 1000;
            } else if (layerBits == 0x02) { // Layer II
                bitrate = bitratesV1L2[bitrateIndex] * 1000;
            } else { // layerBits == 0x01, Layer III
                bitrate = bitratesV1L3[bitrateIndex] * 1000;
            }
        } else if (versionBits == 0x02) { // MPEG2
            sampleRate = sampleRatesV2[sampleRateIndex];
            if (layerBits == 0x03) { // Layer I
                bitrate = bitratesV2L1[bitrateIndex] * 1000;
            } else { // Layer II & III
                bitrate = bitratesV2L23[bitrateIndex] * 1000;
            }
        } else if (versionBits == 0x00) { // MPEG2.5
            sampleRate = sampleRatesV25[sampleRateIndex];
            if (layerBits == 0x03) { // Layer I
                bitrate = bitratesV2L1[bitrateIndex] * 1000;
            } else { // Layer II & III
                bitrate = bitratesV2L23[bitrateIndex] * 1000;
            }
        } else {
            return -1; // reserved version (0x01)
        }

        if (bitrate == 0 || sampleRate == 0) return -1;

        if (layerBits == 0x03) {
            // Layer I: 384 samples/frame, slot size = 4 bytes
            return (12 * bitrate / sampleRate + paddingBit) * 4;
        } else {
            // Layer II & III: 1152 samples/frame (MPEG1), 576 (MPEG2/2.5 Layer III)
            int samplesPerFrame;
            if (versionBits == 0x03) {
                samplesPerFrame = 1152; // MPEG1, both Layer II and III
            } else {
                // MPEG2/2.5: Layer III uses 576, Layer II uses 1152
                samplesPerFrame = (layerBits == 0x01) ? 576 : 1152;
            }
            return samplesPerFrame / 8 * bitrate / sampleRate + paddingBit;
        }
    }

    /**
     * Check if an MP3 frame contains a Xing or VBRI header marker.
     * These are info-only frames (usually silent) that declare VBR metadata.
     */
    boolean isXingOrVbriFrame(byte[] data, int frameStart, int frameSize) {
        int end = Math.min(frameStart + frameSize, data.length);
        // Search for "Xing", "Info", or "VBRI" markers within the frame
        for (int i = frameStart + 4; i + 4 <= end; i++) {
            if ((data[i] == 'X' && data[i+1] == 'i' && data[i+2] == 'n' && data[i+3] == 'g')
             || (data[i] == 'I' && data[i+1] == 'n' && data[i+2] == 'f' && data[i+3] == 'o')
             || (data[i] == 'V' && data[i+1] == 'B' && data[i+2] == 'R' && data[i+3] == 'I')) {
                return true;
            }
        }
        return false;
    }

    /**
     * Trim audio data to the specified duration in seconds.
     * Supports both WAV and MP3 formats.
     * Returns null if the audio cannot be trimmed (e.g., too short).
     */
    public byte[] trimAudioToSeconds(byte[] audioData, double seconds) {
        if (audioData == null || audioData.length < 12 || seconds <= 0) return null;
        AudioFormat format = detectFormat(audioData);
        if (format == AudioFormat.WAV) {
            return trimWavToSeconds(audioData, seconds);
        }
        return trimMp3ToSeconds(audioData, seconds);
    }

    private byte[] trimWavToSeconds(byte[] wavData, double seconds) {
        // Parse fmt chunk
        int fmtPos = 12;
        byte[] fmtChunk = null;
        int fmtChunkSize = 0;
        while (fmtPos + 8 <= wavData.length) {
            String chunkId = new String(wavData, fmtPos, 4, java.nio.charset.StandardCharsets.US_ASCII);
            int chunkSize = ByteBuffer.wrap(wavData, fmtPos + 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
            if ("fmt ".equals(chunkId)) {
                fmtChunkSize = chunkSize;
                fmtChunk = new byte[chunkSize];
                System.arraycopy(wavData, fmtPos + 8, fmtChunk, 0, Math.min(chunkSize, wavData.length - fmtPos - 8));
                break;
            }
            fmtPos += 8 + chunkSize;
            if (chunkSize % 2 != 0) fmtPos++;
        }
        if (fmtChunk == null || fmtChunkSize < 16) return null;

        // Get byte rate from fmt chunk (offset 8 in fmt data: nAvgBytesPerSec)
        int byteRate = ByteBuffer.wrap(fmtChunk, 8, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
        if (byteRate <= 0) return null;

        // Find data chunk
        byte[] pcmData = extractWavPcmData(wavData);
        if (pcmData == null) return null;

        // Calculate trim length
        int trimBytes = (int) (seconds * byteRate);
        // Align to block align (offset 12 in fmt: nBlockAlign)
        int blockAlign = ByteBuffer.wrap(fmtChunk, 12, 2).order(ByteOrder.LITTLE_ENDIAN).getShort() & 0xFFFF;
        if (blockAlign > 0) {
            trimBytes = (trimBytes / blockAlign) * blockAlign;
        }
        trimBytes = Math.min(trimBytes, pcmData.length);
        if (trimBytes <= 0) return null;

        // Rebuild WAV
        long fileSize = 4 + 8 + fmtChunkSize + 8 + trimBytes;
        int totalSize = 12 + 8 + fmtChunkSize + 8 + trimBytes;
        ByteBuffer buf = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN);
        buf.put((byte) 'R').put((byte) 'I').put((byte) 'F').put((byte) 'F');
        buf.putInt((int) fileSize);
        buf.put((byte) 'W').put((byte) 'A').put((byte) 'V').put((byte) 'E');
        buf.put((byte) 'f').put((byte) 'm').put((byte) 't').put((byte) ' ');
        buf.putInt(fmtChunkSize);
        buf.put(fmtChunk);
        buf.put((byte) 'd').put((byte) 'a').put((byte) 't').put((byte) 'a');
        buf.putInt(trimBytes);
        buf.put(pcmData, 0, trimBytes);
        return buf.array();
    }

    private byte[] trimMp3ToSeconds(byte[] mp3Data, double seconds) {
        int offset = skipId3v2Tag(mp3Data);
        int frameStart = findValidFrameSync(mp3Data, offset);
        if (frameStart < 0) return null;

        // Skip Xing/VBRI info frame
        int frameSize = getMp3FrameSize(mp3Data, frameStart);
        int audioStart = frameStart;
        if (frameSize > 0 && isXingOrVbriFrame(mp3Data, frameStart, frameSize)) {
            audioStart = frameStart + frameSize;
        }

        // Walk frames, accumulate duration until we reach the target
        double accumulated = 0.0;
        int pos = audioStart;
        int lastValidPos = audioStart;

        while (pos < mp3Data.length - 3) {
            if ((mp3Data[pos] & 0xFF) != 0xFF || (mp3Data[pos + 1] & 0xE0) != 0xE0) {
                pos++;
                continue;
            }
            int versionBits = (mp3Data[pos + 1] >> 3) & 0x03;
            if (versionBits == 0x01) { pos++; continue; }
            int size = getMp3FrameSize(mp3Data, pos);
            if (size <= 0 || size > 4608 || pos + size > mp3Data.length) { pos++; continue; }

            // Calculate frame duration
            int layerBits = (mp3Data[pos + 1] >> 1) & 0x03;
            int sampleRateIndex = (mp3Data[pos + 2] >> 2) & 0x03;
            int[][] sampleRates = {
                    {11025, 12000, 8000}, {0, 0, 0}, {22050, 24000, 16000}, {44100, 48000, 32000}
            };
            int sampleRate = (sampleRateIndex < 3) ? sampleRates[versionBits][sampleRateIndex] : 0;
            int samplesPerFrame;
            if (layerBits == 0x03) samplesPerFrame = 384;
            else if (layerBits == 0x02) samplesPerFrame = 1152;
            else samplesPerFrame = (versionBits == 0x03) ? 1152 : 576;
            double frameDuration = (sampleRate > 0) ? (double) samplesPerFrame / sampleRate : 0.026;

            if (accumulated + frameDuration > seconds) {
                break;
            }
            accumulated += frameDuration;
            pos += size;
            lastValidPos = pos;
        }

        if (lastValidPos <= audioStart) return null;

        // Copy from start of file (including any ID3 tag) to lastValidPos
        byte[] result = new byte[lastValidPos];
        System.arraycopy(mp3Data, 0, result, 0, lastValidPos);
        return result;
    }

    /**
     * Concatenate MP3 chunks and re-encode with ffmpeg to target bitrate in a single pass.
     * Uses concat demuxer to read all chunks directly and re-encodes to libmp3lame,
     * avoiding the fragile two-step approach (concat demuxer -c copy → re-encode)
     * which produced corrupted intermediate files that ffmpeg couldn't parse.
     */
    public void concatenateAndCompress(List<Path> chunkFiles, Path output, String bitrate) throws IOException {
        Path fileList = output.resolveSibling("ffmpeg_concat_list_" + System.currentTimeMillis() + ".txt");
        try {
            // Write file list for concat demuxer
            try (BufferedWriter writer = Files.newBufferedWriter(fileList)) {
                for (Path chunk : chunkFiles) {
                    writer.write("file '" + chunk.toAbsolutePath().toString().replace("'", "'\\''") + "'");
                    writer.newLine();
                }
            }

            // Single-pass: concat demuxer reads chunks, re-encodes directly to target bitrate
            ProcessBuilder pb = new ProcessBuilder(
                    "ffmpeg",
                    "-f", "concat",
                    "-safe", "0",
                    "-i", fileList.toString(),
                    "-codec:a", "libmp3lame",
                    "-b:a", bitrate,
                    "-y",
                    output.toString()
            );
            pb.redirectErrorStream(true);
            Process process = pb.start();
            byte[] ffmpegOutput = process.getInputStream().readAllBytes();
            int exitCode = process.waitFor();

            if (exitCode != 0) {
                String msg = new String(ffmpegOutput).lines().reduce((a, b) -> b).orElse("");
                log.warn("ffmpeg concat+compress exited with code {}: {}", exitCode, msg);
                // Fallback: direct concat without compression
                concatenateMp3(chunkFiles, output);
                return;
            }

            // Sanity check: output shouldn't be absurdly small
            long outputSize = Files.size(output);
            if (outputSize < 1024 && chunkFiles.size() > 1) {
                log.warn("ffmpeg produced suspiciously small output ({} bytes), using fallback", outputSize);
                concatenateMp3(chunkFiles, output);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("ffmpeg interrupted", e);
        } finally {
            Files.deleteIfExists(fileList);
        }
    }

    /**
     * Generate silence WAV data of the specified duration, matching the format of the reference audio chunk.
     * Returns null if the reference is not WAV or cannot be parsed.
     */
    public byte[] generateSilenceWav(double seconds, byte[] referenceAudioChunk) {
        if (referenceAudioChunk == null || referenceAudioChunk.length < 44) return null;
        if (detectFormat(referenceAudioChunk) != AudioFormat.WAV) return null;

        byte[] fmtChunk = extractWavFmtChunk(referenceAudioChunk);
        if (fmtChunk == null || fmtChunk.length < 16) return null;

        // Parse fmt: audioFormat(2), channels(2), sampleRate(4), byteRate(4), blockAlign(2), bitsPerSample(2)
        ByteBuffer fmt = ByteBuffer.wrap(fmtChunk).order(ByteOrder.LITTLE_ENDIAN);
        fmt.getShort(); // audio format
        int channels = fmt.getShort() & 0xFFFF;
        int sampleRate = fmt.getInt();
        fmt.getInt(); // byteRate
        fmt.getShort(); // blockAlign
        int bitsPerSample = fmt.getShort() & 0xFFFF;

        int bytesPerSample = bitsPerSample / 8;
        int numSamples = (int) (seconds * sampleRate);
        int pcmSize = numSamples * channels * bytesPerSample;

        // Build WAV: 44 bytes header + PCM silence (zeros)
        int fmtChunkSize = fmtChunk.length;
        long fileSize = 4 + 8 + fmtChunkSize + 8 + pcmSize;
        int totalSize = 12 + 8 + fmtChunkSize + 8 + pcmSize;

        ByteBuffer buf = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN);
        // RIFF header
        buf.put((byte) 'R').put((byte) 'I').put((byte) 'F').put((byte) 'F');
        buf.putInt((int) fileSize);
        buf.put((byte) 'W').put((byte) 'A').put((byte) 'V').put((byte) 'E');
        // fmt chunk
        buf.put((byte) 'f').put((byte) 'm').put((byte) 't').put((byte) ' ');
        buf.putInt(fmtChunkSize);
        buf.put(fmtChunk);
        // data chunk (all zeros = silence)
        buf.put((byte) 'd').put((byte) 'a').put((byte) 't').put((byte) 'a');
        buf.putInt(pcmSize);
        // PCM data is already zero-initialized in ByteBuffer

        return buf.array();
    }

    /**
     * Generate a silence audio file at the specified path.
     * For WAV: generates silent WAV matching the reference file's format.
     * For MP3 with ffmpeg: generates silent MP3 using anullsrc filter.
     * Returns null if silence cannot be generated.
     */
    public Path generateSilenceFile(double seconds, String format, Path referenceFile, Path outputFile) {
        // Detect actual format of reference file — it may differ from the format string
        // (e.g., OpenAI TTS returns WAV data even when "mp3" is requested)
        AudioFormat actualFormat;
        try {
            actualFormat = detectFileFormat(referenceFile);
        } catch (IOException e) {
            actualFormat = AudioFormat.UNKNOWN;
        }

        if (actualFormat == AudioFormat.WAV || "wav".equalsIgnoreCase(format)) {
            try {
                byte[] refData = Files.readAllBytes(referenceFile);
                byte[] silence = generateSilenceWav(seconds, refData);
                if (silence != null) {
                    Files.write(outputFile, silence);
                    return outputFile;
                }
            } catch (IOException e) {
                log.warn("Failed to generate WAV silence: {}", e.getMessage());
            }
            return null;
        }

        // MP3: use ffmpeg if available (only when chunks are actually MP3)
        if (isFfmpegAvailable()) {
            try {
                ProcessBuilder pb = new ProcessBuilder(
                        "ffmpeg", "-y", "-f", "lavfi",
                        "-i", "anullsrc=r=24000:cl=mono",
                        "-t", String.format("%.3f", seconds),
                        "-codec:a", "libmp3lame", "-b:a", "128k",
                        outputFile.toString()
                );
                pb.redirectErrorStream(true);
                Process process = pb.start();
                process.getInputStream().readAllBytes(); // consume output
                int exitCode = process.waitFor();
                if (exitCode == 0 && Files.exists(outputFile) && Files.size(outputFile) > 0) {
                    return outputFile;
                }
            } catch (Exception e) {
                log.warn("Failed to generate MP3 silence with ffmpeg: {}", e.getMessage());
            }
        }

        return null;
    }
}
