package com.monopoly.model.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.monopoly.model.core.AiHistoryTracker.ColorMemory;
import com.monopoly.model.core.AiHistoryTracker.HistoryEvent;
import com.monopoly.model.core.AiHistoryTracker.PlayerMemory;
import com.monopoly.model.core.AiHistoryTracker.PublicActionMemory;
import com.monopoly.model.core.AiHistoryTracker.PublicPlayerState;
import com.monopoly.model.player.Player;
import com.monopoly.model.settlement.PropertySetCalculator;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static com.monopoly.model.core.AiHistoryTracker.EFFECT_BIRTHDAY;
import static com.monopoly.model.core.AiHistoryTracker.EFFECT_DEAL_BREAKER;
import static com.monopoly.model.core.AiHistoryTracker.EFFECT_DEBT_COLLECTOR;
import static com.monopoly.model.core.AiHistoryTracker.EFFECT_DOUBLE_RENT;
import static com.monopoly.model.core.AiHistoryTracker.EFFECT_FORCED_DEAL;
import static com.monopoly.model.core.AiHistoryTracker.EFFECT_PASS_GO;
import static com.monopoly.model.core.AiHistoryTracker.EFFECT_RENT;
import static com.monopoly.model.core.AiHistoryTracker.EFFECT_RENT_DUAL;
import static com.monopoly.model.core.AiHistoryTracker.EFFECT_RENT_WAIVER;
import static com.monopoly.model.core.AiHistoryTracker.EFFECT_STEAL_PROPERTY;
import static com.monopoly.model.core.AiHistoryTracker.EVENT_JUST_SAY_NO;
import static com.monopoly.model.core.AiHistoryTracker.IMPORTANT_ACTION_TOTALS;
import static com.monopoly.model.core.AiHistoryTracker.WIN_TARGET_SETS;

/**
 * Renders an {@link AiHistoryTracker}'s public memory into the prompt JSON shapes
 * consumed by the LLM strategy. It reads (and lazily seeds, preserving legacy
 * behavior) the tracker's live memory collections; all event-recording and memory
 * mutation logic stays in the tracker.
 */
final class AiHistoryPromptWriter {

    private static final int PROMPT_RECENT_EVENTS = 10;
    private static final int PROMPT_CONTESTED_COLORS = 6;

    private final Deque<HistoryEvent> recentEvents;
    private final Map<String, PlayerMemory> playerMemory;
    private final Map<String, ColorMemory> colorMemory;
    private final Map<String, PublicActionMemory> publicActionMemory;

    AiHistoryPromptWriter(
            Deque<HistoryEvent> recentEvents,
            Map<String, PlayerMemory> playerMemory,
            Map<String, ColorMemory> colorMemory,
            Map<String, PublicActionMemory> publicActionMemory) {
        this.recentEvents = recentEvents;
        this.playerMemory = playerMemory;
        this.colorMemory = colorMemory;
        this.publicActionMemory = publicActionMemory;
    }

    JsonObject fullPrompt(Player perspective, Map<String, PublicPlayerState> current) {
        JsonObject root = new JsonObject();
        root.addProperty("schema", "ai-history-v1");
        root.addProperty("purpose",
                "Compressed public history for long-term planning. Use it to avoid short-sighted cash moves.");
        root.add("recentEvents", recentEventsJson());
        root.add("contestedColors", contestedColorsJson(current));
        root.add("playerPressure", playerPressureJson(perspective, current));
        root.add("strategicWarnings", strategicWarningsJson(perspective, current));
        root.add("publicCardMemory", publicCardMemoryJson());
        return root;
    }

