package com.storycreator.txtimport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.concurrent.*;

import static com.storycreator.workflow.background.ErrorSanitizer.sanitize;

@Service
public class TxtImportBackgroundService {

    private static final Logger log = LoggerFactory.getLogger(TxtImportBackgroundService.class);
    private static final int DISPLAY_BUFFER_MAX = 20_000;
    private static final int DISPLAY_BUFFER_TRIM_TO = 10_000;

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

    private final ConcurrentHashMap<Long, GenerationTask> activeTasks = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService cleanupScheduler = Executors.newSingleThreadScheduledExecutor();

    public void startTask(Long jobId, Flux<String> flux) {
        GenerationTask task = new GenerationTask();

        GenerationTask existing = activeTasks.compute(jobId, (k, prev) -> {
            if (prev != null && !prev.completed && !prev.errored) {
                return prev;
            }
            return task;
        });
        if (existing != task) {
            throw new IllegalStateException("该任务已在运行中");
        }

        executor.submit(() -> {
            log.info("TXT import reverse-engineering started jobId={}", jobId);
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
                            log.info("TXT import reverse-engineering completed jobId={}", jobId);
                            task.sink.tryEmitComplete();
                            scheduleCleanup(jobId);
                        })
                        .doOnError(error -> {
                            task.errored = true;
                            task.errorMessage = sanitize(error);
                            log.error("TXT import reverse-engineering error jobId={}: {}", jobId, error.getMessage());
                            task.sink.tryEmitNext("[[BG_ERROR:" + task.errorMessage + "]]");
                            task.sink.tryEmitComplete();
                            scheduleCleanup(jobId);
                        })
                        .subscribe();

                while (!task.completed && !task.errored) {
                    if (task.stopRequested) {
                        Disposable d = task.disposable;
                        if (d != null) d.dispose();
                        task.sink.tryEmitNext("[[BG_STOPPED]]");
                        task.sink.tryEmitComplete();
                        activeTasks.remove(jobId);
                        log.info("TXT import reverse-engineering stopped jobId={}", jobId);
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
                log.error("TXT import reverse-engineering exception jobId={}", jobId, e);
                task.sink.tryEmitNext("[[BG_ERROR:" + task.errorMessage + "]]");
                task.sink.tryEmitComplete();
                scheduleCleanup(jobId);
            }
        });
    }

    public void stopTask(Long jobId) {
        GenerationTask task = activeTasks.get(jobId);
        if (task != null && !task.completed && !task.errored) {
            task.stopRequested = true;
        }
    }

    public GenerationTask getActiveTask(Long jobId) {
        return activeTasks.get(jobId);
    }

    public boolean isActive(Long jobId) {
        GenerationTask task = activeTasks.get(jobId);
        return task != null && !task.completed && !task.errored && !task.stopRequested;
    }

    private void scheduleCleanup(Long jobId) {
        cleanupScheduler.schedule(() -> activeTasks.remove(jobId), 30, TimeUnit.SECONDS);
    }
}
