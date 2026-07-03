package com.storycreator.workflow.background;

import com.storycreator.core.domain.WorkflowStep;
import com.storycreator.workflow.engine.WorkflowEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Sinks;

import java.util.concurrent.*;

import static com.storycreator.workflow.background.ErrorSanitizer.sanitize;

@Service
public class BackgroundGenerationService {

    private static final Logger log = LoggerFactory.getLogger(BackgroundGenerationService.class);
    private static final int DISPLAY_BUFFER_MAX = 20_000;
    private static final int DISPLAY_BUFFER_TRIM_TO = 10_000;

    public record GenerationKey(Long projectId, WorkflowStep step, int chapter) {}

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

        public GenerationTask() {
            this(false);
        }

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
    private final WorkflowEngine workflowEngine;

    public BackgroundGenerationService(@Lazy WorkflowEngine workflowEngine) {
        this.workflowEngine = workflowEngine;
    }

    public void startGeneration(Long projectId, WorkflowStep step, int chapter) {
        startGeneration(projectId, step, chapter, null, null);
    }

    public void startGeneration(Long projectId, WorkflowStep step, int chapter, Runnable postCompletionHook) {
        startGeneration(projectId, step, chapter, postCompletionHook, null);
    }

    public void startGeneration(Long projectId, WorkflowStep step, int chapter, Runnable postCompletionHook, java.util.List<Long> materialIds) {
        GenerationKey key = new GenerationKey(projectId, step, chapter);

        GenerationTask task = new GenerationTask(isCappedStep(step));
        GenerationTask existing = activeTasks.compute(key, (k, prev) -> {
            if (prev != null && !prev.completed && !prev.errored) {
                return prev; // keep existing active task, signal rejection
            }
            return task; // replace completed/errored/null with new task
        });
        if (existing != task) {
            throw new IllegalStateException("该步骤已有后台任务在运行中");
        }

        executor.submit(() -> {
            log.info("[P{}] Background generation started step={} chapter={}", projectId, step, chapter);
            try {
                task.disposable = workflowEngine.generate(projectId, step, chapter,
                                materialIds != null ? materialIds : java.util.Collections.emptyList())
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
                            log.info("[P{}] Background generation completed step={}", projectId, step);
                            workflowEngine.saveGeneratedContent(projectId, step, task.getFullContent(), chapter);
                            if (postCompletionHook != null) {
                                try {
                                    postCompletionHook.run();
                                } catch (Exception e) {
                                    log.warn("[P{}] Post-completion hook failed step={}: {}", projectId, step, e.getMessage());
                                }
                            }
                            task.sink.tryEmitComplete();
                            scheduleCleanup(key);
                        })
                        .doOnError(error -> {
                            task.errored = true;
                            task.errorMessage = sanitize(error);
                            log.error("[P{}] Background generation error step={}: {}", projectId, step, error.getMessage());
                            workflowEngine.resetGeneratingStatus(projectId, step, chapter);
                            task.sink.tryEmitNext("[[BG_ERROR:" + task.errorMessage + "]]");
                            task.sink.tryEmitComplete();
                            scheduleCleanup(key);
                        })
                        .subscribe();

                // Wait for completion or stop signal
                Disposable d = task.disposable;
                while (!task.completed && !task.errored) {
                    if (task.stopRequested) {
                        d = task.disposable;
                        if (d != null) {
                            d.dispose();
                        }
                        workflowEngine.resetGeneratingStatus(projectId, step, chapter);
                        task.sink.tryEmitNext("[[BG_STOPPED]]");
                        task.sink.tryEmitComplete();
                        activeTasks.remove(key);
                        log.info("[P{}] Background generation stopped step={}", projectId, step);
                        return;
                    }
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    d = task.disposable;
                    if (d != null && d.isDisposed()) {
                        break;
                    }
                }
            } catch (Exception e) {
                task.errored = true;
                task.errorMessage = sanitize(e);
                log.error("[P{}] Background generation exception step={}", projectId, step, e);
                workflowEngine.resetGeneratingStatus(projectId, step, chapter);
                task.sink.tryEmitNext("[[BG_ERROR:" + task.errorMessage + "]]");
                task.sink.tryEmitComplete();
                scheduleCleanup(key);
            }
        });
    }

    public void stopGeneration(Long projectId, WorkflowStep step, int chapter) {
        GenerationKey key = new GenerationKey(projectId, step, chapter);
        GenerationTask task = activeTasks.get(key);
        if (task != null && !task.completed && !task.errored) {
            task.stopRequested = true;
        }
    }

    public GenerationTask getActiveTask(Long projectId, WorkflowStep step, int chapter) {
        GenerationKey key = new GenerationKey(projectId, step, chapter);
        return activeTasks.get(key);
    }

    public boolean isActive(Long projectId, WorkflowStep step, int chapter) {
        GenerationKey key = new GenerationKey(projectId, step, chapter);
        GenerationTask task = activeTasks.get(key);
        return task != null && !task.completed && !task.errored && !task.stopRequested;
    }

    /**
     * Find the active chapter number for a given project+step combination.
     * Useful for CHAPTER_WRITING/POLISHING where the chapter number varies.
     */
    public java.util.Optional<Integer> getActiveChapterForStep(Long projectId, WorkflowStep step) {
        return activeTasks.entrySet().stream()
                .filter(e -> e.getKey().projectId().equals(projectId)
                        && e.getKey().step() == step
                        && !e.getValue().completed
                        && !e.getValue().errored
                        && !e.getValue().stopRequested)
                .map(e -> e.getKey().chapter())
                .findFirst();
    }

    private static boolean isCappedStep(WorkflowStep step) {
        return step == WorkflowStep.CHAPTER_WRITING
                || step == WorkflowStep.POLISHING
                || step == WorkflowStep.PROOFREADING;
    }

    private void scheduleCleanup(GenerationKey key) {
        cleanupScheduler.schedule(() -> activeTasks.remove(key), 30, TimeUnit.SECONDS);
    }
}
