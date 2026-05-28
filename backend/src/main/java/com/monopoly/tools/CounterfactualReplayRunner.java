package com.monopoly.tools;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.monopoly.controller.GameController;
import com.monopoly.dto.GameStateSnapshot;
import com.monopoly.dto.PlayActionRequest;
import com.monopoly.model.core.AiGameBridge;
import com.monopoly.model.core.GameContext;
import com.monopoly.model.effects.EffectStackEntry;
import com.monopoly.model.effects.StackResponseState;
import com.monopoly.model.player.AIPlayer;
import com.monopoly.model.player.Player;
import com.monopoly.pattern.observer.GameUpdateObserver;
import com.monopoly.pattern.observer.GameUpdateSubject;
import com.monopoly.pattern.strategy.AiHeuristics;
import com.monopoly.pattern.strategy.AiPlayStrategy;
import com.monopoly.pattern.strategy.HardAiPlayStrategy;
import com.monopoly.pattern.strategy.SearchLookaheadAiPlayStrategy;
import com.monopoly.persistence.GameSessionMemento;
import com.monopoly.simulation.JsonlDecisionTraceSink;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Replays one stored decision point from each legal candidate and rolls the
 * rest of the game forward with deterministic local hard policies.
 */
public final class CounterfactualReplayRunner {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int DEFAULT_MAX_ROWS = 20;
    private static final int DEFAULT_MAX_SNAPSHOTS = 320;

    private CounterfactualReplayRunner() {
    }

    public static void main(String[] args) throws IOException {
        Config config = Config.fromProperties();
        JsonArray decisions = new JsonArray();
        int rowsRead = 0;
        int rowsWithMemento = 0;
        int rowsReplayed = 0;
        int rowsSkipped = 0;
        int rowsFiltered = 0;
        PrintStream originalOut = System.out;
        PrintStream quietOut = config.quiet()
                ? new PrintStream(new ByteArrayOutputStream(), false, StandardCharsets.UTF_8)
                : null;
        if (quietOut != null) {
            System.setOut(quietOut);
        }
        try (BufferedReader reader = Files.newBufferedReader(config.inputPath(), StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                rowsRead++;
                JsonObject row = parseRow(line, rowsRead);
                JsonObject request = object(row, "request");
                String decisionKind = string(request, "decisionKind");
                if (!config.includesDecisionKind(decisionKind)) {
                    rowsFiltered++;
                    continue;
                }
                if (config.maxRows() > 0 && rowsReplayed >= config.maxRows()) {
                    break;
                }
                JsonObject context = object(request, "context");
                JsonObject counterfactual = object(context, "counterfactual");
                String mementoJson = string(counterfactual, "mementoJson");
                if (mementoJson.isBlank()) {
                    rowsSkipped++;
                    continue;
                }
                rowsWithMemento++;
                JsonObject decision = replayDecision(row, config);
                decisions.add(decision);
                rowsReplayed++;
            }
        } finally {
            if (quietOut != null) {
                System.setOut(originalOut);
                quietOut.close();
            }
        }

        JsonObject report = new JsonObject();
        report.addProperty("schema", "monopoly-deal-counterfactual-replay-report-v1");
        report.addProperty("createdAt", Instant.now().toString());
        report.addProperty("inputPath", config.inputPath().toString());
        report.addProperty("rowsRead", rowsRead);
        report.addProperty("rowsWithMemento", rowsWithMemento);
        report.addProperty("rowsSkipped", rowsSkipped);
        report.addProperty("rowsFiltered", rowsFiltered);
        report.addProperty("rowsReplayed", rowsReplayed);
        report.addProperty("maxRows", config.maxRows());
        report.addProperty("maxSnapshotsPerCandidate", config.maxSnapshotsPerCandidate());
        report.addProperty("maxCandidatesPerDecision", config.maxCandidatesPerDecision());
        report.addProperty("rollForwardPolicy", config.rollForwardPolicy());
        report.add("decisionKinds", decisionKindsJson(config.decisionKinds()));
        report.add("summary", summary(decisions));
        report.add("decisions", decisions);

        Path parent = config.outputPath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(config.outputPath(), GSON.toJson(report), StandardCharsets.UTF_8);
        if (config.printReport()) {
            originalOut.println(GSON.toJson(report));
        } else {
            originalOut.println("Counterfactual replay report: " + config.outputPath());
            originalOut.println(GSON.toJson(report.getAsJsonObject("summary")));
        }
    }

