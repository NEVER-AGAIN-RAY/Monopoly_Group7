package com.monopoly.controller;

import com.monopoly.model.card.ActionCard;
import com.monopoly.model.card.Card;
import com.monopoly.model.card.PropertyCard;
import com.monopoly.model.player.AIPlayer;
import com.monopoly.model.player.Player;
import com.monopoly.pattern.singleton.GameEngineSingleton;
import com.monopoly.dto.ActionParamContext;
import com.monopoly.model.effects.ActionEffectContext;
import com.monopoly.model.effects.ActionEffectResult;
import com.monopoly.model.settlement.PropertySetCalculator;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Turn lifecycle: draw, play, discard, end turn, action cards (extracted from GameController).
 * <p>
 * Holds per-turn state via TurnState; uses GameController for session checks, snapshots, and errors.
 */
final class TurnFlowService {

    enum TurnPhase {
        DRAW,
        PLAY,
        /** Rent/waiver chain: wait for Just Say No or pass */
        WAITING_FOR_RESPONSE,
        END_TURN
    }

    static final int MAX_ACTIONS_PER_TURN = 3;
    static final int MAX_HAND_SIZE = 7;
    static final int INITIAL_HAND_SIZE = 5;

    private final GameController controller;
    private final GameEngineSingleton engine;
    private final TurnState turnState = new TurnState();
    private EffectStackOrchestrator effectStack;
    private final OverflowDiscardService overflowDiscardService;
    private final CardPlayService cardPlayService;
    private final ActionCardPlayService actionCardPlayService;

    TurnFlowService(GameController controller) {
        this.controller = controller;
        this.engine = controller.getEngine();
        this.overflowDiscardService = new OverflowDiscardService(controller, engine, turnState);
        this.cardPlayService = new CardPlayService(controller, turnState);
        this.actionCardPlayService = new ActionCardPlayService(controller, engine, turnState);
    }

    void wireEffectStack(EffectStackOrchestrator eso) {
        this.effectStack = eso;
        actionCardPlayService.wireEffectStack(eso);
    }

    // --- delegate accessors for encapsulation ---

    TurnPhase phase() {
        return turnState.phase();
    }

    int actionCount() {
        return turnState.actionCount();
    }

    String currentTurnPlayerId() {
        return turnState.currentPlayerId();
    }

    void enterWaitingForResponse() {
        turnState.enterWaitingForResponse();
    }

    void resumeToPlayOrEnd(int actionCountThreshold) {
        turnState.resumeToPlayOrEnd(actionCountThreshold);
    }

    TurnState turnState() {
        return turnState;
    }

    void initForSession(Player firstPlayer) {
        turnState.initForSession(firstPlayer != null ? firstPlayer.getPlayerId() : null);
    }

    // --- draw ---

    void drawCards(Player player, int ignoredRequestedCount) {
        if (player == null) {
            return;
        }
        controller.ensureSessionActive();
        turnState.ensureTurnContext(player);
        if (turnState.phase() == TurnPhase.WAITING_FOR_RESPONSE) {
            throw new IllegalStateException("Awaiting rent response; cannot draw.");
        }
        if (turnState.phase() != TurnPhase.DRAW) {
            throw new IllegalStateException("Not in DRAW phase; cannot draw again.");
        }
        int effectiveCount = player.getHandCardCount() == 0 ? 5 : 2;
        int drawn = 0;
        for (int i = 0; i < effectiveCount; i++) {
            Card card = engine.drawOne();
            if (card == null) {
                break;
            }
            player.receiveCardToHand(card);
            drawn++;
        }
        if (checkWinCondition(player)) {
            controller.endSessionNaturally(
                    player.getDisplayName() + " wins (3 complete property sets).");
            return;
        }
        turnState.enterPlay();
        controller.assertDeckIntegrityOrLog();
        controller.pushSnapshot(controller.getCurrentSessionId(), "DRAW",
                player.getDisplayName() + " drew " + drawn + " card(s).");
    }

    void skipDrawAfterAiFailure(AIPlayer ai) {
        if (ai == null || turnState.phase() != TurnPhase.DRAW) {
            return;
        }
        turnState.ensureTurnContext(ai);
        turnState.enterPlay();
        controller.pushSnapshot(controller.getCurrentSessionId(), "AI_DECISION_FAILED",
                ai.getDisplayName() + " AI draw decision failed; skipping to end turn.");
    }

    // --- play ---

    void playCard(Player player, Card card, String actionType, ActionParamContext params) {
        cardPlayService.playCard(player, card, actionType, params);
    }

    // --- discard ---

