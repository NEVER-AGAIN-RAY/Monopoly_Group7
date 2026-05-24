package com.monopoly.model.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.monopoly.model.card.ActionCard;
import com.monopoly.model.card.Card;
import com.monopoly.model.card.PropertyCard;
import com.monopoly.model.player.Player;
import com.monopoly.model.settlement.PropertySetCalculator;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Compact public-state memory for AI prompts.
 *
 * <p>This intentionally stores summaries and public board deltas, not hidden
 * hand contents or raw full-turn logs.</p>
 */
public final class AiHistoryTracker {

    private static final int MAX_RECENT_EVENTS = 12;
    private static final int PROMPT_RECENT_EVENTS = 10;
    private static final int PROMPT_CONTESTED_COLORS = 6;

    private final Deque<HistoryEvent> recentEvents = new ArrayDeque<>();
    private final Map<String, PlayerMemory> playerMemory = new LinkedHashMap<>();
    private final Map<String, ColorMemory> colorMemory = new LinkedHashMap<>();
    private Map<String, PublicPlayerState> previousState = new LinkedHashMap<>();

    public void reset() {
        recentEvents.clear();
        playerMemory.clear();
        colorMemory.clear();
        previousState = new LinkedHashMap<>();
    }

    public void recordSnapshot(
            long sequence,
            int roundNumber,
            String phase,
            String currentPlayerId,
            String actionSummary,
            Player playedBy,
            Card playedCard,
            String playedActionType,
            List<Player> players) {
        Map<String, PublicPlayerState> current = capture(players);
        updateCurrentPlayerMemories(current);
        updateColorOwners(current);

        if (previousState.isEmpty()) {
            previousState = current;
            return;
        }

        Delta delta = diff(previousState, current);
        String effectCode = effectCode(playedCard);
        String actionType = normalize(playedActionType);
        String actorId = playedBy != null ? playedBy.getPlayerId() : currentPlayerId;
        String eventType = classify(phase, actionType, effectCode, delta);
        boolean meaningful = shouldRecord(eventType, playedCard, phase, delta);

        if (meaningful) {
            Set<String> targetIds = inferTargets(actorId, delta);
            Set<String> colors = new LinkedHashSet<>(delta.changedColors);
            int impact = impactScore(eventType, effectCode, delta);
            HistoryEvent event = new HistoryEvent(
                    sequence,
                    Math.max(1, roundNumber),
                    safe(phase),
                    eventType,
                    actorId,
                    playedBy == null ? null : playedBy.getDisplayName(),
                    actionType,
                    cardName(playedCard),
                    effectCode,
                    List.copyOf(targetIds),
                    List.copyOf(colors),
                    compact(actionSummary, 140),
                    impact);
            addEvent(event);
            updateEventMemories(event, delta);
        }

        previousState = current;
    }

    public JsonObject toPromptJson(Player perspective, List<Player> players) {
        Map<String, PublicPlayerState> current = capture(players);

        JsonObject root = new JsonObject();
        root.addProperty("schema", "ai-history-v1");
        root.addProperty("purpose",
                "Compressed public history for long-term planning. Use it to avoid short-sighted cash moves.");
        root.add("recentEvents", recentEventsJson());
        root.add("contestedColors", contestedColorsJson(current));
        root.add("playerPressure", playerPressureJson(perspective, current));
        root.add("strategicWarnings", strategicWarningsJson(perspective, current));
        return root;
    }

    public JsonObject toCompactPromptJson(Player perspective, List<Player> players) {
        Map<String, PublicPlayerState> current = capture(players);
        JsonObject root = new JsonObject();
        root.addProperty("schema", "ai-history-v1-compact");
        root.addProperty("purpose",
                "Compact public memory for response/payment decisions; use only for threat context.");
        root.add("recentEvents", compactRecentEventsJson());
        root.add("strategicWarnings", strategicWarningsJson(perspective, current));
        return root;
    }

    public int recentBoardTempoScore(String playerId) {
        PlayerMemory memory = playerMemory.get(playerId);
        return memory == null ? 0 : memory.recentBoardTempo;
    }

    public int attacksTakenFrom(String targetPlayerId, String actorPlayerId) {
        PlayerMemory target = playerMemory.get(targetPlayerId);
        if (target == null || actorPlayerId == null) {
            return 0;
        }
        return target.attacksTakenByActor.getOrDefault(actorPlayerId, 0);
    }

