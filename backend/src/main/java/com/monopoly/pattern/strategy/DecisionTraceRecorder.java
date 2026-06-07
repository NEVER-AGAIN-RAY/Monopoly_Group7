package com.monopoly.pattern.strategy;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.monopoly.controller.GameController;
import com.monopoly.dto.PlayActionRequest;
import com.monopoly.model.card.Card;
import com.monopoly.model.core.GameContext;
import com.monopoly.model.player.AIPlayer;
import com.monopoly.persistence.GameSessionMemento;
import com.monopoly.pattern.strategy.decision.DecisionTraceSink;
import com.monopoly.pattern.strategy.decision.SimulationDecisionCandidate;
import com.monopoly.pattern.strategy.decision.SimulationDecisionRequest;
import com.monopoly.pattern.strategy.decision.SimulationDecisionResult;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

final class DecisionTraceRecorder {

    static final Gson GSON = new Gson();
    static final AtomicLong TRACE_SEQUENCE = new AtomicLong();

    private DecisionTraceRecorder() {
    }

    static JsonObject promptContext(
            AIPlayer bot,
            GameContext context,
            List<AiHeuristics.AiPlayCandidate> candidates) {
        try {
            return JsonParser.parseString(
                    DeepSeekAiPlayStrategy.buildUserPrompt(bot, context, candidates)).getAsJsonObject();
        } catch (RuntimeException e) {
            return new JsonObject();
        }
    }

    static void recordPlayTrace(
            DecisionTraceSink traceSink,
            String traceSessionId,
            AIPlayer bot,
            long stateSequence,
            JsonObject contextJson,
            List<AiHeuristics.AiPlayCandidate> candidates,
            List<SearchLookaheadAiPlayStrategy.ScoredCandidate> scoredCandidates,
            SearchLookaheadAiPlayStrategy.ScoredCandidate modelBest,
            SearchLookaheadAiPlayStrategy.ScoredCandidate hardChoice,
            SearchLookaheadAiPlayStrategy.ScoredCandidate chosen,
            JsonObject correctorMetadata,
            SearchLookaheadAiPlayStrategy.ScoredCandidate rawRolloutBest,
            SearchLookaheadAiPlayStrategy.ScoredCandidate rolloutImmediateBest,
            SearchLookaheadAiPlayStrategy.ScoredCandidate rolloutGateChoice) {
        if (chosen == null || bot == null) {
            return;
        }
        String actorId = bot.getPlayerId();
        long seq = TRACE_SEQUENCE.incrementAndGet();
        List<SimulationDecisionCandidate> traceCandidates = new java.util.ArrayList<>();
        for (AiHeuristics.AiPlayCandidate candidate : candidates) {
            traceCandidates.add(new SimulationDecisionCandidate(
                    candidate.id(),
                    candidate.summary(),
                    GSON.toJsonTree(candidate.request()).getAsJsonObject()));
        }
        SimulationDecisionRequest request = new SimulationDecisionRequest(
                traceSessionId + "-" + actorId + "-LOOKAHEAD-" + seq,
                traceSessionId,
                actorId,
                "PLAY_CARD",
                stateSequence,
                contextJson == null ? new JsonObject() : contextJson,
                traceCandidates);
        JsonObject metadata = new JsonObject();
        metadata.addProperty("source", "lookahead");
        metadata.addProperty("score", chosen.score());
        metadata.addProperty("hardMargin", SearchConfig.HARD_MARGIN);
        metadata.addProperty("sameEffectHardTargetMargin", SearchConfig.SAME_EFFECT_HARD_TARGET_MARGIN);
        metadata.addProperty("hardRentFallbackMargin", SearchConfig.HARD_RENT_FALLBACK_MARGIN);
        metadata.addProperty("rolloutRemainingTurn", SearchConfig.ROLLOUT_REMAINING_TURN);
        metadata.addProperty("rolloutOverrideMargin", SearchConfig.ROLLOUT_OVERRIDE_MARGIN);
        metadata.addProperty("rolloutOverrideAllowedEffects", String.join(",", SearchConfig.ROLLOUT_OVERRIDE_ALLOWED_EFFECTS));
        metadata.addProperty("rolloutPolicy", SearchConfig.ROLLOUT_POLICY);
        addRolloutGateMetadata(metadata, rawRolloutBest, rolloutImmediateBest, rolloutGateChoice);
        if (modelBest != null) {
            metadata.addProperty("modelBestId", modelBest.candidate().id());
            metadata.addProperty("modelBestScore", modelBest.score());
        }
        if (hardChoice != null) {
            metadata.addProperty("hardChoiceId", hardChoice.candidate().id());
            metadata.addProperty("hardChoiceScore", hardChoice.score());
            metadata.addProperty("hardFallbackUsed",
                    chosen.candidate().id().equals(hardChoice.candidate().id())
                            && modelBest != null
                            && !modelBest.candidate().id().equals(hardChoice.candidate().id()));
            metadata.addProperty("sameEffectHardTargetFallbackUsed",
                    sameEffectHardTargetFallbackUsed(modelBest, hardChoice, chosen));
            metadata.addProperty("hardRentFallbackUsed",
                    hardRentFallbackUsed(modelBest, hardChoice, chosen));
        }
        JsonObject scores = new JsonObject();
        JsonObject adjustments = new JsonObject();
        for (SearchLookaheadAiPlayStrategy.ScoredCandidate scored : scoredCandidates) {
            scores.addProperty(scored.candidate().id(), scored.score());
            adjustments.addProperty(scored.candidate().id(), BoardEvaluator.candidateAdjustment(bot, scored.candidate()));
        }
        metadata.add("candidateScores", scores);
        metadata.add("candidateAdjustments", adjustments);
        if (correctorMetadata != null && correctorMetadata.size() > 0) {
            metadata.add("corrector", correctorMetadata);
        }
        try {
            traceSink.record(request, new SimulationDecisionResult(
                    request.getDecisionId(),
                    chosen.candidate().id(),
                    null,
                    metadata));
        } catch (RuntimeException ex) {
            AiBattleLogger.log("Lookahead",
                    "trace write skipped after successful choice: "
                            + ex.getClass().getSimpleName() + " " + ex.getMessage());
        }
    }

