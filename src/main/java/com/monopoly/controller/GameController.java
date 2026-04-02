package com.monopoly.controller;

import com.monopoly.model.player.AIPlayer;
import com.monopoly.model.core.AiGameBridge;
import com.monopoly.model.card.Card;
import com.monopoly.model.core.GameConstants;
import com.monopoly.model.core.GameContext;
import com.monopoly.model.effects.StackResponseState;
import com.monopoly.model.player.HumanPlayer;
import com.monopoly.model.player.Player;
import com.monopoly.model.settlement.PaymentSettlement;
import com.monopoly.model.settlement.PropertyZoneSummary;
import com.monopoly.dto.ActionParamContext;
import com.monopoly.dto.GameStateSnapshot;
import com.monopoly.dto.PlayActionRequest;
import com.monopoly.dto.StartSessionRequest;
import com.monopoly.model.effects.ActionEffectContext;
import com.monopoly.model.effects.ActionEffectResult;
import com.monopoly.model.card.ActionCard;
import com.monopoly.pattern.factory.CardFactory;
import com.monopoly.pattern.factory.MonopolyDealCardFactory;
import com.monopoly.pattern.observer.GameUpdateSubject;
import com.monopoly.pattern.strategy.AiPlayStrategy;
import com.monopoly.pattern.strategy.EasyAiPlayStrategy;
import com.monopoly.pattern.strategy.HardAiPlayStrategy;
import com.monopoly.pattern.strategy.NormalAiPlayStrategy;
import com.monopoly.pattern.singleton.GameEngineSingleton;

import static com.monopoly.controller.ProtocolErrors.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 【Facade 外观模式】
 * 对 Flutter 客户端/网络层暴露统一的高层操作入口，隐藏摸牌、租金结算、
 * 回合推进、效果栈编排等协作细节。
 * <p>
 * 核心逻辑已拆分至六个内聚服务：
 * <ul>
 *   <li>{@link TurnFlowService} — 回合流程（摸牌 / 出牌 / 弃牌 / 结束回合 / 行动卡）</li>
 *   <li>{@link EffectStackOrchestrator} — 效果栈 & 响应定时器</li>
 *   <li>{@link AiTurnService} — AI 回合执行</li>
 *   <li>{@link RentSettlementService} — 租金结算</li>
 *   <li>{@link PauseVoteService} — 暂停 / PVP 投票</li>
 *   <li>{@link SaveLoadService} — 存档 / 读档 / 自动保存</li>
 * </ul>
 */
public class GameController implements AiGameBridge {

    private static final Logger LOG = Logger.getLogger(GameController.class.getName());
    private static final boolean VERIFY_DECK = Boolean.parseBoolean(
            System.getProperty("monopoly.verifyDeck", "false"));

    private final GameEngineSingleton engine = GameEngineSingleton.getInstance();
    private final TurnManager turnManager = new TurnManager();
    private final CardFactory cardFactory = new MonopolyDealCardFactory();
    private final GameUpdateSubject gameUpdateSubject;

    /* ── 六大内聚服务 ─────────────────────────────────── */
    private final TurnFlowService turnFlowService;
    private final EffectStackOrchestrator effectStackOrchestrator;
    private final AiTurnService aiTurnService;
    private final SaveLoadService saveLoadService;
    private final PauseVoteService pauseVoteService;
    private final RentSettlementService rentSettlementService;

    /* ── 会话级状态 ───────────────────────────────────── */
    private final GameContext gameContext = new GameContext();
    private final List<Player> sessionPlayers = new ArrayList<>();
    private String currentSessionId = "unknown";
    private String lastErrorCode;
    private String lastErrorMessage;
    private long lastErrorTimestampEpochMs;
    private long sessionStartEpochMs;
    private volatile boolean sessionForceEnded;
    private String forceEndReason;
    private final Set<String> quitPlayerIds = new HashSet<>();
    private String sessionGameMode = "HVM";
    private int fullRoundsCompleted;