    JsonObject compactPrompt(Player perspective, Map<String, PublicPlayerState> current) {
        JsonObject root = new JsonObject();
        root.addProperty("schema", "ai-history-v1-compact");
        root.addProperty("purpose",
                "Compact public memory for response/payment decisions; use only for threat context.");
        root.add("recentEvents", compactRecentEventsJson());
        root.add("strategicWarnings", strategicWarningsJson(perspective, current));
        root.add("publicCardMemory", publicCardMemoryJson());
        return root;
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
            row.addProperty("seq", event.sequence());
            row.addProperty("round", event.roundNumber());
            row.addProperty("phase", event.phase());
            row.addProperty("type", event.eventType());
            row.addProperty("actorPlayerId", event.actorPlayerId());
            row.addProperty("actorName", event.actorName());
            row.addProperty("actionType", event.actionType());
            row.addProperty("cardName", event.cardName());
            row.addProperty("effectCode", event.effectCode());
            row.add("targetPlayerIds", strings(event.targetPlayerIds()));
            row.add("colors", strings(event.colors()));
            row.addProperty("impact", event.impact());
            row.addProperty("summary", event.summary());
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
            if (event.impact() < 5 && !EVENT_JUST_SAY_NO.equals(event.eventType())) {
                continue;
            }
            JsonObject row = new JsonObject();
            row.addProperty("type", event.eventType());
            row.addProperty("actorPlayerId", event.actorPlayerId());
            row.addProperty("effectCode", event.effectCode());
            row.add("targetPlayerIds", strings(event.targetPlayerIds()));
            row.add("colors", strings(event.colors()));
            row.addProperty("impact", event.impact());
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
            row.addProperty("setsNeededToWin", Math.max(0, WIN_TARGET_SETS - state.completeSets));
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
        warnSelfPosition(arr, self, maxOpponentSets(current, self));
        warnOpponentThreats(arr, current, self);
        warnLeaderFocus(arr, pickLeader(current.values()), self);
        warnSelfNearComplete(arr, self);
        warnTempoLoss(arr, self);
        warnPublicCounters(arr, self);
        arr.add("All seats are independent in normal evaluation: attack any leader or near-winner, including another LLM.");
        return arr;
    }

    private static int maxOpponentSets(Map<String, PublicPlayerState> current, PublicPlayerState self) {
        int max = 0;
        for (PublicPlayerState state : current.values()) {
            if (!state.playerId.equals(self.playerId)) {
                max = Math.max(max, state.completeSets);
            }
        }
        return max;
    }

    private static void warnSelfPosition(JsonArray arr, PublicPlayerState self, int maxOpponentSets) {
        if (self.completeSets >= WIN_TARGET_SETS - 1) {
            arr.add("You have 2 complete sets: every play should complete/protect the third set or block an immediate threat.");
        }
        if (self.bankM >= 12 && self.completeSets <= maxOpponentSets) {
            arr.add("Your bank is saturated relative to your set race; prefer deploy/steal/swap/Deal Breaker over more cash.");
        }
    }

    private static void warnOpponentThreats(JsonArray arr, Map<String, PublicPlayerState> current,
            PublicPlayerState self) {
        for (PublicPlayerState state : current.values()) {
            if (!state.playerId.equals(self.playerId) && state.completeSets >= WIN_TARGET_SETS - 1) {
                arr.add("Block " + state.name + " now: they need only "
                        + Math.max(0, WIN_TARGET_SETS - state.completeSets) + " set(s) to win.");
            }
        }
    }

    private static void warnLeaderFocus(JsonArray arr, PublicPlayerState leader, PublicPlayerState self) {
        if (leader != null && !leader.playerId.equals(self.playerId)
                && leader.completeSets >= self.completeSets) {
            arr.add("Current leader is " + leader.name
                    + "; attack their set progress before attacking trailing players.");
        }
    }

    private static void warnSelfNearComplete(JsonArray arr, PublicPlayerState self) {
        for (String color : self.colors.keySet()) {
            int count = self.colors.getOrDefault(color, 0);
            int need = PropertySetCalculator.REQUIRED_BY_COLOR.getOrDefault(color, WIN_TARGET_SETS);
            if (count == need - 1) {
                arr.add("You are one card away on " + color
                        + "; prioritize completing or protecting this color.");
                break;
            }
        }
    }

    private void warnTempoLoss(JsonArray arr, PublicPlayerState self) {
        PlayerMemory selfMemory = playerMemory.computeIfAbsent(self.playerId, PlayerMemory::new);
        if (selfMemory.attacksTaken > selfMemory.attacksMade) {
            arr.add("You have recently lost more property tempo than you gained; answer with board swing, not passive bank.");
        }
    }

