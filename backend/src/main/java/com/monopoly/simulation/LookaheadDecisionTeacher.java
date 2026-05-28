package com.monopoly.simulation;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.monopoly.controller.GameController;
import com.monopoly.dto.GameStateSnapshot;
import com.monopoly.dto.PlayActionRequest;
import com.monopoly.model.core.GameContext;
import com.monopoly.model.player.AIPlayer;
import com.monopoly.model.player.Player;
import com.monopoly.pattern.observer.GameUpdateObserver;
import com.monopoly.pattern.observer.GameUpdateSubject;
import com.monopoly.pattern.strategy.AiHeuristics;
import com.monopoly.pattern.strategy.SearchLookaheadAiPlayStrategy;
import com.monopoly.persistence.GameSessionMemento;

import java.util.ArrayList;
import java.util.List;

/**
 * Replays a traced memento and lets the current lookahead champion relabel the
 * original legal play envelope.
 */
public final class LookaheadDecisionTeacher implements SimulationDecisionTeacher {

    private static final Gson GSON = new Gson();
    private static final String RESULT_SOURCE =
            System.getProperty("monopoly.lookaheadTeacher.resultSource", "lookahead").trim();

    @Override
    public List<SimulationDecisionResult> decideBatch(List<SimulationDecisionRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            return List.of();
        }
        List<SimulationDecisionResult> out = new ArrayList<>();
        for (SimulationDecisionRequest request : requests) {
            out.add(decideOne(request));
        }
        return out;
    }

    private SimulationDecisionResult decideOne(SimulationDecisionRequest request) {
        if (!"PLAY_CARD".equals(request.getDecisionKind())) {
            throw new IllegalArgumentException(
                    "lookahead relabel requires PLAY_CARD decisions, got " + request.getDecisionKind());
        }
        String mementoJson = mementoJson(request);
        CapturingSink sink = new CapturingSink();
        GameController controller = null;
        try {
            GameSessionMemento memento = GameSessionMemento.fromJson(mementoJson);
            controller = GameSessionMemento.restore(new NoopSubject(), memento);
            controller.setSuppressAiAutoContinuation(true);
            controller.resetSessionClockForSimulation();
            Player actor = playerById(controller, request.getActorPlayerId());
            if (!(actor instanceof AIPlayer ai)) {
                throw new IllegalArgumentException("actor is not an AI player: " + request.getActorPlayerId());
            }
            GameContext context = controller.refreshAndGetAiDecisionContextForSimulation();
            SearchLookaheadAiPlayStrategy strategy =
                    new SearchLookaheadAiPlayStrategy(sink, request.getSessionId() + "-lookahead-relabel");
            strategy.tryPlayOneCard(ai, context, controller);
            if (sink.result != null && request.hasCandidate(sink.result.getChoiceId())) {
                JsonObject metadata = sink.result.getMetadata();
                metadata.addProperty("source", resultSource());
                metadata.addProperty("relabelMode", "lookahead_strategy_replay");
                return new SimulationDecisionResult(
                        request.getDecisionId(),
                        sink.result.getChoiceId(),
                        null,
                        metadata);
            }
            return promptPriorFallback(request, mementoJson);
        } finally {
            if (controller != null) {
                controller.shutdown();
            }
        }
    }

    private static SimulationDecisionResult promptPriorFallback(
            SimulationDecisionRequest request,
            String mementoJson) {
        GameController controller = null;
        try {
            GameSessionMemento memento = GameSessionMemento.fromJson(mementoJson);
            controller = GameSessionMemento.restore(new NoopSubject(), memento);
            controller.setSuppressAiAutoContinuation(true);
            controller.resetSessionClockForSimulation();
            Player actor = playerById(controller, request.getActorPlayerId());
            if (!(actor instanceof AIPlayer ai)) {
                throw new IllegalArgumentException("actor is not an AI player: " + request.getActorPlayerId());
            }
            JsonObject prior = SearchLookaheadAiPlayStrategy.promptPrior(
                    controller,
                    ai,
                    playCandidates(request));
            String choiceId = string(prior, "bestChoiceId");
            if (choiceId.isBlank() || !request.hasCandidate(choiceId)) {
                throw new IllegalStateException(
                        "lookahead did not choose a candidate from traced envelope for " + request.getDecisionId());
            }
            JsonObject metadata = new JsonObject();
            metadata.addProperty("source", resultSource());
            metadata.addProperty("relabelMode", "lookahead_prompt_prior_fallback");
            metadata.addProperty("score", prior.has("bestScore") ? prior.get("bestScore").getAsDouble() : 0d);
            if (prior.has("candidateScores") && prior.get("candidateScores").isJsonObject()) {
                metadata.add("candidateScores", prior.getAsJsonObject("candidateScores").deepCopy());
            }
            if (prior.has("candidateAdjustments") && prior.get("candidateAdjustments").isJsonObject()) {
                metadata.add("candidateAdjustments", prior.getAsJsonObject("candidateAdjustments").deepCopy());
            }
            return new SimulationDecisionResult(request.getDecisionId(), choiceId, null, metadata);
        } finally {
            if (controller != null) {
                controller.shutdown();
            }
        }
    }

    private static String resultSource() {
        return RESULT_SOURCE.isBlank() ? "lookahead" : RESULT_SOURCE;
    }

    private static List<AiHeuristics.AiPlayCandidate> playCandidates(SimulationDecisionRequest request) {
        List<AiHeuristics.AiPlayCandidate> out = new ArrayList<>();
        for (SimulationDecisionCandidate candidate : request.getCandidates()) {
            out.add(new AiHeuristics.AiPlayCandidate(
                    candidate.getId(),
                    GSON.fromJson(candidate.getPayload(), PlayActionRequest.class),
                    candidate.getSummary()));
        }
        return out;
    }

    private static String mementoJson(SimulationDecisionRequest request) {
        JsonObject context = request.getContextJson();
        if (!context.has("counterfactual") || !context.get("counterfactual").isJsonObject()) {
            throw new IllegalArgumentException("lookahead relabel requires context.counterfactual.mementoJson");
        }
        String value = string(context.getAsJsonObject("counterfactual"), "mementoJson");
        if (value.isBlank()) {
            throw new IllegalArgumentException("lookahead relabel requires context.counterfactual.mementoJson");
        }
        return value;
    }

    private static Player playerById(GameController controller, String playerId) {
        if (controller == null || playerId == null || playerId.isBlank()) {
            return null;
        }
        for (Player player : controller.getSessionPlayersView()) {
            if (playerId.equals(player.getPlayerId())) {
                return player;
            }
        }
        return null;
    }

    private static String string(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return "";
        }
        return object.get(key).getAsString();
    }

    private static final class CapturingSink implements DecisionTraceSink {
        private SimulationDecisionResult result;

        @Override
        public void record(SimulationDecisionRequest request, SimulationDecisionResult result) {
            this.result = result;
        }
    }

    private static final class NoopSubject implements GameUpdateSubject {
        @Override
        public void registerObserver(GameUpdateObserver observer) {
        }

        @Override
        public void unregisterObserver(GameUpdateObserver observer) {
        }

        @Override
        public void notifyStateChanged(GameStateSnapshot snapshot) {
        }
    }
}
