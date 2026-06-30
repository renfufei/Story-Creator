package com.storycreator.core.port.image;

public record ImageResult(byte[] imageBytes, String mimeType, String revisedPrompt) {
}
