package com.storycreator.workflow.autorun;

import reactor.core.publisher.Sinks;

import java.util.ArrayList;

public class AutoRunObservation {
    private final ArrayList<String> tokenLog = new ArrayList<>(512);
    private final Object logLock = new Object();
    private volatile int tokenLogSize = 0;
    private volatile long batchId = 0;

    // Notify sink: only wakes up readers, payload is irrelevant
    private final Sinks.Many<Integer> notifySink =
            Sinks.many().multicast().onBackpressureBuffer(4096, false);

    private volatile String currentStepName = "";
    private volatile int currentChapter = 0;
    private volatile boolean active = false;

    public void reset(String step, int chapter) {
        synchronized (logLock) {
            tokenLog.clear();
            tokenLogSize = 0;
            batchId++;
        }
        currentStepName = step;
        currentChapter = chapter;
        notifySink.tryEmitNext(0); // wake readers to detect batch change
    }

    public void appendToken(String token) {
        synchronized (logLock) {
            tokenLog.add(token);
            tokenLogSize = tokenLog.size();
        }
        notifySink.tryEmitNext(tokenLogSize);
    }

    public record ReadResult(long batchId, int endIndex, String content) {}

    public ReadResult readFrom(int fromIndex) {
        synchronized (logLock) {
            int end = tokenLog.size();
            if (fromIndex >= end) return new ReadResult(batchId, end, "");
            StringBuilder sb = new StringBuilder();
            for (int i = fromIndex; i < end; i++) sb.append(tokenLog.get(i));
            return new ReadResult(batchId, end, sb.toString());
        }
    }

    public Sinks.Many<Integer> getNotifySink() { return notifySink; }
    public long getBatchId() { return batchId; }
    public String getCurrentStepName() { return currentStepName; }
    public int getCurrentChapter() { return currentChapter; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
}
