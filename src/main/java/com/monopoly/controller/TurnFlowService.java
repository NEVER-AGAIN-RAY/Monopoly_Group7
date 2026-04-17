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
import com.monopoly.model.effects.DoubleRentEffect;
import com.monopoly.model.effects.RentEffect;
import com.monopoly.model.core.RentChargeSequence;
import com.monopoly.pattern.singleton.GameEngineSingleton;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 回合流程核心逻辑：摸牌、出牌、弃牌、结束回合、行动卡效果调度，
 * 从 {@link GameController} 抽出。
 * <p>
 * 持有回合级可变状态（{@code currentTurnPlayerId}、{@code currentTurnPhase}、
 * {@code currentTurnActionCount}）；通过 {@link GameController} 包级方法完成
 * 会话检查、快照推送与错误记录。
 */
final class TurnFlowService {

    enum TurnPhase {
        DRAW,
        PLAY,
        /** 收租/免租连锁：等待特定玩家打出 Just Say No 或放弃 */
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
    }

    // ─── 摸牌 ──────────────────────────────────────────────

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

    // ─── 出牌 ──────────────────────────────────────────────

    void playCard(Player player, Card card, String actionType, ActionParamContext params) {
        if (player == null || card == null) {
            return;
        }
        controller.ensureSessionActive();
        ensureTurnContext(player);
        if (currentTurnPhase == TurnPhase.WAITING_FOR_RESPONSE) {
            throw new IllegalStateException("正在等待免租响应，不能出牌。");
        }
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

        controller.pushSnapshot(controller.getCurrentSessionId(), normalizedActionType,
                player.getDisplayName() + " played " + normalizedActionType + " (" + card.getName() + ").");
    }

    // ─── 弃牌 ──────────────────────────────────────────────

