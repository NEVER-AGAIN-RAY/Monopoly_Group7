package com.monopoly.controller;

import com.monopoly.model.card.ActionCard;
import com.monopoly.model.card.Card;
import com.monopoly.model.effects.EffectStackEntry;
import com.monopoly.model.core.GameContext;
import com.monopoly.model.card.PropertyCard;
import com.monopoly.model.settlement.PropertySetCalculator;
import com.monopoly.model.card.PropertyWildCard;
import com.monopoly.model.settlement.StealTargetZone;
import com.monopoly.dto.ActionParamContext;
import com.monopoly.model.effects.ActionEffectContext;
import com.monopoly.model.effects.ActionEffectDispatcher;
import com.monopoly.model.effects.ActionEffectResult;
import com.monopoly.model.effects.RentEffect;
import com.monopoly.model.core.RentChargeSequence;
import com.monopoly.model.player.AIPlayer;
import com.monopoly.model.player.Player;
import com.monopoly.pattern.strategy.AiChoiceAdvisor;
import com.monopoly.pattern.singleton.GameEngineSingleton;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Turn lifecycle: draw, play, discard, end turn, action cards (extracted from GameController).
 * <p>
 * Holds per-turn state（currentTurnPlayerId、currentTurnPhase、
 * currentTurnActionCount）；Uses GameController for session checks, snapshots, and errors.
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
    private EffectStackOrchestrator effectStack;

    String currentTurnPlayerId;
    int currentTurnActionCount;
    TurnPhase currentTurnPhase;
    private boolean currentTurnMustDiscardOverflow;

    TurnFlowService(GameController controller) {
        this.controller = controller;
        this.engine = controller.getEngine();
        this.currentTurnPhase = TurnPhase.DRAW;
    }

    void wireEffectStack(EffectStackOrchestrator eso) {
        this.effectStack = eso;
    }

    void initForSession(Player firstPlayer) {
        this.currentTurnPlayerId = firstPlayer != null ? firstPlayer.getPlayerId() : null;
        this.currentTurnActionCount = 0;
        this.currentTurnPhase = TurnPhase.DRAW;
        this.currentTurnMustDiscardOverflow = false;
    }

    // --- draw ---

    void drawCards(Player player, int ignoredRequestedCount) {
        if (player == null) {
            return;
        }
        controller.ensureSessionActive();
        ensureTurnContext(player);
        if (currentTurnPhase == TurnPhase.WAITING_FOR_RESPONSE) {
            throw new IllegalStateException("Awaiting rent response; cannot draw.");
        }
        if (currentTurnPhase != TurnPhase.DRAW) {
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
        currentTurnPhase = TurnPhase.PLAY;
        controller.assertDeckIntegrityOrLog();
        controller.pushSnapshot(controller.getCurrentSessionId(), "DRAW",
                player.getDisplayName() + " drew " + drawn + " card(s).");
    }

    void skipDrawAfterAiFailure(AIPlayer ai) {
        if (ai == null || currentTurnPhase != TurnPhase.DRAW) {
            return;
        }
        ensureTurnContext(ai);
        currentTurnPhase = TurnPhase.PLAY;
        controller.pushSnapshot(controller.getCurrentSessionId(), "AI_DECISION_FAILED",
                ai.getDisplayName() + " AI draw decision failed; skipping to end turn.");
    }

    // --- play ---

    void playCard(Player player, Card card, String actionType, ActionParamContext params) {
        if (player == null || card == null) {
            return;
        }
        controller.ensureSessionActive();
        ensureTurnContext(player);
        if (currentTurnPhase == TurnPhase.WAITING_FOR_RESPONSE) {
            throw new IllegalStateException("Awaiting rent response; cannot play cards.");
        }
        ensureNoPendingOverflowDiscard(player, "playing a card");
        ensureTurnActionAvailable();
        if (currentTurnPhase != TurnPhase.PLAY) {
            throw new IllegalStateException("Not in PLAY phase; please draw first.");
        }
        if (!player.getHandCardsView().contains(card)) {
            throw new IllegalStateException("Card not in current player's hand; cannot play.");
        }
        if (actionType == null || actionType.isBlank()) {
            throw new IllegalArgumentException("actionType must not be blank.");
        }
        String normalizedActionType = actionType.trim().toUpperCase();

        currentTurnActionCount++;

        if ("DEPOSIT".equals(normalizedActionType)) {
            player.depositToBank(card);
        } else if ("DEPLOY".equals(normalizedActionType)) {
            if (!(card instanceof PropertyCard)) {
                throw new IllegalArgumentException("DEPLOY requires a PropertyCard.");
            }
            if (card instanceof PropertyWildCard wild) {
                String requested = params != null ? blankToNull(params.getTargetColorKey()) : null;
                String current = wild.getAssignedColorKey();
                if (current != null && requested != null && !current.equalsIgnoreCase(requested)) {
                    throw new IllegalStateException("Wild property already assigned " + current + "; cannot change to " + requested + ".");
                }
                String assign = current != null ? current : requested;
                if (assign == null) {
                    throw new IllegalArgumentException("Deploying a wild property requires targetColorKey.");
                }
                wild.setAssignedColorKey(assign);
            }
            player.deployProperty((PropertyCard) card);
        } else if ("ACTION".equals(normalizedActionType)) {
            if (!(card instanceof ActionCard)) {
                throw new IllegalArgumentException("ACTION requires an ActionCard.");
            }
            player.placeActionToCenter((ActionCard) card);
        } else {
            throw new IllegalArgumentException("Unknown actionType: " + actionType);
        }

        if (currentTurnActionCount >= MAX_ACTIONS_PER_TURN) {
            currentTurnPhase = TurnPhase.END_TURN;
        }

        controller.pushSnapshot(
                controller.getCurrentSessionId(),
                normalizedActionType,
                player.getDisplayName() + " played " + normalizedActionType + " (" + card.getName() + ").",
                player,
                card,
                normalizedActionType);
    }

    // --- discard ---

    void discardFromHand(Player player, Card card) {
        if (player == null || card == null) {
            return;
        }
        controller.ensureSessionActive();
        ensureTurnContext(player);
        if (currentTurnPhase == TurnPhase.WAITING_FOR_RESPONSE) {
            throw new IllegalStateException("Awaiting rent response; cannot discard.");
        }
        if (!player.getHandCardsView().contains(card)) {
            throw new IllegalStateException("Card not in current player's hand; cannot discard.");
        }
        boolean forcedOverflowDiscard =
                player.getHandCardCount() > MAX_HAND_SIZE
                        && (currentTurnMustDiscardOverflow || currentTurnPhase == TurnPhase.END_TURN);
        if (forcedOverflowDiscard) {
            if (currentTurnPhase != TurnPhase.PLAY && currentTurnPhase != TurnPhase.END_TURN) {
                throw new IllegalStateException("Cannot discard in current phase.");
            }
            if (!player.discardFromHand(card)) {
                throw new IllegalStateException("Failed to discard card from hand.");
            }
            engine.discard(card);
            if (player.getHandCardCount() <= MAX_HAND_SIZE) {
                currentTurnMustDiscardOverflow = false;
            }
            controller.pushSnapshot(
                    controller.getCurrentSessionId(),
                    "FORCE_DISCARD",
                    player.getDisplayName() + " force-discarded overflow card (" + card.getName() + ").",
                    player,
                    card,
                    "DISCARD");
            return;
        }
        ensureTurnActionAvailable();
        if (currentTurnPhase != TurnPhase.PLAY) {
            throw new IllegalStateException("Not in PLAY phase; cannot discard.");
        }
        currentTurnActionCount++;
        if (!player.discardFromHand(card)) {
            currentTurnActionCount--;
            throw new IllegalStateException("Failed to discard card from hand.");
        }
        engine.discard(card);
        if (currentTurnActionCount >= MAX_ACTIONS_PER_TURN) {
            currentTurnPhase = TurnPhase.END_TURN;
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
        if (player == null || player.getHandCardCount() <= MAX_HAND_SIZE) {
            return;
        }
        controller.ensureSessionActive();
        ensureTurnContext(player);
        if (currentTurnPhase == TurnPhase.WAITING_FOR_RESPONSE) {
            throw new IllegalStateException("Awaiting rent response; cannot force-discard.");
        }
        List<Card> chosen = player.chooseOverflowDiscardsTo(MAX_HAND_SIZE);
        if (player instanceof AIPlayer ai && ai.getPlayStrategy() instanceof AiChoiceAdvisor advisor) {
            controller.refreshAiDecisionContext();
            controller.attachAuxiliaryDecisionMementoForTrace();
            chosen = advisor.chooseOverflowDiscards(
                    ai, controller.getGameContext(), MAX_HAND_SIZE, chosen);
        }
        List<Card> discarded = player.discardSpecificFromHand(chosen);
        while (player.getHandCardCount() > MAX_HAND_SIZE) {
            discarded.addAll(player.discardOverflowTo(MAX_HAND_SIZE));
        }
        engine.discardMany(discarded);
        currentTurnMustDiscardOverflow = false;
        controller.pushSnapshot(controller.getCurrentSessionId(), "FORCE_DISCARD",
                player.getDisplayName() + " force-discarded "
                        + discarded.size() + " overflow card(s).");
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
        ensureTurnContext(player);
        if (currentTurnPhase == TurnPhase.WAITING_FOR_RESPONSE) {
            throw new IllegalStateException("Awaiting rent response; cannot end turn.");
        }
        if (currentTurnPhase == TurnPhase.DRAW) {
            throw new IllegalStateException("Must draw before ending turn.");
        }

        if (player.getHandCardCount() > MAX_HAND_SIZE) {
            currentTurnMustDiscardOverflow = true;
            currentTurnPhase = TurnPhase.END_TURN;
            int need = player.getHandCardCount() - MAX_HAND_SIZE;
            controller.pushSnapshot(controller.getCurrentSessionId(), "FORCE_DISCARD_REQUIRED",
                    player.getDisplayName() + " has " + need + " cards over limit; must discard before proceeding.");
            return player;
        }
        currentTurnMustDiscardOverflow = false;
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
        this.currentTurnPlayerId = next != null ? next.getPlayerId() : null;
        this.currentTurnActionCount = 0;
        this.currentTurnPhase = TurnPhase.DRAW;
        this.currentTurnMustDiscardOverflow = false;

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

    // --- action cards ---

    ActionEffectResult handleActionCardCommand(
            int handIndex, String targetPlayerId, String colorKey,
            int targetPropIndex, int actorPropIndex) {

        controller.ensureSessionActive();
        Player actor = controller.requireCurrentPlayer();
        if (handIndex < 0 || handIndex >= actor.getHandCardsView().size()) {
            throw new IllegalArgumentException("handIndex out of bounds.");
        }
        String cardId = actor.getHandCardsView().get(handIndex).getId();

        Player target = controller.resolvePlayer(targetPlayerId);
        String targetCardId = null;
        if (target != null && targetPropIndex >= 0
                && targetPropIndex < target.getPropertyCardsView().size()) {
            targetCardId = target.getPropertyCardsView().get(targetPropIndex).getId();
        }
        String actorCardId = null;
        if (actorPropIndex >= 0 && actorPropIndex < actor.getPropertyCardsView().size()) {
            actorCardId = actor.getPropertyCardsView().get(actorPropIndex).getId();
        }

        ActionParamContext params = new ActionParamContext(
                cardId, null, targetPlayerId, colorKey, targetCardId, actorCardId, null);
        return handleActionCardCommand(params);
    }

    ActionEffectResult handleActionCardCommand(ActionParamContext params) {
        if (params == null) {
            throw new IllegalArgumentException("ActionParamContext must not be null.");
        }
        controller.ensureSessionActive();
        Player actor = controller.requireCurrentPlayer();
        Card card = resolveCardInHand(actor, params.getCardId(), params.getHandIndex());
        if (!(card instanceof ActionCard actionCard)) {
            throw new IllegalArgumentException("Specified card is not an action card; cannot trigger effect.");
        }

        Player target = controller.resolvePlayer(params.getTargetPlayerId());
        String effectCode = actionCard.getEffectCode();
        String ec = effectCode == null ? "" : effectCode.trim().toUpperCase();

        PropertyCard targetProp = null;
        Card targetBankCard = null;
        StealTargetZone stealZone = StealTargetZone.PROPERTY;

        if ("STEAL_PROPERTY".equals(ec)) {
            stealZone = StealTargetZone.fromParam(params.getTargetZone());
            if (stealZone == StealTargetZone.BANK) {
                targetBankCard = target != null
                        ? resolveBankCardById(target, params.getTargetCardId()) : null;
            } else {
                targetProp = target != null
                        ? resolvePropertyCardById(target, params.getTargetCardId()) : null;
            }
        } else if (!"HOUSE".equals(ec) && !"HOTEL".equals(ec) && !"PASS_GO".equals(ec)
                && !"RENT_DUAL".equals(ec)) {
            targetProp = target != null
                    ? resolvePropertyCardById(target, params.getTargetCardId()) : null;
        }

        PropertyCard actorProp;
        if ("HOUSE".equals(ec) || "HOTEL".equals(ec)) {
            actorProp = resolvePropertyCardById(actor, params.getActorCardId());
            if (actorProp == null) {
                actorProp = resolvePropertyCardById(actor, params.getTargetCardId());
            }
        } else {
            actorProp = resolvePropertyCardById(actor, params.getActorCardId());
        }

        ActionEffectContext ctx = ActionEffectContext
                .builder(actor, engine, Collections.unmodifiableList(controller.getSessionPlayersView()))
                .target(target)
                .colorKey(blankToNull(params.getTargetColorKey()))
                .targetProperty(targetProp)
                .actorProperty(actorProp)
                .targetBankCard(targetBankCard)
                .stealTargetZone(stealZone)
                .build();

        return playActionCard(actor, actionCard, ctx, params);
    }

    ActionEffectResult playActionCard(
            Player actor, ActionCard card, ActionEffectContext ctx, ActionParamContext params) {
        if (actor == null || card == null) {
            throw new IllegalArgumentException("actor and card must not be null.");
        }
        controller.ensureSessionActive();
        ensureTurnContext(actor);
        if (currentTurnPhase == TurnPhase.WAITING_FOR_RESPONSE) {
            throw new IllegalStateException("Awaiting rent response; cannot play action card.");
        }
        ensureNoPendingOverflowDiscard(actor, "playing an action card");
        ensureTurnActionAvailable();
        if (currentTurnPhase != TurnPhase.PLAY) {
            throw new IllegalStateException("Not in PLAY phase; please draw first.");
        }
        if (!actor.getHandCardsView().contains(card)) {
            throw new IllegalStateException("Card not in current player's hand; cannot play.");
        }

        GameContext gameContext = controller.getGameContext();
        gameContext.bindPlayers(controller.getSessionPlayersView());
        if (!card.canPlay(actor, params, gameContext)) {
            throw new IllegalStateException("Current rules do not allow playing this action card.");
        }

        String effectCodeStr = card.getEffectCode() == null
                ? "" : card.getEffectCode().trim().toUpperCase();

        if ("RENT".equals(effectCodeStr)) {
            RentEffect.DueResult due = RentEffect.computeDue(ctx);
            if (!due.isOk()) {
                throw new IllegalStateException(due.getError());
            }
            int amountDue = consumePendingDoubleRentAmount(gameContext, actor, due.getAmountDue());
            currentTurnActionCount++;
            actor.placeActionToCenter(card);
            EffectStackEntry rentEntry = EffectStackEntry.pendingRent(
                    actor.getPlayerId(),
                    ctx.getTarget().getPlayerId(),
                    ctx.getTargetColorKey(),
                    amountDue,
                    card.getName(),
                    card.getEffectCode());
            gameContext.pushEffect(rentEntry);
            effectStack.enterRentResponseWindow(ctx.getTarget(), actor, card);
            ActionEffectResult result = ActionEffectResult.success(
                    "Rent entered stack; awaiting opponent " + responseWindowPrompt() + ".");
            System.out.println("[ACTION] " + result.getMessage());
            return result;
        }

        if ("DOUBLE_RENT".equals(effectCodeStr)) {
            currentTurnActionCount++;
            actor.placeActionToCenter(card);
            gameContext.setPendingDoubleRentFor(actor.getPlayerId());
            if (currentTurnActionCount >= MAX_ACTIONS_PER_TURN) {
                currentTurnPhase = TurnPhase.END_TURN;
            }
            ActionEffectResult result = ActionEffectResult.success(
                    "Double The Rent in effect: next rent card amount is doubled.");
            controller.pushSnapshot(
                    controller.getCurrentSessionId(),
                    "ACTION_SUCCESS",
                    actor.getDisplayName() + " played ACTION (" + card.getName() + "): "
                            + result.getMessage(),
                    actor,
                    card,
                    "ACTION");
            System.out.println("[ACTION] " + result.getMessage());
            return result;
        }

        if ("RENT_DUAL".equals(effectCodeStr)) {
            if (card.isRentDualChargesEachOtherPlayer()) {
                RentEffect.DueResult dueAll = RentEffect.computeDueLandlordColorOnly(ctx);
                if (!dueAll.isOk()) {
                    throw new IllegalStateException(dueAll.getError());
                }
                int amountDue = consumePendingDoubleRentAmount(gameContext, actor, dueAll.getAmountDue());
                List<String> tenantIds = new ArrayList<>();
                for (Player p : controller.getSessionPlayersView()) {
                    if (p != null && !p.getPlayerId().equals(actor.getPlayerId())) {
                        tenantIds.add(p.getPlayerId());
                    }
                }
                if (tenantIds.isEmpty()) {
                    throw new IllegalStateException("No other players to charge rent to.");
                }
                currentTurnActionCount++;
                actor.placeActionToCenter(card);
                gameContext.clearRentChargeSequence();
                gameContext.setRentChargeSequence(new RentChargeSequence(
                        actor.getPlayerId(),
                        ctx.getTargetColorKey(),
                        amountDue,
                        tenantIds,
                        card.getName(),
                        card.getEffectCode()));
                Player firstTenant = controller.resolvePlayer(tenantIds.get(0));
                if (firstTenant == null) {
                    gameContext.clearRentChargeSequence();
                    throw new IllegalStateException("Tenant player not found.");
                }
                gameContext.pushEffect(EffectStackEntry.pendingRent(
                        actor.getPlayerId(),
                        firstTenant.getPlayerId(),
                        ctx.getTargetColorKey(),
                        amountDue,
                        card.getName(),
                        card.getEffectCode()));
                effectStack.enterRentResponseWindow(firstTenant, actor, card);
                ActionEffectResult result = ActionEffectResult.success(
                        "Dual-rent (all players) entered stack; will charge each other player sequentially; now awaiting "
                                + firstTenant.getDisplayName()
                                + " " + responseWindowPrompt() + ".");
                System.out.println("[ACTION] " + result.getMessage());
                return result;
            }
            RentEffect.DueResult due = RentEffect.computeDue(ctx);
            if (!due.isOk()) {
                throw new IllegalStateException(due.getError());
            }
            int amountDue = consumePendingDoubleRentAmount(gameContext, actor, due.getAmountDue());
            currentTurnActionCount++;
            actor.placeActionToCenter(card);
            EffectStackEntry rentEntry = EffectStackEntry.pendingRent(
                    actor.getPlayerId(),
                    ctx.getTarget().getPlayerId(),
                    ctx.getTargetColorKey(),
                    amountDue,
                    card.getName(),
                    card.getEffectCode());
            gameContext.pushEffect(rentEntry);
            effectStack.enterRentResponseWindow(ctx.getTarget(), actor, card);
            ActionEffectResult result = ActionEffectResult.success(
                    "Dual-rent entered stack; awaiting opponent " + responseWindowPrompt() + ".");
            System.out.println("[ACTION] " + result.getMessage());
            return result;
        }

        if ("BIRTHDAY".equals(effectCodeStr)) {
            List<String> tenantIds = otherPlayerIds(actor);
            if (tenantIds.isEmpty()) {
                throw new IllegalStateException("No other players to collect birthday gift from.");
            }
            currentTurnActionCount++;
            actor.placeActionToCenter(card);
            gameContext.clearRentChargeSequence();
            gameContext.setRentChargeSequence(new RentChargeSequence(
                    actor.getPlayerId(),
                    "BIRTHDAY",
                    2,
                    tenantIds,
                    card.getName(),
                    card.getEffectCode()));
            Player firstTenant = controller.resolvePlayer(tenantIds.get(0));
            if (firstTenant == null) {
                gameContext.clearRentChargeSequence();
                throw new IllegalStateException("Birthday gift target player not found.");
            }
            gameContext.pushEffect(EffectStackEntry.pendingRent(
                    actor.getPlayerId(),
                    firstTenant.getPlayerId(),
                    "BIRTHDAY",
                    2,
                    card.getName(),
                    card.getEffectCode()));
            effectStack.enterRentResponseWindow(firstTenant, actor, card);
            ActionEffectResult result = ActionEffectResult.success(
                    "Birthday gift entered stack; will collect 2M from each other player; now awaiting "
                            + firstTenant.getDisplayName()
                            + " " + responseWindowPrompt() + ".");
            System.out.println("[ACTION] " + result.getMessage());
            return result;
        }

        if ("DEBT_COLLECTOR".equals(effectCodeStr)) {
            if (ctx.getTarget() == null) {
                throw new IllegalStateException("Debt collector requires a target player.");
            }
            currentTurnActionCount++;
            actor.placeActionToCenter(card);
            EffectStackEntry debtEntry = EffectStackEntry.pendingRent(
                    actor.getPlayerId(),
                    ctx.getTarget().getPlayerId(),
                    "DEBT_COLLECTOR",
                    5,
                    card.getName(),
                    card.getEffectCode());
            gameContext.pushEffect(debtEntry);
            effectStack.enterRentResponseWindow(ctx.getTarget(), actor, card);
            ActionEffectResult result = ActionEffectResult.success(
                    "Debt collector entered stack; awaiting opponent " + responseWindowPrompt() + " or pay 5M.");
            System.out.println("[ACTION] " + result.getMessage());
            return result;
        }

        currentTurnActionCount++;
        actor.placeActionToCenter(card);

        if (isSingleTargetJustSayNoAction(effectCodeStr) && ctx.getTarget() != null) {
            int actionCountAfterPlay = currentTurnActionCount;
            effectStack.enterActionResponseWindow(
                    ctx.getTarget(),
                    card,
                    () -> ActionEffectDispatcher.dispatch(card.getEffectCode(), ctx),
                    actionCountAfterPlay);
            ActionEffectResult result = ActionEffectResult.success(
                    card.getName() + " entered stack; awaiting " + ctx.getTarget().getDisplayName()
                            + " " + responseWindowPrompt() + ".");
            System.out.println("[ACTION] " + result.getMessage());
            return result;
        }

        ActionEffectResult result = ActionEffectDispatcher.dispatch(card.getEffectCode(), ctx);

        if (currentTurnActionCount >= MAX_ACTIONS_PER_TURN) {
            currentTurnPhase = TurnPhase.END_TURN;
        }

        String phase = result.isSuccess() ? "ACTION_SUCCESS"
                : (result.getStatus() == ActionEffectResult.Status.COUNTERED
                        ? "ACTION_COUNTERED" : "ACTION_FAILED");
        String actionSummary = actor.getDisplayName() + " played ACTION (" + card.getName() + ")"
                + ": " + (result.getMessage() != null ? result.getMessage() : phase);
        controller.pushSnapshot(
                controller.getCurrentSessionId(),
                phase,
                actionSummary,
                actor,
                card,
                "ACTION");

        System.out.println("[ACTION] " + result.getMessage());
        return result;
    }

    private static boolean isSingleTargetJustSayNoAction(String effectCode) {
        return switch (effectCode) {
            case "STEAL_PROPERTY", "FORCED_DEAL", "DEAL_BREAKER" -> true;
            default -> false;
        };
    }

    private List<String> otherPlayerIds(Player actor) {
        List<String> ids = new ArrayList<>();
        for (Player p : controller.getSessionPlayersView()) {
            if (p != null && actor != null && !p.getPlayerId().equals(actor.getPlayerId())) {
                ids.add(p.getPlayerId());
            }
        }
        return ids;
    }

    private static int consumePendingDoubleRentAmount(GameContext gameContext, Player actor, int baseAmountDue) {
        if (gameContext != null && actor != null && gameContext.hasPendingDoubleRentFor(actor.getPlayerId())) {
            gameContext.clearPendingDoubleRent();
            return baseAmountDue * 2;
        }
        return baseAmountDue;
    }

    private String responseWindowPrompt() {
        return "within " + EffectStackOrchestrator.RESPONSE_WINDOW_SECONDS + " seconds";
    }

    // --- helpers ---

    void ensureTurnContext(Player player) {
        String pid = player.getPlayerId();
        if (currentTurnPlayerId == null) {
            currentTurnPlayerId = pid;
            currentTurnActionCount = 0;
            currentTurnPhase = TurnPhase.DRAW;
            currentTurnMustDiscardOverflow = false;
            return;
        }
        if (!currentTurnPlayerId.equals(pid)) {
            throw new IllegalStateException("Not player " + pid + "'s turn.");
        }
    }

    private void ensureNoPendingOverflowDiscard(Player player, String attemptedAction) {
        if (!currentTurnMustDiscardOverflow) {
            return;
        }
        if (player != null && player.getHandCardCount() <= MAX_HAND_SIZE) {
            currentTurnMustDiscardOverflow = false;
            return;
        }
        throw new IllegalStateException(
                "Must discard down to 7 cards before " + attemptedAction + ".");
    }

    private void ensureTurnActionAvailable() {
        if (currentTurnActionCount >= MAX_ACTIONS_PER_TURN) {
            throw new IllegalStateException("Maximum 3 actions per turn reached.");
        }
    }

    Card resolveCardInHand(Player actor, String cardId, Integer handIndex) {
        List<Card> hand = actor.getHandCardsView();
        if (cardId != null && !cardId.isBlank()) {
            for (Card c : hand) {
                if (cardId.equals(c.getId())) {
                    return c;
                }
            }
            throw new IllegalArgumentException("Card not in hand with id \"" + cardId + "\".");
        }
        if (handIndex != null && handIndex >= 0 && handIndex < hand.size()) {
            return hand.get(handIndex);
        }
        throw new IllegalArgumentException("Provide a valid cardId or handIndex.");
    }

    PropertyCard resolvePropertyCardById(Player owner, String propertyCardId) {
        if (owner == null || propertyCardId == null || propertyCardId.isBlank()) {
            return null;
        }
        for (PropertyCard pc : owner.getPropertyCardsView()) {
            if (propertyCardId.equals(pc.getId())) {
                return pc;
            }
        }
        throw new IllegalArgumentException("No property card with id \"" + propertyCardId + "\" in player " + owner.getPlayerId() + "'s property zone.");
    }

    private Card resolveBankCardById(Player owner, String cardId) {
        if (owner == null || cardId == null || cardId.isBlank()) {
            return null;
        }
        for (Card c : owner.getBankCardsView()) {
            if (cardId.equals(c.getId())) {
                return c;
            }
        }
        throw new IllegalArgumentException("No card with id \"" + cardId + "\" in player " + owner.getPlayerId() + "'s bank.");
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
