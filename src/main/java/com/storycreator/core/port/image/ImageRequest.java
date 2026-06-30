package com.storycreator.core.port.image;

import com.storycreator.core.domain.ImageType;

public class ImageRequest {
    private String model;
    private String prompt;
    private String negativePrompt;
    private ImageType imageType;
    private int width = 1024;
    private int height = 1024;
    private String baseUrl;
    private String apiKey;
    private String extraParams;

    private ImageRequest() {}

    public static Builder builder() { return new Builder(); }

    public String getModel() { return model; }
    public String getPrompt() { return prompt; }
    public String getNegativePrompt() { return negativePrompt; }
    public ImageType getImageType() { return imageType; }
    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public String getBaseUrl() { return baseUrl; }
    public String getApiKey() { return apiKey; }
    public String getExtraParams() { return extraParams; }

    public static class Builder {
        private final ImageRequest r = new ImageRequest();

        public Builder model(String model) { r.model = model; return this; }
        public Builder prompt(String prompt) { r.prompt = prompt; return this; }
        public Builder negativePrompt(String negativePrompt) { r.negativePrompt = negativePrompt; return this; }
        public Builder imageType(ImageType imageType) { r.imageType = imageType; return this; }
        public Builder width(int width) { r.width = width; return this; }
        public Builder height(int height) { r.height = height; return this; }
        public Builder baseUrl(String baseUrl) { r.baseUrl = baseUrl; return this; }
        public Builder apiKey(String apiKey) { r.apiKey = apiKey; return this; }
        public Builder extraParams(String extraParams) { r.extraParams = extraParams; return this; }

        public ImageRequest build() { return r; }
    }
}