    // ─── 构造 ────────────────────────────────────────────

    public GameController(GameUpdateSubject gameUpdateSubject) {
        this.gameUpdateSubject = gameUpdateSubject;
        this.turnFlowService = new TurnFlowService(this);
        this.effectStackOrchestrator = new EffectStackOrchestrator(this, turnFlowService);
        this.turnFlowService.wireEffectStack(effectStackOrchestrator);
        this.aiTurnService = new AiTurnService(this, turnFlowService);
        this.saveLoadService = new SaveLoadService(this);
        this.pauseVoteService = new PauseVoteService(this);
        this.rentSettlementService = new RentSettlementService(this);
        clearLastError();
    }

    // ═══════════════════════════════════════════════════════
    //  会话生命周期
    // ═══════════════════════════════════════════════════════

    public void startNewSession(String sessionId) {
        StartSessionRequest req = new StartSessionRequest();
        req.setSessionId(sessionId);
        req.setPlayerCount(2);
        req.setGameMode("HVM");
        req.setAiDifficulty("EASY");
        req.setRandomizeFirstPlayer(false);
        startNewSession(req);
    }

    public void startNewSession(StartSessionRequest req) {
        if (req == null) {
            throw new IllegalArgumentException("StartSessionRequest 不能为 null。");
        }
        if (pauseVoteService.isPaused()) {
            recordError("PAUSED", "游戏已暂停，无法开始或重开对局。");
            pushSnapshot(currentSessionId, "RULE_VIOLATION");
            return;
        }
        int count = req.getPlayerCount();
        if (count < 2 || count > 5) {
            throw new IllegalArgumentException("playerCount 必须在 2–5 之间，当前为 " + count + "。");
        }
        String mode = req.getGameMode() == null ? "" : req.getGameMode().trim().toUpperCase();
        if (!"HVM".equals(mode) && !"PVP".equals(mode)) {
            throw new IllegalArgumentException(
                    "gameMode 必须为 HVM 或 PVP，当前为 " + req.getGameMode() + "。");
        }

        String sid = req.getSessionId();
        currentSessionId = (sid == null || sid.isBlank()) ? "session-default" : sid;
        sessionGameMode = mode;
        pauseVoteService.reset();
        fullRoundsCompleted = 0;
        sessionStartEpochMs = System.currentTimeMillis();
        sessionForceEnded = false;
        forceEndReason = null;
        quitPlayerIds.clear();
        engine.attachDrawPile(cardFactory.createStandardDeck108());

        sessionPlayers.clear();
        if ("HVM".equals(mode)) {
            String diff = req.getAiDifficulty() == null
                    ? "EASY" : req.getAiDifficulty().trim().toUpperCase();
            String diffLabel = formatAiDifficultyLabel(diff);
            sessionPlayers.add(new HumanPlayer("human-1", "Human"));
            for (int i = 1; i < count; i++) {
                sessionPlayers.add(
                        new AIPlayer("ai-" + i, "AI-" + diffLabel, resolveAiStrategy(diff)));
            }
        } else {
            for (int i = 1; i <= count; i++) {
                sessionPlayers.add(new HumanPlayer("pvp-" + i, "Player-" + i));
            }
        }

        if (req.isRandomizeFirstPlayer()) {
            Collections.shuffle(sessionPlayers);
        }

        turnManager.bindTurnOrder(sessionPlayers);
        gameContext.bindPlayers(sessionPlayers);
        gameContext.clearEffectStack();
        effectStackOrchestrator.cancelPendingResponseTimeout();

        for (Player p : sessionPlayers) {
            for (int i = 0; i < TurnFlowService.INITIAL_HAND_SIZE; i++) {
                Card card = engine.drawOne();
                if (card == null) {
                    break;
                }
                p.receiveCardToHand(card);
            }
        }

        Player current = turnManager.getCurrentPlayer();
        turnFlowService.initForSession(current);
        clearLastError();
        assertDeckIntegrityOrLog();
        pushSnapshot(currentSessionId, "INIT", "New session started.");
    }

