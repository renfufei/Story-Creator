package com.storycreator.tts;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@Service
public class Mp3ProcessingService {

    private static final Logger log = LoggerFactory.getLogger(Mp3ProcessingService.class);

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
     * This produces correct duration metadata without re-encoding.
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
     * Manual MP3 concatenation: writes first chunk fully, strips ID3v2 tags from subsequent chunks.
     * This ensures players don't read stale duration info from embedded tags of later chunks.
     */
    private void concatenateMp3Manual(List<Path> chunkFiles, Path output) throws IOException {
        try (OutputStream out = Files.newOutputStream(output)) {
            for (int i = 0; i < chunkFiles.size(); i++) {
                Path chunk = chunkFiles.get(i);
                if (i == 0) {
                    // First chunk: write completely (keep its ID3 tag)
                    Files.copy(chunk, out);
                } else {
                    // Subsequent chunks: skip ID3v2 tag if present
                    writeSkippingId3v2(chunk, out);
                }
            }
        }
    }

    /**
     * Write MP3 file content to output stream, skipping any leading ID3v2 tag.
     * ID3v2 tag starts with "ID3" followed by version (2 bytes), flags (1 byte),
     * and size (4 bytes, syncsafe integer encoding).
     */
    private void writeSkippingId3v2(Path file, OutputStream out) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] header = new byte[10];
            int read = in.readNBytes(header, 0, 10);
            if (read < 10) {
                // File too small to have ID3 tag, write what we read
                out.write(header, 0, read);
                in.transferTo(out);
                return;
            }

            // Check for "ID3" magic bytes
            if (header[0] == 'I' && header[1] == 'D' && header[2] == '3') {
                // Parse syncsafe integer size (bytes 6-9)
                int size = ((header[6] & 0x7F) << 21)
                         | ((header[7] & 0x7F) << 14)
                         | ((header[8] & 0x7F) << 7)
                         | (header[9] & 0x7F);
                // Total ID3v2 tag size = 10 (header) + size
                long toSkip = size; // already read the 10-byte header
                long skipped = in.skip(toSkip);
                if (skipped < toSkip) {
                    // If we can't skip enough, the file is likely corrupt; skip what we can
                    log.warn("Could not skip full ID3v2 tag in {}: expected {} but skipped {}", file, toSkip, skipped);
                }
                // Write remaining audio data
                in.transferTo(out);
            } else {
                // No ID3 tag: write the header bytes we already read, then the rest
                out.write(header, 0, read);
                in.transferTo(out);
            }
        }
    }

    /**
     * Concatenate MP3 chunks and re-encode with ffmpeg to target bitrate.
     */
    public void concatenateAndCompress(List<Path> chunkFiles, Path output, String bitrate) throws IOException {
        // First concatenate to a temp file
        Path tempConcat = output.resolveSibling("temp_concat_" + System.currentTimeMillis() + ".mp3");
        try {
            concatenateMp3(chunkFiles, tempConcat);

            // Re-encode with ffmpeg
            ProcessBuilder pb = new ProcessBuilder(
                    "ffmpeg", "-i", tempConcat.toString(),
                    "-acodec", "libmp3lame",
                    "-ab", bitrate,
                    "-y",
                    output.toString()
            );
            pb.redirectErrorStream(true);
            Process process = pb.start();
            // Consume output to prevent blocking
            process.getInputStream().readAllBytes();
            int exitCode = process.waitFor();

            if (exitCode != 0) {
                log.warn("ffmpeg exited with code {}, falling back to direct concat", exitCode);
                // Fallback: just use the concatenated file
                if (Files.exists(tempConcat)) {
                    Files.move(tempConcat, output, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("ffmpeg interrupted", e);
        } finally {
            Files.deleteIfExists(tempConcat);
        }
    }
}