    private static JsonObject replayDecision(JsonObject row, Config config) {
        JsonObject request = object(row, "request");
        JsonObject result = object(row, "result");
        JsonObject context = object(request, "context");
        String decisionId = string(request, "decisionId");
        String decisionKind = string(request, "decisionKind").toUpperCase(Locale.ROOT);
        String actorId = string(request, "actorPlayerId");
        String sourceChoiceId = string(result, "choiceId");
        JsonObject sourcePolicy = object(context, "sourcePolicy");
        String policyChoiceId = string(sourcePolicy, "choiceId");
        if (policyChoiceId.isBlank()) {
            policyChoiceId = sourceChoiceId;
        }
        String mementoJson = string(object(context, "counterfactual"), "mementoJson");

        JsonArray candidatesJson = array(request, "candidates");
        HardChoice hardChoice = resolveHardChoice(decisionKind, mementoJson, actorId, candidatesJson, result, context);
        String hardChoiceId = hardChoice.choiceId();
        String metadataHardChoiceId = string(object(sourcePolicy, "metadata"), "hardChoiceId");
        if (metadataHardChoiceId.isBlank()) {
            metadataHardChoiceId = string(object(result, "metadata"), "hardChoiceId");
        }
        if (metadataHardChoiceId.isBlank() && isAuxiliaryDecision(decisionKind)) {
            metadataHardChoiceId = string(object(result, "metadata"), "fallbackChoiceId");
        }
        if (hardChoiceId.isBlank()) {
            hardChoiceId = metadataHardChoiceId;
        }
        JsonArray candidateReports = new JsonArray();
        Map<String, CandidateOutcome> outcomes = new LinkedHashMap<>();
        List<JsonObject> candidatesToReplay = selectedCandidates(
                candidatesJson,
                config.maxCandidatesPerDecision(),
                sourceChoiceId,
                policyChoiceId,
                hardChoiceId);
        for (JsonObject candidate : candidatesToReplay) {
            String candidateId = string(candidate, "id");
            if (candidateId.isBlank()) {
                continue;
            }
            CandidateOutcome outcome = replayCandidate(
                    mementoJson,
                    actorId,
                    decisionKind,
                    candidateId,
                    object(candidate, "payload"),
                    config.maxSnapshotsPerCandidate(),
                    RollForwardPolicy.from(config.rollForwardPolicy()));
            outcomes.put(candidateId, outcome);
            candidateReports.add(outcomeJson(candidate, outcome));
        }

        JsonObject out = new JsonObject();
        out.addProperty("decisionId", decisionId);
        out.addProperty("decisionKind", decisionKind);
        out.addProperty("actorPlayerId", actorId);
        out.addProperty("sourceChoiceId", sourceChoiceId);
        out.addProperty("policyChoiceId", policyChoiceId);
        out.addProperty("hardChoiceId", hardChoiceId);
        out.addProperty("candidateCount", candidatesJson.size());
        out.addProperty("replayedCandidateCount", candidatesToReplay.size());
        out.add("hardChoiceResolution", hardChoice.toJson(metadataHardChoiceId));
        out.add("sourceChoice", selectedOutcomeJson(outcomes.get(sourceChoiceId)));
        out.add("policyChoice", selectedOutcomeJson(outcomes.get(policyChoiceId)));
        out.add("hardChoice", selectedOutcomeJson(outcomes.get(hardChoiceId)));
        CandidateOutcome best = bestOutcome(outcomes);
        out.add("bestCandidate", selectedOutcomeJson(best));
        out.add("bestCandidateIds", bestCandidateIds(outcomes, best));
        out.addProperty("informative", isInformative(outcomes));
        out.add("candidates", candidateReports);
        return out;
    }

    private static HardChoice resolveHardChoice(
            String decisionKind,
            String mementoJson,
            String actorId,
            JsonArray candidatesJson,
            JsonObject result,
            JsonObject context) {
        if (isAuxiliaryDecision(decisionKind)) {
            String fallbackId = string(object(result, "metadata"), "fallbackChoiceId");
            if (fallbackId.isBlank()) {
                fallbackId = string(object(object(context, "sourcePolicy"), "metadata"), "fallbackChoiceId");
            }
            if (fallbackId.isBlank()) {
                return HardChoice.error("auxiliary_missing_fallback_choice");
            }
            if (!candidateExists(candidatesJson, fallbackId)) {
                return new HardChoice(fallbackId, "metadata_fallback_unmatched", "", "");
            }
            return new HardChoice(fallbackId, "metadata_fallback", "", "");
        }
        GameController controller = null;
        try {
            GameSessionMemento memento = GameSessionMemento.fromJson(mementoJson);
            controller = GameSessionMemento.restore(new ReplaySubject(1), memento);
            controller.setSuppressAiAutoContinuation(true);
            controller.resetSessionClockForSimulation();
            forceHardStrategies(controller);
            Player actor = playerById(controller, actorId);
            if (!(actor instanceof AIPlayer ai)) {
                return HardChoice.error("actor_not_ai");
            }
            GameContext replayContext = controller.refreshAndGetAiDecisionContextForSimulation();
            RecordingBridge bridge = new RecordingBridge();
            if (!new HardAiPlayStrategy().tryPlayOneCard(ai, replayContext, bridge)) {
                return HardChoice.error("hard_no_play");
            }
            String requestKey = requestKey(bridge.request);
            if (requestKey.isBlank()) {
                return HardChoice.error("hard_empty_request");
            }
            String candidateId = candidateIdForRequest(candidatesJson, requestKey);
            if (candidateId.isBlank()) {
                return new HardChoice("", "computed_unmatched", requestKey, "");
            }
            return new HardChoice(candidateId, "computed", requestKey, "");
        } catch (RuntimeException ex) {
            return HardChoice.error(ex.getClass().getSimpleName() + ": " + ex.getMessage());
        } finally {
            if (controller != null) {
                controller.shutdown();
            }
        }
    }

