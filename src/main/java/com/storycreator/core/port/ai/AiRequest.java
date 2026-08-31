package com.storycreator.core.port.ai;

import java.util.List;
import java.util.Map;

public class AiRequest {

    private String model;
    private String systemPrompt;
    private String userPrompt;
    private int maxTokens = 4096;
    private double temperature = 0.8;
    private String baseUrl;
    private String apiKey;
    private String extraParams;
    private List<Map<String, String>> messages;

    public AiRequest() {}

    public AiRequest(String model, String systemPrompt, String userPrompt, int maxTokens) {
        this.model = model;
        this.systemPrompt = systemPrompt;
        this.userPrompt = userPrompt;
        this.maxTokens = maxTokens;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public String getSystemPrompt() { return systemPrompt; }
    public void setSystemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; }

    public String getUserPrompt() { return userPrompt; }
    public void setUserPrompt(String userPrompt) { this.userPrompt = userPrompt; }

    public int getMaxTokens() { return maxTokens; }
    public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }

    public double getTemperature() { return temperature; }
    public void setTemperature(double temperature) { this.temperature = temperature; }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

    public String getExtraParams() { return extraParams; }
    public void setExtraParams(String extraParams) { this.extraParams = extraParams; }

    public List<Map<String, String>> getMessages() { return messages; }
    public void setMessages(List<Map<String, String>> messages) { this.messages = messages; }

    public static class Builder {
        private final AiRequest request = new AiRequest();

        public Builder model(String model) { request.model = model; return this; }
        public Builder systemPrompt(String systemPrompt) { request.systemPrompt = systemPrompt; return this; }
        public Builder userPrompt(String userPrompt) { request.userPrompt = userPrompt; return this; }
        public Builder maxTokens(int maxTokens) { request.maxTokens = maxTokens; return this; }
        public Builder temperature(double temperature) { request.temperature = temperature; return this; }
        public Builder baseUrl(String baseUrl) { request.baseUrl = baseUrl; return this; }
        public Builder apiKey(String apiKey) { request.apiKey = apiKey; return this; }
        public Builder extraParams(String extraParams) { request.extraParams = extraParams; return this; }
        public Builder messages(List<Map<String, String>> messages) { request.messages = messages; return this; }

        public AiRequest build() { return request; }
    }
}
