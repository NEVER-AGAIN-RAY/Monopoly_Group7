package com.monopoly.controller;

import com.monopoly.model.ActionCard;
import com.monopoly.model.AIPlayer;
import com.monopoly.model.AiGameBridge;
import com.monopoly.model.Card;
import com.monopoly.model.EffectStackEntry;
import com.monopoly.model.EffectStackResolver;
import com.monopoly.model.GameConstants;
import com.monopoly.model.GameContext;
import com.monopoly.model.PropertyWildCard;
import com.monopoly.model.StackResponseState;
import com.monopoly.model.StealTargetZone;
import com.monopoly.model.HumanPlayer;
import com.monopoly.model.Player;
import com.monopoly.model.PaymentSettlement;
import com.monopoly.model.PropertyCard;
import com.monopoly.model.PropertySetCalculator;
import com.monopoly.model.PropertyZoneSummary;
import com.monopoly.model.RentCalculator;
import com.monopoly.model.dto.ActionParamContext;
import com.monopoly.model.dto.GameStateSnapshot;
import com.monopoly.model.dto.PlayActionRequest;
import com.monopoly.model.dto.StartSessionRequest;
import com.monopoly.model.persistence.GameSessionMemento;
import com.monopoly.model.persistence.SaveEncryption;
import com.monopoly.model.effects.ActionEffectContext;
import com.monopoly.model.effects.ActionEffectDispatcher;
import com.monopoly.model.effects.ActionEffectResult;
import com.monopoly.model.effects.DoubleRentEffect;
import com.monopoly.model.effects.RentEffect;
import com.monopoly.pattern.factory.CardFactory;
import com.monopoly.pattern.factory.MonopolyDealCardFactory;
import com.monopoly.pattern.observer.GameUpdateSubject;
import com.monopoly.pattern.strategy.AiPlayStrategy;
import com.monopoly.pattern.strategy.EasyAiPlayStrategy;
import com.monopoly.pattern.strategy.HardAiPlayStrategy;
import com.monopoly.pattern.strategy.NormalAiPlayStrategy;
import com.monopoly.pattern.singleton.GameEngineSingleton;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 【Facade 外观模式】
 * 对 Flutter 客户端/网络层暴露统一的高层操作入口，隐藏摸牌、租金结算、回合推进等协作细节。
 * 骨架阶段仅做依赖编排与空方法签名。
 */
public class GameController implements AiGameBridge {

    private static final Logger LOG = Logger.getLogger(GameController.class.getName());
    /** 开发期校验：{@code -Dmonopoly.verifyDeck=true} 时检查全场牌数恒为 108。 */
    private static final boolean VERIFY_DECK = Boolean.parseBoolean(System.getProperty("monopoly.verifyDeck", "false"));
    private static final boolean PERF_LOG = Boolean.parseBoolean(System.getProperty("monopoly.perfLog", "false"));

    private enum TurnPhase {
        DRAW,
        PLAY,
        /** 收租/免租连锁：等待特定玩家打出 Just Say No 或放弃 */
        WAITING_FOR_RESPONSE,
        END_TURN
    }

    private static final int RESPONSE_WINDOW_SECONDS = 15;

    private final GameEngineSingleton engine = GameEngineSingleton.getInstance();
    private final TurnManager turnManager = new TurnManager();
    private final CardFactory cardFactory = new MonopolyDealCardFactory();
    private final GameUpdateSubject gameUpdateSubject;
    private final GameContext gameContext = new GameContext();
    private final List<Player> sessionPlayers = new ArrayList<>();
    private String currentSessionId = "unknown";

