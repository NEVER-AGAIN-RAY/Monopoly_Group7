package com.monopoly.simulation;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DecisionBrokerTest {

    @Test
    void batchesConcurrentRequestsIntoOneTeacherCall() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<Integer> batchSize = new AtomicReference<>(0);
        SimulationDecisionTeacher teacher = requests -> {
            calls.incrementAndGet();
            batchSize.set(requests.size());
            List<SimulationDecisionResult> out = new ArrayList<>();
            for (SimulationDecisionRequest request : requests) {
                out.add(new SimulationDecisionResult(request.getDecisionId(), "c1"));
            }
            return out;
        };

        try (DecisionBroker broker = new DecisionBroker(teacher, 8, Duration.ofMillis(80))) {
            CompletableFuture<SimulationDecisionResult> first = broker.submit(request("d1"));
            CompletableFuture<SimulationDecisionResult> second = broker.submit(request("d2"));

            assertEquals("c1", first.get(1, TimeUnit.SECONDS).getChoiceId());
            assertEquals("c1", second.get(1, TimeUnit.SECONDS).getChoiceId());
            assertEquals(1, calls.get());
            assertEquals(2, batchSize.get());
            assertEquals(2, broker.getDecisionCount());
            assertEquals(1, broker.getTeacherCallCount());
        }
    }

    @Test
    void rejectsTeacherChoiceOutsideLegalCandidates() throws Exception {
        SimulationDecisionTeacher teacher = requests -> List.of(
                new SimulationDecisionResult(requests.get(0).getDecisionId(), "not-legal"));

        try (DecisionBroker broker = new DecisionBroker(teacher, 4, Duration.ofMillis(1))) {
            CompletableFuture<SimulationDecisionResult> future = broker.submit(request("d1"));

            ExecutionException ex = org.junit.jupiter.api.Assertions.assertThrows(
                    ExecutionException.class,
                    () -> future.get(1, TimeUnit.SECONDS));
            assertTrue(ex.getCause() instanceof IllegalArgumentException);
        }
    }

    @Test
    void quotaSkipsTeacherAfterKindLimitButCompletesDecision() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        SimulationDecisionTeacher teacher = requests -> {
            calls.incrementAndGet();
            return List.of(new SimulationDecisionResult(requests.get(0).getDecisionId(), "c1"));
        };

        try (DecisionBroker broker = new DecisionBroker(
                teacher,
                1,
                Duration.ofMillis(1),
                DecisionTraceSink.NONE,
                Map.of("PLAY_CARD", 1L))) {
            assertEquals("c1", broker.submit(request("d1")).get(1, TimeUnit.SECONDS).getChoiceId());
            assertEquals("c1", broker.submit(request("d2")).get(1, TimeUnit.SECONDS).getChoiceId());

            assertEquals(1, calls.get());
            assertEquals(1, broker.getDecisionCount());
            assertEquals(1L, broker.getDecisionCountsByKind().get("PLAY_CARD"));
        }
    }

    private static SimulationDecisionRequest request(String id) {
        return new SimulationDecisionRequest(
                id,
                "session-1",
                "ai-1",
                "PLAY_CARD",
                1L,
                new JsonObject(),
                List.of(new SimulationDecisionCandidate("c1", "first", new JsonObject())));
    }
}
