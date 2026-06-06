package com.monopoly.controller;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.monopoly.dto.GameStateSnapshot;
import com.monopoly.dto.PropertyColorProgress;
import com.monopoly.model.card.Card;
import com.monopoly.model.card.PropertyCard;
import com.monopoly.model.effects.EffectStackEntry;
import com.monopoly.model.effects.StackResponseState;
import com.monopoly.model.player.Player;
import com.monopoly.model.settlement.PropertyZoneSummary;
import com.monopoly.presentation.HandCardJson;

import java.util.List;
import java.util.Locale;

final class SnapshotBuilder {

    private final GameController controller;
    private final TurnFlowService turnFlow;
    private long playEventSequence;
    private long stateSequence;

    SnapshotBuilder(GameController controller, TurnFlowService turnFlow) {
        this.controller = controller;
        this.turnFlow = turnFlow;
    }

    void reset() {
        this.stateSequence = 0L;
        this.playEventSequence = 0L;
    }

    long currentStateSequence() {
        return stateSequence;
    }

    void broadcast(
            String sessionId,
            String phase,
            String actionSummary,
            Player playedBy,
            Card playedCard,
            String playedActionType) {
        String originalPhase = phase;
        if (!controller.isSessionForceEnded() && controller.sessionStartEpochMs() > 0 && !controller.isAiBattleMode()) {
            long elapsed = System.currentTimeMillis() - controller.sessionStartEpochMs();
            if (elapsed > GameController.sessionLimitMs()) {
                controller.markTimeoutForceEnd();
            }
        }
        if (controller.isSessionForceEnded()) {
            phase = "GAME_FORCE_END";
        } else if (controller.isSessionEndedNaturally()) {
            phase = "GAME_OVER";
        }

        String summary = (actionSummary != null && !actionSummary.isBlank())
                ? actionSummary
                : fallbackActionSummary(phase);

        TurnFlowService.TurnPhase tp = turnFlow.currentTurnPhase;

        GameStateSnapshot snap = new GameStateSnapshot();
        snap.setSessionId(sessionId);
        snap.setPhase(phase);
        snap.setStateSequence(++stateSequence);
        snap.setLastActionSummary(summary);
        snap.setCurrentPlayerId(turnFlow.currentTurnPlayerId);
        snap.setTurnPhase(tp == null ? "UNKNOWN" : tp.name());
        snap.setActionsUsedThisTurn(turnFlow.currentTurnActionCount);
        snap.setActionsRemainingThisTurn(
                Math.max(0, TurnFlowService.MAX_ACTIONS_PER_TURN - turnFlow.currentTurnActionCount));
        Player currentTurnPlayer = controller.resolvePlayer(turnFlow.currentTurnPlayerId);
        boolean overflowDiscardPhase = tp == TurnFlowService.TurnPhase.END_TURN;
        int overflowDiscardCount = !overflowDiscardPhase || currentTurnPlayer == null
                ? 0
                : Math.max(0, currentTurnPlayer.getHandCardCount() - TurnFlowService.MAX_HAND_SIZE);
        snap.setOverflowDiscardCount(overflowDiscardCount);
        snap.setRoundNumber(Math.max(1, controller.getFullRoundsCompleted() + 1));
        snap.setDrawPileCount(controller.getEngine().remainingCount());
        snap.setDiscardPileCount(controller.getEngine().discardCount());
        Integer pendingPaymentAmt = null;
        if (tp == TurnFlowService.TurnPhase.WAITING_FOR_RESPONSE) {
            StackResponseState st = controller.getGameContext().getResponseState();
            if (st != null) {
                snap.setDecisionPlayerId(st.getAwaitingPlayerId());
                snap.setDecisionDeadlineEpochMs(st.getDeadlineEpochMs());
                snap.setPendingResponsePlayerId(st.getAwaitingPlayerId());
                snap.setPendingResponseRole(st.getRole().name());
                snap.setResponseDeadlineEpochMs(st.getDeadlineEpochMs());
                snap.setPendingResponseHint(
                        EffectStackOrchestrator.buildPendingResponseHint(st));
                snap.setPendingResponseContext(buildPendingResponseContext(st));
                if (st.getRole() == StackResponseState.Role.TENANT) {
                    EffectStackEntry top = controller.getGameContext().peekTopEffect();
                    if (top != null && top.isRentLike()) {
                        pendingPaymentAmt = top.getAmountDue();
                    }
                }
                snap.setDecisionKind(decisionKindForResponse(st, pendingPaymentAmt));
                snap.setDecisionLabel(decisionLabelForResponse(st, pendingPaymentAmt));
            }
            snap.setEffectStackDepth(controller.getGameContext().getEffectStackView().size());
        } else {
            snap.setDecisionPlayerId(turnFlow.currentTurnPlayerId);
            snap.setDecisionKind(decisionKindForTurnPhase(tp));
            snap.setDecisionLabel(decisionLabelForTurnPhase(tp));
            snap.setDecisionDeadlineEpochMs(0L);
            snap.setPendingResponsePlayerId(null);
            snap.setPendingResponseRole(null);
            snap.setResponseDeadlineEpochMs(0L);
            snap.setPendingResponseHint(null);
            snap.setPendingResponseContext(null);
            snap.setEffectStackDepth(0);
        }
        snap.setPendingPaymentAmountM(pendingPaymentAmt);
        snap.setLastErrorCode(controller.lastErrorCode());
        snap.setLastErrorMessage(controller.lastErrorMessage());
        snap.setLastErrorTimestampEpochMs(controller.lastErrorTimestampEpochMs());
        snap.setGameOver(controller.isSessionForceEnded()
                || controller.isSessionEndedNaturally()
                || "GAME_OVER".equals(originalPhase));
        snap.setForceEndReason(controller.isSessionForceEnded() ? controller.forceEndReason() : null);
        if (playedCard != null) {
            long seq = ++playEventSequence;
            String actorId = playedBy != null ? playedBy.getPlayerId() : turnFlow.currentTurnPlayerId;
            snap.setLastPlayedSequence(seq);
            snap.setLastPlayedPlayerId(actorId);
            snap.setLastPlayedActionType(
                    playedActionType != null && !playedActionType.isBlank()
                            ? playedActionType.trim().toUpperCase(Locale.ROOT)
                            : (phase != null ? phase : ""));
            snap.setLastPlayedCard(HandCardJson.toHandCardObject(playedCard));
        }
        for (Player p : controller.getSessionPlayersView()) {
            JsonArray bankArr = new JsonArray();
            for (Card c : p.getBankCardsView()) {
                if (c != null) {
                    bankArr.add(HandCardJson.toHandCardObject(c));
                }
            }
            JsonArray propArr = new JsonArray();
            for (PropertyCard pc : p.getPropertyCardsView()) {
                if (pc != null) {
                    propArr.add(HandCardJson.toHandCardObject(pc));
                }
            }
            List<PropertyColorProgress> prog =
                    PropertyZoneSummary.colorProgress(p.getPropertyCardsView());
            snap.addPlayerSummary(
                    p.getPlayerId(),
                    p.getDisplayName(),
                    p.getHandCardCount(),
                    p.getBankCardCount(),
                    p.getPropertyCardCount(),
                    p.getActionZoneCardCount(),
                    p.countCompletePropertySets(),
                    p.totalBankValueM(),
                    PropertyZoneSummary.summarizeByColor(p.getPropertyCardsView()),
                    bankArr,
                    propArr,
                    prog
            );
        }
        controller.getGameContext().getAiHistoryTracker().recordSnapshot(
                snap.getStateSequence(),
                snap.getRoundNumber(),
                snap.getPhase(),
                snap.getCurrentPlayerId(),
                snap.getLastActionSummary(),
                playedBy,
                playedCard,
                playedActionType,
                controller.getSessionPlayersView());
        controller.gameUpdateSubject().notifyStateChanged(snap);
    }

