package com.storycreator.sidestory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.concurrent.*;

import static com.storycreator.workflow.background.ErrorSanitizer.sanitize;

@Service
public class SideStoryBackgroundService {

    private static final Logger log = LoggerFactory.getLogger(SideStoryBackgroundService.class);
    private static final int DISPLAY_BUFFER_MAX = 20_000;
    private static final int DISPLAY_BUFFER_TRIM_TO = 10_000;

    public record GenerationKey(Long sideStoryId, String operation, int chapterNumber) {}

    public static class GenerationTask {
        final Sinks.Many<String> sink = Sinks.many().multicast().onBackpressureBuffer(4096, false);
        final StringBuffer contentBuffer = new StringBuffer();
        final StringBuffer displayBuffer = new StringBuffer();
        final boolean cappedDisplay;
        volatile Disposable disposable;
        volatile boolean stopRequested;
        volatile boolean completed;
        volatile boolean errored;
        volatile String errorMessage;

        public GenerationTask(boolean cappedDisplay) {
            this.cappedDisplay = cappedDisplay;
        }

        public Sinks.Many<String> getSink() { return sink; }
        public String getContentBuffer() {
            if (cappedDisplay) {
                synchronized (displayBuffer) {
                    return displayBuffer.toString();
                }
            }
            return contentBuffer.toString();
        }
        public String getFullContent() { return contentBuffer.toString(); }
        public boolean isCompleted() { return completed; }
        public boolean isErrored() { return errored; }
        public String getErrorMessage() { return errorMessage; }
    }

    private final ConcurrentHashMap<GenerationKey, GenerationTask> activeTasks = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService cleanupScheduler = Executors.newSingleThreadScheduledExecutor();
    private final SideStoryWorkflowService workflowService;

    public SideStoryBackgroundService(SideStoryWorkflowService workflowService) {
        this.workflowService = workflowService;
    }

    public void startGeneration(Long sideStoryId, String operation, int chapterNumber,
                                 Flux<String> flux, Runnable onComplete, Runnable onError) {
        GenerationKey key = new GenerationKey(sideStoryId, operation, chapterNumber);
        boolean cappedDisplay = "WRITING".equals(operation) || "POLISH".equals(operation);
        GenerationTask task = new GenerationTask(cappedDisplay);

        GenerationTask existing = activeTasks.compute(key, (k, prev) -> {
            if (prev != null && !prev.completed && !prev.errored) {
                return prev;
            }
            return task;
        });
        if (existing != task) {
            throw new IllegalStateException("该番外已有后台任务在运行中");
        }

        executor.submit(() -> {
            log.info("Side story bg-gen started sideStory={} op={} ch={}", sideStoryId, operation, chapterNumber);
            try {
                task.disposable = flux
                        .doOnNext(token -> {
                            task.contentBuffer.append(token);
                            if (task.cappedDisplay) {
                                synchronized (task.displayBuffer) {
                                    task.displayBuffer.append(token);
                                    if (task.displayBuffer.length() > DISPLAY_BUFFER_MAX) {
                                        int start = task.displayBuffer.length() - DISPLAY_BUFFER_TRIM_TO;
                                        String tail = task.displayBuffer.substring(start);
                                        task.displayBuffer.setLength(0);
                                        task.displayBuffer.append(tail);
                                    }
                                }
                            }
                            task.sink.tryEmitNext(token);
                        })
                        .doOnComplete(() -> {
                            task.completed = true;
                            log.info("Side story bg-gen completed sideStory={} op={}", sideStoryId, operation);
                            if (onComplete != null) {
                                try {
                                    onComplete.run();
                                } catch (Exception e) {
                                    log.warn("Side story post-completion hook failed: {}", e.getMessage());
                                }
                            }
                            task.sink.tryEmitComplete();
                            scheduleCleanup(key);
                        })
                        .doOnError(error -> {
                            task.errored = true;
                            task.errorMessage = sanitize(error);
                            log.error("Side story bg-gen error sideStory={} op={}: {}", sideStoryId, operation, error.getMessage());
                            if (onError != null) {
                                try {
                                    onError.run();
                                } catch (Exception e) {
                                    log.warn("Side story error hook failed: {}", e.getMessage());
                                }
                            }
                            task.sink.tryEmitNext("[[BG_ERROR:" + task.errorMessage + "]]");
                            task.sink.tryEmitComplete();
                            scheduleCleanup(key);
                        })
                        .subscribe();

                // Wait for completion or stop signal
                while (!task.completed && !task.errored) {
                    if (task.stopRequested) {
                        Disposable d = task.disposable;
                        if (d != null) d.dispose();
                        if (onError != null) {
                            try { onError.run(); } catch (Exception ignored) {}
                        }
                        task.sink.tryEmitNext("[[BG_STOPPED]]");
                        task.sink.tryEmitComplete();
                        activeTasks.remove(key);
                        log.info("Side story bg-gen stopped sideStory={} op={}", sideStoryId, operation);
                        return;
                    }
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    Disposable d = task.disposable;
                    if (d != null && d.isDisposed()) break;
                }
            } catch (Exception e) {
                task.errored = true;
                task.errorMessage = sanitize(e);
                log.error("Side story bg-gen exception sideStory={} op={}", sideStoryId, operation, e);
                if (onError != null) {
                    try { onError.run(); } catch (Exception ignored) {}
                }
                task.sink.tryEmitNext("[[BG_ERROR:" + task.errorMessage + "]]");
                task.sink.tryEmitComplete();
                scheduleCleanup(key);
            }
        });
    }

    public void stopGeneration(Long sideStoryId, String operation, int chapterNumber) {
        GenerationKey key = new GenerationKey(sideStoryId, operation, chapterNumber);
        GenerationTask task = activeTasks.get(key);
        if (task != null && !task.completed && !task.errored) {
            task.stopRequested = true;
        }
    }

    public GenerationTask getActiveTask(Long sideStoryId, String operation, int chapterNumber) {
        GenerationKey key = new GenerationKey(sideStoryId, operation, chapterNumber);
        return activeTasks.get(key);
    }

    public boolean isActive(Long sideStoryId, String operation, int chapterNumber) {
        GenerationKey key = new GenerationKey(sideStoryId, operation, chapterNumber);
        GenerationTask task = activeTasks.get(key);
        return task != null && !task.completed && !task.errored && !task.stopRequested;
    }

    private void scheduleCleanup(GenerationKey key) {
        cleanupScheduler.schedule(() -> activeTasks.remove(key), 30, TimeUnit.SECONDS);
    }
}