    private static AiPlayStrategy resolveAiStrategy(String normalizedDifficulty) {
        return switch (normalizedDifficulty) {
            case "NORMAL" -> new NormalAiPlayStrategy();
            case "HARD" -> new HardAiPlayStrategy();
            default -> new EasyAiPlayStrategy();
        };
    }

    private static String formatAiDifficultyLabel(String normalizedDifficulty) {
        return switch (normalizedDifficulty) {
            case "NORMAL" -> "Normal";
            case "HARD" -> "Hard";
            default -> "Easy";
        };
    }

    // ═══════════════════════════════════════════════════════
    //  回合操作（委托 TurnFlowService）
    // ═══════════════════════════════════════════════════════

    public void drawCards(Player player, int count) {
        turnFlowService.drawCards(player, count);
    }

    public void playCard(Player player, Card card, String actionType) {
        turnFlowService.playCard(player, card, actionType, null);
    }

    public void playCard(Player player, Card card, String actionType, ActionParamContext params) {
        turnFlowService.playCard(player, card, actionType, params);
    }

    public void discardFromHand(Player player, Card card) {
        turnFlowService.discardFromHand(player, card);
    }

    public void reassignWildProperty(Player player, String wildPropertyCardId, String newColorKey) {
        turnFlowService.reassignWildProperty(player, wildPropertyCardId, newColorKey);
    }

    public void handleReassignWildCommand(String wildPropertyCardId, String newColorKey) {
        turnFlowService.reassignWildProperty(requireCurrentPlayer(), wildPropertyCardId, newColorKey);
    }

    public void endTurn(Player player) {
        if (player == null) {
            return;
        }
        Player next = turnFlowService.endTurn(player);
        if (next instanceof AIPlayer ai) {
            aiTurnService.executeAiTurn(ai);
        }
    }

    public ActionEffectResult handleActionCardCommand(
            int handIndex, String targetPlayerId, String colorKey,
            int targetPropIndex, int actorPropIndex) {
        return turnFlowService.handleActionCardCommand(
                handIndex, targetPlayerId, colorKey, targetPropIndex, actorPropIndex);
    }

    public ActionEffectResult handleActionCardCommand(ActionParamContext params) {
        return turnFlowService.handleActionCardCommand(params);
    }

    public ActionEffectResult playActionCard(
            Player actor, ActionCard card, ActionEffectContext ctx, ActionParamContext params) {
        return turnFlowService.playActionCard(actor, card, ctx, params);
    }

    // ═══════════════════════════════════════════════════════
    //  网络层命令入口
    // ═══════════════════════════════════════════════════════

    public void handleDrawCommand(int count) {
        try {
            ensureNotPaused();
            ensureSessionActive();
            clearLastError();
            Player current = requireCurrentPlayer();
            drawCards(current, count);
        } catch (RuntimeException e) {
            if (!PauseVoteService.MSG_PAUSED.equals(e.getMessage())) {
                recordErrorAndSnapshot(e);
            }
            throw e;
        }
    }

