package com.monopoly.model.persistence;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.monopoly.controller.GameController;
import com.monopoly.controller.TurnManager;
import com.monopoly.model.ActionCard;
import com.monopoly.model.AIPlayer;
import com.monopoly.model.Card;
import com.monopoly.model.EffectStackEntry;
import com.monopoly.model.GameContext;
import com.monopoly.model.HumanPlayer;
import com.monopoly.model.Player;
import com.monopoly.model.PropertyCard;
import com.monopoly.model.StackResponseState;
import com.monopoly.pattern.observer.GameUpdateSubject;
import com.monopoly.pattern.strategy.AiPlayStrategy;
import com.monopoly.pattern.strategy.EasyAiPlayStrategy;
import com.monopoly.pattern.strategy.HardAiPlayStrategy;
import com.monopoly.pattern.strategy.NormalAiPlayStrategy;
import com.monopoly.pattern.singleton.GameEngineSingleton;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * 完整对局快照（Memento）：仅 Java 对象与 JSON 字符串互转，不涉及文件 IO。
 * <p>
 * 捕获/恢复通过反射读取 {@link GameController} 未公开字段，以便在不扩展 Facade 的前提下存档。
 * 未纳入或仅部分恢复的内容（可后续补全）：{@code pendingResponseFuture} 调度、
 * {@code lastError*} 诊断字段、观察者订阅需调用方自行重新挂接。
 */
public final class GameSessionMemento {

    private String sessionId;
    private String gameMode;
    private String aiDifficulty;

    private List<SessionPlayerMemento> sessionPlayers = new ArrayList<>();

    private List<PersistedCard> drawPile = new ArrayList<>();
    private List<PersistedCard> discardPile = new ArrayList<>();

    private int turnManagerCurrentIndex;
    private String currentTurnPlayerId;
    private String currentTurnPhase;
    private int currentTurnActionCount;

    private long sessionStartEpochMs;
    private boolean sessionForceEnded;
    private String forceEndReason;

    private List<EffectStackEntryMemento> effectStack = new ArrayList<>();
    private StackResponseStateMemento responseState;

    private static final Gson GSON = new GsonBuilder()
            .serializeNulls()
            .setPrettyPrinting()
            .create();

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getGameMode() {
        return gameMode;
    }

    public void setGameMode(String gameMode) {
        this.gameMode = gameMode;
    }

    public String getAiDifficulty() {
        return aiDifficulty;
    }

    public void setAiDifficulty(String aiDifficulty) {
        this.aiDifficulty = aiDifficulty;
    }

    public List<SessionPlayerMemento> getSessionPlayers() {
        return sessionPlayers;
    }

    public void setSessionPlayers(List<SessionPlayerMemento> sessionPlayers) {
        this.sessionPlayers = sessionPlayers != null ? sessionPlayers : new ArrayList<>();
    }

    public List<PersistedCard> getDrawPile() {
        return drawPile;
    }

    public void setDrawPile(List<PersistedCard> drawPile) {
        this.drawPile = drawPile != null ? drawPile : new ArrayList<>();
    }

    public List<PersistedCard> getDiscardPile() {
        return discardPile;
    }

    public void setDiscardPile(List<PersistedCard> discardPile) {
        this.discardPile = discardPile != null ? discardPile : new ArrayList<>();
    }

    public int getTurnManagerCurrentIndex() {
        return turnManagerCurrentIndex;
    }

    public void setTurnManagerCurrentIndex(int turnManagerCurrentIndex) {
        this.turnManagerCurrentIndex = turnManagerCurrentIndex;
    }

    public String getCurrentTurnPlayerId() {
        return currentTurnPlayerId;
    }

    public void setCurrentTurnPlayerId(String currentTurnPlayerId) {
        this.currentTurnPlayerId = currentTurnPlayerId;
    }

    public String getCurrentTurnPhase() {
        return currentTurnPhase;
    }

    public void setCurrentTurnPhase(String currentTurnPhase) {
        this.currentTurnPhase = currentTurnPhase;
    }

    public int getCurrentTurnActionCount() {
        return currentTurnActionCount;
    }

    public void setCurrentTurnActionCount(int currentTurnActionCount) {
        this.currentTurnActionCount = currentTurnActionCount;
    }

