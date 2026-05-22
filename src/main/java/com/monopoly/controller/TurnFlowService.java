package com.monopoly.controller;

import com.monopoly.model.card.ActionCard;
import com.monopoly.model.card.Card;
import com.monopoly.model.effects.EffectStackEntry;
import com.monopoly.model.core.GameContext;
import com.monopoly.model.player.Player;
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
    private final GameEngineSingleton engine = GameEngineSingleton.getInstance();
    private EffectStackOrchestrator effectStack;

    String currentTurnPlayerId;
    int currentTurnActionCount;
    TurnPhase currentTurnPhase;
    private boolean currentTurnMustDiscardOverflow;

    TurnFlowService(GameController controller) {
        this.controller = controller;
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

    void drawCards(Player player, int count) {
        if (player == null) {
            return;
        }
        controller.ensureSessionActive();
        ensureTurnContext(player);
        if (currentTurnPhase == TurnPhase.WAITING_FOR_RESPONSE) {
            throw new IllegalStateException("正在等待免租响应，不能摸牌。");
        }
        if (currentTurnPhase != TurnPhase.DRAW) {
            throw new IllegalStateException("当前不是摸牌阶段，不能重复摸牌。");
        }
        int effectiveCount;
        if (currentTurnPhase == TurnPhase.DRAW && player.getHandCardCount() == 0) {
            effectiveCount = 5;
        } else if (count <= 0) {
            effectiveCount = 2;
        } else {
            effectiveCount = Math.max(1, count);
        }
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
            controller.pushSnapshot(controller.getCurrentSessionId(), "GAME_OVER",
                    player.getDisplayName() + " wins (3 complete property sets).");
            return;
        }
        currentTurnPhase = TurnPhase.PLAY;
        controller.assertDeckIntegrityOrLog();
        controller.pushSnapshot(controller.getCurrentSessionId(), "DRAW",
                player.getDisplayName() + " drew " + drawn + " card(s).");
    }

    // --- play ---

    void playCard(Player player, Card card, String actionType, ActionParamContext params) {
        if (player == null || card == null) {
            return;
        }
        controller.ensureSessionActive();
        ensureTurnContext(player);
        if (currentTurnPhase == TurnPhase.WAITING_FOR_RESPONSE) {
            throw new IllegalStateException("正在等待免租响应，不能出牌。");
        }
        ensureNoPendingOverflowDiscard(player, "出牌");
        if (currentTurnPhase != TurnPhase.PLAY) {
            throw new IllegalStateException("当前不是出牌阶段，请先完成摸牌。");
        }
        if (!player.getHandCardsView().contains(card)) {
            throw new IllegalStateException("该卡牌不在当前玩家手牌中，不能打出。");
        }
        if (actionType == null || actionType.isBlank()) {
            throw new IllegalArgumentException("actionType 不能为空。");
        }
        String normalizedActionType = actionType.trim().toUpperCase();

        if (currentTurnActionCount >= MAX_ACTIONS_PER_TURN) {
            throw new IllegalStateException("每回合最多可出 3 张牌，已达到上限。");
        }
        currentTurnActionCount++;

        if ("DEPOSIT".equals(normalizedActionType)) {
            player.depositToBank(card);
        } else if ("DEPLOY".equals(normalizedActionType)) {
            if (!(card instanceof PropertyCard)) {
                throw new IllegalArgumentException("DEPLOY 需要 PropertyCard（房产卡）。");
            }
            if (card instanceof PropertyWildCard wild) {
                String assign = params != null ? blankToNull(params.getTargetColorKey()) : wild.getAssignedColorKey();
                if (assign == null) {
                    throw new IllegalArgumentException("部署万能房产牌时必须指定 targetColorKey。");
                }
                wild.setAssignedColorKey(assign);
            }
            player.deployProperty((PropertyCard) card);
        } else if ("ACTION".equals(normalizedActionType)) {
            if (!(card instanceof ActionCard)) {
                throw new IllegalArgumentException("ACTION 需要 ActionCard（行动卡）。");
            }
            player.placeActionToCenter((ActionCard) card);
        } else {
            throw new IllegalArgumentException("未知 actionType: " + actionType);
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
            throw new IllegalStateException("正在等待免租响应，不能弃牌。");
        }
        if (!player.getHandCardsView().contains(card)) {
            throw new IllegalStateException("该卡牌不在当前玩家手牌中，不能弃牌。");
        }
        boolean forcedOverflowDiscard =
                currentTurnMustDiscardOverflow && player.getHandCardCount() > MAX_HAND_SIZE;
        if (forcedOverflowDiscard) {
            if (currentTurnPhase != TurnPhase.PLAY && currentTurnPhase != TurnPhase.END_TURN) {
                throw new IllegalStateException("当前阶段不能弃牌。");
            }
            if (!player.discardFromHand(card)) {
                throw new IllegalStateException("从手牌弃置失败。");
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
        if (currentTurnPhase != TurnPhase.PLAY) {
            throw new IllegalStateException("当前不是出牌阶段，不能弃牌。");
        }
        if (currentTurnActionCount >= MAX_ACTIONS_PER_TURN) {
            throw new IllegalStateException("每回合最多可出 3 张牌，已达到上限。");
        }
        currentTurnActionCount++;
        if (!player.discardFromHand(card)) {
            currentTurnActionCount--;
            throw new IllegalStateException("从手牌弃置失败。");
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
            throw new IllegalStateException("正在等待免租响应，不能强制弃牌。");
        }
        List<Card> discarded = player.discardOverflowTo(MAX_HAND_SIZE);
        engine.discardMany(discarded);
        currentTurnMustDiscardOverflow = false;
        controller.pushSnapshot(controller.getCurrentSessionId(), "FORCE_DISCARD",
                player.getDisplayName() + " force-discarded "
                        + discarded.size() + " overflow card(s).");
    }

    // --- reassign wild property color ---

    void reassignWildProperty(Player player, String wildPropertyCardId, String newColorKey) {
        if (player == null) {
            throw new IllegalArgumentException("player 不能为 null。");
        }
        controller.ensureSessionActive();
        if (wildPropertyCardId == null || wildPropertyCardId.isBlank()) {
            throw new IllegalArgumentException("wildPropertyCardId 不能为空。");
        }
        ensureTurnContext(player);
        if (currentTurnPhase == TurnPhase.WAITING_FOR_RESPONSE) {
            throw new IllegalStateException("正在等待免租响应，不能调整万能房产颜色。");
        }
        ensureNoPendingOverflowDiscard(player, "调整万能房产颜色");
        if (currentTurnPhase != TurnPhase.PLAY) {
            throw new IllegalStateException("当前不是出牌阶段，不能调整万能房产颜色。");
        }
        String normalizedColor = normalizeWildReassignColorKey(newColorKey);

        PropertyWildCard wild = null;
        for (PropertyCard pc : player.getPropertyCardsView()) {
            if (wildPropertyCardId.equals(pc.getId()) && pc instanceof PropertyWildCard w) {
                wild = w;
                break;
            }
        }
        if (wild == null) {
            throw new IllegalArgumentException(
                    "财产区不存在 id 为 \"" + wildPropertyCardId + "\" 的万能房产牌。");
        }

        wild.setAssignedColorKey(normalizedColor);
        controller.pushSnapshot(controller.getCurrentSessionId(), "REASSIGN_WILD",
                player.getDisplayName() + " reassigned wild property to " + normalizedColor + ".");
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
            throw new IllegalStateException("正在等待免租响应，不能结束回合。");
        }
        if (currentTurnPhase == TurnPhase.DRAW) {
            throw new IllegalStateException("当前回合尚未摸牌，不能结束回合。");
        }

        if (player.getHandCardCount() > MAX_HAND_SIZE) {
            currentTurnMustDiscardOverflow = true;
            throw new IllegalStateException("手牌超过 7 张，必须先弃牌至最多 7 张才能结束回合。");
        }
        currentTurnMustDiscardOverflow = false;
        controller.assertDeckIntegrityOrLog();

        if (checkWinCondition(player)) {
            controller.pushSnapshot(controller.getCurrentSessionId(), "GAME_OVER",
                    player.getDisplayName() + " wins (3 complete property sets).");
            return null;
        }

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

    // --- action cards ---

    ActionEffectResult handleActionCardCommand(
            int handIndex, String targetPlayerId, String colorKey,
            int targetPropIndex, int actorPropIndex) {

        controller.ensureSessionActive();
        Player actor = controller.requireCurrentPlayer();
        if (handIndex < 0 || handIndex >= actor.getHandCardsView().size()) {
            throw new IllegalArgumentException("handIndex 越界。");
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
            throw new IllegalArgumentException("ActionParamContext 不能为 null。");
        }
        controller.ensureSessionActive();
        Player actor = controller.requireCurrentPlayer();
        Card card = resolveCardInHand(actor, params.getCardId(), params.getHandIndex());
        if (!(card instanceof ActionCard actionCard)) {
            throw new IllegalArgumentException("指定卡牌不是行动卡，无法触发效果。");
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
            throw new IllegalArgumentException("actor 和 card 不能为 null。");
        }
        controller.ensureSessionActive();
        ensureTurnContext(actor);
        if (currentTurnPhase != TurnPhase.PLAY) {
            throw new IllegalStateException("当前不是出牌阶段，请先完成摸牌。");
        }
        ensureNoPendingOverflowDiscard(actor, "打出行动牌");
        if (!actor.getHandCardsView().contains(card)) {
            throw new IllegalStateException("该卡牌不在当前玩家手牌中，不能打出。");
        }
        if (currentTurnActionCount >= MAX_ACTIONS_PER_TURN) {
            throw new IllegalStateException("每回合最多可出 3 张牌，已达到上限。");
        }

        GameContext gameContext = controller.getGameContext();
        gameContext.bindPlayers(controller.getSessionPlayersView());
        if (!card.canPlay(actor, params, gameContext)) {
            throw new IllegalStateException("当前规则不允许打出该行动卡。");
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
                    amountDue);
            gameContext.pushEffect(rentEntry);
            effectStack.enterRentResponseWindow(ctx.getTarget(), actor, card);
            ActionEffectResult result = ActionEffectResult.success(
                    "收租已入栈，等待对方" + responseWindowPrompt() + "。");
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
                    "Double The Rent 已生效：你下一张租金牌金额翻倍。");
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
                    throw new IllegalStateException("没有其他玩家可收租。");
                }
                currentTurnActionCount++;
                actor.placeActionToCenter(card);
                gameContext.clearRentChargeSequence();
                gameContext.setRentChargeSequence(new RentChargeSequence(
                        actor.getPlayerId(),
                        ctx.getTargetColorKey(),
                        amountDue,
                        tenantIds));
                Player firstTenant = controller.resolvePlayer(tenantIds.get(0));
                if (firstTenant == null) {
                    gameContext.clearRentChargeSequence();
                    throw new IllegalStateException("承租人玩家不存在。");
                }
                gameContext.pushEffect(EffectStackEntry.pendingRent(
                        actor.getPlayerId(),
                        firstTenant.getPlayerId(),
                        ctx.getTargetColorKey(),
                        amountDue));
                effectStack.enterRentResponseWindow(firstTenant, actor, card);
                ActionEffectResult result = ActionEffectResult.success(
                        "双色全员收租已入栈，将依次向每位其他玩家收租；当前等待 "
                                + firstTenant.getDisplayName()
                                + " " + responseWindowPrompt() + "。");
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
                    amountDue);
            gameContext.pushEffect(rentEntry);
            effectStack.enterRentResponseWindow(ctx.getTarget(), actor, card);
            ActionEffectResult result = ActionEffectResult.success(
                    "双色收租已入栈，等待对方" + responseWindowPrompt() + "。");
            System.out.println("[ACTION] " + result.getMessage());
            return result;
        }

        if ("BIRTHDAY".equals(effectCodeStr)) {
            List<String> tenantIds = otherPlayerIds(actor);
            if (tenantIds.isEmpty()) {
                throw new IllegalStateException("没有其他玩家可收取生日礼金。");
            }
            currentTurnActionCount++;
            actor.placeActionToCenter(card);
            gameContext.clearRentChargeSequence();
            gameContext.setRentChargeSequence(new RentChargeSequence(
                    actor.getPlayerId(),
                    "BIRTHDAY",
                    2,
                    tenantIds));
            Player firstTenant = controller.resolvePlayer(tenantIds.get(0));
            if (firstTenant == null) {
                gameContext.clearRentChargeSequence();
                throw new IllegalStateException("生日礼金目标玩家不存在。");
            }
            gameContext.pushEffect(EffectStackEntry.pendingRent(
                    actor.getPlayerId(),
                    firstTenant.getPlayerId(),
                    "BIRTHDAY",
                    2));
            effectStack.enterRentResponseWindow(firstTenant, actor, card);
            ActionEffectResult result = ActionEffectResult.success(
                    "生日礼金已入栈，将依次向每位其他玩家收 2M；当前等待 "
                            + firstTenant.getDisplayName()
                            + " " + responseWindowPrompt() + "。");
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
                    card.getName() + " 已入栈，等待 " + ctx.getTarget().getDisplayName()
                            + " " + responseWindowPrompt() + "。");
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
            case "STEAL_PROPERTY", "FORCED_DEAL", "DEBT_COLLECTOR", "DEAL_BREAKER" -> true;
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
        if (controller.isPvpMode()) {
            return "在 " + EffectStackOrchestrator.RESPONSE_WINDOW_SECONDS + " 秒内打出免租或放弃";
        }
        return "打出免租或放弃";
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
            throw new IllegalStateException("当前不是玩家 " + pid + " 的回合。");
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
                "手牌超过 7 张，必须先弃牌至最多 7 张，不能" + attemptedAction + "。");
    }

    Card resolveCardInHand(Player actor, String cardId, Integer handIndex) {
        List<Card> hand = actor.getHandCardsView();
        if (cardId != null && !cardId.isBlank()) {
            for (Card c : hand) {
                if (cardId.equals(c.getId())) {
                    return c;
                }
            }
            throw new IllegalArgumentException("手牌中不存在 id 为 \"" + cardId + "\" 的卡牌。");
        }
        if (handIndex != null && handIndex >= 0 && handIndex < hand.size()) {
            return hand.get(handIndex);
        }
        throw new IllegalArgumentException("请提供有效的 cardId 或 handIndex。");
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
        throw new IllegalArgumentException(
                "玩家 " + owner.getPlayerId() + " 财产区不存在 id 为 \"" + propertyCardId + "\" 的房产卡。");
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
        throw new IllegalArgumentException(
                "玩家 " + owner.getPlayerId() + " 银行不存在 id 为 \"" + cardId + "\" 的卡牌。");
    }

    boolean checkWinCondition(Player player) {
        return player.countCompletePropertySets() >= 3;
    }

    static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    static String normalizeWildReassignColorKey(String newColorKey) {
        if (newColorKey == null || newColorKey.isBlank()) {
            throw new IllegalArgumentException("newColorKey 不能为空。");
        }
        String key = newColorKey.trim().toUpperCase(Locale.ROOT);
        if (!PropertySetCalculator.REQUIRED_BY_COLOR.containsKey(key)) {
            throw new IllegalArgumentException("无效的颜色键，须为轨道标准色之一: " + key);
        }
        return key;
    }
}