    static JsonObject contextWithSourcePolicy(
            AIPlayer bot,
            JsonObject baseContext,
            List<SearchLookaheadAiPlayStrategy.ScoredCandidate> scoredCandidates,
            SearchLookaheadAiPlayStrategy.ScoredCandidate modelBest,
            SearchLookaheadAiPlayStrategy.ScoredCandidate hardChoice,
            SearchLookaheadAiPlayStrategy.ScoredCandidate currentChoice,
            SearchLookaheadAiPlayStrategy.ScoredCandidate rawRolloutBest,
            SearchLookaheadAiPlayStrategy.ScoredCandidate rolloutImmediateBest,
            SearchLookaheadAiPlayStrategy.ScoredCandidate rolloutGateChoice) {
        JsonObject out = baseContext == null ? new JsonObject() : baseContext.deepCopy();
        JsonObject sourcePolicy = new JsonObject();
        if (currentChoice != null && currentChoice.candidate() != null) {
            sourcePolicy.addProperty("choiceId", currentChoice.candidate().id());
        }
        JsonObject metadata = new JsonObject();
        metadata.addProperty("source", "lookahead");
        metadata.addProperty("hardMargin", SearchConfig.HARD_MARGIN);
        metadata.addProperty("sameEffectHardTargetMargin", SearchConfig.SAME_EFFECT_HARD_TARGET_MARGIN);
        metadata.addProperty("hardRentFallbackMargin", SearchConfig.HARD_RENT_FALLBACK_MARGIN);
        metadata.addProperty("rolloutRemainingTurn", SearchConfig.ROLLOUT_REMAINING_TURN);
        metadata.addProperty("rolloutOverrideMargin", SearchConfig.ROLLOUT_OVERRIDE_MARGIN);
        metadata.addProperty("rolloutOverrideAllowedEffects", String.join(",", SearchConfig.ROLLOUT_OVERRIDE_ALLOWED_EFFECTS));
        metadata.addProperty("rolloutPolicy", SearchConfig.ROLLOUT_POLICY);
        addRolloutGateMetadata(metadata, rawRolloutBest, rolloutImmediateBest, rolloutGateChoice);
        if (modelBest != null && modelBest.candidate() != null) {
            metadata.addProperty("modelBestId", modelBest.candidate().id());
            metadata.addProperty("modelBestScore", modelBest.score());
        }
        if (hardChoice != null && hardChoice.candidate() != null) {
            metadata.addProperty("hardChoiceId", hardChoice.candidate().id());
            metadata.addProperty("hardChoiceScore", hardChoice.score());
            metadata.addProperty("hardFallbackUsed",
                    currentChoice != null
                            && currentChoice.candidate() != null
                            && currentChoice.candidate().id().equals(hardChoice.candidate().id())
                            && modelBest != null
                            && modelBest.candidate() != null
                            && !modelBest.candidate().id().equals(hardChoice.candidate().id()));
            metadata.addProperty("sameEffectHardTargetFallbackUsed",
                    sameEffectHardTargetFallbackUsed(modelBest, hardChoice, currentChoice));
            metadata.addProperty("hardRentFallbackUsed",
                    hardRentFallbackUsed(modelBest, hardChoice, currentChoice));
        }
        JsonObject scores = new JsonObject();
        JsonObject adjustments = new JsonObject();
        if (scoredCandidates != null) {
            for (SearchLookaheadAiPlayStrategy.ScoredCandidate scored : scoredCandidates) {
                if (scored == null || scored.candidate() == null) {
                    continue;
                }
                scores.addProperty(scored.candidate().id(), scored.score());
                adjustments.addProperty(scored.candidate().id(), BoardEvaluator.candidateAdjustment(bot, scored.candidate()));
            }
        }
        metadata.add("candidateScores", scores);
        metadata.add("candidateAdjustments", adjustments);
        sourcePolicy.add("metadata", metadata);
        out.add("sourcePolicy", sourcePolicy);
        return out;
    }