    public long getSessionStartEpochMs() {
        return sessionStartEpochMs;
    }

    public void setSessionStartEpochMs(long sessionStartEpochMs) {
        this.sessionStartEpochMs = sessionStartEpochMs;
    }

    public boolean isSessionForceEnded() {
        return sessionForceEnded;
    }

    public void setSessionForceEnded(boolean sessionForceEnded) {
        this.sessionForceEnded = sessionForceEnded;
    }

    public String getForceEndReason() {
        return forceEndReason;
    }

    public void setForceEndReason(String forceEndReason) {
        this.forceEndReason = forceEndReason;
    }

    public List<EffectStackEntryMemento> getEffectStack() {
        return effectStack;
    }

    public void setEffectStack(List<EffectStackEntryMemento> effectStack) {
        this.effectStack = effectStack != null ? effectStack : new ArrayList<>();
    }

    public StackResponseStateMemento getResponseState() {
        return responseState;
    }

    public void setResponseState(StackResponseStateMemento responseState) {
        this.responseState = responseState;
    }

    public String toJson() {
        return GSON.toJson(this);
    }

    public static GameSessionMemento fromJson(String json) {
        return GSON.fromJson(json, GameSessionMemento.class);
    }

    /**
     * 从运行中的控制器捕获快照（使用反射读取会话与回合私有字段）。
     */
    public static GameSessionMemento capture(GameController controller) {
        if (controller == null) {
            throw new IllegalArgumentException("controller 不能为 null");
        }
        try {
            GameSessionMemento m = new GameSessionMemento();
            m.sessionId = (String) readField(controller, "currentSessionId");
            m.currentTurnPlayerId = (String) readField(controller, "currentTurnPlayerId");
            m.currentTurnActionCount = (Integer) readField(controller, "currentTurnActionCount");
            Object phase = readField(controller, "currentTurnPhase");
            m.currentTurnPhase = phase != null ? ((Enum<?>) phase).name() : null;
            m.sessionStartEpochMs = (Long) readField(controller, "sessionStartEpochMs");
            m.sessionForceEnded = (Boolean) readField(controller, "sessionForceEnded");
            m.forceEndReason = (String) readField(controller, "forceEndReason");

            List<Player> players = controller.getSessionPlayersView();
            m.sessionPlayers = new ArrayList<>();
            boolean anyAi = false;
            String firstAiDiff = "EASY";
            for (Player p : players) {
                SessionPlayerMemento sp = new SessionPlayerMemento();
                sp.setPlayerId(p.getPlayerId());
                sp.setDisplayName(p.getDisplayName());
                if (p instanceof AIPlayer ai) {
                    anyAi = true;
                    sp.setPlayerKind(SessionPlayerMemento.PlayerKind.AI);
                    firstAiDiff = inferAiDifficulty(ai);
                    sp.setAiDifficulty(firstAiDiff);
                } else {
                    sp.setPlayerKind(SessionPlayerMemento.PlayerKind.HUMAN);
                }
                sp.setHandCards(mapCards(p.getHandCardsView()));
                sp.setBankCards(mapCards(p.getBankCardsView()));
                sp.setPropertyCards(mapPropertyCards(p.getPropertyCardsView()));
                sp.setActionZoneCards(mapCards(p.getActionZoneCardsView()));
                m.sessionPlayers.add(sp);
            }
            m.gameMode = anyAi ? "HVM" : "PVP";
            m.aiDifficulty = anyAi ? firstAiDiff : null;

            GameEngineSingleton engine = GameEngineSingleton.getInstance();
            m.drawPile = mapCards(engine.getDrawPileView());
            m.discardPile = mapCards(engine.getDiscardPileView());

            TurnManager tm = controller.getTurnManager();
            m.turnManagerCurrentIndex = (Integer) readField(tm, "currentIndex");

            GameContext ctx = (GameContext) readField(controller, "gameContext");
            m.effectStack = new ArrayList<>();
            for (EffectStackEntry e : ctx.getEffectStackView()) {
                m.effectStack.add(EffectStackEntryMemento.fromEntry(e));
            }
            StackResponseState st = ctx.getResponseState();
            m.responseState = StackResponseStateMemento.fromState(st);

            return m;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("捕获会话快照失败", e);
        }
    }

