package com.storycreator.core.port.image;

public interface ImageProvider {
    String getProviderName();
    ImageResult generateImage(ImageRequest request);
}
