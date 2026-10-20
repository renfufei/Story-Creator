package com.storycreator.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseBody;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;

@ControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseBody
    public ResponseEntity<Map<String, String>> handleIllegalArgument(IllegalArgumentException ex, HttpServletRequest request) {
        log.warn("Bad request [{}]: {}", request.getRequestURI(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    @ResponseBody
    public ResponseEntity<Map<String, String>> handleIllegalState(IllegalStateException ex, HttpServletRequest request) {
        log.warn("Conflict [{}]: {}", request.getRequestURI(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    @ResponseBody
    public ResponseEntity<Map<String, String>> handleNoResource(NoResourceFoundException ex, HttpServletRequest request) {
        log.warn("No static resource [{}]", request.getRequestURI());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", "资源不存在"));
    }

    // Async SSE timeout. The underlying stream is already gone (or completing). Do NOT try to
    // serialize a JSON error body — the response is text/event-stream and may already be
    // committed, which would itself throw HttpMessageNotWritableException ("No converter for
    // ImmutableCollections$Map1 with preset Content-Type 'text/event-stream'"). The SSE layer
    // already handles this gracefully via emitter.onTimeout, so we simply avoid the crash here.
    @ExceptionHandler(AsyncRequestTimeoutException.class)
    public void handleAsyncTimeout(AsyncRequestTimeoutException ex, HttpServletResponse response) {
        if (!response.isCommitted()) {
            try {
                response.setStatus(HttpStatus.NO_CONTENT.value());
            } catch (Exception ignored) {
            }
        }
    }

    @ExceptionHandler(ResponseStatusException.class)
    @ResponseBody
    public ResponseEntity<Map<String, String>> handleResponseStatus(ResponseStatusException ex, HttpServletRequest request) {
        log.warn("Response status exception [{}]: {}", request.getRequestURI(), ex.getReason());
        Map<String, String> body = new java.util.HashMap<>();
        body.put("error", ex.getReason() == null ? ex.getStatusCode().toString() : ex.getReason());
        return ResponseEntity.status(ex.getStatusCode()).body(body);
    }

    /**
     * 方法不匹配（例如对只支持 POST 的流式接口发 GET）。
     * 必须回 405：兜底的 {@link Exception} 处理器会把它压成 500，等于把"调错了"说成"服务器坏了"。
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    @ResponseBody
    public ResponseEntity<Map<String, String>> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException ex, HttpServletRequest request) {
        java.util.Set<HttpMethod> supported = ex.getSupportedHttpMethods();
        String allow = supported == null ? null : supported.stream()
                .map(HttpMethod::name).sorted().collect(java.util.stream.Collectors.joining(", "));
        log.warn("Method not allowed [{} {}] allow={}", request.getMethod(), request.getRequestURI(), allow);
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED);
        if (allow != null) {
            builder.header(HttpHeaders.ALLOW, allow);
        }
        return builder.body(Map.of("error", "该接口只支持 " + (allow == null ? "其他" : allow) + " 方法"));
    }

    /** 请求体读不出来（JSON 写错、字段类型不符）→ 400，而不是被兜底成 500。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseBody
    public ResponseEntity<Map<String, String>> handleUnreadableBody(
            HttpMessageNotReadableException ex, HttpServletRequest request) {
        log.warn("Unreadable body [{}]: {}", request.getRequestURI(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", "请求体不是合法的 JSON"));
    }

    @ExceptionHandler(Exception.class)
    @ResponseBody
    public ResponseEntity<Map<String, String>> handleGeneral(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception [{}]", request.getRequestURI(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "服务器内部错误，请稍后重试"));
    }
}
