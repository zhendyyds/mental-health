package com.flj.rag;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@ConditionalOnProperty(prefix = "app.rag", name = "enabled", havingValue = "true")
public class RagIndexScheduler {
    private static final Logger log = LoggerFactory.getLogger(RagIndexScheduler.class);
    private final RagIndexWorker worker;
    private final AtomicBoolean busy = new AtomicBoolean();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "knowledge-index-worker");
        thread.setDaemon(true);
        return thread;
    });

    public RagIndexScheduler(RagIndexWorker worker, KnowledgeIndexRepository repository, RagIndexSettings settings) {
        this.worker = worker;
        repository.registerConfig(settings.currentConfig());
    }

    @Scheduled(fixedDelayString = "${app.rag.poll-interval-ms:2000}")
    public void poll() {
        if (!busy.compareAndSet(false, true)) return;
        try {
            executor.execute(() -> {
                try {
                    worker.runOnce();
                } catch (RuntimeException error) {
                    log.warn("Knowledge index poll failed, type={}", error.getClass().getSimpleName());
                } finally {
                    busy.set(false);
                }
            });
        } catch (RejectedExecutionException shutdown) {
            busy.set(false);
        }
    }

    @PreDestroy
    void close() {
        executor.shutdownNow();
    }
}
