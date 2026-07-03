package com.storycreator.workflow.background;

/**
 * Sanitizes exception messages to prevent leaking internal details in SSE sentinels and stored error messages.
 */
public final class ErrorSanitizer {

    private ErrorSanitizer() {}

    private static final String GENERIC_ERROR = "服务器内部错误，请稍后重试";

    public static String sanitize(Throwable error) {
        if (error == null) return GENERIC_ERROR;
        String msg = error.getMessage();
        if (msg == null || msg.isBlank()) return GENERIC_ERROR;
        if (msg.length() > 200) return GENERIC_ERROR;
        if (msg.contains("Exception") || msg.contains("at ") || msg.contains(".java:")) return GENERIC_ERROR;
        if (msg.contains("password") || msg.contains("secret") || msg.contains("token")) return GENERIC_ERROR;
        return msg;
    }
}