    static JsonObject withCounterfactualMemento(
            GameController controller,
            JsonObject traceContext) {
        JsonObject out = traceContext == null ? new JsonObject() : traceContext.deepCopy();
        if (!SearchConfig.TRACE_INCLUDE_COUNTERFACTUAL_MEMENTO || controller == null) {
            return out;
        }
        JsonObject counterfactual = new JsonObject();
        counterfactual.addProperty("schema", "monopoly-deal-counterfactual-v1");
        counterfactual.addProperty("capture", "before_choice");
        counterfactual.addProperty("mementoJson", GameSessionMemento.capture(controller).toJson());
        out.add("counterfactual", counterfactual);
        return out;
    }

    static JsonObject auxiliaryContextWithMemento(String prompt, GameContext context) {
        JsonObject out = parsePrompt(prompt);
        if (context == null) {
            return out;
        }
        out.addProperty("roundNumber", context.getRoundNumber());
        out.addProperty("currentTurnPhase", currentPhase(context));
        out.addProperty("currentTurnPlayerId", context.getCurrentTurnPlayerId());
        String memento = SearchConfig.trim(context.getAuxiliaryDecisionMementoJson());
        if (!memento.isBlank()) {
            JsonObject counterfactual = new JsonObject();
            counterfactual.addProperty("schema", "monopoly-deal-counterfactual-v1");
            counterfactual.addProperty("capture", "before_choice");
            counterfactual.addProperty("mementoJson", memento);
            out.add("counterfactual", counterfactual);
        }
        return out;
    }

    static JsonObject parsePrompt(String prompt) {
        try {
            return JsonParser.parseString(prompt).getAsJsonObject();
        } catch (RuntimeException ex) {
            JsonObject fallback = new JsonObject();
            fallback.addProperty("rawPrompt", prompt == null ? "" : prompt);
            return fallback;
        }
    }

    static JsonObject cardIdsPayload(List<? extends Card> cards, int amountPaid) {
        JsonObject payload = new JsonObject();
        JsonArray ids = new JsonArray();
        if (cards != null) {
            for (Card card : cards) {
                ids.add(card.getId());
            }
        }
        payload.add("cardIds", ids);
        if (amountPaid > 0) {
            payload.addProperty("amountPaidM", amountPaid);
        }
        return payload;
    }

