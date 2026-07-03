package com.storycreator.tts;

public record TtsChunkResult(byte[] audio, String issueType) {

    public static TtsChunkResult success(byte[] audio) {
        return new TtsChunkResult(audio, null);
    }

    public static TtsChunkResult failed(byte[] audio, String issueType) {
        return new TtsChunkResult(audio, issueType);
    }

    public boolean passed() {
        return issueType == null;
    }
}