    private JsonObject buildPendingResponseContext(StackResponseState st) {
        if (st == null) {
            return null;
        }
        EffectStackEntry entry = responseEntryForContext(st);
        JsonObject o = new JsonObject();
        o.addProperty("role", st.getRole().name());
        if (entry != null) {
            o.addProperty("kind", entry.getKind().name());
            addIfPresent(o, "actorPlayerId", entry.getActorPlayerId());
            addIfPresent(o, "actorName", displayNameFor(entry.getActorPlayerId()));
            String actionEffectCode = inferredActionEffectCode(entry);
            String targetPlayerId = entry.getTenantPlayerId();
            if (entry.getKind() == EffectStackEntry.Kind.WAIVER) {
                EffectStackEntry targetEntry = findEffectEntryById(entry.getWaiverTargetEntryId());
                if (targetEntry != null) {
                    targetPlayerId = targetEntry.getActorPlayerId();
                }
                addIfPresent(o, "actionCardName", "Just Say No");
                addIfPresent(o, "actionEffectCode", "RENT_WAIVER");
            }
            addIfPresent(o, "targetPlayerId", targetPlayerId);
            addIfPresent(o, "targetName", displayNameFor(targetPlayerId));
            addIfPresent(o, "colorKey", entry.getColorKey());
            if (entry.getKind() != EffectStackEntry.Kind.WAIVER) {
                addIfPresent(o, "actionCardName", entry.getActionCardName());
                addIfPresent(o, "actionEffectCode", actionEffectCode);
            }
            if (entry.getAmountDue() > 0) {
                o.addProperty("amountDueM", entry.getAmountDue());
            }
        }
        if (st.getRole() == StackResponseState.Role.LANDLORD_COUNTER) {
            EffectStackEntry bottom = bottomActionOrRentEntry();
            if (bottom != null && bottom != entry) {
                addIfPresent(o, "originalActorPlayerId", bottom.getActorPlayerId());
                addIfPresent(o, "originalActorName", displayNameFor(bottom.getActorPlayerId()));
                addIfPresent(o, "originalTargetPlayerId", bottom.getTenantPlayerId());
                addIfPresent(o, "originalTargetName", displayNameFor(bottom.getTenantPlayerId()));
                addIfPresent(o, "originalActionCardName", bottom.getActionCardName());
                addIfPresent(o, "originalActionEffectCode", inferredActionEffectCode(bottom));
                addIfPresent(o, "originalColorKey", bottom.getColorKey());
                if (bottom.getAmountDue() > 0) {
                    o.addProperty("originalAmountDueM", bottom.getAmountDue());
                }
            }
        }
        return o.size() == 1 && o.has("role") ? null : o;
    }