    private JsonArray recentEventsJson() {
        JsonArray arr = new JsonArray();
        int skip = Math.max(0, recentEvents.size() - PROMPT_RECENT_EVENTS);
        int index = 0;
        for (HistoryEvent event : recentEvents) {
            if (index++ < skip) {
                continue;
            }
            JsonObject row = new JsonObject();
            row.addProperty("seq", event.sequence);
            row.addProperty("round", event.roundNumber);
            row.addProperty("phase", event.phase);
            row.addProperty("type", event.eventType);
            row.addProperty("actorPlayerId", event.actorPlayerId);
            row.addProperty("actorName", event.actorName);
            row.addProperty("actionType", event.actionType);
            row.addProperty("cardName", event.cardName);
            row.addProperty("effectCode", event.effectCode);
            row.add("targetPlayerIds", strings(event.targetPlayerIds));
            row.add("colors", strings(event.colors));
            row.addProperty("impact", event.impact);
            row.addProperty("summary", event.summary);
            arr.add(row);
        }
        return arr;
    }

    private JsonArray compactRecentEventsJson() {
        JsonArray arr = new JsonArray();
        int kept = 0;
        List<HistoryEvent> reversed = new ArrayList<>(recentEvents);
        for (int i = reversed.size() - 1; i >= 0 && kept < 4; i--) {
            HistoryEvent event = reversed.get(i);
            if (event.impact < 5 && !"JUST_SAY_NO".equals(event.eventType)) {
                continue;
            }
            JsonObject row = new JsonObject();
            row.addProperty("type", event.eventType);
            row.addProperty("actorPlayerId", event.actorPlayerId);
            row.addProperty("effectCode", event.effectCode);
            row.add("targetPlayerIds", strings(event.targetPlayerIds));
            row.add("colors", strings(event.colors));
            row.addProperty("impact", event.impact);
            arr.add(row);
            kept++;
        }
        return arr;
    }

    private JsonArray contestedColorsJson(Map<String, PublicPlayerState> current) {
        List<ColorMemory> colors = new ArrayList<>(colorMemory.values());
        colors.sort(Comparator
                .comparingInt(ColorMemory::activity).reversed()
                .thenComparingLong(ColorMemory::lastSequence).reversed()
                .thenComparing(ColorMemory::color));
        JsonArray arr = new JsonArray();
        int count = 0;
        for (ColorMemory memory : colors) {
            if (memory.activity <= 0 && memory.owners.isEmpty()) {
                continue;
            }
            JsonObject row = new JsonObject();
            row.addProperty("color", memory.color);
            row.addProperty("activity", memory.activity);
            row.addProperty("lastActorPlayerId", memory.lastActorPlayerId);
            row.addProperty("lastSequence", memory.lastSequence);
            row.add("owners", colorOwnersJson(memory.color, current));
            arr.add(row);
            if (++count >= PROMPT_CONTESTED_COLORS) {
                break;
            }
        }
        return arr;
    }

    private JsonArray playerPressureJson(Player perspective, Map<String, PublicPlayerState> current) {
        JsonArray arr = new JsonArray();
        String selfId = perspective == null ? null : perspective.getPlayerId();
        String leaderId = leaderId(current);
        for (PublicPlayerState state : current.values()) {
            PlayerMemory memory = playerMemory.computeIfAbsent(state.playerId, PlayerMemory::new);
            JsonObject row = new JsonObject();
            row.addProperty("playerId", state.playerId);
            row.addProperty("name", state.name);
            row.addProperty("isSelf", state.playerId.equals(selfId));
            row.addProperty("isLeader", state.playerId.equals(leaderId));
            row.addProperty("completeSets", state.completeSets);
            row.addProperty("setsNeededToWin", Math.max(0, 3 - state.completeSets));
            row.addProperty("propertyCount", state.propertyCount);
            row.addProperty("bankM", state.bankM);
            row.addProperty("handCount", state.handCount);
            row.addProperty("cashHeavyWithoutSets", state.bankM >= 12 && state.completeSets < 2);
            row.addProperty("recentBoardTempo", memory.recentBoardTempo);
            row.addProperty("highImpactActionsSeen", memory.highImpactActionsSeen);
            row.addProperty("attacksMade", memory.attacksMade);
            row.addProperty("attacksTaken", memory.attacksTaken);
            row.addProperty("justSayNoSeen", memory.justSayNoSeen);
            row.add("nearCompleteColors", nearCompleteColorsJson(state));
            arr.add(row);
        }
        return arr;
    }