    private final ScheduledExecutorService responseScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "effect-response-timeout");
        t.setDaemon(true);
        return t;
    });
    private volatile ScheduledFuture<?> pendingResponseFuture;

    // 仅骨架阶段使用：记录“当前轮次玩家”的出牌/行动次数，避免一个回合打出超过 3 张牌
    private static final int MAX_ACTIONS_PER_TURN = 3;
    private static final int MAX_HAND_SIZE = 7;
    private static final int INITIAL_HAND_SIZE = 5;
    public static final String ERR_PLAY_REQUEST_EMPTY = "PLAY_REQUEST_EMPTY";
    public static final String ERR_PLAY_ACTION_TYPE_REQUIRED = "PLAY_ACTION_TYPE_REQUIRED";
    public static final String ERR_PLAY_ACTION_TYPE_INVALID = "PLAY_ACTION_TYPE_INVALID";
    public static final String ERR_PLAY_CARD_SELECTOR_REQUIRED = "PLAY_CARD_SELECTOR_REQUIRED";
    public static final String ERR_PLAY_HAND_INDEX_INVALID = "PLAY_HAND_INDEX_INVALID";
    public static final String ERR_PLAY_ACTING_PLAYER_REQUIRED = "PLAY_ACTING_PLAYER_REQUIRED";
    private String currentTurnPlayerId;
    private int currentTurnActionCount;
    private TurnPhase currentTurnPhase;

    private String lastErrorCode;
    private String lastErrorMessage;
    private long lastErrorTimestampEpochMs;

    /** 当前对局开始时间（epoch ms），{@link #startNewSession} 时设置 */
    private long sessionStartEpochMs;
    private volatile boolean sessionForceEnded;
    /** 强制结束原因，如 TIMEOUT、ALL_QUIT */
    private String forceEndReason;

    /** 已发送 QUIT 的玩家（无连接映射时以人数达标触发 ALL_QUIT） */
    private final Set<String> quitPlayerIds = new HashSet<>();

    /** §2.1.6a：人机模式可立即暂停；人人模式需 PAUSE_REQUEST + 全员 PAUSE_ACK。 */
    private volatile boolean paused;
    private static final String MSG_PAUSED = "游戏已暂停。";

    /** 当前对局模式：{@code HVM} / {@code PVP}，由 {@link #startNewSession(StartSessionRequest)} 写入。 */
    private String sessionGameMode = "HVM";

    /** PVP：当前回合玩家发起暂停请求后，等待全员 ACK 前为 true；未确认前不冻结。 */
    private volatile boolean pausePending;
    private final Set<String> pauseAcks = new HashSet<>();

    /**
     * 「一轮」= 每名玩家至少完成过一次 {@link #endTurn} 推进；当轮转回到 {@link #sessionPlayers} 首位玩家时 +1。
     */
    private int fullRoundsCompleted;

    public GameController(GameUpdateSubject gameUpdateSubject) {
        this.gameUpdateSubject = gameUpdateSubject;
        this.currentTurnPlayerId = null;
        this.currentTurnActionCount = 0;
        this.currentTurnPhase = TurnPhase.DRAW;
        clearLastError();
    }

    /** 创建对局、绑定玩家与牌堆初始化入口（默认 2 人、HVM、EASY、不随机先手） */
    public void startNewSession(String sessionId) {
        StartSessionRequest req = new StartSessionRequest();
        req.setSessionId(sessionId);
        req.setPlayerCount(2);
        req.setGameMode("HVM");
        req.setAiDifficulty("EASY");
        req.setRandomizeFirstPlayer(false);
        startNewSession(req);
    }

    /**
     * 按配置创建对局：2–5 人、人机/人人、AI 难度、可选随机先手。
     */
    public void startNewSession(StartSessionRequest req) {
        if (req == null) {
            throw new IllegalArgumentException("StartSessionRequest 不能为 null。");
        }
        if (paused) {
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
            throw new IllegalArgumentException("gameMode 必须为 HVM 或 PVP，当前为 " + req.getGameMode() + "。");
        }

        String sid = req.getSessionId();
        currentSessionId = (sid == null || sid.isBlank()) ? "session-default" : sid;
        sessionGameMode = mode;
        pauseAcks.clear();
        pausePending = false;
        paused = false;
        fullRoundsCompleted = 0;
        sessionStartEpochMs = System.currentTimeMillis();
        sessionForceEnded = false;
        forceEndReason = null;
        quitPlayerIds.clear();
        engine.attachDrawPile(cardFactory.createStandardDeck108());

        sessionPlayers.clear();
        if ("HVM".equals(mode)) {
            String diff = req.getAiDifficulty() == null ? "EASY" : req.getAiDifficulty().trim().toUpperCase();
            String diffLabel = formatAiDifficultyLabel(diff);
            sessionPlayers.add(new HumanPlayer("human-1", "Human"));
            for (int i = 1; i < count; i++) {
                sessionPlayers.add(new AIPlayer("ai-" + i, "AI-" + diffLabel, resolveAiStrategy(diff)));
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
        cancelPendingResponseTimeout();

        for (Player p : sessionPlayers) {
            for (int i = 0; i < INITIAL_HAND_SIZE; i++) {
                Card card = engine.drawOne();
                if (card == null) {
                    break;
                }
                p.receiveCardToHand(card);
            }
        }

        Player current = turnManager.getCurrentPlayer();
        this.currentTurnPlayerId = current != null ? current.getPlayerId() : null;
        this.currentTurnActionCount = 0;
        this.currentTurnPhase = TurnPhase.DRAW;
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

    /** 摸牌：封装抽牌堆、补堆、广播等内部步骤 */
    public void drawCards(Player player, int count) {
        if (player == null) {
            return;
        }
        ensureSessionActive();
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
        // 真实抽牌骨架：从引擎抽牌堆取牌，抽到空堆就提前结束
        int drawn = 0;
        for (int i = 0; i < effectiveCount; i++) {
            Card card = engine.drawOne();
            if (card == null) {
                break;
            }
            player.receiveCardToHand(card);
            drawn++;
        }
        // 摸牌阶段完成后进入出牌阶段
        currentTurnPhase = TurnPhase.PLAY;
        assertDeckIntegrityOrLog();
        // 提示网络层刷新状态（骨架 phase）
        pushSnapshot(currentSessionId, "DRAW", player.getDisplayName() + " drew " + drawn + " card(s).");
    }

    /** 出牌并触发效果链 */
    public void playCard(Player player, Card card, String actionType) {
        playCard(player, card, actionType, null);
    }

    /**
     * @param params 部署万能牌时需带 {@link PlayActionRequest#getTargetColorKey()}；可为 null（如 AI 在调用前已设置万能颜色）
     */
    public void playCard(Player player, Card card, String actionType, ActionParamContext params) {
        if (player == null || card == null) {
            return;
        }
        ensureSessionActive();
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

        // 每回合最多可打出三张牌（所有出牌行为累计）
        if (currentTurnActionCount >= MAX_ACTIONS_PER_TURN) {
            throw new IllegalStateException("每回合最多可出 3 张牌，已达到上限。");
        }
        currentTurnActionCount++;

        // 根据 actionType 把牌转移到对应分区
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
            // 不经过效果分派：仅移入行动区。AI/前端打出行动卡须走 handlePlayActionRequest(ACTION)
            // → handleActionCardCommand → playActionCard（收租入栈、其余 ActionEffectDispatcher）。
            if (!(card instanceof ActionCard)) {
                throw new IllegalArgumentException("ACTION 需要 ActionCard（行动卡）。");
            }
            player.placeActionToCenter((ActionCard) card);
        } else {
            throw new IllegalArgumentException("未知 actionType: " + actionType);
        }

        if (currentTurnActionCount >= MAX_ACTIONS_PER_TURN) {
            // 达到上限后允许直接结束回合
            currentTurnPhase = TurnPhase.END_TURN;
        }

        pushSnapshot(currentSessionId, normalizedActionType,
                player.getDisplayName() + " played " + normalizedActionType + " (" + card.getName() + ").");
    }

    /**
     * 出牌阶段将手牌弃入弃牌堆，计入本回合最多 3 次出牌/行动。
     */
    public void discardFromHand(Player player, Card card) {
        if (player == null || card == null) {
            return;
        }
        ensureSessionActive();
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
        pushSnapshot(currentSessionId, "DISCARD",
                player.getDisplayName() + " discarded a card (" + card.getName() + ").");
    }

    /**
     * 出牌阶段调整已部署万能房产的目标颜色，不消耗本回合 3 次出牌计数。
     */
    public void reassignWildProperty(Player player, String wildPropertyCardId, String newColorKey) {
        if (player == null) {
            throw new IllegalArgumentException("player 不能为 null。");
        }
        ensureSessionActive();
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
        pushSnapshot(currentSessionId, "REASSIGN_WILD",
                player.getDisplayName() + " reassigned wild property to " + normalizedColor + ".");
    }

    public void handleReassignWildCommand(String wildPropertyCardId, String newColorKey) {
        reassignWildProperty(requireCurrentPlayer(), wildPropertyCardId, newColorKey);
    }

    private static String normalizeWildReassignColorKey(String newColorKey) {
        if (newColorKey == null || newColorKey.isBlank()) {
            throw new IllegalArgumentException("newColorKey 不能为空。");
        }
        String key = newColorKey.trim().toUpperCase(Locale.ROOT);
        if (!PropertySetCalculator.REQUIRED_BY_COLOR.containsKey(key)) {
            throw new IllegalArgumentException("无效的颜色键，须为轨道标准色之一: " + key);
        }
        return key;
    }

    /**
     * 租金支付：{@code from} 向 {@code to} 支付 {@code amount}（M）。
     * 仅使用银行 + 财产区；银行牌归收租方银行，房产牌进弃牌堆；找零不退。
     *
     * @return 结算结果，便于网络层展示原因
     */
    public PaymentSettlement.Result requestRentPayment(Player from, Player to, int amount, GameContext context) {
        ensureSessionActive();
        PaymentSettlement.Result result = PaymentSettlement.settle(from, to, amount, engine);
        String rentPhase = result.isSuccess() ? "RENT_PAID" : "RENT_FAILED";
        pushSnapshot(currentSessionId, rentPhase,
                "Rent " + amount + "M from " + from.getDisplayName() + " to " + to.getDisplayName()
                        + " (" + (result.isSuccess() ? "paid" : "failed") + ").");
        return result;
    }

    /**
     * 按收租方财产区指定颜色计算应付租金（M），并尝试由 {@code tenant} 支付给 {@code landlord}。
     */
    public PaymentSettlement.Result collectRentForColor(Player landlord, Player tenant, String colorKey) {
        ensureSessionActive();
        int due = RentCalculator.computeRentForColor(landlord, colorKey);
        return requestRentPayment(tenant, landlord, due, new GameContext());
    }

    /** 仅查询某颜色应收租金，不执行支付。 */
    public int computeRentDueForColor(Player landlord, String colorKey) {
        long startNs = PERF_LOG ? System.nanoTime() : 0L;
        int due = RentCalculator.computeRentForColor(landlord, colorKey);
        if (PERF_LOG) {
            long elapsedUs = (System.nanoTime() - startNs) / 1_000L;
            LOG.info("perf computeRentDueForColor: " + elapsedUs + "us color=" + colorKey);
        }
        return due;
    }

    /** 结束回合：弃牌限制、轮转 */
    public void endTurn(Player player) {
        if (player == null) {
            return;
        }
        ensureNotPaused();
        ensureSessionActive();
        ensureTurnContext(player);
        if (currentTurnPhase == TurnPhase.WAITING_FOR_RESPONSE) {
            throw new IllegalStateException("正在等待免租响应，不能结束回合。");
        }
        if (currentTurnPhase == TurnPhase.DRAW) {
            throw new IllegalStateException("当前回合尚未摸牌，不能结束回合。");
        }

        // 执行真实弃牌到上限（骨架版自动弃牌）
        if (player.getHandCardCount() > MAX_HAND_SIZE) {
            List<Card> discarded = player.discardOverflowTo(MAX_HAND_SIZE);
            engine.discardMany(discarded);
            System.out.println("[FORCE_DISCARD] 玩家 " + player.getPlayerId() + " 弃牌 " + discarded.size() + " 张至上限。");
        }
        assertDeckIntegrityOrLog();

        if (checkWinCondition(player)) {
            pushSnapshot(currentSessionId, "GAME_OVER",
                    player.getDisplayName() + " wins (3 complete property sets).");
            return;
        }

        turnManager.advanceTurn();

        // 回合推进后重置行动计数对象
        Player next = turnManager.getCurrentPlayer();
        this.currentTurnPlayerId = next != null ? next.getPlayerId() : null;
        this.currentTurnActionCount = 0;
        this.currentTurnPhase = TurnPhase.DRAW;

        if (next != null && !sessionPlayers.isEmpty()
                && sessionPlayers.get(0).getPlayerId().equals(next.getPlayerId())) {
            fullRoundsCompleted++;
            maybeAutosaveAfterFullRound();
        }

        pushSnapshot(currentSessionId, "TURN_END", player.getDisplayName() + " ended turn.");

        // 人机模式：若下一位是 AI，自动执行一个最小 AI 回合
        if (next instanceof AIPlayer ai) {
            executeAiTurn(ai);
        }
    }

    /** 每满 3 个整轮：可选写入 {@code ~/.monopoly-deal/autosave.json}，见 {@link GameConstants#AUTOSAVE_PROPERTY}。 */
    private void maybeAutosaveAfterFullRound() {
        if (fullRoundsCompleted <= 0 || fullRoundsCompleted % 3 != 0) {
            return;
        }
        Path path = Path.of(System.getProperty("user.home"), ".monopoly-deal", "autosave.json");
        LOG.info("autosave eligible: fullRoundsCompleted=" + fullRoundsCompleted + " path=" + path);
        if (!Boolean.parseBoolean(System.getProperty(GameConstants.AUTOSAVE_PROPERTY, "false"))) {
            return;
        }
        try {
            String json = exportSessionJson();
            String payload = SaveEncryption.encodeForStorage(json);
            Files.createDirectories(path.getParent());
            Files.writeString(path, payload, StandardCharsets.UTF_8);
            LOG.info("autosave written chars=" + payload.length() + " path=" + path);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "autosave failed: " + path, e);
        }
    }

    public int getFullRoundsCompleted() {
        return fullRoundsCompleted;
    }

    public TurnManager getTurnManager() {
        return turnManager;
    }

    public List<Player> getSessionPlayersView() {
        return Collections.unmodifiableList(sessionPlayers);
    }

    public Player getCurrentPlayer() {
        return turnManager.getCurrentPlayer();
    }

    /**
     * 导出当前会话的 T3-1 完整快照 JSON（Gson），供网络层 SAVE_GAME 或客户端持久化。
     */
    public String exportSessionJson() {
        return GameSessionMemento.capture(this).toJson();
    }

    /**
     * 从 T3-1 JSON 恢复会话：重置 {@link GameEngineSingleton} 牌堆、重建 {@link Player}、
     * {@link TurnManager#bindTurnOrder}、{@link GameContext} 与效果栈，取消响应超时调度，并广播 {@code INIT}。
     */
    public void importSessionJson(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("memento JSON 不能为空。");
        }
        String plain = SaveEncryption.decodeFromStorage(json);
        GameSessionMemento m = GameSessionMemento.fromJson(plain);
        GameSessionMemento.applyToController(this, m);
        cancelPendingResponseTimeout();
        clearLastError();
        this.paused = false;
        this.pausePending = false;
        this.pauseAcks.clear();
        String gm = m.getGameMode();
        this.sessionGameMode = (gm != null && !gm.isBlank()) ? gm.trim().toUpperCase() : "HVM";
        this.fullRoundsCompleted = 0;
        quitPlayerIds.clear();
        assertDeckIntegrityOrLog();
        pushSnapshot(currentSessionId, "INIT", "Session loaded from save.");
    }

    @Override
    public void submitPlayAction(PlayActionRequest request) {
        handlePlayActionRequest(request);
    }

    public void handleDrawCommand(int count) {
        try {
            ensureNotPaused();
            ensureSessionActive();
            clearLastError();
            Player current = requireCurrentPlayer();
            drawCards(current, count);
        } catch (RuntimeException e) {
            if (!MSG_PAUSED.equals(e.getMessage())) {
                recordErrorAndSnapshot(e);
            }
            throw e;
        }
    }

    /**
     * 解析 {@link PlayActionRequest}：DISCARD 走弃牌；ACTION 时走 {@link #handleActionCardCommand(ActionParamContext)}；否则按 DEPLOY/DEPOSIT 出牌。
     */
    public void handlePlayActionRequest(PlayActionRequest req) {
        try {
            ensureNotPaused();
            ensureSessionActive();
            clearLastError();
            validatePlayActionRequest(req);
            String normalized = req.getActionType().trim().toUpperCase();

            if (currentTurnPhase == TurnPhase.WAITING_FOR_RESPONSE) {
                if ("RESPONSE_PASS".equals(normalized)) {
                    performResponsePass(req.getActingPlayerId());
                    return;
                }
                if ("ACTION".equals(normalized)) {
                    handleWaiverPlay(req);
                    return;
                }
                throw new IllegalStateException("等待响应阶段仅允许打出免租（ACTION）或放弃（RESPONSE_PASS）。");
            }

            if ("DISCARD".equals(normalized)) {
                handleDiscardRequest(req);
                return;
            }

            Player current = requireCurrentPlayer();
            ActionParamContext params = ActionParamContext.fromPlayRequest(req);
            if ("ACTION".equals(normalized)) {
                handleActionCardCommand(params);
                return;
            }
            Card card = resolveCardInHand(current, req.getCardId(), req.getHandIndex());
            playCard(current, card, normalized, params);
        } catch (RuntimeException e) {
            if (!MSG_PAUSED.equals(e.getMessage())) {
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
            throw new ProtocolValidationException(ERR_PLAY_ACTION_TYPE_REQUIRED, "actionType 不能为空。");
        }
        String normalized = req.getActionType().trim().toUpperCase(Locale.ROOT);
        Set<String> allowed = Set.of("DEPLOY", "DEPOSIT", "ACTION", "DISCARD", "RESPONSE_PASS");
        if (!allowed.contains(normalized)) {
            throw new ProtocolValidationException(ERR_PLAY_ACTION_TYPE_INVALID, "不支持的 actionType: " + req.getActionType());
        }
        if ("RESPONSE_PASS".equals(normalized)) {
            if (req.getActingPlayerId() == null || req.getActingPlayerId().isBlank()) {
                throw new ProtocolValidationException(ERR_PLAY_ACTING_PLAYER_REQUIRED, "RESPONSE_PASS 必须提供 actingPlayerId。");
            }
            return;
        }
        if (req.getCardId() == null || req.getCardId().isBlank()) {
            if (req.getHandIndex() == null) {
                throw new ProtocolValidationException(ERR_PLAY_CARD_SELECTOR_REQUIRED, "必须提供 cardId 或 handIndex。");
            }
            if (req.getHandIndex() < 0) {
                throw new ProtocolValidationException(ERR_PLAY_HAND_INDEX_INVALID, "handIndex 必须 >= 0。");
            }
        }
    }

    private void handleDiscardRequest(PlayActionRequest req) {
        Card card = resolveCardInHand(requireCurrentPlayer(), req.getCardId(), req.getHandIndex());
        discardFromHand(requireCurrentPlayer(), card);
    }

    /** 兼容仅传 handIndex 的调用方；网络层已统一走 {@link #handlePlayActionRequest(PlayActionRequest)}。 */
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
            if (!MSG_PAUSED.equals(e.getMessage())) {
                recordErrorAndSnapshot(e);
            }
            throw e;
        }
    }

    /**
     * QUIT 消息入口：payload 含 {@code playerId}；当已登记退出的不同玩家数达到
     * {@link #sessionPlayers} 人数时强制结束（无 WebSocket 连接映射时的简化规则）。
     */
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

    /**
     * 出行动卡并触发效果链（人类玩家网络命令入口）。
     *
     * @param handIndex          手牌索引（从 0 开始）
     * @param targetPlayerId     目标玩家 ID（可为 null，用于收租/偷牌等）
     * @param colorKey           收租/双倍租金时指定的颜色（可为 null）
     * @param targetPropIndex    目标玩家财产区中的目标房产索引（偷牌/强制交换，-1 表示不需要）
     * @param actorPropIndex     己方财产区出让房产索引（强制交换，-1 表示不需要）
     * @return 行动卡效果执行结果
     */
    public ActionEffectResult handleActionCardCommand(
            int handIndex,
            String targetPlayerId,
            String colorKey,
            int targetPropIndex,
            int actorPropIndex) {

        ensureSessionActive();
        Player actor = requireCurrentPlayer();
        if (handIndex < 0 || handIndex >= actor.getHandCardsView().size()) {
            throw new IllegalArgumentException("handIndex 越界。");
        }
        String cardId = actor.getHandCardsView().get(handIndex).getId();

        Player target = resolvePlayer(targetPlayerId);
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

    /**
     * 根据 {@link ActionParamContext} 解析手牌/目标房产并执行行动卡效果链。
     */
    public ActionEffectResult handleActionCardCommand(ActionParamContext params) {
        if (params == null) {
            throw new IllegalArgumentException("ActionParamContext 不能为 null。");
        }
        ensureSessionActive();
        Player actor = requireCurrentPlayer();
        Card card = resolveCardInHand(actor, params.getCardId(), params.getHandIndex());
        if (!(card instanceof ActionCard actionCard)) {
            throw new IllegalArgumentException("指定卡牌不是行动卡，无法触发效果。");
        }

        Player target = resolvePlayer(params.getTargetPlayerId());
        String effectCode = actionCard.getEffectCode();
        String ec = effectCode == null ? "" : effectCode.trim().toUpperCase();

        PropertyCard targetProp = null;
        Card targetBankCard = null;
        StealTargetZone stealZone = StealTargetZone.PROPERTY;

        if ("STEAL_PROPERTY".equals(ec)) {
            stealZone = StealTargetZone.fromParam(params.getTargetZone());
            if (stealZone == StealTargetZone.BANK) {
                targetBankCard = target != null
                        ? resolveBankCardById(target, params.getTargetCardId())
                        : null;
            } else {
                targetProp = target != null
                        ? resolvePropertyCardById(target, params.getTargetCardId())
                        : null;
            }
        } else if (!"HOUSE".equals(ec) && !"HOTEL".equals(ec) && !"PASS_GO".equals(ec)) {
            targetProp = target != null
                    ? resolvePropertyCardById(target, params.getTargetCardId())
                    : null;
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
                .builder(actor, engine, Collections.unmodifiableList(sessionPlayers))
                .target(target)
                .colorKey(blankToNull(params.getTargetColorKey()))
                .targetProperty(targetProp)
                .actorProperty(actorProp)
                .targetBankCard(targetBankCard)
                .stealTargetZone(stealZone)
                .build();

        return playActionCard(actor, actionCard, ctx, params);
    }

    /**
     * 出行动卡核心逻辑：校验回合→移入行动区→调度效果链→广播快照。
     *
     * @return 效果执行结果
     */
    public ActionEffectResult playActionCard(
            Player actor, ActionCard card, ActionEffectContext ctx, ActionParamContext params) {
        if (actor == null || card == null) {
            throw new IllegalArgumentException("actor 和 card 不能为 null。");
        }
        ensureSessionActive();
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

        gameContext.bindPlayers(sessionPlayers);
        if (!card.canPlay(actor, params, gameContext)) {
            throw new IllegalStateException("当前规则不允许打出该行动卡。");
        }

        String effectCode = card.getEffectCode() == null ? "" : card.getEffectCode().trim().toUpperCase();

        if ("RENT".equals(effectCode)) {
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
            enterRentResponseWindow(ctx.getTarget());
            ActionEffectResult result = ActionEffectResult.success(
                    "收租已入栈，等待对方在 " + RESPONSE_WINDOW_SECONDS + " 秒内打出免租或放弃。");
            System.out.println("[ACTION] " + result.getMessage());
            return result;
        }

        if ("DOUBLE_RENT".equals(effectCode)) {
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
            enterRentResponseWindow(ctx.getTarget());
            ActionEffectResult result = ActionEffectResult.success(
                    "双倍收租已入栈，等待对方在 " + RESPONSE_WINDOW_SECONDS + " 秒内打出免租或放弃。");
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
        pushSnapshot(currentSessionId, phase, actionSummary);

        System.out.println("[ACTION] " + result.getMessage());
        return result;
    }

    /**
     * 被收租方放弃或倒计时结束：立即结算效果栈。
     */
    public void handleResponsePass(String actingPlayerId) {
        try {
            ensureSessionActive();
            clearLastError();
            performResponsePass(actingPlayerId);
        } catch (RuntimeException e) {
            recordErrorAndSnapshot(e);
            throw e;
        }
    }

    private void performResponsePass(String actingPlayerId) {
        if (actingPlayerId == null || actingPlayerId.isBlank()) {
            throw new IllegalArgumentException("放弃响应时必须提供 actingPlayerId。");
        }
        if (!gameContext.isAwaitingResponseFrom(actingPlayerId)) {
            throw new IllegalStateException("当前未轮到该玩家响应或已超时。");
        }
        cancelPendingResponseTimeout();
        resolveEffectStackAndResume("RESPONSE_PASS");
    }

    private void handleWaiverPlay(PlayActionRequest req) {
        Player actor = resolvePlayer(req.getActingPlayerId());
        if (actor == null) {
            throw new IllegalArgumentException("等待响应阶段必须提供有效的 actingPlayerId。");
        }
        if (!gameContext.isAwaitingResponseFrom(actor.getPlayerId())) {
            throw new IllegalStateException("当前未轮到该玩家打出免租牌。");
        }
        Card card = resolveCardInHand(actor, req.getCardId(), req.getHandIndex());
        if (!(card instanceof ActionCard actionCard)) {
            throw new IllegalArgumentException("只能打出行动卡。");
        }
        if (!"RENT_WAIVER".equalsIgnoreCase(actionCard.getEffectCode())) {
            throw new IllegalStateException("当前只能打出免租牌（Just Say No）。");
        }
        gameContext.bindPlayers(sessionPlayers);
        ActionParamContext p = ActionParamContext.fromPlayRequest(req);
        if (!actionCard.canPlay(actor, p, gameContext)) {
            throw new IllegalStateException("当前不能打出免租牌。");
        }
        StackResponseState st = gameContext.getResponseState();
        if (st == null) {
            throw new IllegalStateException("响应状态丢失。");
        }
        String targetId;
        if (st.getRole() == StackResponseState.Role.TENANT) {
            targetId = gameContext.findBottomRentEntryId();
        } else {
            EffectStackEntry top = gameContext.peekTopEffect();
            targetId = top != null ? top.getId() : null;
        }
        if (targetId == null) {
            throw new IllegalStateException("找不到可抵消的效果条目。");
        }

        actor.placeActionToCenter(actionCard);
        gameContext.pushEffect(EffectStackEntry.waiver(actor.getPlayerId(), targetId));
        cancelPendingResponseTimeout();

        if (st.getRole() == StackResponseState.Role.TENANT) {
            Player landlord = resolvePlayer(currentTurnPlayerId);
            if (landlord == null) {
                throw new IllegalStateException("当前回合玩家丢失。");
            }
            long deadline = System.currentTimeMillis() + RESPONSE_WINDOW_SECONDS * 1000L;
            gameContext.setResponseState(new StackResponseState(
                    StackResponseState.Role.LANDLORD_COUNTER, landlord.getPlayerId(), deadline));
            scheduleResponseTimeout(deadline);
            pushSnapshot(currentSessionId, "JSN_AWAITING_COUNTER",
                    actor.getDisplayName() + " played Just Say No; landlord may counter.");
        } else {
            resolveEffectStackAndResume("JSN_COUNTER_RESOLVED");
        }
    }

    private void enterRentResponseWindow(Player tenant) {
        if (tenant == null) {
            throw new IllegalStateException("收租目标无效。");
        }
        long deadline = System.currentTimeMillis() + RESPONSE_WINDOW_SECONDS * 1000L;
        gameContext.setResponseState(
                new StackResponseState(StackResponseState.Role.TENANT, tenant.getPlayerId(), deadline));
        currentTurnPhase = TurnPhase.WAITING_FOR_RESPONSE;
        scheduleResponseTimeout(deadline);
        EffectStackEntry top = gameContext.peekTopEffect();
        int due = top != null ? top.getAmountDue() : 0;
        String rentSummary = "Rent " + due + "M — awaiting response from " + tenant.getDisplayName() + ".";
        pushSnapshot(currentSessionId, "RENT_AWAITING_RESPONSE", rentSummary);
    }

    private void scheduleResponseTimeout(long deadlineEpochMs) {
        cancelPendingResponseTimeout();
        pendingResponseFuture = responseScheduler.schedule(() -> {
            StackResponseState st = gameContext.getResponseState();
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

    private void cancelPendingResponseTimeout() {
        if (pendingResponseFuture != null) {
            pendingResponseFuture.cancel(false);
            pendingResponseFuture = null;
        }
    }

    private void resolveEffectStackAndResume(String phaseHint) {
        if (sessionForceEnded) {
            return;
        }
        List<EffectStackEntry> copy = new ArrayList<>(gameContext.getEffectStackView());
        gameContext.clearEffectStack();
        currentTurnPhase = TurnPhase.PLAY;

        PaymentSettlement.Result pay = EffectStackResolver.resolveRentPayments(copy, sessionPlayers, engine);
        if (currentTurnActionCount >= MAX_ACTIONS_PER_TURN) {
            currentTurnPhase = TurnPhase.END_TURN;
        }

        pushSnapshot(currentSessionId, phaseHint,
                "Effect stack resolved: " + pay.getMessage());
        System.out.println("[EFFECT_STACK] " + phaseHint + " " + pay.getMessage());
    }

    private static String buildPendingResponseHint(StackResponseState st) {
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

    private Card resolveCardInHand(Player actor, String cardId, Integer handIndex) {
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

    /**
     * @param propertyCardId 非空且在该玩家财产区找不到时抛错
     */
    private PropertyCard resolvePropertyCardById(Player owner, String propertyCardId) {
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

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    private Player resolvePlayer(String playerId) {
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

    private void ensureTurnContext(Player player) {
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

    private Player requireCurrentPlayer() {
        Player current = turnManager.getCurrentPlayer();
        if (current == null) {
            throw new IllegalStateException("当前没有可行动玩家，请先 startNewSession。");
        }
        return current;
    }

    private void executeAiTurn(AIPlayer ai) {
        ensureNotPaused();
        ensureSessionActive();
        gameContext.bindPlayers(sessionPlayers);
        drawCards(ai, 2);

        int played = 0;
        AiPlayStrategy strategy = ai.getPlayStrategy();
        while (played < MAX_ACTIONS_PER_TURN && !ai.getHandCardsView().isEmpty()) {
            boolean progressed = strategy != null && strategy.tryPlayOneCard(ai, gameContext, this);
            if (!progressed) {
                break;
            }
            played++;
        }
        endTurn(ai);
    }

    /**
     * 胜利：任意颜色合计至少 3 套完整地产集（与 Monopoly Deal 常见规则一致）。
     * 与需求原文「同色」表述差异见 {@code docs/REQ_TRACE.md}。
     */
    private boolean checkWinCondition(Player player) {
        return player.countCompletePropertySets() >= 3;
    }

    private void assertDeckIntegrityOrLog() {
        if (!VERIFY_DECK) {
            return;
        }
        int total = engine.countAllCardsInPlay(sessionPlayers);
        int expected = GameConstants.STANDARD_DECK_SIZE;
        if (total == expected) {
            return;
        }
        String msg = "Deck integrity violation: expected " + expected + " cards in play, found " + total;
        LOG.log(Level.SEVERE, msg);
        System.err.println("[DECK_VERIFY] " + msg);
        throw new IllegalStateException(msg);
    }

    private void recordError(String code, String message) {
        this.lastErrorCode = code;
        this.lastErrorMessage = message;
        this.lastErrorTimestampEpochMs = System.currentTimeMillis();
    }

    private void recordErrorAndSnapshot(RuntimeException e) {
        recordError(e.getClass().getSimpleName(), runtimeExceptionDetail(e));
        pushSnapshot(currentSessionId, sessionForceEnded ? "GAME_FORCE_END" : "RULE_VIOLATION");
    }

    private void clearLastError() {
        this.lastErrorCode = null;
        this.lastErrorMessage = null;
        this.lastErrorTimestampEpochMs = 0L;
    }

    private static String runtimeExceptionDetail(RuntimeException e) {
        String m = e.getMessage();
        if (m != null && !m.isBlank()) {
            return m;
        }
        return e.getClass().getSimpleName();
    }

    private static long sessionLimitMs() {
        Long v = Long.getLong(GameConstants.SESSION_LIMIT_MS_PROPERTY);
        return v != null ? v : GameConstants.DEFAULT_SESSION_LIMIT_MS;
    }

    public static final class ProtocolValidationException extends IllegalArgumentException {
        private final String code;

        public ProtocolValidationException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String getCode() {
            return code;
        }
    }

    public boolean isPaused() {
        return paused;
    }

    /** PVP：是否已有玩家发起暂停请求且尚在等待全员确认。 */
    public boolean isPausePending() {
        return pausePending;
    }

    /** 测试/快照：当前已收到暂停确认的玩家 id（含 PVP 投票过程）。 */
    public Set<String> getPauseAcksView() {
        return Collections.unmodifiableSet(new HashSet<>(pauseAcks));
    }

    public boolean isPvpMode() {
        return "PVP".equals(sessionGameMode);
    }

    /**
     * 人机模式：立即暂停。人人模式请使用 {@link #requestPause()} / {@link #acknowledgePause(String)}，
     * 若误调本方法将抛错。
     */
    public void pause() {
        if ("PVP".equals(sessionGameMode)) {
            throw new IllegalStateException("人人模式请使用 PAUSE_REQUEST / PAUSE_ACK。");
        }
        pauseImmediate();
    }

    private void pauseImmediate() {
        if (paused) {
            return;
        }
        paused = true;
        pausePending = false;
        pauseAcks.clear();
        recordError("PAUSED", MSG_PAUSED);
        pushSnapshot(currentSessionId, "RULE_VIOLATION");
    }

    /**
     * 人人模式：仅当前回合玩家可发起暂停请求；未全员确认前不冻结。
     * 人机模式等价于 {@link #pauseImmediate()}。
     */
    public void requestPause() {
        if (paused) {
            return;
        }
        if (!"PVP".equals(sessionGameMode)) {
            pauseImmediate();
            return;
        }
        Player cur = requireCurrentPlayer();
        if (cur == null || currentTurnPlayerId == null || !currentTurnPlayerId.equals(cur.getPlayerId())) {
            throw new IllegalStateException("仅当前回合玩家可发起 PAUSE_REQUEST。");
        }
        if (pausePending) {
            return;
        }
        pausePending = true;
        pauseAcks.clear();
        pushSnapshot(currentSessionId, "PAUSE_PENDING");
    }

    /**
     * 人人模式：玩家确认暂停（payload 中的 playerId 须为本人）；当 {@link #sessionPlayers} 全员 id 均已收录后
     * {@link #paused}{@code =true}，行为同 T3-3 即时暂停。
     */
    public void acknowledgePause(String playerId) {
        if (playerId == null || playerId.isBlank()) {
            throw new IllegalArgumentException("playerId 不能为空。");
        }
        if (!"PVP".equals(sessionGameMode)) {
            return;
        }
        if (paused || !pausePending) {
            return;
        }
        String pid = playerId.trim();
        if (gameContext.findPlayer(pid) == null) {
            throw new IllegalArgumentException("未知玩家: " + pid);
        }
        pauseAcks.add(pid);
        if (pauseAcks.size() >= sessionPlayers.size()) {
            paused = true;
            pausePending = false;
            pauseAcks.clear();
            recordError("PAUSED", MSG_PAUSED);
            pushSnapshot(currentSessionId, "RULE_VIOLATION");
        } else {
            pushSnapshot(currentSessionId, "PAUSE_PENDING");
        }
    }

    /** 解除暂停并清除暂停类错误提示。 */
    public void resume() {
        if (!paused && !pausePending) {
            return;
        }
        paused = false;
        pausePending = false;
        pauseAcks.clear();
        clearLastError();
        pushSnapshot(currentSessionId, "INIT");
    }

    /** 暂停中则 {@link #recordError}、广播并抛错，供指令入口与 {@link #endTurn}、{@link #executeAiTurn} 共用。 */
    private void ensureNotPaused() {
        if (!paused) {
            return;
        }
        recordError("PAUSED", MSG_PAUSED);
        pushSnapshot(currentSessionId, "RULE_VIOLATION");
        throw new IllegalStateException(MSG_PAUSED);
    }

    /**
     * 单局超时或已强制结束时禁止继续操作；首次检测到超时则广播 {@code GAME_FORCE_END} 并抛错。
     */
    private void ensureSessionActive() {
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

    private void pushSnapshot(String sessionId, String phase) {
        pushSnapshot(sessionId, phase, null);
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
            case "GAME_FORCE_END" -> forceEndReason != null ? "Game ended: " + forceEndReason : "Game force-ended.";
            case "ACTION_SUCCESS", "ACTION_FAILED", "ACTION_COUNTERED" -> "Action card effect resolved.";
            case "RENT_AWAITING_RESPONSE" -> "Awaiting rent response.";
            case "JSN_AWAITING_COUNTER" -> "Awaiting landlord counter to Just Say No.";
            case "RESPONSE_PASS", "RESPONSE_TIMEOUT" -> "Rent response chain updated.";
            default -> phase.replace('_', ' ');
        };
    }

    private void pushSnapshot(String sessionId, String phase, String actionSummary) {
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

        GameStateSnapshot snap = new GameStateSnapshot();
        snap.setSessionId(sessionId);
        snap.setPhase(phase);
        snap.setLastActionSummary(summary);
        snap.setCurrentPlayerId(currentTurnPlayerId);
        snap.setTurnPhase(currentTurnPhase == null ? "UNKNOWN" : currentTurnPhase.name());
        snap.setDrawPileCount(engine.remainingCount());
        snap.setDiscardPileCount(engine.discardCount());
        if (currentTurnPhase == TurnPhase.WAITING_FOR_RESPONSE) {
            StackResponseState st = gameContext.getResponseState();
            if (st != null) {
                snap.setPendingResponsePlayerId(st.getAwaitingPlayerId());
                snap.setPendingResponseRole(st.getRole().name());
                snap.setResponseDeadlineEpochMs(st.getDeadlineEpochMs());
                snap.setPendingResponseHint(buildPendingResponseHint(st));
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
}