    static void recordResponseTrace(
            DecisionTraceSink traceSink,
            String traceSessionId,
            AIPlayer bot,
            GameContext context,
            boolean counterRole,
            AiHeuristics.AiResponseDecision fallbackDecision,
            AiHeuristics.AiResponseDecision chosen) {
        if (!shouldRecordAuxiliaryTrace(bot, context, traceSink)) {
            return;
        }
        List<SimulationDecisionCandidate> candidates = new java.util.ArrayList<>();
        JsonObject passPayload = new JsonObject();
        passPayload.addProperty("playJustSayNo", false);
        candidates.add(new SimulationDecisionCandidate(
                "PASS",
                "Pass Just Say No response.",
                passPayload));
        PlayActionRequest playRequest = responseTracePlayRequest(fallbackDecision, chosen);
        if (playRequest != null) {
            JsonObject playPayload = GSON.toJsonTree(playRequest).getAsJsonObject();
            playPayload.addProperty("playJustSayNo", true);
            candidates.add(new SimulationDecisionCandidate(
                    "PLAY_JSN",
                    "Play Just Say No.",
                    playPayload));
        }
        String choiceId = chosen != null && chosen.playWaiver() ? "PLAY_JSN" : "PASS";
        JsonObject contextJson = auxiliaryContextWithMemento(
                DeepSeekAiPlayStrategy.buildResponsePrompt(bot, context, counterRole, fallbackDecision),
                context);
        contextJson.addProperty("counterRole", counterRole);
        contextJson.addProperty("fallbackPlayJustSayNo",
                fallbackDecision != null && fallbackDecision.playWaiver());
        JsonObject metadata = new JsonObject();
        metadata.addProperty("source", "lookahead");
        metadata.addProperty("fallbackChoiceId",
                fallbackDecision != null && fallbackDecision.playWaiver() ? "PLAY_JSN" : "PASS");
        metadata.addProperty("responseTenantThreshold", SearchConfig.RESPONSE_TENANT_THRESHOLD);
        metadata.addProperty("responseCounterThreshold", SearchConfig.RESPONSE_COUNTER_THRESHOLD);
        recordAuxiliaryTrace(traceSink, traceSessionId, bot, context, "JUST_SAY_NO", contextJson, candidates, choiceId, metadata);
    }

    static PlayActionRequest responseTracePlayRequest(
            AiHeuristics.AiResponseDecision fallbackDecision,
            AiHeuristics.AiResponseDecision chosen) {
        if (chosen != null && chosen.request() != null) {
            return chosen.request();
        }
        if (fallbackDecision != null && fallbackDecision.request() != null) {
            return fallbackDecision.request();
        }
        return null;
    }

    static boolean shouldRecordAuxiliaryTrace(AIPlayer bot, GameContext context, DecisionTraceSink traceSink) {
        return bot != null && context != null && traceSink != null && traceSink != DecisionTraceSink.NONE;
    }

    static void recordAuxiliaryTrace(
            DecisionTraceSink traceSink,
            String traceSessionId,
            AIPlayer bot,
            GameContext context,
            String kind,
            JsonObject contextJson,
            List<SimulationDecisionCandidate> candidates,
            String choiceId,
            JsonObject metadata) {
        if (candidates == null || candidates.isEmpty() || choiceId == null || choiceId.isBlank()) {
            return;
        }
        String actorId = bot.getPlayerId();
        long seq = TRACE_SEQUENCE.incrementAndGet();
        SimulationDecisionRequest request = new SimulationDecisionRequest(
                traceSessionId + "-" + actorId + "-" + kind + "-" + seq,
                traceSessionId,
                actorId,
                kind,
                context == null ? 0L : context.getStateSequence(),
                contextJson == null ? new JsonObject() : contextJson,
                candidates);
        try {
            traceSink.record(request, new SimulationDecisionResult(
                    request.getDecisionId(),
                    choiceId,
                    null,
                    metadata == null ? new JsonObject() : metadata));
        } catch (RuntimeException ex) {
            AiBattleLogger.log("Lookahead",
                    "auxiliary trace write skipped after successful choice: "
                            + ex.getClass().getSimpleName() + " " + ex.getMessage());
        }
    }