    private JsonArray strategicWarningsJson(Player perspective, Map<String, PublicPlayerState> current) {
        JsonArray arr = new JsonArray();
        if (perspective == null || current.isEmpty()) {
            arr.add("Track property tempo first; bank cash is only a shield.");
            return arr;
        }
        PublicPlayerState self = current.get(perspective.getPlayerId());
        if (self == null) {
            return arr;
        }
        int maxOpponentSets = 0;
        PublicPlayerState leader = null;
        for (PublicPlayerState state : current.values()) {
            if (!state.playerId.equals(self.playerId)) {
                maxOpponentSets = Math.max(maxOpponentSets, state.completeSets);
            }
            if (leader == null
                    || state.completeSets > leader.completeSets
                    || (state.completeSets == leader.completeSets && state.propertyCount > leader.propertyCount)
                    || (state.completeSets == leader.completeSets
                    && state.propertyCount == leader.propertyCount
                    && state.bankM > leader.bankM)) {
                leader = state;
            }
        }
        if (self.completeSets >= 2) {
            arr.add("You have 2 complete sets: every play should complete/protect the third set or block an immediate threat.");
        }
        if (self.bankM >= 12 && self.completeSets <= maxOpponentSets) {
            arr.add("Your bank is saturated relative to your set race; prefer deploy/steal/swap/Deal Breaker over more cash.");
        }
        for (PublicPlayerState state : current.values()) {
            if (!state.playerId.equals(self.playerId) && state.completeSets >= 2) {
                arr.add("Block " + state.name + " now: they need only "
                        + Math.max(0, 3 - state.completeSets) + " set(s) to win.");
            }
        }
        if (leader != null && !leader.playerId.equals(self.playerId)
                && leader.completeSets >= self.completeSets) {
            arr.add("Current leader is " + leader.name
                    + "; attack their set progress before attacking trailing players.");
        }
        for (String color : self.colors.keySet()) {
            int count = self.colors.getOrDefault(color, 0);
            int need = PropertySetCalculator.REQUIRED_BY_COLOR.getOrDefault(color, 3);
            if (count == need - 1) {
                arr.add("You are one card away on " + color
                        + "; prioritize completing or protecting this color.");
                break;
            }
        }
        PlayerMemory selfMemory = playerMemory.computeIfAbsent(self.playerId, PlayerMemory::new);
        if (selfMemory.attacksTaken > selfMemory.attacksMade) {
            arr.add("You have recently lost more property tempo than you gained; answer with board swing, not passive bank.");
        }
        arr.add("All seats are independent in normal evaluation: attack any leader or near-winner, including another LLM.");
        return arr;
    }

    private void addEvent(HistoryEvent event) {
        recentEvents.addLast(event);
        while (recentEvents.size() > MAX_RECENT_EVENTS) {
            recentEvents.removeFirst();
        }
    }

    private void updateEventMemories(HistoryEvent event, Delta delta) {
        if (event.actorPlayerId != null) {
            PlayerMemory actor = playerMemory.computeIfAbsent(event.actorPlayerId, PlayerMemory::new);
            actor.recentBoardTempo = Math.max(0, actor.recentBoardTempo + Math.max(0, event.impact));
            if (isHighImpact(event.effectCode, event.eventType)) {
                actor.highImpactActionsSeen++;
            }
            if ("JUST_SAY_NO".equals(event.eventType)) {
                actor.justSayNoSeen++;
            }
            if (!event.targetPlayerIds.isEmpty() && isAttackEvent(event)) {
                actor.attacksMade += event.targetPlayerIds.size();
            }
        }
        for (String targetId : event.targetPlayerIds) {
            PlayerMemory target = playerMemory.computeIfAbsent(targetId, PlayerMemory::new);
            if (isAttackEvent(event)) {
                target.attacksTaken++;
                if (event.actorPlayerId != null) {
                    target.attacksTakenByActor.merge(event.actorPlayerId, 1, Integer::sum);
                }
            }
        }
        for (String color : event.colors) {
            ColorMemory memory = colorMemory.computeIfAbsent(color, ColorMemory::new);
            memory.activity++;
            memory.lastActorPlayerId = event.actorPlayerId;
            memory.lastSequence = event.sequence;
        }
        for (String playerId : delta.propertyGainers) {
            PlayerMemory memory = playerMemory.computeIfAbsent(playerId, PlayerMemory::new);
            memory.recentBoardTempo += 2;
        }
        for (String playerId : delta.propertyLosers) {
            PlayerMemory memory = playerMemory.computeIfAbsent(playerId, PlayerMemory::new);
            memory.recentBoardTempo = Math.max(0, memory.recentBoardTempo - 1);
        }
    }

