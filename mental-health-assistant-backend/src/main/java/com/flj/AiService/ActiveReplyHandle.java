package com.flj.AiService;

import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import com.flj.entity.AiRequestRecord;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** One generation owns its subscription; SSE connections only observe its replay. */
public class ActiveReplyHandle {
    private final Long sessionId;
    private final long startedAt = System.currentTimeMillis();
    private final String requestId = UUID.randomUUID().toString();
    private final Sinks.Many<String> sink = Sinks.many().replay().all();
    private final Object emissionLock = new Object();
    private final CountDownLatch terminalSignal = new CountDownLatch(1);
    private Disposable disposable;
    private Runnable stopAction;
    private volatile boolean stopRequested;
    private volatile boolean discard;
    private volatile ReplyFailure failure;
    private boolean terminated;
    private volatile String clientRequestId;
    private volatile String requestFingerprint;
    private volatile AiRequestRecord persistentRequest;

    ActiveReplyHandle(Long sessionId) { this.sessionId = sessionId; }
    public Long getSessionId() { return sessionId; }
    public long getStartedAt() { return startedAt; }
    public String getRequestId() { return requestId; }
    public String getClientRequestId() { return clientRequestId; }
    public String getRequestFingerprint() { return requestFingerprint; }
    public AiRequestRecord getPersistentRequest() { return persistentRequest; }
    public void bindClientRequest(String clientRequestId, String fingerprint) {
        this.requestFingerprint = fingerprint;
        this.clientRequestId = clientRequestId;
    }
    public void setPersistentRequest(AiRequestRecord request) { this.persistentRequest = request; }
    public ReplyFailure getFailure() { return failure; }
    public Flux<String> asFlux() { return sink.asFlux(); }
    void emitNext(String fragment) { synchronized (emissionLock) { sink.tryEmitNext(fragment); } }
    void emitComplete() {
        synchronized (emissionLock) { sink.tryEmitComplete(); }
        terminalSignal.countDown();
    }
    void emitError(Throwable error) {
        if (error instanceof ReplyFailure replyFailure) { failure = replyFailure; }
        synchronized (emissionLock) { sink.tryEmitError(error); }
        terminalSignal.countDown();
    }

    /** Persistence and observer termination must finish before a stop can report success. */
    public boolean awaitTermination(long timeoutMillis) {
        try {
            return terminalSignal.await(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Ends observers when controller validation fails after the slot was claimed. */
    public void failBeforeStart(ReplyFailure failure) {
        terminateGeneration();
        emitError(failure);
    }

    void onStop(Runnable action) {
        boolean invoke;
        synchronized (this) {
            stopAction = action;
            invoke = stopRequested && !terminated;
        }
        if (invoke) { action.run(); }
    }

    /** The stop/start decision and subscription creation share the same gate. */
    synchronized boolean startIfAllowed(Supplier<Disposable> subscription) {
        if (stopRequested || terminated) { return false; }
        Disposable started = subscription.get();
        disposable = started;
        // A synchronous source may already have completed inside subscription.get().
        if (terminated && started != null) { started.dispose(); }
        return true;
    }

    void terminateGeneration() {
        Disposable current;
        synchronized (this) {
            terminated = true;
            current = disposable;
        }
        if (current != null) { current.dispose(); }
    }

    void requestStop(boolean discard) {
        Runnable action;
        synchronized (this) {
            this.discard |= discard;
            stopRequested = true;
            action = terminated ? null : stopAction;
        }
        if (action != null) { action.run(); }
    }

    boolean isStopRequested() { return stopRequested; }
    boolean isDiscard() { return discard; }
}
