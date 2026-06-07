package com.monopoly.pattern.strategy;

import com.monopoly.controller.GameController;
import com.monopoly.dto.GameStateSnapshot;
import com.monopoly.dto.PlayActionRequest;
import com.monopoly.model.card.Card;
import com.monopoly.model.core.AiGameBridge;
import com.monopoly.model.core.GameContext;
import com.monopoly.model.player.AIPlayer;
import com.monopoly.model.player.Player;
import com.monopoly.model.settlement.PaymentSettlement;
import com.monopoly.pattern.observer.GameUpdateObserver;
import com.monopoly.pattern.observer.GameUpdateSubject;
import com.monopoly.persistence.GameSessionMemento;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

public final class RolloutSimulator {

    static final double INVALID_SCORE = -1_000_000_000d;

    private RolloutSimulator() {
    }

    static double evaluateCandidate(
            GameController controller,
            AIPlayer sourceBot,
            AiHeuristics.AiPlayCandidate candidate,
            HardAiPlayStrategy fallback) {
        CapturingSubject subject = new CapturingSubject();
        GameController clone = null;
        try {
            String actorId = sourceBot.getPlayerId();
            GameSessionMemento memento = GameSessionMemento.capture(controller);
            clone = GameSessionMemento.restore(subject, memento);
            clone.setSuppressAiAutoContinuation(true);
            setCloneAiStrategiesToHard(clone);
            Player actor = playerById(clone, actorId);
            boolean revealsHiddenDraw = BoardEvaluator.requestMayRevealHiddenCards(actor, candidate.request());
            clone.handlePlayActionRequest(candidate.request());
            if (SearchConfig.ROLLOUT_REMAINING_TURN && !revealsHiddenDraw) {
                rolloutRemainingTurn(clone, actorId, fallback);
            }
            if (SearchConfig.ROLLOUT_NEXT_OPPONENT_TURN) {
                rolloutNextOpponentTurn(clone, actorId, fallback);
            }
            GameStateSnapshot snapshot = subject.last();
            if (snapshot == null) {
                return INVALID_SCORE;
            }
            return BoardEvaluator.stateValue(snapshot, actorId)
                    + DeepSeekAiPlayStrategy.candidateScore(candidate) * 0.03d
                    + BoardEvaluator.candidateAdjustment(actor, candidate);
        } catch (RuntimeException ex) {
            return INVALID_SCORE;
        } finally {
            if (clone != null) {
                clone.shutdown();
            }
        }
    }

    static double evaluateImmediate(
            GameController controller,
            String actorId,
            AiHeuristics.AiPlayCandidate candidate) {
        CapturingSubject subject = new CapturingSubject();
        GameController clone = null;
        try {
            GameSessionMemento memento = GameSessionMemento.capture(controller);
            clone = GameSessionMemento.restore(subject, memento);
            clone.setSuppressAiAutoContinuation(true);
            setCloneAiStrategiesToHard(clone);
            Player actor = playerById(clone, actorId);
            clone.handlePlayActionRequest(candidate.request());
            GameStateSnapshot snapshot = subject.last();
            return snapshot == null
                    ? INVALID_SCORE
                    : BoardEvaluator.stateValue(snapshot, actorId) + BoardEvaluator.candidateAdjustment(actor, candidate);
        } catch (RuntimeException ex) {
            return INVALID_SCORE;
        } finally {
            if (clone != null) {
                clone.shutdown();
            }
        }
    }

    static void rolloutNextOpponentTurn(GameController clone, String actorId, HardAiPlayStrategy fallback) {
        int guard = 0;
        while (guard++ < 6 && !clone.isSessionEndedPublic()) {
            Player current = clone.getCurrentPlayer();
            if (!(current instanceof AIPlayer ai) || actorId.equals(ai.getPlayerId())) {
                return;
            }
            GameContext context = clone.refreshAndGetAiDecisionContextForSimulation();
            String phase = currentPhase(context);
            if ("WAITING_FOR_RESPONSE".equals(phase)) {
                return;
            }
            if ("DRAW".equals(phase)) {
                clone.drawCards(ai, 2);
                return;
            }
            if ("END_TURN".equals(phase)) {
                clone.endTurn(ai);
                return;
            }
            if ("PLAY".equals(phase)
                    && context.remainingTurnActions() > 0
                    && !ai.getHandCardsView().isEmpty()) {
                RolloutStep step = tryFairRolloutPlay(clone, ai, context, actorId, fallback);
                if (step == RolloutStep.CONTINUE) {
                    continue;
                }
                if (step == RolloutStep.STOP_AFTER_HIDDEN_DRAW) {
                    return;
                }
            }
            clone.endTurn(ai);
            return;
        }
    }

