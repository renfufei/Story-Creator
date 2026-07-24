package com.storycreator.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Map;

@RestController
public class VendorStaticController {

    private static final Logger log = LoggerFactory.getLogger(VendorStaticController.class);

    private static final Path BASE_DIR = Path.of("data/static/vendor");

    private static final Map<String, String> CDN_MAP = Map.of(
            "bootstrap/css/bootstrap.min.css",
            "https://cdn.jsdelivr.net/npm/bootstrap@5.3.3/dist/css/bootstrap.min.css",

            "bootstrap-icons/css/bootstrap-icons.min.css",
            "https://cdn.jsdelivr.net/npm/bootstrap-icons@1.11.3/font/bootstrap-icons.min.css",

            "bootstrap-icons/css/fonts/bootstrap-icons.woff2",
            "https://cdn.jsdelivr.net/npm/bootstrap-icons@1.11.3/font/fonts/bootstrap-icons.woff2",

            "bootstrap/js/bootstrap.bundle.min.js",
            "https://cdn.jsdelivr.net/npm/bootstrap@5.3.3/dist/js/bootstrap.bundle.min.js",

            "alpinejs/cdn.min.js",
            "https://unpkg.com/alpinejs@3.14.3/dist/cdn.min.js"
    );

    private static final Map<String, String> CONTENT_TYPE_MAP = Map.of(
            ".css", "text/css",
            ".js", "application/javascript",
            ".woff2", "font/woff2"
    );

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @GetMapping("/vendor/**")
    public ResponseEntity<byte[]> serveVendorResource(HttpServletRequest request) {
        String subPath = request.getRequestURI().substring("/vendor/".length());

        // Security: reject path traversal
        if (subPath.contains("..") || subPath.startsWith("/")) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }

        // Check if mapped
        if (!CDN_MAP.containsKey(subPath)) {
            return ResponseEntity.notFound().build();
        }

        Path localFile = BASE_DIR.resolve(subPath);

        // Serve from disk if exists
        if (Files.exists(localFile)) {
            return serveFile(localFile, subPath);
        }

        // Cache miss: download from CDN
        String cdnUrl = CDN_MAP.get(subPath);
        log.info("Cache miss, downloading: {}", cdnUrl);

        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(cdnUrl))
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();

            HttpResponse<InputStream> response = httpClient.send(req, HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() != 200) {
                log.error("CDN download failed: {} status={}", cdnUrl, response.statusCode());
                return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
            }

            // Save to disk
            Files.createDirectories(localFile.getParent());
            try (InputStream is = response.body()) {
                Files.copy(is, localFile, StandardCopyOption.REPLACE_EXISTING);
            }

            return serveFile(localFile, subPath);

        } catch (IOException | InterruptedException e) {
            log.error("Failed to download vendor resource: {}", cdnUrl, e);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
        }
    }

    private ResponseEntity<byte[]> serveFile(Path file, String subPath) {
        try {
            byte[] content = Files.readAllBytes(file);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.parseMediaType(getContentType(subPath)));
            headers.setCacheControl("public, max-age=31536000, immutable");
            return new ResponseEntity<>(content, headers, HttpStatus.OK);
        } catch (IOException e) {
            log.error("Failed to read vendor file: {}", file, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    private String getContentType(String path) {
        for (Map.Entry<String, String> entry : CONTENT_TYPE_MAP.entrySet()) {
            if (path.endsWith(entry.getKey())) {
                return entry.getValue();
            }
        }
        return "application/octet-stream";
    }
}
