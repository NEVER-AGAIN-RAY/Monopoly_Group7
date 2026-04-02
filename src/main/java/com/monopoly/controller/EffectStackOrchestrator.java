package com.monopoly.controller;

import com.monopoly.model.card.ActionCard;
import com.monopoly.model.card.Card;
import com.monopoly.model.effects.EffectStackEntry;
import com.monopoly.model.effects.EffectStackResolver;
import com.monopoly.model.core.GameContext;
import com.monopoly.model.settlement.PaymentSettlement;
import com.monopoly.model.player.Player;
import com.monopoly.model.effects.StackResponseState;
import com.monopoly.dto.ActionParamContext;
import com.monopoly.dto.PlayActionRequest;
import com.monopoly.pattern.singleton.GameEngineSingleton;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 效果栈编排器：管理行动牌响应窗口、免租连锁（Just Say No）、
 * 响应超时定时器等逻辑，从 {@link GameController} 抽出。
 * <p>
 * 持有单线程 {@link ScheduledExecutorService}；通过包级回调与
 * {@link TurnFlowService} 协作完成回合阶段切换。
 * <p>
 * <b>并发安全</b>：{@code pendingResponseFuture} 为 {@code volatile}，定时器回调
 * 通过 {@code deadlineEpochMs} 校验确保过期任务不会与主线程响应操作竞争。
 */
final class EffectStackOrchestrator {

    static final int RESPONSE_WINDOW_SECONDS = 15;

    private final GameController controller;
    private final TurnFlowService turnFlow;
    private final GameEngineSingleton engine = GameEngineSingleton.getInstance();