    public void handlePlayActionRequest(PlayActionRequest req) {
        try {
            ensureNotPaused();
            ensureSessionActive();
            clearLastError();
            validatePlayActionRequest(req);
            String normalized = req.getActionType().trim().toUpperCase();

            if (turnFlowService.currentTurnPhase
                    == TurnFlowService.TurnPhase.WAITING_FOR_RESPONSE) {
                if ("RESPONSE_PASS".equals(normalized)) {
                    effectStackOrchestrator.performResponsePass(req.getActingPlayerId());
                    return;
                }
                if ("ACTION".equals(normalized)) {
                    effectStackOrchestrator.handleWaiverPlay(req);
                    return;
                }
                throw new IllegalStateException(
                        "等待响应阶段仅允许打出免租（ACTION）或放弃（RESPONSE_PASS）。");
            }

            if ("DISCARD".equals(normalized)) {
                handleDiscardRequest(req);
                return;
            }

            Player current = requireCurrentPlayer();
            ActionParamContext params = ActionParamContext.fromPlayRequest(req);
            if ("ACTION".equals(normalized)) {
                turnFlowService.handleActionCardCommand(params);
                return;
            }
            Card card = turnFlowService.resolveCardInHand(
                    current, req.getCardId(), req.getHandIndex());
            turnFlowService.playCard(current, card, normalized, params);
        } catch (RuntimeException e) {
            if (!PauseVoteService.MSG_PAUSED.equals(e.getMessage())) {
                recordErrorAndSnapshot(e);
            }
            throw e;
        }
    }

    public void validatePlayActionRequest(PlayActionRequest req) {
        if (req == null) {
            throw new ProtocolValidationException(ERR_PLAY_REQUEST_EMPTY, "出牌请求不能为空。");
        }
        if (req.getActionType() == null || req.getActionType().isBlank()) {
            throw new ProtocolValidationException(
                    ERR_PLAY_ACTION_TYPE_REQUIRED, "actionType 不能为空。");
        }
        String normalized = req.getActionType().trim().toUpperCase(Locale.ROOT);
        Set<String> allowed = Set.of(
                "DEPLOY", "DEPOSIT", "ACTION", "DISCARD", "RESPONSE_PASS");
        if (!allowed.contains(normalized)) {
            throw new ProtocolValidationException(
                    ERR_PLAY_ACTION_TYPE_INVALID, "不支持的 actionType: " + req.getActionType());
        }
        if ("RESPONSE_PASS".equals(normalized)) {
            if (req.getActingPlayerId() == null || req.getActingPlayerId().isBlank()) {
                throw new ProtocolValidationException(
                        ERR_PLAY_ACTING_PLAYER_REQUIRED,
                        "RESPONSE_PASS 必须提供 actingPlayerId。");
            }
            return;
        }
        if (req.getCardId() == null || req.getCardId().isBlank()) {
            if (req.getHandIndex() == null) {
                throw new ProtocolValidationException(
                        ERR_PLAY_CARD_SELECTOR_REQUIRED, "必须提供 cardId 或 handIndex。");
            }
            if (req.getHandIndex() < 0) {
                throw new ProtocolValidationException(
                        ERR_PLAY_HAND_INDEX_INVALID, "handIndex 必须 >= 0。");
            }
        }
    }

    private void handleDiscardRequest(PlayActionRequest req) {
        Player current = requireCurrentPlayer();
        Card card = turnFlowService.resolveCardInHand(
                current, req.getCardId(), req.getHandIndex());
        turnFlowService.discardFromHand(current, card);
    }

    public void handlePlayCommand(int handIndex, String actionType) {
        ensureSessionActive();
        PlayActionRequest r = new PlayActionRequest();
        r.setHandIndex(handIndex);
        r.setActionType(actionType);
        handlePlayActionRequest(r);
    }

    public void handleEndTurnCommand() {
        try {
            ensureSessionActive();
            clearLastError();
            Player current = requireCurrentPlayer();
            endTurn(current);
        } catch (RuntimeException e) {
            if (!PauseVoteService.MSG_PAUSED.equals(e.getMessage())) {
                recordErrorAndSnapshot(e);
            }
            throw e;
        }
    }

    public void handleQuitCommand(String playerId) {
        if (sessionForceEnded) {
            return;
        }
        if (playerId == null || playerId.isBlank()) {
            return;
        }
        if (sessionPlayers.isEmpty()) {
            return;
        }
        boolean known = false;
        for (Player p : sessionPlayers) {
            if (playerId.equals(p.getPlayerId())) {
                known = true;
                break;
            }
        }
        if (!known) {
            return;
        }
        quitPlayerIds.add(playerId);
        if (quitPlayerIds.size() >= sessionPlayers.size()) {
            sessionForceEnded = true;
            forceEndReason = "ALL_QUIT";
            pushSnapshot(currentSessionId, "GAME_FORCE_END");
        }
    }