    private void updateCurrentPlayerMemories(Map<String, PublicPlayerState> current) {
        for (PublicPlayerState state : current.values()) {
            playerMemory.computeIfAbsent(state.playerId, PlayerMemory::new);
        }
        for (PlayerMemory memory : playerMemory.values()) {
            memory.recentBoardTempo = Math.max(0, memory.recentBoardTempo - 1);
        }
    }

    private void updateColorOwners(Map<String, PublicPlayerState> current) {
        for (String color : PropertySetCalculator.REQUIRED_BY_COLOR.keySet()) {
            ColorMemory memory = colorMemory.computeIfAbsent(color, ColorMemory::new);
            memory.owners.clear();
            for (PublicPlayerState state : current.values()) {
                if (state.colors.getOrDefault(color, 0) > 0) {
                    memory.owners.add(state.playerId);
                }
            }
        }
    }

    private static Map<String, PublicPlayerState> capture(List<Player> players) {
        Map<String, PublicPlayerState> states = new LinkedHashMap<>();
        if (players == null) {
            return states;
        }
        for (Player p : players) {
            if (p == null) {
                continue;
            }
            states.put(p.getPlayerId(), new PublicPlayerState(
                    p.getPlayerId(),
                    p.getDisplayName(),
                    p.getHandCardCount(),
                    p.getPropertyCardCount(),
                    p.totalBankValueM(),
                    p.countCompletePropertySets(),
                    colorCounts(p)));
        }
        return states;
    }