    private void warnPublicCounters(JsonArray arr, PublicPlayerState self) {
        PublicActionMemory justSayNo = publicActionMemory.get(EFFECT_RENT_WAIVER);
        if (justSayNo != null) {
            int remaining = remainingEstimate(justSayNo);
            if (remaining <= 0 && justSayNo.seenCount() > 0) {
                arr.add("All Just Say No cards have been publicly seen; decisive Deal Breaker, steals, and high rent are harder to stop.");
            } else if (remaining > 0 && self.completeSets >= 1) {
                arr.add(remaining + " Just Say No card(s) remain unseen by public memory; expect key attacks or rents may be blocked.");
            }
        }
        PublicActionMemory dealBreaker = publicActionMemory.get(EFFECT_DEAL_BREAKER);
        if (dealBreaker != null && remainingEstimate(dealBreaker) > 0 && self.completeSets >= 1) {
            arr.add("At least " + remainingEstimate(dealBreaker)
                    + " Deal Breaker card(s) remain unseen; complete sets still need protection.");
        }
    }

    private JsonObject publicCardMemoryJson() {
        JsonObject root = new JsonObject();
        root.addProperty("schema", "public-card-memory-v1");
        root.addProperty("visibility",
                "Counts only cards publicly observed in action zone, bank, or visible discard/play events; hidden hands and draw pile are not inspected.");
        JsonObject important = new JsonObject();
        for (Map.Entry<String, Integer> entry : IMPORTANT_ACTION_TOTALS.entrySet()) {
            String effect = entry.getKey();
            PublicActionMemory memory = publicActionMemory.computeIfAbsent(
                    effect, key -> new PublicActionMemory(key, entry.getValue()));
            JsonObject row = new JsonObject();
            row.addProperty("name", importantActionName(effect));
            row.addProperty("totalInDeck", memory.totalInDeck);
            row.addProperty("seen", memory.seenCount());
            row.addProperty("played", memory.playedCardIds.size());
            row.addProperty("banked", memory.bankedCardIds.size());
            row.addProperty("discarded", memory.discardedCardIds.size());
            row.addProperty("currentlyPublic", memory.currentPublicCardIds.size());
            row.addProperty("remainingEstimate", remainingEstimate(memory));
            important.add(effect, row);
        }
        root.add("importantActions", important);
        return root;
    }

    private static JsonArray colorOwnersJson(String color, Map<String, PublicPlayerState> current) {
        JsonArray arr = new JsonArray();
        for (PublicPlayerState state : current.values()) {
            int count = state.colors.getOrDefault(color, 0);
            if (count <= 0) {
                continue;
            }
            int need = PropertySetCalculator.REQUIRED_BY_COLOR.getOrDefault(color, WIN_TARGET_SETS);
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
            int need = PropertySetCalculator.REQUIRED_BY_COLOR.getOrDefault(entry.getKey(), WIN_TARGET_SETS);
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
        PublicPlayerState leader = pickLeader(current.values());
        return leader == null ? null : leader.playerId;
    }

    /** Leader by completeSets, then propertyCount, then bank; null if none. */
    private static PublicPlayerState pickLeader(Collection<PublicPlayerState> states) {
        PublicPlayerState leader = null;
        for (PublicPlayerState state : states) {
            if (leader == null
                    || state.completeSets > leader.completeSets
                    || (state.completeSets == leader.completeSets && state.propertyCount > leader.propertyCount)
                    || (state.completeSets == leader.completeSets
                    && state.propertyCount == leader.propertyCount
                    && state.bankM > leader.bankM)) {
                leader = state;
            }
        }
        return leader;
    }

    private static int remainingEstimate(PublicActionMemory memory) {
        if (memory == null) {
            return 0;
        }
        return Math.max(0, memory.totalInDeck - memory.seenCount());
    }

    private static String importantActionName(String effectCode) {
        return switch (effectCode) {
            case EFFECT_RENT_WAIVER -> "Just Say No";
            case EFFECT_DEAL_BREAKER -> "Deal Breaker";
            case EFFECT_STEAL_PROPERTY -> "Sly Deal";
            case EFFECT_FORCED_DEAL -> "Forced Deal";
            case EFFECT_DOUBLE_RENT -> "Double The Rent";
            case EFFECT_PASS_GO -> "Pass Go";
            case EFFECT_DEBT_COLLECTOR -> "Debt Collector";
            case EFFECT_BIRTHDAY -> "It's My Birthday";
            case EFFECT_RENT -> "Any-Color Rent";
            case EFFECT_RENT_DUAL -> "Dual-Color Rent";
            default -> effectCode;
        };
    }
}