    static void addRolloutGateMetadata(
            JsonObject metadata,
            SearchLookaheadAiPlayStrategy.ScoredCandidate rawRolloutBest,
            SearchLookaheadAiPlayStrategy.ScoredCandidate rolloutImmediateBest,
            SearchLookaheadAiPlayStrategy.ScoredCandidate rolloutGateChoice) {
        if (metadata == null || !SearchConfig.ROLLOUT_REMAINING_TURN || SearchConfig.ROLLOUT_OVERRIDE_MARGIN <= 0d) {
            return;
        }
        if (rawRolloutBest != null && rawRolloutBest.candidate() != null) {
            metadata.addProperty("rolloutRawBestId", rawRolloutBest.candidate().id());
            metadata.addProperty("rolloutRawBestScore", rawRolloutBest.score());
            metadata.addProperty("rolloutRawBestEffect", BoardEvaluator.candidateEffect(null, rawRolloutBest.candidate()));
        }
        if (rolloutImmediateBest != null && rolloutImmediateBest.candidate() != null) {
            metadata.addProperty("rolloutImmediateBestId", rolloutImmediateBest.candidate().id());
            metadata.addProperty("rolloutImmediateBestScore", rolloutImmediateBest.score());
            metadata.addProperty("rolloutImmediateBestEffect", BoardEvaluator.candidateEffect(null, rolloutImmediateBest.candidate()));
        }
        if (rawRolloutBest != null
                && rawRolloutBest.candidate() != null
                && rolloutImmediateBest != null
                && rolloutImmediateBest.candidate() != null) {
            double scoreGap = rawRolloutBest.score() - rolloutImmediateBest.score();
            metadata.addProperty("rolloutOverrideScoreGap", scoreGap);
            metadata.addProperty("rolloutOverrideEffectAllowed",
                    SearchLookaheadAiPlayStrategy.rolloutOverrideEffectAllowed(rawRolloutBest.candidate()));
            metadata.addProperty("rolloutOverrideTransitionAllowed",
                    SearchLookaheadAiPlayStrategy.rolloutOverrideTransitionAllowed(rolloutImmediateBest.candidate(), rawRolloutBest.candidate()));
            metadata.addProperty("rolloutOverrideSameCandidate",
                    rawRolloutBest.candidate().id().equals(rolloutImmediateBest.candidate().id()));
        }
        if (rolloutGateChoice != null && rolloutGateChoice.candidate() != null) {
            metadata.addProperty("rolloutGateChoiceId", rolloutGateChoice.candidate().id());
            metadata.addProperty("rolloutGateChoiceScore", rolloutGateChoice.score());
            metadata.addProperty("rolloutGateChoiceEffect", BoardEvaluator.candidateEffect(null, rolloutGateChoice.candidate()));
        }
        metadata.addProperty("rolloutOverrideUsed",
                rawRolloutBest != null
                        && rawRolloutBest.candidate() != null
                        && rolloutGateChoice != null
                        && rolloutGateChoice.candidate() != null
                        && rawRolloutBest.candidate().id().equals(rolloutGateChoice.candidate().id())
                        && (rolloutImmediateBest == null
                        || rolloutImmediateBest.candidate() == null
                        || !rawRolloutBest.candidate().id().equals(rolloutImmediateBest.candidate().id())));
    }

    static boolean sameEffectHardTargetFallbackUsed(
            SearchLookaheadAiPlayStrategy.ScoredCandidate modelBest,
            SearchLookaheadAiPlayStrategy.ScoredCandidate hardChoice,
            SearchLookaheadAiPlayStrategy.ScoredCandidate currentChoice) {
        return modelBest != null
                && modelBest.candidate() != null
                && hardChoice != null
                && hardChoice.candidate() != null
                && currentChoice != null
                && currentChoice.candidate() != null
                && currentChoice.candidate().id().equals(hardChoice.candidate().id())
                && !modelBest.candidate().id().equals(hardChoice.candidate().id())
                && SearchLookaheadAiPlayStrategy.sameEffectDifferentTarget(modelBest.candidate(), hardChoice.candidate());
    }

    static boolean hardRentFallbackUsed(
            SearchLookaheadAiPlayStrategy.ScoredCandidate modelBest,
            SearchLookaheadAiPlayStrategy.ScoredCandidate hardChoice,
            SearchLookaheadAiPlayStrategy.ScoredCandidate currentChoice) {
        if (modelBest == null
                || modelBest.candidate() == null
                || hardChoice == null
                || hardChoice.candidate() == null
                || currentChoice == null
                || currentChoice.candidate() == null
                || !currentChoice.candidate().id().equals(hardChoice.candidate().id())
                || modelBest.candidate().id().equals(hardChoice.candidate().id())) {
            return false;
        }
        String hardEffect = BoardEvaluator.candidateEffect(null, hardChoice.candidate());
        String modelEffect = BoardEvaluator.candidateEffect(null, modelBest.candidate());
        return ("RENT".equals(hardEffect) || "RENT_DUAL".equals(hardEffect))
                && ("DEPLOY".equals(modelEffect) || "PASS_GO".equals(modelEffect));
    }

    private static String currentPhase(GameContext context) {
        return context.getCurrentTurnPhase() == null
                ? ""
                : context.getCurrentTurnPhase().trim().toUpperCase(Locale.ROOT);
    }

    }