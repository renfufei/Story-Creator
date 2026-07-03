package com.storycreator.tts;

/**
 * Bundles TTS playback parameters: config selection, voice, format, and speed.
 */
public record TtsPlaybackSettings(
    Long configId,
    String voice,
    String format,
    double speed
) {}
