package com.storycreator.tts.template;

public record TtsReplacementRule(String pattern, String replacement, boolean isRegex, String description) {
}