    private static String candidateIdForRequest(JsonArray candidatesJson, String requestKey) {
        if (requestKey.isBlank()) {
            return "";
        }
        for (JsonElement element : candidatesJson) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject candidate = element.getAsJsonObject();
            String candidateKey = requestKey(GSON.fromJson(object(candidate, "payload"), PlayActionRequest.class));
            if (requestKey.equals(candidateKey)) {
                return string(candidate, "id");
            }
        }
        return "";
    }

    private static CandidateOutcome replayCandidate(
            String mementoJson,
            String actorId,
            String decisionKind,
            String candidateId,
            JsonObject payload,
            int maxSnapshots,
            RollForwardPolicy rollForwardPolicy) {
        ReplaySubject subject = new ReplaySubject(maxSnapshots);
        GameController controller = null;
        try {
            GameSessionMemento memento = GameSessionMemento.fromJson(mementoJson);
            controller = GameSessionMemento.restore(subject, memento);
            controller.setSuppressAiAutoContinuation(true);
            controller.resetSessionClockForSimulation();
            configureRollForwardStrategies(controller, rollForwardPolicy);
            applyCandidate(controller, actorId, decisionKind, payload);
            rollForward(controller, maxSnapshots);
            GameStateSnapshot snapshot = subject.last();
            JsonObject outcomeJson = JsonlDecisionTraceSink.outcomeJsonForTool(snapshot, actorId);
            return CandidateOutcome.from(candidateId, outcomeJson, subject.count(), null);
        } catch (RuntimeException ex) {
            JsonObject failed = new JsonObject();
            failed.addProperty("schema", "monopoly-deal-outcome-v1");
            failed.addProperty("actorPlayerId", actorId);
            failed.addProperty("gameOver", false);
            failed.addProperty("naturalWin", false);
            failed.addProperty("reward", -1.0d);
            return CandidateOutcome.from(
                    candidateId,
                    failed,
                    subject.count(),
                    ex.getClass().getSimpleName() + ": " + ex.getMessage());
        } finally {
            if (controller != null) {
                controller.shutdown();
            }
        }
    }

    private static void applyCandidate(
            GameController controller,
            String actorId,
            String decisionKind,
            JsonObject payload) {
        String kind = decisionKind == null ? "" : decisionKind.trim().toUpperCase(Locale.ROOT);
        switch (kind) {
            case "PAYMENT" -> applyPaymentCandidate(controller, actorId, payload);
            case "OVERFLOW_DISCARD" -> applyOverflowDiscardCandidate(controller, actorId, payload);
            case "JUST_SAY_NO" -> applyJustSayNoCandidate(controller, actorId, payload);
            default -> controller.handlePlayActionRequest(GSON.fromJson(payload, PlayActionRequest.class));
        }
    }

    private static void applyPaymentCandidate(
            GameController controller,
            String actorId,
            JsonObject payload) {
        GameContext context = controller.refreshAndGetAiDecisionContextForSimulation();
        StackResponseState responseState = context.getResponseState();
        if (responseState != null
                && responseState.getRole() == StackResponseState.Role.TENANT
                && actorId != null
                && actorId.equals(responseState.getAwaitingPlayerId())) {
            PlayActionRequest request = new PlayActionRequest();
            request.setActionType("RESPONSE_PASS");
            request.setActingPlayerId(actorId);
            request.setPaymentCardIds(stringList(payload, "cardIds"));
            controller.handlePlayActionRequest(request);
            return;
        }
        if (responseState != null
                && responseState.getRole() == StackResponseState.Role.LANDLORD_COUNTER) {
            controller.resolvePaymentChoiceForSimulation(actorId, stringList(payload, "cardIds"));
            return;
        }
        EffectStackEntry due = firstActiveRentFor(context, actorId);
        if (due != null) {
            controller.resolvePaymentChoiceForSimulation(actorId, stringList(payload, "cardIds"));
            return;
        }
        PlayActionRequest request = new PlayActionRequest();
        request.setActionType("RESPONSE_PASS");
        request.setActingPlayerId(actorId);
        request.setPaymentCardIds(stringList(payload, "cardIds"));
        controller.handlePlayActionRequest(request);
    }

    private static EffectStackEntry firstActiveRentFor(GameContext context, String tenantId) {
        if (context == null || tenantId == null || tenantId.isBlank()) {
            return null;
        }
        Set<String> cancelled = com.monopoly.model.effects.EffectStackResolver.computeCancelledEntryIds(
                new ArrayList<>(context.getEffectStackView()));
        for (EffectStackEntry entry : context.getEffectStackView()) {
            if (entry.isRentLike()
                    && !cancelled.contains(entry.getId())
                    && tenantId.equals(entry.getTenantPlayerId())) {
                return entry;
            }
        }
        return null;
    }

    private static void applyOverflowDiscardCandidate(
            GameController controller,
            String actorId,
            JsonObject payload) {
        Player actor = playerById(controller, actorId);
        if (!(actor instanceof AIPlayer ai)) {
            throw new IllegalStateException("overflow actor is not AI: " + actorId);
        }
        for (String cardId : stringList(payload, "cardIds")) {
            PlayActionRequest request = new PlayActionRequest();
            request.setActionType("DISCARD");
            request.setCardId(cardId);
            controller.handlePlayActionRequest(request);
        }
        if (!controller.isSessionEndedPublic()) {
            controller.endTurn(ai);
        }
    }

    private static void applyJustSayNoCandidate(
            GameController controller,
            String actorId,
            JsonObject payload) {
        if (bool(payload, "playJustSayNo")) {
            PlayActionRequest request = GSON.fromJson(payload, PlayActionRequest.class);
            if (request.getActingPlayerId() == null || request.getActingPlayerId().isBlank()) {
                request.setActingPlayerId(actorId);
            }
            controller.handlePlayActionRequest(request);
            return;
        }
        PlayActionRequest request = new PlayActionRequest();
        request.setActionType("RESPONSE_PASS");
        request.setActingPlayerId(actorId);
        controller.handlePlayActionRequest(request);
    }

    private static void rollForward(GameController controller, int maxSnapshots) {
        int guard = 0;
        while (!controller.isSessionEndedPublic() && guard++ < maxSnapshots) {
            Player current = controller.getCurrentPlayer();
            if (!(current instanceof AIPlayer ai)) {
                controller.forceEndSession("COUNTERFACTUAL_NON_AI_TURN");
                return;
            }
            GameContext context = controller.refreshAndGetAiDecisionContextForSimulation();
            String phase = context.getCurrentTurnPhase();
            if ("DRAW".equals(phase)) {
                controller.drawCards(ai, 2);
                continue;
            }
            if ("WAITING_FOR_RESPONSE".equals(phase)) {
                if (!autoResolveResponse(controller, ai)) {
                    return;
                }
                continue;
            }
            if ("END_TURN".equals(phase)) {
                controller.endTurn(ai);
                continue;
            }
            if ("PLAY".equals(phase)) {
                if (ai.getHandCardCount() <= 0
                        || context.remainingTurnActions() <= 0) {
                    finishTurn(controller, ai);
                    continue;
                }
                AiPlayStrategy strategy = ai.getPlayStrategy();
                boolean progressed = strategy != null
                        && strategy.tryPlayOneCard(ai, context, controller);
                if (!progressed) {
                    finishTurn(controller, ai);
                }
                continue;
            }
            controller.forceEndSession("COUNTERFACTUAL_UNKNOWN_PHASE_" + phase);
            return;
        }
        if (!controller.isSessionEndedPublic()) {
            controller.forceEndSession("COUNTERFACTUAL_SNAPSHOT_LIMIT");
        }
    }

    private static boolean autoResolveResponse(GameController controller, AIPlayer current) {
        GameContext context = controller.refreshAndGetAiDecisionContextForSimulation();
        if (context.getResponseState() == null) {
            return false;
        }
        String awaiting = context.getResponseState().getAwaitingPlayerId();
        Player responder = playerById(controller, awaiting);
        if (!(responder instanceof AIPlayer ai)) {
            return false;
        }
        boolean counterRole = context.getResponseState().getRole()
                == com.monopoly.model.effects.StackResponseState.Role.LANDLORD_COUNTER;
        AiHeuristics.AiResponseDecision decision =
                AiHeuristics.chooseResponse(ai, context, counterRole);
        if (decision.playWaiver() && decision.request() != null) {
            controller.handlePlayActionRequest(decision.request());
        } else {
            PlayActionRequest pass = new PlayActionRequest();
            pass.setActionType("RESPONSE_PASS");
            pass.setActingPlayerId(ai.getPlayerId());
            controller.handlePlayActionRequest(pass);
        }
        return controller.getCurrentPlayer() == current
                || controller.getCurrentPlayer() instanceof AIPlayer;
    }

    private static void finishTurn(GameController controller, AIPlayer ai) {
        if (ai.getHandCardCount() > 7) {
            List<com.monopoly.model.card.Card> chosen = ai.chooseOverflowDiscardsTo(7);
            if (ai.getPlayStrategy() instanceof com.monopoly.pattern.strategy.AiChoiceAdvisor advisor) {
                chosen = advisor.chooseOverflowDiscards(
                        ai,
                        controller.refreshAndGetAiDecisionContextForSimulation(),
                        7,
                        chosen);
            }
            for (com.monopoly.model.card.Card card : chosen) {
                PlayActionRequest req = new PlayActionRequest();
                req.setActionType("DISCARD");
                req.setActingPlayerId(ai.getPlayerId());
                req.setCardId(card.getId());
                controller.handlePlayActionRequest(req);
            }
        }
        controller.endTurn(ai);
    }

    private static void forceHardStrategies(GameController controller) {
        configureRollForwardStrategies(controller, RollForwardPolicy.HARD);
    }

    private static void configureRollForwardStrategies(
            GameController controller,
            RollForwardPolicy policy) {
        for (Player player : controller.getSessionPlayersView()) {
            if (player instanceof AIPlayer ai) {
                ai.setPlayStrategy(policy == RollForwardPolicy.LOOKAHEAD
                        ? new SearchLookaheadAiPlayStrategy()
                        : new HardAiPlayStrategy());
            }
        }
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

    private static String requestKey(PlayActionRequest req) {
        if (req == null) {
            return "";
        }
        return String.join("|",
                trim(req.getActionType()).toUpperCase(Locale.ROOT),
                trim(req.getCardId()),
                trim(req.getTargetPlayerId()),
                trim(req.getTargetColorKey()).toUpperCase(Locale.ROOT),
                trim(req.getTargetCardId()),
                trim(req.getActorCardId()),
                trim(req.getTargetZone()).toUpperCase(Locale.ROOT));
    }

    private static JsonObject outcomeJson(JsonObject candidate, CandidateOutcome outcome) {
        JsonObject out = selectedOutcomeJson(outcome);
        out.addProperty("candidateId", string(candidate, "id"));
        out.addProperty("summary", string(candidate, "summary"));
        out.add("payload", object(candidate, "payload"));
        return out;
    }

    private static JsonObject selectedOutcomeJson(CandidateOutcome outcome) {
        JsonObject out = new JsonObject();
        if (outcome == null) {
            return out;
        }
        out.addProperty("candidateId", outcome.candidateId());
        out.addProperty("reward", outcome.reward());
        out.addProperty("boardScore", outcome.boardScore());
        out.addProperty("boardRank", outcome.boardRank());
        out.addProperty("completeSets", outcome.completeSets());
        out.addProperty("naturalWin", outcome.naturalWin());
        out.addProperty("gameOver", outcome.gameOver());
        out.addProperty("forceEndReason", outcome.forceEndReason());
        out.addProperty("snapshots", outcome.snapshots());
        out.addProperty("error", outcome.error());
        return out;
    }

    private static List<JsonObject> selectedCandidates(
            JsonArray candidatesJson,
            int limit,
            String sourceChoiceId,
            String policyChoiceId,
            String hardChoiceId) {
        List<JsonObject> all = new ArrayList<>();
        for (JsonElement element : candidatesJson) {
            if (element.isJsonObject()) {
                all.add(element.getAsJsonObject());
            }
        }
        if (limit <= 0 || all.size() <= limit) {
            return all;
        }
        Set<String> required = new LinkedHashSet<>();
        addRequiredId(required, sourceChoiceId);
        addRequiredId(required, policyChoiceId);
        addRequiredId(required, hardChoiceId);
        Set<String> seen = new LinkedHashSet<>();
        List<JsonObject> out = new ArrayList<>();
        for (JsonObject candidate : all) {
            String id = string(candidate, "id");
            if (id.isBlank() || seen.contains(id)) {
                continue;
            }
            if (out.size() < limit || required.contains(id)) {
                out.add(candidate);
                seen.add(id);
            }
        }
        return out;
    }

    private static void addRequiredId(Set<String> required, String id) {
        if (id != null && !id.isBlank()) {
            required.add(id);
        }
    }

    private static CandidateOutcome bestOutcome(Map<String, CandidateOutcome> outcomes) {
        return outcomes.values().stream()
                .max(Comparator.comparingDouble(CandidateOutcome::reward)
                        .thenComparingInt(CandidateOutcome::boardScore))
                .orElse(null);
    }

    private static JsonArray bestCandidateIds(
            Map<String, CandidateOutcome> outcomes,
            CandidateOutcome best) {
        JsonArray out = new JsonArray();
        if (best == null) {
            return out;
        }
        for (CandidateOutcome outcome : outcomes.values()) {
            if (sameOutcomeScore(outcome, best)) {
                out.add(outcome.candidateId());
            }
        }
        return out;
    }

    private static boolean isInformative(Map<String, CandidateOutcome> outcomes) {
        CandidateOutcome first = null;
        for (CandidateOutcome outcome : outcomes.values()) {
            if (first == null) {
                first = outcome;
                continue;
            }
            if (!sameOutcomeScore(first, outcome)) {
                return true;
            }
        }
        return false;
    }

    private static JsonObject summary(JsonArray decisions) {
        int rows = decisions.size();
        int sourceBest = 0;
        int policyBest = 0;
        int hardBest = 0;
        int informativeRows = 0;
        int hardResolutionErrors = 0;
        int hardResolutionUnmatched = 0;
        int candidateErrors = 0;
        int forcedCandidates = 0;
        int incompleteCandidates = 0;
        int sourceBetterThanHard = 0;
        int hardBetterThanSource = 0;
        int sourceSameAsHard = 0;
        int sourceNaturalWins = 0;
        int policyNaturalWins = 0;
        int hardNaturalWins = 0;
        double sourceReward = 0d;
        double policyReward = 0d;
        double hardReward = 0d;
        double sourceMinusHardReward = 0d;
        double sourceMinusHardBoard = 0d;
        for (JsonElement element : decisions) {
            JsonObject row = element.getAsJsonObject();
            JsonArray bestIds = array(row, "bestCandidateIds");
            JsonObject source = object(row, "sourceChoice");
            JsonObject policy = object(row, "policyChoice");
            JsonObject hard = object(row, "hardChoice");
            if (bool(row, "informative")) {
                informativeRows++;
            }
            JsonObject hardResolution = object(row, "hardChoiceResolution");
            String hardResolutionSource = string(hardResolution, "source");
            if ("error".equals(hardResolutionSource)) {
                hardResolutionErrors++;
            }
            if ("computed_unmatched".equals(hardResolutionSource)) {
                hardResolutionUnmatched++;
            }
            for (JsonElement candidateElement : array(row, "candidates")) {
                if (!candidateElement.isJsonObject()) {
                    continue;
                }
                JsonObject candidate = candidateElement.getAsJsonObject();
                if (!string(candidate, "error").isBlank()) {
                    candidateErrors++;
                }
                if (!string(candidate, "forceEndReason").isBlank()) {
                    forcedCandidates++;
                }
                if (!bool(candidate, "gameOver")) {
                    incompleteCandidates++;
                }
            }
            if (containsString(bestIds, string(source, "candidateId"))) {
                sourceBest++;
            }
            if (containsString(bestIds, string(policy, "candidateId"))) {
                policyBest++;
            }
            if (containsString(bestIds, string(hard, "candidateId"))) {
                hardBest++;
            }
            sourceReward += doubleValue(source, "reward");
            policyReward += doubleValue(policy, "reward");
            hardReward += doubleValue(hard, "reward");
            sourceMinusHardReward += doubleValue(source, "reward") - doubleValue(hard, "reward");
            sourceMinusHardBoard += doubleValue(source, "boardScore") - doubleValue(hard, "boardScore");
            int cmp = compareOutcome(source, hard);
            if (cmp > 0) {
                sourceBetterThanHard++;
            } else if (cmp < 0) {
                hardBetterThanSource++;
            } else {
                sourceSameAsHard++;
            }
            sourceNaturalWins += bool(source, "naturalWin") ? 1 : 0;
            policyNaturalWins += bool(policy, "naturalWin") ? 1 : 0;
            hardNaturalWins += bool(hard, "naturalWin") ? 1 : 0;
        }
        JsonObject out = new JsonObject();
        out.addProperty("decisions", rows);
        out.addProperty("informativeDecisions", informativeRows);
        out.addProperty("hardChoiceResolutionErrors", hardResolutionErrors);
        out.addProperty("hardChoiceResolutionUnmatched", hardResolutionUnmatched);
        out.addProperty("candidateErrors", candidateErrors);
        out.addProperty("forcedCandidates", forcedCandidates);
        out.addProperty("incompleteCandidates", incompleteCandidates);
        out.addProperty("sourceChoiceBestCount", sourceBest);
        out.addProperty("policyChoiceBestCount", policyBest);
        out.addProperty("hardChoiceBestCount", hardBest);
        out.addProperty("sourceBetterThanHardCount", sourceBetterThanHard);
        out.addProperty("sourceSameAsHardCount", sourceSameAsHard);
        out.addProperty("hardBetterThanSourceCount", hardBetterThanSource);
        out.addProperty("sourceChoiceNaturalWins", sourceNaturalWins);
        out.addProperty("policyChoiceNaturalWins", policyNaturalWins);
        out.addProperty("hardChoiceNaturalWins", hardNaturalWins);
        out.addProperty("averageSourceChoiceReward", rows == 0 ? 0d : sourceReward / rows);
        out.addProperty("averagePolicyChoiceReward", rows == 0 ? 0d : policyReward / rows);
        out.addProperty("averageHardChoiceReward", rows == 0 ? 0d : hardReward / rows);
        out.addProperty("averageSourceMinusHardReward", rows == 0 ? 0d : sourceMinusHardReward / rows);
        out.addProperty("averageSourceMinusHardBoardScore", rows == 0 ? 0d : sourceMinusHardBoard / rows);
        return out;
    }

    private static boolean isAuxiliaryDecision(String decisionKind) {
        String kind = decisionKind == null ? "" : decisionKind.trim().toUpperCase(Locale.ROOT);
        return "PAYMENT".equals(kind)
                || "OVERFLOW_DISCARD".equals(kind)
                || "JUST_SAY_NO".equals(kind);
    }

    private static boolean candidateExists(JsonArray candidatesJson, String candidateId) {
        if (candidateId == null || candidateId.isBlank()) {
            return false;
        }
        for (JsonElement element : candidatesJson) {
            if (element.isJsonObject()
                    && candidateId.equals(string(element.getAsJsonObject(), "id"))) {
                return true;
            }
        }
        return false;
    }

    private static int compareOutcome(JsonObject left, JsonObject right) {
        int byReward = Double.compare(doubleValue(left, "reward"), doubleValue(right, "reward"));
        if (byReward != 0) {
            return byReward;
        }
        return Double.compare(doubleValue(left, "boardScore"), doubleValue(right, "boardScore"));
    }

    private static boolean sameOutcomeScore(CandidateOutcome left, CandidateOutcome right) {
        if (left == null || right == null) {
            return false;
        }
        return Double.compare(left.reward(), right.reward()) == 0
                && left.boardScore() == right.boardScore();
    }

    private static boolean containsString(JsonArray array, String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        for (JsonElement element : array) {
            if (!element.isJsonNull() && value.equals(element.getAsString())) {
                return true;
            }
        }
        return false;
    }

    private static JsonObject parseRow(String raw, int lineNumber) {
        try {
            JsonElement parsed = JsonParser.parseString(raw);
            if (!parsed.isJsonObject()) {
                throw new IllegalArgumentException("line is not an object");
            }
            return parsed.getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("invalid JSON at line " + lineNumber, e);
        }
    }

    private static JsonObject object(JsonObject parent, String key) {
        return parent != null && parent.has(key) && parent.get(key).isJsonObject()
                ? parent.getAsJsonObject(key)
                : new JsonObject();
    }

    private static JsonArray array(JsonObject parent, String key) {
        return parent != null && parent.has(key) && parent.get(key).isJsonArray()
                ? parent.getAsJsonArray(key)
                : new JsonArray();
    }

    private static String string(JsonObject object, String key) {
        return object != null && object.has(key) && !object.get(key).isJsonNull()
                ? object.get(key).getAsString()
                : "";
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static double doubleValue(JsonObject object, String key) {
        try {
            return object != null && object.has(key) && !object.get(key).isJsonNull()
                    ? object.get(key).getAsDouble()
                    : 0d;
        } catch (RuntimeException ignored) {
            return 0d;
        }
    }

    private static boolean bool(JsonObject object, String key) {
        return object != null && object.has(key) && !object.get(key).isJsonNull()
                && object.get(key).getAsBoolean();
    }

    private static List<String> stringList(JsonObject object, String key) {
        JsonArray array = array(object, key);
        List<String> out = new ArrayList<>();
        for (JsonElement element : array) {
            if (!element.isJsonNull()) {
                String value = element.getAsString();
                if (value != null && !value.isBlank()) {
                    out.add(value.trim());
                }
            }
        }
        return out;
    }

    private static JsonArray decisionKindsJson(Set<String> decisionKinds) {
        JsonArray out = new JsonArray();
        for (String kind : decisionKinds) {
            out.add(kind);
        }
        return out;
    }

    private record HardChoice(String choiceId, String source, String requestKey, String error) {

        static HardChoice error(String error) {
            return new HardChoice("", "error", "", error == null ? "" : error);
        }

        JsonObject toJson(String metadataHardChoiceId) {
            JsonObject out = new JsonObject();
            out.addProperty("choiceId", choiceId);
            out.addProperty("source", source);
            out.addProperty("requestKey", requestKey);
            out.addProperty("metadataHardChoiceId", metadataHardChoiceId == null ? "" : metadataHardChoiceId);
            out.addProperty("matchesMetadata",
                    !choiceId.isBlank()
                            && metadataHardChoiceId != null
                            && choiceId.equals(metadataHardChoiceId));
            out.addProperty("error", error);
            return out;
        }
    }

    private record CandidateOutcome(
            String candidateId,
            double reward,
            int boardScore,
            int boardRank,
            int completeSets,
            boolean naturalWin,
            boolean gameOver,
            String forceEndReason,
            int snapshots,
            String error) {

        static CandidateOutcome from(
                String candidateId,
                JsonObject outcome,
                int snapshots,
                String error) {
            return new CandidateOutcome(
                    candidateId,
                    doubleValue(outcome, "reward"),
                    (int) doubleValue(outcome, "boardScore"),
                    (int) doubleValue(outcome, "boardRank"),
                    (int) doubleValue(outcome, "completeSets"),
                    bool(outcome, "naturalWin"),
                    bool(outcome, "gameOver"),
                    string(outcome, "forceEndReason"),
                    snapshots,
                    error == null ? "" : error);
        }
    }

    private record Config(
            Path inputPath,
            Path outputPath,
            int maxRows,
            int maxSnapshotsPerCandidate,
            int maxCandidatesPerDecision,
            String rollForwardPolicy,
            Set<String> decisionKinds,
            boolean quiet,
            boolean printReport) {

        static Config fromProperties() {
            return new Config(
                    Path.of(required("monopoly.counterfactual.inputPath")),
                    Path.of(required("monopoly.counterfactual.outputPath")),
                    Math.max(0, Integer.getInteger("monopoly.counterfactual.maxRows", DEFAULT_MAX_ROWS)),
                    Math.max(20, Integer.getInteger(
                            "monopoly.counterfactual.maxSnapshotsPerCandidate",
                            DEFAULT_MAX_SNAPSHOTS)),
                    Math.max(0, Integer.getInteger("monopoly.counterfactual.maxCandidatesPerDecision", 0)),
                    System.getProperty("monopoly.counterfactual.rollForwardPolicy", "hard")
                            .trim()
                            .toLowerCase(Locale.ROOT),
                    parseDecisionKinds(System.getProperty("monopoly.counterfactual.decisionKinds", "")),
                    Boolean.parseBoolean(System.getProperty("monopoly.counterfactual.quiet", "true")),
                    Boolean.parseBoolean(System.getProperty("monopoly.counterfactual.printReport", "false")));
        }

        boolean includesDecisionKind(String decisionKind) {
            return decisionKinds.isEmpty()
                    || decisionKinds.contains(trim(decisionKind).toUpperCase(Locale.ROOT));
        }

        private static String required(String key) {
            String raw = System.getProperty(key, "").trim();
            if (raw.isBlank()) {
                throw new IllegalArgumentException("missing required property " + key);
            }
            return raw;
        }

        private static Set<String> parseDecisionKinds(String raw) {
            Set<String> out = new LinkedHashSet<>();
            if (raw == null || raw.isBlank()) {
                return out;
            }
            for (String part : raw.split(",")) {
                String kind = trim(part).toUpperCase(Locale.ROOT);
                if (!kind.isBlank()) {
                    out.add(kind);
                }
            }
            return out;
        }
    }

    private enum RollForwardPolicy {
        HARD,
        LOOKAHEAD;

        static RollForwardPolicy from(String raw) {
            String value = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
            if (value.isBlank() || "HARD".equals(value)) {
                return HARD;
            }
            if ("LOOKAHEAD".equals(value)
                    || "SEARCH".equals(value)
                    || "BUILDINGA".equals(value)) {
                return LOOKAHEAD;
            }
            throw new IllegalArgumentException("unsupported monopoly.counterfactual.rollForwardPolicy=" + raw);
        }
    }

    private static final class RecordingBridge implements AiGameBridge {
        private PlayActionRequest request;

        @Override
        public void submitPlayAction(PlayActionRequest request) {
            this.request = request;
        }
    }

    private static final class ReplaySubject implements GameUpdateSubject {
        private final int maxSnapshots;
        private GameStateSnapshot last;
        private int count;

        ReplaySubject(int maxSnapshots) {
            this.maxSnapshots = Math.max(1, maxSnapshots);
        }

        @Override
        public void registerObserver(GameUpdateObserver observer) {
        }

        @Override
        public void unregisterObserver(GameUpdateObserver observer) {
        }

        @Override
        public void notifyStateChanged(GameStateSnapshot snapshot) {
            if (snapshot == null) {
                return;
            }
            last = snapshot;
            count++;
        }

        private GameStateSnapshot last() {
            return last;
        }

        private int count() {
            return Math.min(count, maxSnapshots);
        }
    }
}
