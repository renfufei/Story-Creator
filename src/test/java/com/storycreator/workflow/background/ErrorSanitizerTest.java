package com.storycreator.workflow.background;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ErrorSanitizerTest {

    private static final String GENERIC = "服务器内部错误，请稍后重试";

    @Test
    void nullError_returnsGeneric() {
        assertThat(ErrorSanitizer.sanitize(null)).isEqualTo(GENERIC);
    }

    @Test
    void nullMessage_returnsGeneric() {
        assertThat(ErrorSanitizer.sanitize(new RuntimeException((String) null))).isEqualTo(GENERIC);
    }

    @Test
    void blankMessage_returnsGeneric() {
        assertThat(ErrorSanitizer.sanitize(new RuntimeException("   "))).isEqualTo(GENERIC);
    }

    @Test
    void messageTooLong_returnsGeneric() {
        String longMsg = "x".repeat(201);
        assertThat(ErrorSanitizer.sanitize(new RuntimeException(longMsg))).isEqualTo(GENERIC);
    }

    @Test
    void messageExactly200Chars_passesThrough() {
        String msg = "x".repeat(200);
        assertThat(ErrorSanitizer.sanitize(new RuntimeException(msg))).isEqualTo(msg);
    }

    @Test
    void messageContainsException_returnsGeneric() {
        assertThat(ErrorSanitizer.sanitize(new RuntimeException("NullPointerException occurred"))).isEqualTo(GENERIC);
    }

    @Test
    void messageContainsStackTraceLine_returnsGeneric() {
        assertThat(ErrorSanitizer.sanitize(new RuntimeException("at com.example.Foo"))).isEqualTo(GENERIC);
    }

    @Test
    void messageContainsDotJavaColon_returnsGeneric() {
        assertThat(ErrorSanitizer.sanitize(new RuntimeException("Foo.java:42"))).isEqualTo(GENERIC);
    }

    @Test
    void messageContainsPassword_returnsGeneric() {
        assertThat(ErrorSanitizer.sanitize(new RuntimeException("wrong password supplied"))).isEqualTo(GENERIC);
    }

    @Test
    void messageContainsSecret_returnsGeneric() {
        assertThat(ErrorSanitizer.sanitize(new RuntimeException("invalid secret key"))).isEqualTo(GENERIC);
    }

    @Test
    void messageContainsToken_returnsGeneric() {
        assertThat(ErrorSanitizer.sanitize(new RuntimeException("expired token"))).isEqualTo(GENERIC);
    }

    @Test
    void safeMessage_passesThrough() {
        String msg = "API 速率限制，请稍后重试";
        assertThat(ErrorSanitizer.sanitize(new RuntimeException(msg))).isEqualTo(msg);
    }

    @Test
    void safeEnglishMessage_passesThrough() {
        String msg = "Rate limit exceeded";
        assertThat(ErrorSanitizer.sanitize(new RuntimeException(msg))).isEqualTo(msg);
    }

    @Test
    void emptyMessage_returnsGeneric() {
        assertThat(ErrorSanitizer.sanitize(new RuntimeException(""))).isEqualTo(GENERIC);
    }
}