    void discardFromHand(Player player, Card card) {
        if (player == null || card == null) {
            return;
        }
        controller.ensureSessionActive();
        ensureTurnContext(player);
        if (currentTurnPhase == TurnPhase.WAITING_FOR_RESPONSE) {
            throw new IllegalStateException("正在等待免租响应，不能弃牌。");
        }
        if (currentTurnPhase != TurnPhase.PLAY) {
            throw new IllegalStateException("当前不是出牌阶段，不能弃牌。");
        }
        if (!player.getHandCardsView().contains(card)) {
            throw new IllegalStateException("该卡牌不在当前玩家手牌中，不能弃牌。");
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
        controller.pushSnapshot(controller.getCurrentSessionId(), "DISCARD",
                player.getDisplayName() + " discarded a card (" + card.getName() + ").");
    }

    // ─── 万能房产重新分配颜色 ──────────────────────────────

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

    // ─── 结束回合 ──────────────────────────────────────────

    /**
     * 结束回合：弃牌限制、胜负判定、轮转。
     * <p>
     * 不触发 AI 回合——AI 触发由 {@link GameController#endTurn} 负责。
     *
     * @return 下一位玩家，供外层判断是否触发 AI 回合；若 game over 则返回 {@code null}
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
            List<Card> discarded = player.discardOverflowTo(MAX_HAND_SIZE);
            engine.discardMany(discarded);
            System.out.println("[FORCE_DISCARD] 玩家 " + player.getPlayerId()
                    + " 弃牌 " + discarded.size() + " 张至上限。");
        }
        controller.assertDeckIntegrityOrLog();

        if (checkWinCondition(player)) {
            controller.pushSnapshot(controller.getCurrentSessionId(), "GAME_OVER",
                    player.getDisplayName() + " wins (3 complete property sets).");
            return null;
        }

        TurnManager tm = controller.getTurnManager();
        tm.advanceTurn();

        Player next = tm.getCurrentPlayer();
        this.currentTurnPlayerId = next != null ? next.getPlayerId() : null;
        this.currentTurnActionCount = 0;
        this.currentTurnPhase = TurnPhase.DRAW;

        controller.onTurnAdvanced(next);

        controller.pushSnapshot(controller.getCurrentSessionId(), "TURN_END",
                player.getDisplayName() + " ended turn.");

        return next;
    }

    // ─── 行动卡 ──────────────────────────────────────────

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
            currentTurnActionCount++;
            actor.placeActionToCenter(card);
            EffectStackEntry rentEntry = EffectStackEntry.pendingRent(
                    actor.getPlayerId(),
                    ctx.getTarget().getPlayerId(),
                    ctx.getTargetColorKey(),
                    due.getAmountDue());
            gameContext.pushEffect(rentEntry);
            effectStack.enterRentResponseWindow(ctx.getTarget());
            ActionEffectResult result = ActionEffectResult.success(
                    "收租已入栈，等待对方在 "
                            + EffectStackOrchestrator.RESPONSE_WINDOW_SECONDS
                            + " 秒内打出免租或放弃。");
            System.out.println("[ACTION] " + result.getMessage());
            return result;
        }

        if ("DOUBLE_RENT".equals(effectCodeStr)) {
            RentEffect.DueResult due = DoubleRentEffect.computeDue(ctx);
            if (!due.isOk()) {
                throw new IllegalStateException(due.getError());
            }
            currentTurnActionCount++;
            actor.placeActionToCenter(card);
            EffectStackEntry drEntry = EffectStackEntry.pendingDoubleRent(
                    actor.getPlayerId(),
                    ctx.getTarget().getPlayerId(),
                    ctx.getTargetColorKey(),
                    due.getAmountDue());
            gameContext.pushEffect(drEntry);
            effectStack.enterRentResponseWindow(ctx.getTarget());
            ActionEffectResult result = ActionEffectResult.success(
                    "双倍收租已入栈，等待对方在 "
                            + EffectStackOrchestrator.RESPONSE_WINDOW_SECONDS
                            + " 秒内打出免租或放弃。");
            System.out.println("[ACTION] " + result.getMessage());
            return result;
        }

        if ("RENT_DUAL".equals(effectCodeStr)) {
            if (card.isRentDualChargesEachOtherPlayer()) {
                RentEffect.DueResult dueAll = RentEffect.computeDueLandlordColorOnly(ctx);
                if (!dueAll.isOk()) {
                    throw new IllegalStateException(dueAll.getError());
                }
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
                        dueAll.getAmountDue(),
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
                        dueAll.getAmountDue()));
                effectStack.enterRentResponseWindow(firstTenant);
                ActionEffectResult result = ActionEffectResult.success(
                        "双色全员收租已入栈，将依次向每位其他玩家收租；当前等待 "
                                + firstTenant.getDisplayName()
                                + " 在 "
                                + EffectStackOrchestrator.RESPONSE_WINDOW_SECONDS
                                + " 秒内打出免租或放弃。");
                System.out.println("[ACTION] " + result.getMessage());
                return result;
            }
            RentEffect.DueResult due = RentEffect.computeDue(ctx);
            if (!due.isOk()) {
                throw new IllegalStateException(due.getError());
            }
            currentTurnActionCount++;
            actor.placeActionToCenter(card);
            EffectStackEntry rentEntry = EffectStackEntry.pendingRent(
                    actor.getPlayerId(),
                    ctx.getTarget().getPlayerId(),
                    ctx.getTargetColorKey(),
                    due.getAmountDue());
            gameContext.pushEffect(rentEntry);
            effectStack.enterRentResponseWindow(ctx.getTarget());
            ActionEffectResult result = ActionEffectResult.success(
                    "双色收租已入栈，等待对方在 "
                            + EffectStackOrchestrator.RESPONSE_WINDOW_SECONDS
                            + " 秒内打出免租或放弃。");
            System.out.println("[ACTION] " + result.getMessage());
            return result;
        }

        currentTurnActionCount++;
        actor.placeActionToCenter(card);

        ActionEffectResult result = ActionEffectDispatcher.dispatch(card.getEffectCode(), ctx);

        if (currentTurnActionCount >= MAX_ACTIONS_PER_TURN) {
            currentTurnPhase = TurnPhase.END_TURN;
        }

        String phase = result.isSuccess() ? "ACTION_SUCCESS"
                : (result.getStatus() == ActionEffectResult.Status.COUNTERED
                        ? "ACTION_COUNTERED" : "ACTION_FAILED");
        String actionSummary = actor.getDisplayName() + " action " + card.getEffectCode()
                + ": " + (result.getMessage() != null ? result.getMessage() : phase);
        controller.pushSnapshot(controller.getCurrentSessionId(), phase, actionSummary);

        System.out.println("[ACTION] " + result.getMessage());
        return result;
    }

    // ─── 内部工具方法 ──────────────────────────────────────

    void ensureTurnContext(Player player) {
        String pid = player.getPlayerId();
        if (currentTurnPlayerId == null) {
            currentTurnPlayerId = pid;
            currentTurnActionCount = 0;
            currentTurnPhase = TurnPhase.DRAW;
            return;
        }
        if (!currentTurnPlayerId.equals(pid)) {
            throw new IllegalStateException("当前不是玩家 " + pid + " 的回合。");
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