    void discardFromHand(Player player, Card card) {
        if (player == null || card == null) {
            return;
        }
        controller.ensureSessionActive();
        turnState.ensureTurnContext(player);
        if (turnState.phase() == TurnPhase.WAITING_FOR_RESPONSE) {
            throw new IllegalStateException("Awaiting rent response; cannot discard.");
        }
        if (!player.getHandCardsView().contains(card)) {
            throw new IllegalStateException("Card not in current player's hand; cannot discard.");
        }
        boolean forcedOverflowDiscard =
                player.getHandCardCount() > MAX_HAND_SIZE
                        && (turnState.mustDiscardOverflow() || turnState.phase() == TurnPhase.END_TURN);
        if (forcedOverflowDiscard) {
            if (overflowDiscardService.handleForcedOverflowDiscard(player, card)) {
                return;
            }
        }
        turnState.ensureTurnActionAvailable();
        if (turnState.phase() != TurnPhase.PLAY) {
            throw new IllegalStateException("Not in PLAY phase; cannot discard.");
        }
        turnState.incrementAction();
        if (!player.discardFromHand(card)) {
            turnState.decrementAction();
            throw new IllegalStateException("Failed to discard card from hand.");
        }
        engine.discard(card);
        if (turnState.actionCount() >= MAX_ACTIONS_PER_TURN) {
            turnState.markEndTurn();
        }
        controller.pushSnapshot(
                controller.getCurrentSessionId(),
                "DISCARD",
                player.getDisplayName() + " discarded a card (" + card.getName() + ").",
                player,
                card,
                "DISCARD");
    }

    void forceDiscardOverflowToLimit(Player player) {
        overflowDiscardService.forceDiscardOverflowToLimit(player);
    }

    // --- rejected legacy wild property recolor command ---

    void reassignWildProperty(Player player, String wildPropertyCardId, String newColorKey) {
        throw new UnsupportedOperationException("Wild property color cannot be changed once assigned.");
    }

    // --- end turn ---

    /**
     * Ends the turn: trim hand to 7, check win, advance turn order.
     * AI is started by GameController.endTurn, not here.
     *
     * @return next player, or null if the game ended
     */
    Player endTurn(Player player) {
        if (player == null) {
            return null;
        }
        controller.ensureNotPaused();
        controller.ensureSessionActive();
        turnState.ensureTurnContext(player);
        if (turnState.phase() == TurnPhase.WAITING_FOR_RESPONSE) {
            throw new IllegalStateException("Awaiting rent response; cannot end turn.");
        }
        if (turnState.phase() == TurnPhase.DRAW) {
            throw new IllegalStateException("Must draw before ending turn.");
        }

        if (player.getHandCardCount() > MAX_HAND_SIZE) {
            turnState.setMustDiscardOverflow(true);
            turnState.markEndTurn();
            int need = player.getHandCardCount() - MAX_HAND_SIZE;
            controller.pushSnapshot(controller.getCurrentSessionId(), "FORCE_DISCARD_REQUIRED",
                    player.getDisplayName() + " has " + need + " cards over limit; must discard before proceeding.");
            return player;
        }
        turnState.setMustDiscardOverflow(false);
        controller.assertDeckIntegrityOrLog();

        if (checkWinCondition(player)) {
            controller.endSessionNaturally(
                    player.getDisplayName() + " wins (3 complete property sets).");
            return null;
        }

        discardActionZonesForTurn();
        controller.getGameContext().clearPendingDoubleRent();

        TurnManager tm = controller.getTurnManager();
        tm.advanceTurn();

        Player next = tm.getCurrentPlayer();
        turnState.initForSession(next != null ? next.getPlayerId() : null);

        controller.onTurnAdvanced(next);

        controller.pushSnapshot(controller.getCurrentSessionId(), "TURN_END",
                player.getDisplayName() + " ended turn.");

        return next;
    }

    private void discardActionZonesForTurn() {
        List<Card> discarded = new ArrayList<>();
        for (Player p : controller.getSessionPlayersView()) {
            if (p != null && p.getActionZoneCardCount() > 0) {
                discarded.addAll(p.clearActionZone());
            }
        }
        engine.discardMany(discarded);
    }

    // --- action cards (delegated to ActionCardPlayService) ---

    ActionEffectResult handleActionCardCommand(
            int handIndex, String targetPlayerId, String colorKey,
            int targetPropIndex, int actorPropIndex) {
        return actionCardPlayService.handleActionCardCommand(handIndex, targetPlayerId, colorKey, targetPropIndex, actorPropIndex);
    }

    ActionEffectResult handleActionCardCommand(ActionParamContext params) {
        return actionCardPlayService.handleActionCardCommand(params);
    }

    ActionEffectResult playActionCard(
            Player actor, ActionCard card, ActionEffectContext ctx, ActionParamContext params) {
        return actionCardPlayService.playActionCard(actor, card, ctx, params);
    }

    Card resolveCardInHand(Player actor, String cardId, Integer handIndex) {
        return actionCardPlayService.resolveCardInHand(actor, cardId, handIndex);
    }

    PropertyCard resolvePropertyCardById(Player owner, String propertyCardId) {
        return actionCardPlayService.resolvePropertyCardById(owner, propertyCardId);
    }

    boolean checkWinCondition(Player player) {
        return player.countCompletePropertySets() >= 3;
    }

    static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    static String normalizeWildReassignColorKey(String newColorKey) {
        if (newColorKey == null || newColorKey.isBlank()) {
            throw new IllegalArgumentException("newColorKey must not be blank.");
        }
        String key = newColorKey.trim().toUpperCase(Locale.ROOT);
        if (!PropertySetCalculator.REQUIRED_BY_COLOR.containsKey(key)) {
            throw new IllegalArgumentException("Invalid color key; must be a standard track color: " + key);
        }
        return key;
    }
}
