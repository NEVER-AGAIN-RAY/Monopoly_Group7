package com.monopoly.simulation;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Collects independent game decisions into small batches and dispatches labels
 * back to the waiting game workers.
 */
public final class DecisionBroker implements AutoCloseable {

    private final SimulationDecisionTeacher teacher;
    private final int maxBatchSize;
    private final long maxWaitNanos;
    private final DecisionTraceSink traceSink;
    private final BlockingQueue<QueuedDecision> queue = new LinkedBlockingQueue<>();
    private final ExecutorService worker;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong teacherCallCount = new AtomicLong();
    private final AtomicLong decisionCount = new AtomicLong();
    private final ConcurrentHashMap<String, AtomicLong> decisionCountsByKind = new ConcurrentHashMap<>();
    private final Map<String, Long> maxRecordedByKind;
    private final SimulationDecisionTeacher quotaFallbackTeacher = new HeuristicDecisionTeacher();

    public DecisionBroker(
            SimulationDecisionTeacher teacher,
            int maxBatchSize,
            Duration maxWait) {
        this(teacher, maxBatchSize, maxWait, DecisionTraceSink.NONE);
    }

    public DecisionBroker(
            SimulationDecisionTeacher teacher,
            int maxBatchSize,
            Duration maxWait,
            DecisionTraceSink traceSink) {
        this(teacher, maxBatchSize, maxWait, traceSink, Map.of());
    }