    static void rolloutRemainingTurn(GameController clone, String actorId, HardAiPlayStrategy fallback) {
        int guard = 0;
        while (guard++ < 3 && !clone.isSessionEndedPublic()) {
            Player current = clone.getCurrentPlayer();
            if (!(current instanceof AIPlayer ai) || !actorId.equals(ai.getPlayerId())) {
                return;
            }
            GameContext context = clone.refreshAndGetAiDecisionContextForSimulation();
            String phase = currentPhase(context);
            if ("WAITING_FOR_RESPONSE".equals(phase) || "DRAW".equals(phase)) {
                return;
            }
            if ("END_TURN".equals(phase) || ai.getHandCardsView().isEmpty()) {
                clone.endTurn(ai);
                return;
            }
            RolloutStep step = tryRolloutPlay(clone, ai, context, actorId, fallback);
            if (step == RolloutStep.CONTINUE) {
                continue;
            }
            if (step == RolloutStep.NO_PLAY) {
                clone.endTurn(ai);
            }
            return;
        }
    }

    static RolloutStep tryRolloutPlay(
            GameController clone,
            AIPlayer ai,
            GameContext context,
            String actorId,
            HardAiPlayStrategy fallback) {
        if (!"search".equals(SearchConfig.ROLLOUT_POLICY) && !"greedy".equals(SearchConfig.ROLLOUT_POLICY)) {
            return tryFairHardRolloutPlay(clone, ai, context, fallback);
        }
        AiHeuristics.AiPlayCandidate candidate =
                bestImmediateRolloutCandidate(clone, ai, context, actorId, fallback);
        if (candidate == null) {
            return tryFairHardRolloutPlay(clone, ai, context, fallback);
        }
        try {
            boolean revealsHiddenDraw = BoardEvaluator.requestMayRevealHiddenCards(ai, candidate.request());
            clone.handlePlayActionRequest(candidate.request());
            return revealsHiddenDraw ? RolloutStep.STOP_AFTER_HIDDEN_DRAW : RolloutStep.CONTINUE;
        } catch (RuntimeException ex) {
            return tryFairHardRolloutPlay(clone, ai, context, fallback);
        }
    }

    static RolloutStep tryFairRolloutPlay(
            GameController clone,
            AIPlayer ai,
            GameContext context,
            String actorId,
            HardAiPlayStrategy fallback) {
        if ("search".equals(SearchConfig.ROLLOUT_POLICY) || "greedy".equals(SearchConfig.ROLLOUT_POLICY)) {
            return tryRolloutPlay(clone, ai, context, actorId, fallback);
        }
        return tryFairHardRolloutPlay(clone, ai, context, fallback);
    }

    static RolloutStep tryFairHardRolloutPlay(
            GameController clone,
            AIPlayer ai,
            GameContext context,
            HardAiPlayStrategy fallback) {
        PlayActionRequest request = hardRequest(ai, context, fallback);
        if (request == null) {
            return RolloutStep.NO_PLAY;
        }
        boolean revealsHiddenDraw = BoardEvaluator.requestMayRevealHiddenCards(ai, request);
        try {
            clone.handlePlayActionRequest(request);
            return revealsHiddenDraw ? RolloutStep.STOP_AFTER_HIDDEN_DRAW : RolloutStep.CONTINUE;
        } catch (RuntimeException ex) {
            return RolloutStep.NO_PLAY;
        }
    }

