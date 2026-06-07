package com.monopoly.controller;

import com.monopoly.dto.ActionParamContext;
import com.monopoly.model.card.ActionCard;
import com.monopoly.model.card.Card;
import com.monopoly.model.card.PropertyCard;
import com.monopoly.model.settlement.StealTargetZone;
import com.monopoly.model.effects.ActionEffectContext;
import com.monopoly.model.effects.ActionEffectDispatcher;
import com.monopoly.model.effects.ActionEffectResult;
import com.monopoly.model.effects.EffectStackEntry;
import com.monopoly.model.effects.RentEffect;
import com.monopoly.model.core.GameContext;
import com.monopoly.model.core.RentChargeSequence;
import com.monopoly.model.player.Player;
import com.monopoly.pattern.singleton.GameEngineSingleton;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ActionCardPlayService {

    private static final int BIRTHDAY_GIFT_AMOUNT = 2;
    private static final int DEBT_COLLECTOR_AMOUNT = 5;

    @FunctionalInterface
    private interface ActionPlay {
        ActionEffectResult play(Player actor, ActionCard card, ActionEffectContext ctx, ActionParamContext params);
    }

    private final GameController controller;
    private final GameEngineSingleton engine;
    private final TurnState turnState;
    private EffectStackOrchestrator effectStack;

    private final Map<String, ActionPlay> actionDispatch = new LinkedHashMap<>();

    ActionCardPlayService(GameController controller, GameEngineSingleton engine, TurnState turnState) {
        this.controller = controller;
        this.engine = engine;
        this.turnState = turnState;
        initActionDispatch();
    }

    void wireEffectStack(EffectStackOrchestrator eso) {
        this.effectStack = eso;
    }

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
                .colorKey(TurnFlowService.blankToNull(params.getTargetColorKey()))
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
        turnState.ensureTurnContext(actor);
        if (turnState.phase() == TurnFlowService.TurnPhase.WAITING_FOR_RESPONSE) {
            throw new IllegalStateException("Awaiting rent response; cannot play action card.");
        }
        turnState.ensureNoPendingOverflowDiscard(actor, "playing an action card");
        turnState.ensureTurnActionAvailable();
        if (turnState.phase() != TurnFlowService.TurnPhase.PLAY) {
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

        ActionPlay handler = actionDispatch.getOrDefault(effectCodeStr, this::playGenericAction);
        return handler.play(actor, card, ctx, params);
    }

    private void initActionDispatch() {
        actionDispatch.put("RENT", this::playRent);
        actionDispatch.put("DOUBLE_RENT", this::playDoubleRent);
        actionDispatch.put("RENT_DUAL", this::playRentDual);
        actionDispatch.put("BIRTHDAY", this::playBirthday);
        actionDispatch.put("DEBT_COLLECTOR", this::playDebtCollector);
    }

    private ActionEffectResult playRent(Player actor, ActionCard card, ActionEffectContext ctx, ActionParamContext params) {
        GameContext gameContext = controller.getGameContext();
        RentEffect.DueResult due = RentEffect.computeDue(ctx);
        if (!due.isOk()) {
            throw new IllegalStateException(due.getError());
        }
        int amountDue = consumePendingDoubleRentAmount(gameContext, actor, due.getAmountDue());
        markActionPlayed(actor, card);
        return enterRentWindow(
                actor, ctx.getTarget(), ctx.getTargetColorKey(), amountDue, card,
                "Rent entered stack; awaiting opponent " + responseWindowPrompt() + ".");
    }

    private ActionEffectResult playDoubleRent(Player actor, ActionCard card, ActionEffectContext ctx, ActionParamContext params) {
        GameContext gameContext = controller.getGameContext();
        markActionPlayed(actor, card);
        gameContext.setPendingDoubleRentFor(actor.getPlayerId());
        if (turnState.actionCount() >= TurnFlowService.MAX_ACTIONS_PER_TURN) {
            turnState.markEndTurn();
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

    private ActionEffectResult playRentDual(Player actor, ActionCard card, ActionEffectContext ctx, ActionParamContext params) {
        GameContext gameContext = controller.getGameContext();
        if (card.isRentDualChargesEachOtherPlayer()) {
            RentEffect.DueResult dueAll = RentEffect.computeDueLandlordColorOnly(ctx);
            if (!dueAll.isOk()) {
                throw new IllegalStateException(dueAll.getError());
            }
            int amountDue = consumePendingDoubleRentAmount(gameContext, actor, dueAll.getAmountDue());
            List<String> tenantIds = otherPlayerIds(actor);
            if (tenantIds.isEmpty()) {
                throw new IllegalStateException("No other players to charge rent to.");
            }
            markActionPlayed(actor, card);
            Player firstTenant = beginTenantSequence(
                    actor, ctx.getTargetColorKey(), amountDue, tenantIds, card,
                    "Tenant player not found.");
            return enterRentWindow(
                    actor, firstTenant, ctx.getTargetColorKey(), amountDue, card,
                    "Dual-rent (all players) entered stack; will charge each other player sequentially; now awaiting "
                            + firstTenant.getDisplayName()
                            + " " + responseWindowPrompt() + ".");
        }
        RentEffect.DueResult due = RentEffect.computeDue(ctx);
        if (!due.isOk()) {
            throw new IllegalStateException(due.getError());
        }
        int amountDue = consumePendingDoubleRentAmount(gameContext, actor, due.getAmountDue());
        markActionPlayed(actor, card);
        return enterRentWindow(
                actor, ctx.getTarget(), ctx.getTargetColorKey(), amountDue, card,
                "Dual-rent entered stack; awaiting opponent " + responseWindowPrompt() + ".");
    }

    private ActionEffectResult playBirthday(Player actor, ActionCard card, ActionEffectContext ctx, ActionParamContext params) {
        List<String> tenantIds = otherPlayerIds(actor);
        if (tenantIds.isEmpty()) {
            throw new IllegalStateException("No other players to collect birthday gift from.");
        }
        markActionPlayed(actor, card);
        Player firstTenant = beginTenantSequence(
                actor, "BIRTHDAY", BIRTHDAY_GIFT_AMOUNT, tenantIds, card,
                "Birthday gift target player not found.");
        return enterRentWindow(
                actor, firstTenant, "BIRTHDAY", BIRTHDAY_GIFT_AMOUNT, card,
                "Birthday gift entered stack; will collect 2M from each other player; now awaiting "
                        + firstTenant.getDisplayName()
                        + " " + responseWindowPrompt() + ".");
    }

    private ActionEffectResult playDebtCollector(Player actor, ActionCard card, ActionEffectContext ctx, ActionParamContext params) {
        if (ctx.getTarget() == null) {
            throw new IllegalStateException("Debt collector requires a target player.");
        }
        markActionPlayed(actor, card);
        return enterRentWindow(
                actor, ctx.getTarget(), "DEBT_COLLECTOR", DEBT_COLLECTOR_AMOUNT, card,
                "Debt collector entered stack; awaiting opponent " + responseWindowPrompt() + " or pay 5M.");
    }

    private ActionEffectResult playGenericAction(Player actor, ActionCard card, ActionEffectContext ctx, ActionParamContext params) {
        String effectCodeStr = card.getEffectCode() == null
                ? "" : card.getEffectCode().trim().toUpperCase();
        GameContext gameContext = controller.getGameContext();
        markActionPlayed(actor, card);

        if (isSingleTargetJustSayNoAction(effectCodeStr) && ctx.getTarget() != null) {
            int actionCountAfterPlay = turnState.actionCount();
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

        if (turnState.actionCount() >= TurnFlowService.MAX_ACTIONS_PER_TURN) {
            turnState.markEndTurn();
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

    private void markActionPlayed(Player actor, ActionCard card) {
        turnState.incrementAction();
        actor.placeActionToCenter(card);
    }

    private ActionEffectResult enterRentWindow(
            Player actor, Player target, String colorKey, int amountDue,
            ActionCard card, String message) {
        controller.getGameContext().pushEffect(EffectStackEntry.pendingRent(
                actor.getPlayerId(),
                target.getPlayerId(),
                colorKey,
                amountDue,
                card.getName(),
                card.getEffectCode()));
        effectStack.enterRentResponseWindow(target, actor, card);
        ActionEffectResult result = ActionEffectResult.success(message);
        System.out.println("[ACTION] " + result.getMessage());
        return result;
    }

    private Player beginTenantSequence(
            Player actor, String colorKey, int amountDue,
            List<String> tenantIds, ActionCard card, String notFoundMessage) {
        GameContext gameContext = controller.getGameContext();
        gameContext.clearRentChargeSequence();
        gameContext.setRentChargeSequence(new RentChargeSequence(
                actor.getPlayerId(),
                colorKey,
                amountDue,
                tenantIds,
                card.getName(),
                card.getEffectCode()));
        Player firstTenant = controller.resolvePlayer(tenantIds.get(0));
        if (firstTenant == null) {
            gameContext.clearRentChargeSequence();
            throw new IllegalStateException(notFoundMessage);
        }
        return firstTenant;
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
}