    private static Map<String, Integer> colorCounts(Player player) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String color : PropertySetCalculator.REQUIRED_BY_COLOR.keySet()) {
            int count = PropertySetCalculator.effectiveCountForColor(
                    player.getPropertyCardsView(), color);
            if (count > 0) {
                counts.put(color, count);
            }
        }
        return counts;
    }

    private static Delta diff(
            Map<String, PublicPlayerState> previous,
            Map<String, PublicPlayerState> current) {
        Delta delta = new Delta();
        Set<String> playerIds = new LinkedHashSet<>();
        playerIds.addAll(previous.keySet());
        playerIds.addAll(current.keySet());
        for (String playerId : playerIds) {
            PublicPlayerState before = previous.get(playerId);
            PublicPlayerState after = current.get(playerId);
            if (before == null || after == null) {
                continue;
            }
            int propertyDelta = after.propertyCount - before.propertyCount;
            int bankDelta = after.bankM - before.bankM;
            int setDelta = after.completeSets - before.completeSets;
            if (propertyDelta > 0) {
                delta.propertyGainers.add(playerId);
            } else if (propertyDelta < 0) {
                delta.propertyLosers.add(playerId);
            }
            if (bankDelta != 0) {
                delta.bankDeltaByPlayer.put(playerId, bankDelta);
            }
            if (setDelta != 0) {
                delta.completeSetDeltaByPlayer.put(playerId, setDelta);
            }
            Set<String> colors = new LinkedHashSet<>();
            colors.addAll(before.colors.keySet());
            colors.addAll(after.colors.keySet());
            for (String color : colors) {
                int oldCount = before.colors.getOrDefault(color, 0);
                int newCount = after.colors.getOrDefault(color, 0);
                if (oldCount != newCount) {
                    delta.changedColors.add(color);
                    delta.colorDeltaByPlayer
                            .computeIfAbsent(playerId, ignored -> new LinkedHashMap<>())
                            .put(color, newCount - oldCount);
                }
            }
        }
        return delta;
    }

    private static String classify(String phase, String actionType, String effectCode, Delta delta) {
        String p = normalize(phase);
        String action = normalize(actionType);
        String effect = normalize(effectCode);
        if (p.contains("JSN") || "RENT_WAIVER".equals(effect)) {
            return "JUST_SAY_NO";
        }
        if ("DEAL_BREAKER".equals(effect)) {
            return "DEAL_BREAKER";
        }
        if ("STEAL_PROPERTY".equals(effect) || "FORCED_DEAL".equals(effect)) {
            return "PROPERTY_SWING";
        }
        if ("DEPLOY".equals(action) || "DEPLOY".equals(p)) {
            return "PROPERTY_DEVELOPMENT";
        }
        if ("DEPOSIT".equals(action) || "DEPOSIT".equals(p)) {
            return "BANKING";
        }
        if ("RENT".equals(effect) || "RENT_DUAL".equals(effect)
                || "DEBT_COLLECTOR".equals(effect) || "BIRTHDAY".equals(effect)
                || p.contains("RENT")) {
            return "CASH_PRESSURE";
        }
        if (!delta.propertyGainers.isEmpty() || !delta.propertyLosers.isEmpty()) {
            return "BOARD_DELTA";
        }
        if (!delta.completeSetDeltaByPlayer.isEmpty()) {
            return "SET_RACE_DELTA";
        }
        if ("PASS_GO".equals(effect)) {
            return "CARD_DRAW";
        }
        return p.isBlank() ? "STATE" : p;
    }

    private static boolean shouldRecord(String eventType, Card playedCard, String phase, Delta delta) {
        if (playedCard != null) {
            return true;
        }
        if (!delta.propertyGainers.isEmpty()
                || !delta.propertyLosers.isEmpty()
                || !delta.completeSetDeltaByPlayer.isEmpty()) {
            return true;
        }
        String p = normalize(phase);
        return p.contains("RENT")
                || p.contains("ACTION")
                || p.contains("JSN")
                || p.contains("RESPONSE")
                || p.contains("GAME_OVER")
                || p.contains("FORCE_END")
                || "JUST_SAY_NO".equals(eventType);
    }

    private static int impactScore(String eventType, String effectCode, Delta delta) {
        int score = 0;
        String effect = normalize(effectCode);
        if ("DEAL_BREAKER".equals(effect)) {
            score += 9;
        } else if ("STEAL_PROPERTY".equals(effect) || "FORCED_DEAL".equals(effect)) {
            score += 7;
        } else if ("PROPERTY_DEVELOPMENT".equals(eventType)) {
            score += 4;
        } else if ("CASH_PRESSURE".equals(eventType)) {
            score += 3;
        }
        score += Math.max(0, delta.propertyGainers.size() + delta.propertyLosers.size()) * 2;
        for (int setDelta : delta.completeSetDeltaByPlayer.values()) {
            score += Math.abs(setDelta) * 4;
        }
        return score;
    }

    private static Set<String> inferTargets(String actorId, Delta delta) {
        Set<String> targets = new LinkedHashSet<>();
        for (String loser : delta.propertyLosers) {
            if (actorId == null || !actorId.equals(loser)) {
                targets.add(loser);
            }
        }
        for (Map.Entry<String, Integer> entry : delta.bankDeltaByPlayer.entrySet()) {
            if (entry.getValue() < 0 && (actorId == null || !actorId.equals(entry.getKey()))) {
                targets.add(entry.getKey());
            }
        }
        return targets;
    }

    private static JsonArray colorOwnersJson(String color, Map<String, PublicPlayerState> current) {
        JsonArray arr = new JsonArray();
        for (PublicPlayerState state : current.values()) {
            int count = state.colors.getOrDefault(color, 0);
            if (count <= 0) {
                continue;
            }
            int need = PropertySetCalculator.REQUIRED_BY_COLOR.getOrDefault(color, 3);
            JsonObject owner = new JsonObject();
            owner.addProperty("playerId", state.playerId);
            owner.addProperty("name", state.name);
            owner.addProperty("count", count);
            owner.addProperty("need", need);
            owner.addProperty("missing", Math.max(0, need - count));
            owner.addProperty("complete", count >= need);
            arr.add(owner);
        }
        return arr;
    }

    private static JsonArray nearCompleteColorsJson(PublicPlayerState state) {
        JsonArray arr = new JsonArray();
        for (Map.Entry<String, Integer> entry : state.colors.entrySet()) {
            int need = PropertySetCalculator.REQUIRED_BY_COLOR.getOrDefault(entry.getKey(), 3);
            int missing = need - entry.getValue();
            if (missing > 1) {
                continue;
            }
            JsonObject row = new JsonObject();
            row.addProperty("color", entry.getKey());
            row.addProperty("count", entry.getValue());
            row.addProperty("need", need);
            row.addProperty("missing", Math.max(0, missing));
            row.addProperty("complete", missing <= 0);
            arr.add(row);
        }
        return arr;
    }

    private static JsonArray strings(List<String> values) {
        JsonArray arr = new JsonArray();
        if (values != null) {
            for (String value : values) {
                arr.add(value);
            }
        }
        return arr;
    }

    private static String leaderId(Map<String, PublicPlayerState> current) {
        PublicPlayerState leader = null;
        for (PublicPlayerState state : current.values()) {
            if (leader == null
                    || state.completeSets > leader.completeSets
                    || (state.completeSets == leader.completeSets && state.propertyCount > leader.propertyCount)
                    || (state.completeSets == leader.completeSets
                    && state.propertyCount == leader.propertyCount
                    && state.bankM > leader.bankM)) {
                leader = state;
            }
        }
        return leader == null ? null : leader.playerId;
    }

    private static boolean isHighImpact(String effectCode, String eventType) {
        String effect = normalize(effectCode);
        return "DEAL_BREAKER".equals(effect)
                || "STEAL_PROPERTY".equals(effect)
                || "FORCED_DEAL".equals(effect)
                || "DEAL_BREAKER".equals(eventType)
                || "PROPERTY_SWING".equals(eventType);
    }

    private static boolean isAttackEvent(HistoryEvent event) {
        return isHighImpact(event.effectCode, event.eventType)
                || "CASH_PRESSURE".equals(event.eventType);
    }

    private static String effectCode(Card card) {
        if (card instanceof ActionCard action) {
            return normalize(action.getEffectCode());
        }
        return null;
    }

    private static String cardName(Card card) {
        return card == null ? null : compact(card.getName(), 60);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String compact(String value, int max) {
        if (value == null) {
            return "";
        }
        String s = value.replace('\n', ' ').replace('\r', ' ').trim();
        return s.length() <= max ? s : s.substring(0, Math.max(0, max - 3)) + "...";
    }

    private record HistoryEvent(
            long sequence,
            int roundNumber,
            String phase,
            String eventType,
            String actorPlayerId,
            String actorName,
            String actionType,
            String cardName,
            String effectCode,
            List<String> targetPlayerIds,
            List<String> colors,
            String summary,
            int impact) {
    }

    private static final class Delta {
        private final Set<String> propertyGainers = new LinkedHashSet<>();
        private final Set<String> propertyLosers = new LinkedHashSet<>();
        private final Set<String> changedColors = new LinkedHashSet<>();
        private final Map<String, Integer> bankDeltaByPlayer = new LinkedHashMap<>();
        private final Map<String, Integer> completeSetDeltaByPlayer = new LinkedHashMap<>();
        private final Map<String, Map<String, Integer>> colorDeltaByPlayer = new LinkedHashMap<>();
    }

    private static final class PublicPlayerState {
        private final String playerId;
        private final String name;
        private final int handCount;
        private final int propertyCount;
        private final int bankM;
        private final int completeSets;
        private final Map<String, Integer> colors;

        private PublicPlayerState(
                String playerId,
                String name,
                int handCount,
                int propertyCount,
                int bankM,
                int completeSets,
                Map<String, Integer> colors) {
            this.playerId = playerId;
            this.name = name;
            this.handCount = handCount;
            this.propertyCount = propertyCount;
            this.bankM = bankM;
            this.completeSets = completeSets;
            this.colors = colors;
        }
    }

    private static final class PlayerMemory {
        private final String playerId;
        private final Map<String, Integer> attacksTakenByActor = new LinkedHashMap<>();
        private int highImpactActionsSeen;
        private int attacksMade;
        private int attacksTaken;
        private int justSayNoSeen;
        private int recentBoardTempo;

        private PlayerMemory(String playerId) {
            this.playerId = playerId;
        }
    }

    private static final class ColorMemory {
        private final String color;
        private final Set<String> owners = new LinkedHashSet<>();
        private int activity;
        private long lastSequence;
        private String lastActorPlayerId;

        private ColorMemory(String color) {
            this.color = color;
        }

        private int activity() {
            return activity;
        }

        private long lastSequence() {
            return lastSequence;
        }

        private String color() {
            return color;
        }
    }
}