    public void handleResponsePass(String actingPlayerId) {
        try {
            ensureSessionActive();
            clearLastError();
            effectStackOrchestrator.performResponsePass(actingPlayerId);
        } catch (RuntimeException e) {
            recordErrorAndSnapshot(e);
            throw e;
        }
    }

    @Override
    public void submitPlayAction(PlayActionRequest request) {
        handlePlayActionRequest(request);
    }

    // ═══════════════════════════════════════════════════════
    //  租金结算（委托 RentSettlementService）
    // ═══════════════════════════════════════════════════════

    public PaymentSettlement.Result requestRentPayment(
            Player from, Player to, int amount, GameContext context) {
        return rentSettlementService.requestRentPayment(from, to, amount, context);
    }

    public PaymentSettlement.Result collectRentForColor(
            Player landlord, Player tenant, String colorKey) {
        return rentSettlementService.collectRentForColor(landlord, tenant, colorKey);
    }

    public int computeRentDueForColor(Player landlord, String colorKey) {
        return rentSettlementService.computeRentDueForColor(landlord, colorKey);
    }

    // ═══════════════════════════════════════════════════════
    //  暂停 / PVP 投票（委托 PauseVoteService）
    // ═══════════════════════════════════════════════════════

    public boolean isPaused() {
        return pauseVoteService.isPaused();
    }

    public boolean isPausePending() {
        return pauseVoteService.isPausePending();
    }

    public Set<String> getPauseAcksView() {
        return pauseVoteService.getPauseAcksView();
    }

    public boolean isPvpMode() {
        return "PVP".equals(sessionGameMode);
    }

    public void pause() {
        pauseVoteService.pause();
    }

    public void requestPause() {
        pauseVoteService.requestPause();
    }

    public void acknowledgePause(String playerId) {
        pauseVoteService.acknowledgePause(playerId);
    }

    public void resume() {
        pauseVoteService.resume();
    }

    // ═══════════════════════════════════════════════════════
    //  存档 / 读档（委托 SaveLoadService）
    // ═══════════════════════════════════════════════════════

    public String exportSessionJson() {
        return saveLoadService.exportSessionJson();
    }

    public void importSessionJson(String json) {
        saveLoadService.importSessionJson(json);
    }

    // ═══════════════════════════════════════════════════════
    //  查询 API
    // ═══════════════════════════════════════════════════════

    public TurnManager getTurnManager() {
        return turnManager;
    }

    public List<Player> getSessionPlayersView() {
        return Collections.unmodifiableList(sessionPlayers);
    }

    public Player getCurrentPlayer() {
        return turnManager.getCurrentPlayer();
    }

    public int getFullRoundsCompleted() {
        return fullRoundsCompleted;
    }

    String getCurrentSessionId() {
        return currentSessionId;
    }

    String getCurrentTurnPlayerId() {
        return turnFlowService.currentTurnPlayerId;
    }

    Player requireCurrentPlayer() {
        Player current = turnManager.getCurrentPlayer();
        if (current == null) {
            throw new IllegalStateException("当前没有可行动玩家，请先 startNewSession。");
        }
        return current;
    }

    // ═══════════════════════════════════════════════════════
    //  包级回调 / 内部状态访问（供服务类使用）
    // ═══════════════════════════════════════════════════════

    GameContext getGameContext() {
        return gameContext;
    }

    Player resolvePlayer(String playerId) {
        if (playerId == null || playerId.isBlank()) {
            return null;
        }
        for (Player p : sessionPlayers) {
            if (p.getPlayerId().equals(playerId)) {
                return p;
            }
        }
        return null;
    }

    boolean isSessionForceEnded() {
        return sessionForceEnded;
    }