    private final ScheduledExecutorService responseScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "effect-response-timeout");
                t.setDaemon(true);
                return t;
            });
    private volatile ScheduledFuture<?> pendingResponseFuture;

    EffectStackOrchestrator(GameController controller, TurnFlowService turnFlow) {
        this.controller = controller;
        this.turnFlow = turnFlow;
    }

    // ─── 响应窗口 ──────────────────────────────────────────

    void enterRentResponseWindow(Player tenant) {
        if (tenant == null) {
            throw new IllegalStateException("收租目标无效。");
        }
        GameContext ctx = controller.getGameContext();
        long deadline = System.currentTimeMillis() + RESPONSE_WINDOW_SECONDS * 1000L;
        ctx.setResponseState(
                new StackResponseState(StackResponseState.Role.TENANT, tenant.getPlayerId(), deadline));
        turnFlow.currentTurnPhase = TurnFlowService.TurnPhase.WAITING_FOR_RESPONSE;
        scheduleResponseTimeout(deadline);
        EffectStackEntry top = ctx.peekTopEffect();
        int due = top != null ? top.getAmountDue() : 0;
        String rentSummary = "Rent " + due + "M — awaiting response from "
                + tenant.getDisplayName() + ".";
        controller.pushSnapshot(controller.getCurrentSessionId(),
                "RENT_AWAITING_RESPONSE", rentSummary);
    }

    // ─── 定时器 ────────────────────────────────────────────

    void scheduleResponseTimeout(long deadlineEpochMs) {
        cancelPendingResponseTimeout();
        pendingResponseFuture = responseScheduler.schedule(() -> {
            StackResponseState st = controller.getGameContext().getResponseState();
            if (st == null || st.getDeadlineEpochMs() != deadlineEpochMs) {
                return;
            }
            try {
                cancelPendingResponseTimeout();
                resolveEffectStackAndResume("RESPONSE_TIMEOUT");
            } catch (RuntimeException ex) {
                ex.printStackTrace();
            }
        }, RESPONSE_WINDOW_SECONDS, TimeUnit.SECONDS);
    }

    void cancelPendingResponseTimeout() {
        if (pendingResponseFuture != null) {
            pendingResponseFuture.cancel(false);
            pendingResponseFuture = null;
        }
    }

    // ─── 响应/放弃 ─────────────────────────────────────────

    void performResponsePass(String actingPlayerId) {
        if (actingPlayerId == null || actingPlayerId.isBlank()) {
            throw new IllegalArgumentException("放弃响应时必须提供 actingPlayerId。");
        }
        if (!controller.getGameContext().isAwaitingResponseFrom(actingPlayerId)) {
            throw new IllegalStateException("当前未轮到该玩家响应或已超时。");
        }
        cancelPendingResponseTimeout();
        resolveEffectStackAndResume("RESPONSE_PASS");
    }

    // ─── 免租牌（Just Say No）响应 ────────────────────────

    void handleWaiverPlay(PlayActionRequest req) {
        Player actor = controller.resolvePlayer(req.getActingPlayerId());
        if (actor == null) {
            throw new IllegalArgumentException("等待响应阶段必须提供有效的 actingPlayerId。");
        }
        GameContext ctx = controller.getGameContext();
        if (!ctx.isAwaitingResponseFrom(actor.getPlayerId())) {
            throw new IllegalStateException("当前未轮到该玩家打出免租牌。");
        }
        Card card = turnFlow.resolveCardInHand(actor, req.getCardId(), req.getHandIndex());
        if (!(card instanceof ActionCard actionCard)) {
            throw new IllegalArgumentException("只能打出行动卡。");
        }
        if (!"RENT_WAIVER".equalsIgnoreCase(actionCard.getEffectCode())) {
            throw new IllegalStateException("当前只能打出免租牌（Just Say No）。");
        }
        ctx.bindPlayers(controller.getSessionPlayersView());
        ActionParamContext p = ActionParamContext.fromPlayRequest(req);
        if (!actionCard.canPlay(actor, p, ctx)) {
            throw new IllegalStateException("当前不能打出免租牌。");
        }
        StackResponseState st = ctx.getResponseState();
        if (st == null) {
            throw new IllegalStateException("响应状态丢失。");
        }
        String targetId;
        if (st.getRole() == StackResponseState.Role.TENANT) {
            targetId = ctx.findBottomRentEntryId();
        } else {
            EffectStackEntry top = ctx.peekTopEffect();
            targetId = top != null ? top.getId() : null;
        }
        if (targetId == null) {
            throw new IllegalStateException("找不到可抵消的效果条目。");
        }

        actor.placeActionToCenter(actionCard);
        ctx.pushEffect(EffectStackEntry.waiver(actor.getPlayerId(), targetId));
        cancelPendingResponseTimeout();

        if (st.getRole() == StackResponseState.Role.TENANT) {
            Player landlord = controller.resolvePlayer(turnFlow.currentTurnPlayerId);
            if (landlord == null) {
                throw new IllegalStateException("当前回合玩家丢失。");
            }
            long deadline = System.currentTimeMillis() + RESPONSE_WINDOW_SECONDS * 1000L;
            ctx.setResponseState(new StackResponseState(
                    StackResponseState.Role.LANDLORD_COUNTER, landlord.getPlayerId(), deadline));
            scheduleResponseTimeout(deadline);
            controller.pushSnapshot(controller.getCurrentSessionId(), "JSN_AWAITING_COUNTER",
                    actor.getDisplayName() + " played Just Say No; landlord may counter.");
        } else {
            resolveEffectStackAndResume("JSN_COUNTER_RESOLVED");
        }
    }

    // ─── 效果栈结算 ────────────────────────────────────────

    void resolveEffectStackAndResume(String phaseHint) {
        if (controller.isSessionForceEnded()) {
            return;
        }
        GameContext ctx = controller.getGameContext();
        List<EffectStackEntry> copy = new ArrayList<>(ctx.getEffectStackView());
        ctx.clearEffectStack();
        turnFlow.currentTurnPhase = TurnFlowService.TurnPhase.PLAY;

        PaymentSettlement.Result pay = EffectStackResolver.resolveRentPayments(
                copy, controller.getSessionPlayersView(), engine);
        if (turnFlow.currentTurnActionCount >= TurnFlowService.MAX_ACTIONS_PER_TURN) {
            turnFlow.currentTurnPhase = TurnFlowService.TurnPhase.END_TURN;
        }

        controller.pushSnapshot(controller.getCurrentSessionId(), phaseHint,
                "Effect stack resolved: " + pay.getMessage());
        System.out.println("[EFFECT_STACK] " + phaseHint + " " + pay.getMessage());
    }

    // ─── 响应提示（静态工具） ──────────────────────────────

    static String buildPendingResponseHint(StackResponseState st) {
        if (st == null) {
            return null;
        }
        if (st.getRole() == StackResponseState.Role.TENANT) {
            return "有人向你收租，你有 "
                    + RESPONSE_WINDOW_SECONDS
                    + " 秒打出 Just Say No，否则默认接受。";
        }
        return "对方打出免租，你有 "
                + RESPONSE_WINDOW_SECONDS
                + " 秒打出 Just Say No 反制，否则默认放弃反制。";
    }
}
