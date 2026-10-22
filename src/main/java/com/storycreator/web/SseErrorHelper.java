package com.storycreator.web;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Sanitizes exception messages before sending via SSE to prevent leaking internal details.
 * Known business exceptions (IllegalState, IllegalArgument) keep their messages as they are user-facing.
 * Unknown/unexpected exceptions get a generic message.
 */
final class SseErrorHelper {

    private SseErrorHelper() {}

    private static final String GENERIC_ERROR = "服务器内部错误，请稍后重试";

    /**
     * 启动期就被拒（如项目已完本 / 已废弃）时用：返回一个「只发一条 error 事件就关闭」的 emitter。
     *
     * <p>为什么不用 HTTP 4xx：这些端点前端是用 EventSource / fetch 读流的，
     * 返回 JSON 或错误页会让前端只看到「连接失败」；发一条规范 error 事件，
     * 前端现有的错误处理逻辑不用改，用户也能看到具体原因。
     */
    static SseEmitter rejected(String message) {
        SseEmitter emitter = new SseEmitter(60_000L);
        try {
            emitter.send(SseEmitter.event().name("error").data(message));
            emitter.complete();
        } catch (Exception e) {
            emitter.completeWithError(e);
        }
        return emitter;
    }

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