    void onTurnAdvanced(Player nextPlayer) {
        if (nextPlayer != null && !sessionPlayers.isEmpty()
                && sessionPlayers.get(0).getPlayerId().equals(nextPlayer.getPlayerId())) {
            fullRoundsCompleted++;
            maybeAutosaveAfterFullRound();
        }
    }

    void ensureNotPaused() {
        pauseVoteService.ensureNotPaused();
    }

    void ensureSessionActive() {
        if (sessionForceEnded) {
            throw new IllegalStateException("对局已强制结束，不可继续操作。");
        }
        if (sessionStartEpochMs <= 0) {
            return;
        }
        long elapsed = System.currentTimeMillis() - sessionStartEpochMs;
        if (elapsed <= sessionLimitMs()) {
            return;
        }
        sessionForceEnded = true;
        forceEndReason = "TIMEOUT";
        pushSnapshot(currentSessionId, "GAME_FORCE_END");
        throw new IllegalStateException("对局已超过单局时长上限，已强制结束。");
    }

    void recordError(String code, String message) {
        this.lastErrorCode = code;
        this.lastErrorMessage = message;
        this.lastErrorTimestampEpochMs = System.currentTimeMillis();
    }

    void clearLastError() {
        this.lastErrorCode = null;
        this.lastErrorMessage = null;
        this.lastErrorTimestampEpochMs = 0L;
    }

    void assertDeckIntegrityOrLog() {
        if (!VERIFY_DECK) {
            return;
        }
        int total = engine.countAllCardsInPlay(sessionPlayers);
        int expected = GameConstants.STANDARD_DECK_SIZE;
        if (total == expected) {
            return;
        }
        String msg = "Deck integrity violation: expected " + expected
                + " cards in play, found " + total;
        LOG.log(Level.SEVERE, msg);
        System.err.println("[DECK_VERIFY] " + msg);
        throw new IllegalStateException(msg);
    }

    /**
     * {@link SaveLoadService#importSessionJson} 回调：memento 已写入控制器私有字段后，
     * 重置暂停/退出/轮次等运行时状态，并广播 INIT。
     */
    void resetStateAfterLoad(String gameMode) {
        effectStackOrchestrator.cancelPendingResponseTimeout();
        clearLastError();
        pauseVoteService.reset();
        this.sessionGameMode = (gameMode != null && !gameMode.isBlank())
                ? gameMode.trim().toUpperCase() : "HVM";
        this.fullRoundsCompleted = 0;
        quitPlayerIds.clear();
        assertDeckIntegrityOrLog();
        pushSnapshot(currentSessionId, "INIT", "Session loaded from save.");
    }

    // ─── 快照推送 ──────────────────────────────────────────

    void pushSnapshot(String sessionId, String phase) {
        pushSnapshot(sessionId, phase, null);
    }

