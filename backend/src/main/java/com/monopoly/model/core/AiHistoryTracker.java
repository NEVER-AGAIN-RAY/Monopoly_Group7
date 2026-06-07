package com.monopoly.model.core;

import com.google.gson.JsonObject;
import com.monopoly.model.card.ActionCard;
import com.monopoly.model.card.Card;
import com.monopoly.model.player.Player;
import com.monopoly.model.settlement.PropertySetCalculator;

import java.util.ArrayDeque;
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
    /** Complete property sets required to win. */
    static final int WIN_TARGET_SETS = 3;
    static final Map<String, Integer> IMPORTANT_ACTION_TOTALS = importantActionTotals();

    // Effect codes (normalized, uppercase) — see MonopolyDealCardFactory deck composition.
    static final String EFFECT_RENT = "RENT";
    static final String EFFECT_RENT_DUAL = "RENT_DUAL";
    static final String EFFECT_RENT_WAIVER = "RENT_WAIVER";
    static final String EFFECT_DEAL_BREAKER = "DEAL_BREAKER";
    static final String EFFECT_STEAL_PROPERTY = "STEAL_PROPERTY";
    static final String EFFECT_FORCED_DEAL = "FORCED_DEAL";
    static final String EFFECT_DEBT_COLLECTOR = "DEBT_COLLECTOR";
    static final String EFFECT_BIRTHDAY = "BIRTHDAY";
    static final String EFFECT_DOUBLE_RENT = "DOUBLE_RENT";
    static final String EFFECT_PASS_GO = "PASS_GO";

    // Classified event types.
    static final String EVENT_JUST_SAY_NO = "JUST_SAY_NO";
    static final String EVENT_DEAL_BREAKER = "DEAL_BREAKER";
    static final String EVENT_PROPERTY_SWING = "PROPERTY_SWING";
    static final String EVENT_PROPERTY_DEVELOPMENT = "PROPERTY_DEVELOPMENT";
    static final String EVENT_BANKING = "BANKING";
    static final String EVENT_CASH_PRESSURE = "CASH_PRESSURE";
    static final String EVENT_BOARD_DELTA = "BOARD_DELTA";
    static final String EVENT_SET_RACE_DELTA = "SET_RACE_DELTA";
    static final String EVENT_CARD_DRAW = "CARD_DRAW";
    static final String EVENT_STATE = "STATE";

    // Observation types (how a card became publicly visible).
    static final String OBS_ACTION = "ACTION";
    static final String OBS_ACTION_ZONE = "ACTION_ZONE";
    static final String OBS_DEPOSIT = "DEPOSIT";
    static final String OBS_BANK = "BANK";
    static final String OBS_DISCARD = "DISCARD";
    static final String OBS_FORCE_DISCARD = "FORCE_DISCARD";
    static final String OBS_OVERFLOW_DISCARD = "OVERFLOW_DISCARD";

    private final Deque<HistoryEvent> recentEvents = new ArrayDeque<>();
    private final Map<String, PlayerMemory> playerMemory = new LinkedHashMap<>();
    private final Map<String, ColorMemory> colorMemory = new LinkedHashMap<>();
    private final Map<String, PublicActionMemory> publicActionMemory = new LinkedHashMap<>();
    private Map<String, PublicPlayerState> previousState = new LinkedHashMap<>();

    public void reset() {
        recentEvents.clear();
        playerMemory.clear();
        colorMemory.clear();
        publicActionMemory.clear();
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
        recordCurrentPublicActions(players);

        if (previousState.isEmpty()) {
            recordPlayedActionCard(playedCard, playedActionType);
            previousState = current;
            return;
        }

        Delta delta = diff(previousState, current);
        String effectCode = effectCode(playedCard);
        String actionType = normalize(playedActionType);
        String actorId = playedBy != null ? playedBy.getPlayerId() : currentPlayerId;
        String eventType = classify(phase, actionType, effectCode, delta);
        boolean meaningful = shouldRecord(eventType, playedCard, phase, delta);
        recordPlayedActionCard(playedCard, actionType);

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
        return writer().fullPrompt(perspective, capture(players));
    }

    public JsonObject toCompactPromptJson(Player perspective, List<Player> players) {
        return writer().compactPrompt(perspective, capture(players));
    }

    private AiHistoryPromptWriter writer() {
        return new AiHistoryPromptWriter(recentEvents, playerMemory, colorMemory, publicActionMemory);
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
            if (EVENT_JUST_SAY_NO.equals(event.eventType)) {
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

    private void recordCurrentPublicActions(List<Player> players) {
        for (PublicActionMemory memory : publicActionMemory.values()) {
            memory.currentPublicCardIds.clear();
        }
        if (players == null) {
            return;
        }
        for (Player p : players) {
            if (p == null) {
                continue;
            }
            for (Card card : p.getBankCardsView()) {
                if (card instanceof ActionCard actionCard) {
                    recordActionCard(actionCard, OBS_BANK);
                }
            }
            for (ActionCard actionCard : p.getActionZoneCardsView()) {
                recordActionCard(actionCard, OBS_ACTION_ZONE);
            }
        }
    }

    private void recordPlayedActionCard(Card playedCard, String playedActionType) {
        if (!(playedCard instanceof ActionCard actionCard)) {
            return;
        }
        recordActionCard(actionCard, normalize(playedActionType));
    }

    private void recordActionCard(ActionCard card, String observationType) {
        if (card == null) {
            return;
        }
        String effect = normalize(card.getEffectCode());
        if (effect.isBlank() || !IMPORTANT_ACTION_TOTALS.containsKey(effect)) {
            return;
        }
        String id = card.getId();
        if (id == null || id.isBlank()) {
            return;
        }
        PublicActionMemory memory = publicActionMemory.computeIfAbsent(
                effect,
                key -> new PublicActionMemory(key, IMPORTANT_ACTION_TOTALS.getOrDefault(key, 0)));
        memory.seenCardIds.add(id);
        String type = normalize(observationType);
        if (OBS_ACTION.equals(type) || OBS_ACTION_ZONE.equals(type)) {
            memory.playedCardIds.add(id);
        } else if (OBS_DEPOSIT.equals(type) || OBS_BANK.equals(type)) {
            memory.bankedCardIds.add(id);
        } else if (OBS_DISCARD.equals(type) || OBS_FORCE_DISCARD.equals(type)
                || OBS_OVERFLOW_DISCARD.equals(type)) {
            memory.discardedCardIds.add(id);
        }
        if (OBS_ACTION_ZONE.equals(type) || OBS_BANK.equals(type)) {
            memory.currentPublicCardIds.add(id);
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
        if (p.contains("JSN") || EFFECT_RENT_WAIVER.equals(effect)) {
            return EVENT_JUST_SAY_NO;
        }
        if (EFFECT_DEAL_BREAKER.equals(effect)) {
            return EVENT_DEAL_BREAKER;
        }
        if (EFFECT_STEAL_PROPERTY.equals(effect) || EFFECT_FORCED_DEAL.equals(effect)) {
            return EVENT_PROPERTY_SWING;
        }
        if ("DEPLOY".equals(action) || "DEPLOY".equals(p)) {
            return EVENT_PROPERTY_DEVELOPMENT;
        }
        if (OBS_DEPOSIT.equals(action) || OBS_DEPOSIT.equals(p)) {
            return EVENT_BANKING;
        }
        if (EFFECT_RENT.equals(effect) || EFFECT_RENT_DUAL.equals(effect)
                || EFFECT_DEBT_COLLECTOR.equals(effect) || EFFECT_BIRTHDAY.equals(effect)
                || p.contains("RENT")) {
            return EVENT_CASH_PRESSURE;
        }
        if (!delta.propertyGainers.isEmpty() || !delta.propertyLosers.isEmpty()) {
            return EVENT_BOARD_DELTA;
        }
        if (!delta.completeSetDeltaByPlayer.isEmpty()) {
            return EVENT_SET_RACE_DELTA;
        }
        if (EFFECT_PASS_GO.equals(effect)) {
            return EVENT_CARD_DRAW;
        }
        return p.isBlank() ? EVENT_STATE : p;
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
                || EVENT_JUST_SAY_NO.equals(eventType);
    }

    private static int impactScore(String eventType, String effectCode, Delta delta) {
        int score = 0;
        String effect = normalize(effectCode);
        if (EFFECT_DEAL_BREAKER.equals(effect)) {
            score += 9;
        } else if (EFFECT_STEAL_PROPERTY.equals(effect) || EFFECT_FORCED_DEAL.equals(effect)) {
            score += 7;
        } else if (EVENT_PROPERTY_DEVELOPMENT.equals(eventType)) {
            score += 4;
        } else if (EVENT_CASH_PRESSURE.equals(eventType)) {
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

    private static boolean isHighImpact(String effectCode, String eventType) {
        String effect = normalize(effectCode);
        return EFFECT_DEAL_BREAKER.equals(effect)
                || EFFECT_STEAL_PROPERTY.equals(effect)
                || EFFECT_FORCED_DEAL.equals(effect)
                || EVENT_DEAL_BREAKER.equals(eventType)
                || EVENT_PROPERTY_SWING.equals(eventType);
    }

    private static boolean isAttackEvent(HistoryEvent event) {
        return isHighImpact(event.effectCode, event.eventType)
                || EVENT_CASH_PRESSURE.equals(event.eventType);
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

    private static Map<String, Integer> importantActionTotals() {
        Map<String, Integer> totals = new LinkedHashMap<>();
        totals.put(EFFECT_RENT_WAIVER, 3);
        totals.put(EFFECT_DEAL_BREAKER, 2);
        totals.put(EFFECT_STEAL_PROPERTY, 3);
        totals.put(EFFECT_FORCED_DEAL, 3);
        totals.put(EFFECT_DOUBLE_RENT, 2);
        totals.put(EFFECT_PASS_GO, 10);
        totals.put(EFFECT_DEBT_COLLECTOR, 3);
        totals.put(EFFECT_BIRTHDAY, 3);
        totals.put(EFFECT_RENT, 3);
        totals.put(EFFECT_RENT_DUAL, 10);
        return totals;
    }

    // Data holders below are package-private so AiHistoryPromptWriter (same package) can read them.

    record HistoryEvent(
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

    /** Tracker-only scratch type; not shared with the prompt writer. */
    private static final class Delta {
        private final Set<String> propertyGainers = new LinkedHashSet<>();
        private final Set<String> propertyLosers = new LinkedHashSet<>();
        private final Set<String> changedColors = new LinkedHashSet<>();
        private final Map<String, Integer> bankDeltaByPlayer = new LinkedHashMap<>();
        private final Map<String, Integer> completeSetDeltaByPlayer = new LinkedHashMap<>();
        private final Map<String, Map<String, Integer>> colorDeltaByPlayer = new LinkedHashMap<>();
    }

    static final class PublicPlayerState {
        final String playerId;
        final String name;
        final int handCount;
        final int propertyCount;
        final int bankM;
        final int completeSets;
        final Map<String, Integer> colors;

        PublicPlayerState(
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

    static final class PlayerMemory {
        final String playerId;
        final Map<String, Integer> attacksTakenByActor = new LinkedHashMap<>();
        int highImpactActionsSeen;
        int attacksMade;
        int attacksTaken;
        int justSayNoSeen;
        int recentBoardTempo;

        PlayerMemory(String playerId) {
            this.playerId = playerId;
        }
    }

    static final class ColorMemory {
        final String color;
        final Set<String> owners = new LinkedHashSet<>();
        int activity;
        long lastSequence;
        String lastActorPlayerId;

        ColorMemory(String color) {
            this.color = color;
        }

        int activity() {
            return activity;
        }

        long lastSequence() {
            return lastSequence;
        }

        String color() {
            return color;
        }
    }

    static final class PublicActionMemory {
        final String effectCode;
        final int totalInDeck;
        final Set<String> seenCardIds = new LinkedHashSet<>();
        final Set<String> playedCardIds = new LinkedHashSet<>();
        final Set<String> bankedCardIds = new LinkedHashSet<>();
        final Set<String> discardedCardIds = new LinkedHashSet<>();
        final Set<String> currentPublicCardIds = new LinkedHashSet<>();

        PublicActionMemory(String effectCode, int totalInDeck) {
            this.effectCode = effectCode;
            this.totalInDeck = Math.max(0, totalInDeck);
        }

        int seenCount() {
            return seenCardIds.size();
        }
    }
}