    public DecisionBroker(
            SimulationDecisionTeacher teacher,
            int maxBatchSize,
            Duration maxWait,
            DecisionTraceSink traceSink,
            Map<String, Long> maxRecordedByKind) {
        this.teacher = Objects.requireNonNull(teacher, "teacher");
        this.maxBatchSize = Math.max(1, maxBatchSize);
        Duration wait = maxWait == null ? Duration.ofMillis(50) : maxWait;
        this.maxWaitNanos = Math.max(0L, wait.toNanos());
        this.traceSink = traceSink == null ? DecisionTraceSink.NONE : traceSink;
        this.maxRecordedByKind = normalizeQuotas(maxRecordedByKind);
        this.worker = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "simulation-decision-broker");
            t.setDaemon(true);
            return t;
        });
        this.worker.submit(this::runLoop);
    }

    public CompletableFuture<SimulationDecisionResult> submit(SimulationDecisionRequest request) {
        Objects.requireNonNull(request, "request");
        if (closed.get()) {
            CompletableFuture<SimulationDecisionResult> f = new CompletableFuture<>();
            f.completeExceptionally(new IllegalStateException("decision broker is closed"));
            return f;
        }
        CompletableFuture<SimulationDecisionResult> future = new CompletableFuture<>();
        queue.offer(new QueuedDecision(request, future));
        return future;
    }

    public SimulationDecisionResult submitAndWait(
            SimulationDecisionRequest request,
            Duration timeout) throws Exception {
        Duration effectiveTimeout = timeout == null ? Duration.ofSeconds(20) : timeout;
        return submit(request).get(
                Math.max(1L, effectiveTimeout.toMillis()),
                TimeUnit.MILLISECONDS);
    }

    public long getTeacherCallCount() {
        return teacherCallCount.get();
    }

    public long getDecisionCount() {
        return decisionCount.get();
    }

    public Map<String, Long> getDecisionCountsByKind() {
        Map<String, Long> out = new HashMap<>();
        for (Map.Entry<String, AtomicLong> entry : decisionCountsByKind.entrySet()) {
            out.put(entry.getKey(), entry.getValue().get());
        }
        return Map.copyOf(out);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        worker.shutdownNow();
        List<QueuedDecision> leftovers = new ArrayList<>();
        queue.drainTo(leftovers);
        for (QueuedDecision q : leftovers) {
            q.future().completeExceptionally(new IllegalStateException("decision broker closed"));
        }
        try {
            traceSink.close();
        } catch (Exception e) {
            throw new IllegalStateException("failed to close decision trace sink", e);
        }
    }

    private void runLoop() {
        while (!closed.get() || !queue.isEmpty()) {
            try {
                QueuedDecision first = queue.poll(100, TimeUnit.MILLISECONDS);
                if (first == null) {
                    continue;
                }
                List<QueuedDecision> batch = new ArrayList<>();
                batch.add(first);
                long deadline = System.nanoTime() + maxWaitNanos;
                while (batch.size() < maxBatchSize) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0L) {
                        break;
                    }
                    QueuedDecision next = queue.poll(remaining, TimeUnit.NANOSECONDS);
                    if (next == null) {
                        break;
                    }
                    batch.add(next);
                }
                processBatch(batch);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void processBatch(List<QueuedDecision> batch) {
        if (batch.isEmpty()) {
            return;
        }
        List<QueuedDecision> recordable = new ArrayList<>();
        for (QueuedDecision q : batch) {
            if (!reserveQuota(q.request())) {
                completeWithQuotaFallback(q);
            } else {
                recordable.add(q);
            }
        }
        if (recordable.isEmpty()) {
            return;
        }
        List<SimulationDecisionRequest> requests = recordable.stream()
                .map(QueuedDecision::request)
                .toList();
        try {
            List<SimulationDecisionResult> results = teacher.decideBatch(requests);
            teacherCallCount.incrementAndGet();
            Map<String, SimulationDecisionResult> byDecision = new HashMap<>();
            if (results != null) {
                for (SimulationDecisionResult result : results) {
                    byDecision.put(result.getDecisionId(), result);
                }
            }
            for (QueuedDecision q : recordable) {
                SimulationDecisionResult result = byDecision.get(q.request().getDecisionId());
                if (result == null) {
                    releaseQuota(q.request());
                    q.future().completeExceptionally(new TimeoutException(
                            "teacher returned no result for " + q.request().getDecisionId()));
                    continue;
                }
                if (!q.request().hasCandidate(result.getChoiceId())) {
                    releaseQuota(q.request());
                    q.future().completeExceptionally(new IllegalArgumentException(
                            "teacher chose invalid candidate " + result.getChoiceId()
                                    + " for " + q.request().getDecisionId()));
                    continue;
                }
                traceSink.record(q.request(), result);
                decisionCount.incrementAndGet();
                q.future().complete(result);
            }
        } catch (Exception e) {
            for (QueuedDecision q : recordable) {
                releaseQuota(q.request());
            }
            for (QueuedDecision q : recordable) {
                q.future().completeExceptionally(e);
            }
        }
    }

    private boolean reserveQuota(SimulationDecisionRequest request) {
        Long limit = maxRecordedByKind.get(normalizeKind(request.getDecisionKind()));
        if (limit == null || limit <= 0L) {
            return true;
        }
        AtomicLong counter = decisionCountsByKind
                .computeIfAbsent(normalizeKind(request.getDecisionKind()), ignored -> new AtomicLong());
        while (true) {
            long current = counter.get();
            if (current >= limit) {
                return false;
            }
            if (counter.compareAndSet(current, current + 1L)) {
                return true;
            }
        }
    }

    private void completeWithQuotaFallback(QueuedDecision q) {
        try {
            SimulationDecisionResult result = quotaFallbackTeacher.decideBatch(List.of(q.request())).get(0);
            q.future().complete(result);
        } catch (Exception e) {
            q.future().completeExceptionally(e);
        }
    }

    private void releaseQuota(SimulationDecisionRequest request) {
        Long limit = maxRecordedByKind.get(normalizeKind(request.getDecisionKind()));
        if (limit == null || limit <= 0L) {
            return;
        }
        decisionCountsByKind
                .computeIfAbsent(normalizeKind(request.getDecisionKind()), ignored -> new AtomicLong())
                .updateAndGet(value -> Math.max(0L, value - 1L));
    }

    private static Map<String, Long> normalizeQuotas(Map<String, Long> quotas) {
        if (quotas == null || quotas.isEmpty()) {
            return Map.of();
        }
        Map<String, Long> out = new HashMap<>();
        for (Map.Entry<String, Long> entry : quotas.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null || entry.getValue() <= 0L) {
                continue;
            }
            out.put(normalizeKind(entry.getKey()), entry.getValue());
        }
        return Map.copyOf(out);
    }

    private static String normalizeKind(String kind) {
        return kind == null ? "" : kind.trim().toUpperCase(java.util.Locale.ROOT);
    }

    private record QueuedDecision(
            SimulationDecisionRequest request,
            CompletableFuture<SimulationDecisionResult> future) {
    }
}
