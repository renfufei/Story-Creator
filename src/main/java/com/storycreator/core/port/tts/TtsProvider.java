package com.storycreator.core.port.tts;

public interface TtsProvider {
    String getProviderName();
    byte[] generateAudio(TtsRequest request);
}
