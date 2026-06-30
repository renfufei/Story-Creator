package com.storycreator.workflow.autorun;

import reactor.core.publisher.Sinks;

public class AutoRunObservation {
    private final Sinks.Many<String> sink = Sinks.many().multicast().onBackpressureBuffer(4096, false);
    private final StringBuffer tokenBuffer = new StringBuffer();
    private volatile String currentStepName = "";
    private volatile int currentChapter = 0;
    private volatile boolean active = false;

    public void reset(String step, int chapter) {
        tokenBuffer.setLength(0);
        currentStepName = step;
        currentChapter = chapter;
    }

    public Sinks.Many<String> getSink() { return sink; }
    public StringBuffer getTokenBufferRaw() { return tokenBuffer; }
    public String getTokenBuffer() { return tokenBuffer.toString(); }
    public String getCurrentStepName() { return currentStepName; }
    public int getCurrentChapter() { return currentChapter; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
}