    /**
     * 重置 {@link GameEngineSingleton}（封装对包内 {@code resetForTests} 的反射，供存档恢复与测试使用）。
     */
    public static void resetSingletonEngineForTests() {
        try {
            Method m = GameEngineSingleton.class.getDeclaredMethod("resetForTests");
            m.setAccessible(true);
            m.invoke(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("无法重置 GameEngineSingleton", e);
        }
    }

    /**
     * 将快照应用到已有 {@link GameController}：重置引擎单例、抽/弃牌堆、玩家列表、回合与 {@link GameContext}。
     * 不广播状态；调用方负责 {@link GameController#importSessionJson(String)} 中的收尾与快照。
     */
    public static void applyToController(GameController controller, GameSessionMemento memento) {
        if (controller == null || memento == null) {
            throw new IllegalArgumentException("controller 与 memento 不能为 null");
        }
        try {
            resetSingletonEngineForTests();
            GameEngineSingleton engine = GameEngineSingleton.getInstance();

            List<Player> players = new ArrayList<>();
            for (SessionPlayerMemento sm : memento.getSessionPlayers()) {
                Player p = buildPlayer(sm);
                players.add(p);
            }

            List<Card> draw = new ArrayList<>();
            for (PersistedCard pc : memento.getDrawPile()) {
                draw.add(PersistedCard.toCard(pc));
            }
            engine.attachDrawPile(draw);
            for (PersistedCard pc : memento.getDiscardPile()) {
                engine.discard(PersistedCard.toCard(pc));
            }

            for (int i = 0; i < players.size(); i++) {
                SessionPlayerMemento sm = memento.getSessionPlayers().get(i);
                fillPlayerZones(players.get(i), sm);
            }

            writeField(controller, "currentSessionId", memento.getSessionId());
            @SuppressWarnings("unchecked")
            List<Player> sessionList = (List<Player>) readField(controller, "sessionPlayers");
            sessionList.clear();
            sessionList.addAll(players);

            TurnManager tm = controller.getTurnManager();
            tm.bindTurnOrder(sessionList);
            writeField(tm, "currentIndex", memento.getTurnManagerCurrentIndex());

            GameContext ctx = (GameContext) readField(controller, "gameContext");
            ctx.bindPlayers(sessionList);
            ctx.clearEffectStack();
            for (EffectStackEntryMemento em : memento.getEffectStack()) {
                ctx.pushEffect(restoreEffectEntry(em));
            }
            ctx.setResponseState(restoreResponseState(memento.getResponseState()));

            Class<?> phaseClass = Class.forName("com.monopoly.controller.GameController$TurnPhase");
            @SuppressWarnings({"unchecked", "rawtypes"})
            Object phaseEnum = Enum.valueOf((Class<? extends Enum>) phaseClass, memento.getCurrentTurnPhase());
            writeField(controller, "currentTurnPhase", phaseEnum);
            writeField(controller, "currentTurnPlayerId", memento.getCurrentTurnPlayerId());
            writeField(controller, "currentTurnActionCount", memento.getCurrentTurnActionCount());
            writeField(controller, "sessionStartEpochMs", memento.getSessionStartEpochMs());
            writeField(controller, "sessionForceEnded", memento.isSessionForceEnded());
            writeField(controller, "forceEndReason", memento.getForceEndReason());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("恢复快照失败", e);
        }
    }

    public static GameController restore(GameUpdateSubject subject, GameSessionMemento memento) {
        if (subject == null || memento == null) {
            throw new IllegalArgumentException("subject 与 memento 不能为 null");
        }
        GameController controller = new GameController(subject);
        applyToController(controller, memento);
        return controller;
    }

    public static GameController restoreFromJson(GameUpdateSubject subject, String json) {
        return restore(subject, fromJson(json));
    }

    private static List<PersistedCard> mapCards(List<? extends Card> cards) {
        List<PersistedCard> out = new ArrayList<>();
        if (cards == null) {
            return out;
        }
        for (Card c : cards) {
            out.add(PersistedCard.fromCard(c));
        }
        return out;
    }

    private static List<PersistedCard> mapPropertyCards(List<PropertyCard> cards) {
        List<PersistedCard> out = new ArrayList<>();
        if (cards == null) {
            return out;
        }
        for (PropertyCard c : cards) {
            out.add(PersistedCard.fromCard(c));
        }
        return out;
    }

    private static String inferAiDifficulty(AIPlayer ai) {
        AiPlayStrategy s = ai.getPlayStrategy();
        if (s == null) {
            return "EASY";
        }
        String n = s.getClass().getSimpleName();
        if (n.contains("Hard")) {
            return "HARD";
        }
        if (n.contains("Normal")) {
            return "NORMAL";
        }
        return "EASY";
    }

    private static Player buildPlayer(SessionPlayerMemento sm) {
        if (sm.getPlayerKind() == SessionPlayerMemento.PlayerKind.AI) {
            AiPlayStrategy st = resolveAiStrategy(sm.getAiDifficulty());
            return new AIPlayer(sm.getPlayerId(), sm.getDisplayName(), st);
        }
        return new HumanPlayer(sm.getPlayerId(), sm.getDisplayName());
    }

    private static AiPlayStrategy resolveAiStrategy(String diff) {
        String d = diff == null ? "EASY" : diff.trim().toUpperCase();
        return switch (d) {
            case "NORMAL" -> new NormalAiPlayStrategy();
            case "HARD" -> new HardAiPlayStrategy();
            default -> new EasyAiPlayStrategy();
        };
    }

    private static void fillPlayerZones(Player p, SessionPlayerMemento sm) {
        for (PersistedCard pc : sm.getHandCards()) {
            p.receiveCardToHand(PersistedCard.toCard(pc));
        }
        for (PersistedCard pc : sm.getBankCards()) {
            p.addToBank(PersistedCard.toCard(pc));
        }
        for (PersistedCard pc : sm.getPropertyCards()) {
            Card c = PersistedCard.toCard(pc);
            if (!(c instanceof PropertyCard prop)) {
                throw new IllegalStateException("财产区需要 PropertyCard: " + pc.getId());
            }
            p.addToPropertyZone(prop);
        }
        for (PersistedCard pc : sm.getActionZoneCards()) {
            Card c = PersistedCard.toCard(pc);
            if (!(c instanceof ActionCard ac)) {
                throw new IllegalStateException("行动区需要 ActionCard: " + pc.getId());
            }
            p.receiveCardToHand(ac);
            p.placeActionToCenter(ac);
        }
    }

    private static EffectStackEntry restoreEffectEntry(EffectStackEntryMemento m)
            throws ReflectiveOperationException {
        if (m == null || m.getId() == null || m.getKind() == null) {
            throw new IllegalArgumentException("effectStack 条目不完整");
        }
        EffectStackEntry.Kind kind = EffectStackEntry.Kind.valueOf(m.getKind().trim());
        Constructor<EffectStackEntry> ctor = EffectStackEntry.class.getDeclaredConstructor(
                String.class,
                EffectStackEntry.Kind.class,
                String.class,
                String.class,
                String.class,
                int.class,
                String.class);
        ctor.setAccessible(true);
        return ctor.newInstance(
                m.getId(),
                kind,
                m.getActorPlayerId(),
                m.getTenantPlayerId(),
                m.getColorKey(),
                m.getAmountDue(),
                m.getWaiverTargetEntryId());
    }

    private static StackResponseState restoreResponseState(StackResponseStateMemento m) {
        if (m == null || m.getRole() == null) {
            return null;
        }
        return new StackResponseState(
                StackResponseState.Role.valueOf(m.getRole().trim()),
                m.getAwaitingPlayerId(),
                m.getDeadlineEpochMs());
    }

    private static Object readField(Object target, String name) throws ReflectiveOperationException {
        Field f = findField(target.getClass(), name);
        f.setAccessible(true);
        return f.get(target);
    }

    private static void writeField(Object target, String name, Object value) throws ReflectiveOperationException {
        Field f = findField(target.getClass(), name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static Field findField(Class<?> start, String name) {
        Class<?> c = start;
        while (c != null) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        throw new IllegalArgumentException("字段不存在: " + name + " on " + start.getName());
    }
}
