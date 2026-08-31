package com.storycreator.workflow.background;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.concurrent.*;

import static com.storycreator.workflow.background.ErrorSanitizer.sanitize;

@Service
public class ExpansionBackgroundService {

    private static final Logger log = LoggerFactory.getLogger(ExpansionBackgroundService.class);
    private static final int DISPLAY_BUFFER_MAX = 20_000;
    private static final int DISPLAY_BUFFER_TRIM_TO = 10_000;

    public record GenerationKey(Long projectId) {}

    public static class GenerationTask {
        final Sinks.Many<String> sink = Sinks.many().multicast().onBackpressureBuffer(4096, false);
        final StringBuffer contentBuffer = new StringBuffer();
        final StringBuffer displayBuffer = new StringBuffer();
        volatile Disposable disposable;
        volatile boolean stopRequested;
        volatile boolean completed;
        volatile boolean errored;
        volatile String errorMessage;

        public Sinks.Many<String> getSink() { return sink; }
        public String getContentBuffer() {
            synchronized (displayBuffer) {
                return displayBuffer.toString();
            }
        }
        public String getFullContent() { return contentBuffer.toString(); }
        public boolean isCompleted() { return completed; }
        public boolean isErrored() { return errored; }
        public boolean isStopRequested() { return stopRequested; }
        public String getErrorMessage() { return errorMessage; }
    }

    private final ConcurrentHashMap<GenerationKey, GenerationTask> activeTasks = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService cleanupScheduler = Executors.newSingleThreadScheduledExecutor();

    public void startExpansion(Long projectId, Flux<String> flux, Runnable onComplete, Runnable onError) {
        GenerationKey key = new GenerationKey(projectId);
        GenerationTask task = new GenerationTask();

        GenerationTask existing = activeTasks.compute(key, (k, prev) -> {
            if (prev != null && !prev.completed && !prev.errored) {
                return prev;
            }
            return task;
        });
        if (existing != task) {
            throw new IllegalStateException("该项目已有扩写任务在运行中");
        }

        executor.submit(() -> {
            log.info("Expansion bg-gen started project={}", projectId);
            try {
                task.disposable = flux
                        .doOnNext(token -> {
                            task.contentBuffer.append(token);
                            synchronized (task.displayBuffer) {
                                task.displayBuffer.append(token);
                                if (task.displayBuffer.length() > DISPLAY_BUFFER_MAX) {
                                    int start = task.displayBuffer.length() - DISPLAY_BUFFER_TRIM_TO;
                                    String tail = task.displayBuffer.substring(start);
                                    task.displayBuffer.setLength(0);
                                    task.displayBuffer.append(tail);
                                }
                            }
                            task.sink.tryEmitNext(token);
                        })
                        .doOnComplete(() -> {
                            task.completed = true;
                            log.info("Expansion bg-gen completed project={}", projectId);
                            if (onComplete != null) {
                                try { onComplete.run(); } catch (Exception e) {
                                    log.warn("Expansion post-completion hook failed: {}", e.getMessage());
                                }
                            }
                            task.sink.tryEmitComplete();
                            scheduleCleanup(key);
                        })
                        .doOnError(error -> {
                            task.errored = true;
                            task.errorMessage = sanitize(error);
                            log.error("Expansion bg-gen error project={}: {}", projectId, error.getMessage());
                            if (onError != null) {
                                try { onError.run(); } catch (Exception e) {
                                    log.warn("Expansion error hook failed: {}", e.getMessage());
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
                        log.info("Expansion bg-gen stopped project={}", projectId);
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
                log.error("Expansion bg-gen exception project={}", projectId, e);
                if (onError != null) {
                    try { onError.run(); } catch (Exception ignored) {}
                }
                task.sink.tryEmitNext("[[BG_ERROR:" + task.errorMessage + "]]");
                task.sink.tryEmitComplete();
                scheduleCleanup(key);
            }
        });
    }

    public void stopExpansion(Long projectId) {
        GenerationKey key = new GenerationKey(projectId);
        GenerationTask task = activeTasks.get(key);
        if (task != null && !task.completed && !task.errored) {
            task.stopRequested = true;
        }
    }

    public GenerationTask getActiveTask(Long projectId) {
        GenerationKey key = new GenerationKey(projectId);
        return activeTasks.get(key);
    }

    public boolean isActive(Long projectId) {
        GenerationKey key = new GenerationKey(projectId);
        GenerationTask task = activeTasks.get(key);
        return task != null && !task.completed && !task.errored && !task.stopRequested;
    }

    private void scheduleCleanup(GenerationKey key) {
        cleanupScheduler.schedule(() -> activeTasks.remove(key), 30, TimeUnit.SECONDS);
    }
}
