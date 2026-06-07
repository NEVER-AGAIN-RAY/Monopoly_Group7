package com.monopoly.controller;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

final class ResponseTimeoutScheduler {

    private final ScheduledExecutorService executor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "effect-response-timeout");
                t.setDaemon(true);
                return t;
            });
    private volatile ScheduledFuture<?> pendingFuture;
    private final int windowSeconds;

    ResponseTimeoutScheduler(int windowSeconds) {
        this.windowSeconds = windowSeconds;
    }

    void schedule(Runnable task) {
        cancel();
        pendingFuture = executor.schedule(task, windowSeconds, TimeUnit.SECONDS);
    }

    void cancel() {
        if (pendingFuture != null) {
            pendingFuture.cancel(false);
            pendingFuture = null;
        }
    }

    void shutdown() {
        cancel();
        executor.shutdownNow();
    }
}