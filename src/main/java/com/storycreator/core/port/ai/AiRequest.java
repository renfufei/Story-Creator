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

    /**
     * 调用元数据：这次请求是为「哪个项目的第几章、属于哪一卷」发出的。
     *
     * <p><b>不是提示词内容</b>——绝不会拼进 prompt，也不会随请求发给模型服务端。
     * 它的作用是把「生成前定下来的卷归属」跟着请求一起走到返回结果那一步，
     * 这样内容被接受、落库时不必再靠章号整除去猜自己是哪一卷。</p>
     */
    private Long projectId;
    private int chapterNumber;
    private int volumeNumber;
    private String volumeTitle;

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

    // --- 调用元数据（不进提示词）---

    public Long getProjectId() { return projectId; }
    public void setProjectId(Long projectId) { this.projectId = projectId; }

    public int getChapterNumber() { return chapterNumber; }
    public void setChapterNumber(int chapterNumber) { this.chapterNumber = chapterNumber; }

    /** 本次生成属于第几卷；0 = 未知 / 项目没有分卷。 */
    public int getVolumeNumber() { return volumeNumber; }
    public void setVolumeNumber(int volumeNumber) { this.volumeNumber = volumeNumber; }

    public String getVolumeTitle() { return volumeTitle; }
    public void setVolumeTitle(String volumeTitle) { this.volumeTitle = volumeTitle; }

    public boolean hasVolume() { return volumeNumber > 0; }

    /**
     * 一次性写入「项目 / 章 / 卷」这三个标识。
     *
     * @return this，方便在 builder 之后链式调用
     */
    public AiRequest applyScope(Long projectId, int chapterNumber, int volumeNumber, String volumeTitle) {
        this.projectId = projectId;
        this.chapterNumber = chapterNumber;
        this.volumeNumber = volumeNumber;
        this.volumeTitle = volumeTitle;
        return this;
    }

    /** 日志用：{@code P26 v1 c7}；没有卷时省略 v 段。 */
    public String scopeTag() {
        StringBuilder sb = new StringBuilder("P").append(projectId != null ? projectId : "-");
        if (volumeNumber > 0) sb.append(" v").append(volumeNumber);
        if (chapterNumber > 0) sb.append(" c").append(chapterNumber);
        return sb.toString();
    }

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
        public Builder projectId(Long projectId) { request.projectId = projectId; return this; }
        public Builder chapterNumber(int chapterNumber) { request.chapterNumber = chapterNumber; return this; }
        public Builder volumeNumber(int volumeNumber) { request.volumeNumber = volumeNumber; return this; }
        public Builder volumeTitle(String volumeTitle) { request.volumeTitle = volumeTitle; return this; }

        public AiRequest build() { return request; }
    }
}