    void pushSnapshot(String sessionId, String phase, String actionSummary) {
        String originalPhase = phase;
        if (!sessionForceEnded && sessionStartEpochMs > 0) {
            long elapsed = System.currentTimeMillis() - sessionStartEpochMs;
            if (elapsed > sessionLimitMs()) {
                sessionForceEnded = true;
                forceEndReason = "TIMEOUT";
            }
        }
        if (sessionForceEnded) {
            phase = "GAME_FORCE_END";
        }

        String summary = (actionSummary != null && !actionSummary.isBlank())
                ? actionSummary
                : fallbackActionSummary(phase);

        TurnFlowService.TurnPhase tp = turnFlowService.currentTurnPhase;

        GameStateSnapshot snap = new GameStateSnapshot();
        snap.setSessionId(sessionId);
        snap.setPhase(phase);
        snap.setLastActionSummary(summary);
        snap.setCurrentPlayerId(turnFlowService.currentTurnPlayerId);
        snap.setTurnPhase(tp == null ? "UNKNOWN" : tp.name());
        snap.setDrawPileCount(engine.remainingCount());
        snap.setDiscardPileCount(engine.discardCount());
        if (tp == TurnFlowService.TurnPhase.WAITING_FOR_RESPONSE) {
            StackResponseState st = gameContext.getResponseState();
            if (st != null) {
                snap.setPendingResponsePlayerId(st.getAwaitingPlayerId());
                snap.setPendingResponseRole(st.getRole().name());
                snap.setResponseDeadlineEpochMs(st.getDeadlineEpochMs());
                snap.setPendingResponseHint(
                        EffectStackOrchestrator.buildPendingResponseHint(st));
            }
            snap.setEffectStackDepth(gameContext.getEffectStackView().size());
        } else {
            snap.setPendingResponsePlayerId(null);
            snap.setPendingResponseRole(null);
            snap.setResponseDeadlineEpochMs(0L);
            snap.setPendingResponseHint(null);
            snap.setEffectStackDepth(0);
        }
        snap.setLastErrorCode(lastErrorCode);
        snap.setLastErrorMessage(lastErrorMessage);
        snap.setLastErrorTimestampEpochMs(lastErrorTimestampEpochMs);
        snap.setGameOver(sessionForceEnded || "GAME_OVER".equals(originalPhase));
        snap.setForceEndReason(sessionForceEnded ? forceEndReason : null);
        for (Player p : sessionPlayers) {
            snap.addPlayerSummary(
                    p.getPlayerId(),
                    p.getDisplayName(),
                    p.getHandCardCount(),
                    p.getBankCardCount(),
                    p.getPropertyCardCount(),
                    p.getActionZoneCardCount(),
                    p.countCompletePropertySets(),
                    p.totalBankValueM(),
                    PropertyZoneSummary.summarizeByColor(p.getPropertyCardsView())
            );
        }
        gameUpdateSubject.notifyStateChanged(snap);
    }

    // ─── 私有工具 ──────────────────────────────────────────

    private void recordErrorAndSnapshot(RuntimeException e) {
        recordError(e.getClass().getSimpleName(), runtimeExceptionDetail(e));
        pushSnapshot(currentSessionId,
                sessionForceEnded ? "GAME_FORCE_END" : "RULE_VIOLATION");
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
            case "GAME_OVER" -> "Game over.";
            case "REASSIGN_WILD" -> "Wild property reassigned.";
            case "RENT_PAID", "RENT_FAILED" -> "Rent settlement updated.";
            case "PAUSE_PENDING" -> "Pause vote in progress.";
            case "RULE_VIOLATION" -> (lastErrorMessage != null && !lastErrorMessage.isBlank())
                    ? lastErrorMessage
                    : "Rule violation.";
            case "GAME_FORCE_END" -> forceEndReason != null
                    ? "Game ended: " + forceEndReason : "Game force-ended.";
            case "ACTION_SUCCESS", "ACTION_FAILED", "ACTION_COUNTERED" ->
                    "Action card effect resolved.";
            case "RENT_AWAITING_RESPONSE" -> "Awaiting rent response.";
            case "JSN_AWAITING_COUNTER" -> "Awaiting landlord counter to Just Say No.";
            case "RESPONSE_PASS", "RESPONSE_TIMEOUT" -> "Rent response chain updated.";
            default -> phase.replace('_', ' ');
        };
    }

    private void maybeAutosaveAfterFullRound() {
        saveLoadService.maybeAutosaveAfterFullRound(fullRoundsCompleted);
    }

    private static long sessionLimitMs() {
        Long v = Long.getLong(GameConstants.SESSION_LIMIT_MS_PROPERTY);
        return v != null ? v : GameConstants.DEFAULT_SESSION_LIMIT_MS;
    }

    private static String runtimeExceptionDetail(RuntimeException e) {
        String m = e.getMessage();
        if (m != null && !m.isBlank()) {
            return m;
        }
        return e.getClass().getSimpleName();
    }
}