    static AiHeuristics.AiPlayCandidate bestImmediateRolloutCandidate(
            GameController controller,
            AIPlayer bot,
            GameContext context,
            String actorId,
            HardAiPlayStrategy fallback) {
        List<AiHeuristics.AiPlayCandidate> candidates =
                AiHeuristics.buildCandidates(AiStrategyProfile.HARD, bot, context);
        if (candidates.isEmpty()) {
            return null;
        }
        PlayActionRequest hardRequest = hardRequest(bot, context, fallback);
        candidates = CandidatePruner.pruneToLimit(candidates, hardRequest, Math.max(4, SearchConfig.MAX_ROLLOUT_CANDIDATES));
        SearchLookaheadAiPlayStrategy.ScoredCandidate best = null;
        SearchLookaheadAiPlayStrategy.ScoredCandidate hardChoice = null;
        String hardKey = BoardEvaluator.requestKey(hardRequest);
        for (AiHeuristics.AiPlayCandidate candidate : candidates) {
            double score = evaluateImmediate(controller, actorId, candidate)
                    + DeepSeekAiPlayStrategy.candidateScore(candidate) * 0.02d;
            SearchLookaheadAiPlayStrategy.ScoredCandidate scored = new SearchLookaheadAiPlayStrategy.ScoredCandidate(candidate, score);
            if (!hardKey.isBlank() && hardKey.equals(BoardEvaluator.requestKey(candidate.request()))) {
                hardChoice = scored;
            }
            if (best == null || scored.score() > best.score()) {
                best = scored;
            }
        }
        if (best == null || best.score() <= INVALID_SCORE / 2d) {
            return null;
        }
        return chooseWithHardMargin(best, hardChoice).candidate();
    }

    static SearchLookaheadAiPlayStrategy.ScoredCandidate chooseWithHardMargin(
            SearchLookaheadAiPlayStrategy.ScoredCandidate best,
            SearchLookaheadAiPlayStrategy.ScoredCandidate hardChoice) {
        if (SearchConfig.HARD_MARGIN < 0d || hardChoice == null) {
            return best;
        }
        return best.score() - hardChoice.score() >= SearchConfig.HARD_MARGIN ? best : hardChoice;
    }

    static PlayActionRequest hardRequest(AIPlayer bot, GameContext context, HardAiPlayStrategy fallback) {
        RecordingBridge recording = new RecordingBridge();
        try {
            if (fallback.tryPlayOneCard(bot, context, recording)) {
                return recording.request;
            }
        } catch (RuntimeException ex) {
            return null;
        }
        return null;
    }

    static void setCloneAiStrategiesToHard(GameController clone) {
        for (Player player : clone.getSessionPlayersView()) {
            if (player instanceof AIPlayer ai) {
                ai.setPlayStrategy(new BlindHardRolloutStrategy());
            }
        }
    }

    static String currentPhase(GameContext context) {
        return context.getCurrentTurnPhase() == null
                ? ""
                : context.getCurrentTurnPhase().trim().toUpperCase(Locale.ROOT);
    }

    static Player playerById(GameController controller, String playerId) {
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

    enum RolloutStep {
        CONTINUE,
        STOP_AFTER_HIDDEN_DRAW,
        NO_PLAY
    }

    static final class BlindHardRolloutStrategy implements AiPlayStrategy, AiChoiceAdvisor {
        private final HardAiPlayStrategy hard = new HardAiPlayStrategy();

        @Override
        public boolean tryPlayOneCard(AIPlayer bot, GameContext context, AiGameBridge bridge) {
            return hard.tryPlayOneCard(bot, context, bridge);
        }

        @Override
        public AiHeuristics.AiResponseDecision chooseResponse(
                AIPlayer bot,
                GameContext context,
                boolean counterRole) {
            return AiHeuristics.AiResponseDecision.pass();
        }

        @Override
        public PaymentSettlement.PaymentChoice choosePayment(
                AIPlayer bot,
                GameContext context,
                Player creditor,
                int amountDue,
                PaymentSettlement.PaymentChoice fallbackChoice) {
            return fallbackChoice;
        }

        @Override
        public List<Card> chooseOverflowDiscards(
                AIPlayer bot,
                GameContext context,
                int limit,
                List<Card> fallbackCards) {
            return fallbackCards;
        }
    }

    static final class RecordingBridge implements AiGameBridge {
        PlayActionRequest request;

        @Override
        public void submitPlayAction(PlayActionRequest request) {
            this.request = request;
        }
    }

    static final class CapturingSubject implements GameUpdateSubject {
        private final AtomicReference<GameStateSnapshot> last = new AtomicReference<>();

        @Override
        public void registerObserver(GameUpdateObserver observer) {
        }

        @Override
        public void unregisterObserver(GameUpdateObserver observer) {
        }

        @Override
        public void notifyStateChanged(GameStateSnapshot snapshot) {
            last.set(snapshot);
        }

        GameStateSnapshot last() {
            return last.get();
        }
    }
}