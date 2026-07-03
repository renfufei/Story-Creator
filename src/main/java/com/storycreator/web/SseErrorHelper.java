package com.storycreator.web;

/**
 * Sanitizes exception messages before sending via SSE to prevent leaking internal details.
 * Known business exceptions (IllegalState, IllegalArgument) keep their messages as they are user-facing.
 * Unknown/unexpected exceptions get a generic message.
 */
final class SseErrorHelper {

    private SseErrorHelper() {}

    private static final String GENERIC_ERROR = "服务器内部错误，请稍后重试";

    static String sanitize(Throwable error) {
        if (error == null) return GENERIC_ERROR;
        if (error instanceof IllegalStateException || error instanceof IllegalArgumentException) {
            String msg = error.getMessage();
            return msg != null ? msg : GENERIC_ERROR;
        }
        // For AI/network errors, extract a safe summary
        String msg = error.getMessage();
        if (msg != null && isLikelySafeMessage(msg)) {
            return msg;
        }
        return GENERIC_ERROR;
    }

    /**
     * Heuristic: messages that don't contain stack trace indicators or sensitive paths are considered safe.
     */
    private static boolean isLikelySafeMessage(String msg) {
        if (msg.length() > 200) return false;
        if (msg.contains("Exception") || msg.contains("at ") || msg.contains(".java:")) return false;
        if (msg.contains("password") || msg.contains("secret") || msg.contains("token")) return false;
        return true;
    }
}