    private EffectStackEntry responseEntryForContext(StackResponseState st) {
        if (st.getRole() == StackResponseState.Role.LANDLORD_COUNTER) {
            return controller.getGameContext().peekTopEffect();
        }
        EffectStackEntry top = controller.getGameContext().peekTopEffect();
        if (top != null && (top.isRentLike() || top.isActionLike())) {
            return top;
        }
        return bottomActionOrRentEntry();
    }

    private EffectStackEntry bottomActionOrRentEntry() {
        for (EffectStackEntry entry : controller.getGameContext().getEffectStackView()) {
            if (entry != null && (entry.isRentLike() || entry.isActionLike())) {
                return entry;
            }
        }
        return null;
    }

    private EffectStackEntry findEffectEntryById(String entryId) {
        if (entryId == null || entryId.isBlank()) {
            return null;
        }
        for (EffectStackEntry entry : controller.getGameContext().getEffectStackView()) {
            if (entry != null && entryId.equals(entry.getId())) {
                return entry;
            }
        }
        return null;
    }

    private static String inferredActionEffectCode(EffectStackEntry entry) {
        if (entry == null) {
            return null;
        }
        String explicit = entry.getActionEffectCode();
        if (explicit != null && !explicit.isBlank()) {
            return explicit;
        }
        if (!entry.isRentLike()) {
            return null;
        }
        String colorKey = entry.getColorKey();
        if ("DEBT_COLLECTOR".equals(colorKey) || "BIRTHDAY".equals(colorKey)) {
            return colorKey;
        }
        return "RENT";
    }

