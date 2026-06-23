package com.storycreator.core.port.tts;

public class TtsRequest {

    private String model;
    private String input;
    private String voice;
    private String responseFormat = "mp3";
    private double speed = 1.0;
    private String baseUrl;
    private String apiKey;

    public TtsRequest() {}

    public TtsRequest(String model, String input, String voice, String responseFormat, double speed, String baseUrl, String apiKey) {
        this.model = model;
        this.input = input;
        this.voice = voice;
        this.responseFormat = responseFormat;
        this.speed = speed;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
    }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public String getInput() { return input; }
    public void setInput(String input) { this.input = input; }

    public String getVoice() { return voice; }
    public void setVoice(String voice) { this.voice = voice; }

    public String getResponseFormat() { return responseFormat; }
    public void setResponseFormat(String responseFormat) { this.responseFormat = responseFormat; }

    public double getSpeed() { return speed; }
    public void setSpeed(double speed) { this.speed = speed; }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private String model;
        private String input;
        private String voice = "alloy";
        private String responseFormat = "mp3";
        private double speed = 1.0;
        private String baseUrl;
        private String apiKey;

        public Builder model(String model) { this.model = model; return this; }
        public Builder input(String input) { this.input = input; return this; }
        public Builder voice(String voice) { this.voice = voice; return this; }
        public Builder responseFormat(String responseFormat) { this.responseFormat = responseFormat; return this; }
        public Builder speed(double speed) { this.speed = speed; return this; }
        public Builder baseUrl(String baseUrl) { this.baseUrl = baseUrl; return this; }
        public Builder apiKey(String apiKey) { this.apiKey = apiKey; return this; }

        public TtsRequest build() {
            return new TtsRequest(model, input, voice, responseFormat, speed, baseUrl, apiKey);
        }
    }
}