    private String displayNameFor(String playerId) {
        Player p = controller.resolvePlayer(playerId);
        return p != null ? p.getDisplayName() : null;
    }

    private static void addIfPresent(JsonObject o, String key, String value) {
        if (value != null && !value.isBlank()) {
            o.addProperty(key, value);
        }
    }

    private String fallbackActionSummary(String phase) {
        if (phase == null) {
            return "";
        }
        return switch (phase) {
            case "INIT" -> "State refreshed.";
            case "DRAW" -> "Draw phase updated.";
            case "DEPLOY", "DEPOSIT", "ACTION", "DISCARD" -> "A play action completed.";
            case "TURN_END" -> "Turn ended.";
            case "FORCE_DISCARD_REQUIRED" -> "Discard down to 7 cards before ending turn.";
            case "GAME_OVER" -> "Game over.";
            case "REASSIGN_WILD" -> "Wild property color changes are not allowed.";
            case "RENT_PAID", "RENT_FAILED" -> "Rent settlement updated.";
            case "PAUSE_PENDING" -> "Pause vote in progress.";
            case "RULE_VIOLATION" -> (controller.lastErrorMessage() != null && !controller.lastErrorMessage().isBlank())
                    ? controller.lastErrorMessage()
                    : "Rule violation.";
            case "GAME_FORCE_END" -> controller.forceEndReason() != null
                    ? "Game ended: " + controller.forceEndReason() : "Game force-ended.";
            case "ACTION_SUCCESS", "ACTION_FAILED", "ACTION_COUNTERED" ->
                    "Action card effect resolved.";
            case "RENT_AWAITING_RESPONSE" -> "Awaiting rent response.";
            case "JSN_AWAITING_COUNTER" -> "Awaiting landlord counter to Just Say No.";
            case "RESPONSE_PASS", "RESPONSE_TIMEOUT" -> "Rent response chain updated.";
            default -> phase.replace('_', ' ');
        };
    }

    private static String decisionKindForTurnPhase(TurnFlowService.TurnPhase phase) {
        if (phase == null) {
            return "UNKNOWN";
        }
        return switch (phase) {
            case DRAW -> "DRAW";
            case PLAY -> "PLAY";
            case END_TURN -> "END_TURN";
            case WAITING_FOR_RESPONSE -> "RESPONSE";
        };
    }

    private static String decisionLabelForTurnPhase(TurnFlowService.TurnPhase phase) {
        if (phase == null) {
            return "等待状态更新";
        }
        return switch (phase) {
            case DRAW -> "摸牌";
            case PLAY -> "出牌";
            case END_TURN -> "结束回合";
            case WAITING_FOR_RESPONSE -> "响应";
        };
    }

    private static String decisionKindForResponse(StackResponseState st, Integer pendingPaymentAmt) {
        if (st == null) {
            return "RESPONSE";
        }
        if (st.getRole() == StackResponseState.Role.LANDLORD_COUNTER) {
            return "JUST_SAY_NO_COUNTER";
        }
        if (pendingPaymentAmt != null && pendingPaymentAmt > 0) {
            return "PAY_OR_JUST_SAY_NO";
        }
        return "JUST_SAY_NO_RESPONSE";
    }

    private static String decisionLabelForResponse(StackResponseState st, Integer pendingPaymentAmt) {
        if (st == null) {
            return "等待响应";
        }
        if (st.getRole() == StackResponseState.Role.LANDLORD_COUNTER) {
            return "反制 Just Say No";
        }
        if (pendingPaymentAmt != null && pendingPaymentAmt > 0) {
            return "付款或 Just Say No";
        }
        return "接受或 Just Say No";
    }
}
