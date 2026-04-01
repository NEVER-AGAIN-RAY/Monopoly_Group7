package com.monopoly.pattern.strategy;

import com.monopoly.model.ActionCard;
import com.monopoly.model.AIPlayer;
import com.monopoly.model.AiGameBridge;
import com.monopoly.model.BuildingLevel;
import com.monopoly.model.Card;
import com.monopoly.model.GameContext;
import com.monopoly.model.PayableCards;
import com.monopoly.model.Player;
import com.monopoly.model.PropertyCard;
import com.monopoly.model.PropertySetCalculator;
import com.monopoly.model.PropertyWildCard;
import com.monopoly.model.RentCalculator;
import com.monopoly.model.dto.ActionParamContext;
import com.monopoly.model.dto.PlayActionRequest;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * AI 出牌启发式：三档 {@link AiStrategyProfile} 在相同公开信息下使用不同阶段顺序与目标选择。
 * <p>
 * 系统属性：{@code -Dmonopoly.ai.seed=<long>} 固定随机种子（便于测试）；
 * {@code -Dmonopoly.ai.trace=true} 输出决策轨迹。
 */
public final class AiHeuristics {

    private static final String[] ACTION_TRY_ORDER = {
            "RENT",
            "DOUBLE_RENT",
            "DEBT_COLLECTOR",
            "FORCED_DEAL",
            "PASS_GO",
            "BIRTHDAY",
            "HOUSE",
            "HOTEL"
    };

    private AiHeuristics() {
    }

    public static boolean tryPlayOneCard(
            AiStrategyProfile profile,
            AIPlayer bot,
            GameContext context,
            AiGameBridge bridge) {
        if (bot.getHandCardsView().isEmpty()) {
            return false;
        }
        Random rng = randomFor(profile);
        return switch (profile) {
            case EASY -> pipelineEasy(bot, context, bridge, rng);
            case NORMAL -> pipelineNormal(bot, context, bridge, rng);
            case HARD -> pipelineHard(bot, context, bridge, rng);
        };
    }

    private static Random randomFor(AiStrategyProfile profile) {
        Long prop = Long.getLong("monopoly.ai.seed");
        long base = prop != null ? prop : System.nanoTime();
        long mix = base ^ ((long) profile.ordinal() + 1L) * 0x9E3779B97F4A7C15L;
        return new Random(mix);
    }

    private static void trace(String msg) {
        if (!Boolean.parseBoolean(System.getProperty("monopoly.ai.trace", "false"))) {
            return;
        }
        System.out.println("[AI] " + msg);
    }

    // --- pipelines (phase order differs) ---

    private static boolean pipelineEasy(AIPlayer bot, GameContext context, AiGameBridge bridge, Random rng) {
        List<String> actions = new ArrayList<>(Arrays.asList(ACTION_TRY_ORDER));
        Collections.shuffle(actions, rng);
        trace("EASY phase: steal -> shuffled actions -> deploy -> deposit");
        if (tryPlayStealProperty(bot, context, bridge, AiStrategyProfile.EASY, rng)) {
            return true;
        }
        if (tryPlayActionCardsInOrder(bot, context, bridge, actions, AiStrategyProfile.EASY, rng)) {
            return true;
        }
        if (tryDeployWild(bot, bridge, AiStrategyProfile.EASY, rng)) {
            return true;
        }
        if (tryDeployProperty(bot, bridge, AiStrategyProfile.EASY, rng)) {
            return true;
        }
        return tryDeposit(bot, bridge);
    }

    private static boolean pipelineNormal(AIPlayer bot, GameContext context, AiGameBridge bridge, Random rng) {
        trace("NORMAL phase: deploy -> steal -> actions -> deposit");
        if (tryDeployWild(bot, bridge, AiStrategyProfile.NORMAL, rng)) {
            return true;
        }
        if (tryDeployProperty(bot, bridge, AiStrategyProfile.NORMAL, rng)) {
            return true;
        }
        if (tryPlayStealProperty(bot, context, bridge, AiStrategyProfile.NORMAL, rng)) {
            return true;
        }
        if (tryPlayActionCardsInOrder(bot, context, bridge, Arrays.asList(ACTION_TRY_ORDER), AiStrategyProfile.NORMAL, rng)) {
            return true;
        }
        return tryDeposit(bot, bridge);
    }

    private static boolean pipelineHard(AIPlayer bot, GameContext context, AiGameBridge bridge, Random rng) {
        trace("HARD phase: steal -> actions -> deploy -> deposit");
        if (tryPlayStealProperty(bot, context, bridge, AiStrategyProfile.HARD, rng)) {
            return true;
        }
        if (tryPlayActionCardsInOrder(bot, context, bridge, Arrays.asList(ACTION_TRY_ORDER), AiStrategyProfile.HARD, rng)) {
            return true;
        }
        if (tryDeployWild(bot, bridge, AiStrategyProfile.HARD, rng)) {
            return true;
        }
        if (tryDeployProperty(bot, bridge, AiStrategyProfile.HARD, rng)) {
            return true;
        }
        return tryDeposit(bot, bridge);
    }

    // --- threat & deploy scoring ---

    static int threatScore(Player p) {
        if (p == null) {
            return 0;
        }
        return p.countCompletePropertySets() * 20
                + p.totalBankValueM() * 2
                + p.getPropertyCardCount() * 3;
    }

    private static int deployCompletionScore(AIPlayer bot, PropertyCard pc) {
        if (pc == null || pc instanceof PropertyWildCard) {
            return 0;
        }
        String cg = pc.getColorGroup();
        if (cg == null || cg.isBlank()) {
            return 0;
        }
        String key = cg.trim().toUpperCase(Locale.ROOT);
        int need = PropertySetCalculator.REQUIRED_BY_COLOR.getOrDefault(key, 3);
        int eff = PropertySetCalculator.effectiveCountForColor(bot.getPropertyCardsView(), key);
        int after = eff + 1;
        if (after >= need) {
            return 10_000 + after;
        }
        return after * 100 / Math.max(1, need);
    }

    private static String pickWildColor(AIPlayer bot, AiStrategyProfile profile, Random rng) {
        List<String> keys = new ArrayList<>(PropertySetCalculator.REQUIRED_BY_COLOR.keySet());
        if (profile == AiStrategyProfile.EASY) {
            Collections.shuffle(keys, rng);
            return keys.get(0);
        }
        String best = keys.get(0);
        int bestScore = -1;
        for (String color : keys) {
            int eff = PropertySetCalculator.effectiveCountForColor(bot.getPropertyCardsView(), color);
            int need = PropertySetCalculator.REQUIRED_BY_COLOR.getOrDefault(color, 3);
            int score = eff * 100 / Math.max(1, need);
            if (score > bestScore) {
                bestScore = score;
                best = color;
            }
        }
        return best;
    }

    private static List<Player> opponentsExcluding(AIPlayer bot, GameContext ctx) {
        List<Player> out = new ArrayList<>();
        if (ctx.getPlayers() == null) {
            return out;
        }
        for (Player p : ctx.getPlayers()) {
            if (p != null && !p.getPlayerId().equals(bot.getPlayerId())) {
                out.add(p);
            }
        }
        return out;
    }

    // --- steal ---

    private static boolean tryPlayStealProperty(
            AIPlayer bot,
            GameContext context,
            AiGameBridge bridge,
            AiStrategyProfile profile,
            Random rng) {
        StealTarget best = resolveStealTarget(bot, context, profile, rng);
        if (best == null) {
            return false;
        }
        for (Card c : bot.getHandCardsView()) {
            if (!(c instanceof ActionCard ac)) {
                continue;
            }
            if (!"STEAL_PROPERTY".equalsIgnoreCase(trim(ac.getEffectCode()))) {
                continue;
            }
            ActionParamContext probe = new ActionParamContext(
                    ac.getId(),
                    null,
                    best.ownerPlayerId,
                    null,
                    best.propertyCardId,
                    null,
                    "PROPERTY");
            if (!ac.canPlay(bot, probe, context)) {
                continue;
            }
            PlayActionRequest req = new PlayActionRequest();
            req.setActionType("ACTION");
            req.setCardId(ac.getId());
            req.setTargetPlayerId(best.ownerPlayerId);
            req.setTargetCardId(best.propertyCardId);
            req.setTargetZone("PROPERTY");
            try {
                bridge.submitPlayAction(req);
                trace(profile + " STEAL -> " + best.propertyCardId);
                return true;
            } catch (RuntimeException ex) {
                // next
            }
        }
        return false;
    }

    private static StealTarget resolveStealTarget(AIPlayer bot, GameContext ctx, AiStrategyProfile profile, Random rng) {
        if (ctx.getPlayers() == null) {
            return null;
        }
        if (profile == AiStrategyProfile.HARD) {
            Player topThreat = null;
            int ts = -1;
            for (Player p : ctx.getPlayers()) {
                if (p == null || p.getPlayerId().equals(bot.getPlayerId())) {
                    continue;
                }
                int t = threatScore(p);
                if (t > ts) {
                    ts = t;
                    topThreat = p;
                }
            }
            if (topThreat == null || topThreat.getPropertyCardCount() == 0) {
                return null;
            }
            PropertyCard bestPc = null;
            int bv = -1;
            for (PropertyCard pc : topThreat.getPropertyCardsView()) {
                int v = PayableCards.valueOf(pc);
                if (v > bv) {
                    bv = v;
                    bestPc = pc;
                }
            }
            if (bestPc == null) {
                return null;
            }
            return new StealTarget(topThreat.getPlayerId(), bestPc.getId());
        }
        if (profile == AiStrategyProfile.EASY && rng.nextDouble() < 0.4) {
            List<StealTarget> all = new ArrayList<>();
            for (Player p : ctx.getPlayers()) {
                if (p == null || p.getPlayerId().equals(bot.getPlayerId())) {
                    continue;
                }
                for (PropertyCard pc : p.getPropertyCardsView()) {
                    all.add(new StealTarget(p.getPlayerId(), pc.getId()));
                }
            }
            if (all.isEmpty()) {
                return null;
            }
            return all.get(rng.nextInt(all.size()));
        }
        return findHighestValuePropertyAmongOpponents(bot, ctx);
    }

    private static StealTarget findHighestValuePropertyAmongOpponents(AIPlayer self, GameContext context) {
        if (context.getPlayers() == null) {
            return null;
        }
        int bestVal = -1;
        PropertyCard bestCard = null;
        String bestOwnerId = null;
        for (Player p : context.getPlayers()) {
            if (p.getPlayerId().equals(self.getPlayerId())) {
                continue;
            }
            for (PropertyCard pc : p.getPropertyCardsView()) {
                int v = PayableCards.valueOf(pc);
                if (v > bestVal) {
                    bestVal = v;
                    bestCard = pc;
                    bestOwnerId = p.getPlayerId();
                }
            }
        }
        if (bestCard == null || bestOwnerId == null) {
            return null;
        }
        return new StealTarget(bestOwnerId, bestCard.getId());
    }

    // --- action cards ---

    private static boolean tryPlayActionCardsInOrder(
            AIPlayer bot,
            GameContext context,
            AiGameBridge bridge,
            List<String> order,
            AiStrategyProfile profile,
            Random rng) {
        for (String wanted : order) {
            List<Card> hand = new ArrayList<>(bot.getHandCardsView());
            if (profile == AiStrategyProfile.EASY) {
                Collections.shuffle(hand, rng);
            }
            for (Card c : hand) {
                if (!(c instanceof ActionCard ac)) {
                    continue;
                }
                if (!wanted.equalsIgnoreCase(trim(ac.getEffectCode()))) {
                    continue;
                }
                PlayActionRequest req = buildActionRequest(bot, context, ac, wanted, profile, rng);
                if (req == null) {
                    continue;
                }
                try {
                    bridge.submitPlayAction(req);
                    trace(profile + " ACTION " + wanted + " card=" + ac.getId());
                    return true;
                } catch (RuntimeException ex) {
                    // next
                }
            }
        }
        return false;
    }

    private static PlayActionRequest buildActionRequest(
            AIPlayer bot,
            GameContext context,
            ActionCard ac,
            String wanted,
            AiStrategyProfile profile,
            Random rng) {
        return switch (wanted.toUpperCase(Locale.ROOT)) {
            case "RENT" -> buildRentLike(bot, context, ac, false, profile, rng);
            case "DOUBLE_RENT" -> buildRentLike(bot, context, ac, true, profile, rng);
            case "DEBT_COLLECTOR" -> buildDebtCollector(bot, context, ac, profile, rng);
            case "FORCED_DEAL" -> buildForcedDeal(bot, context, ac, profile, rng);
            case "PASS_GO", "BIRTHDAY" -> buildSimpleAction(bot, context, ac);
            case "HOUSE" -> buildHouse(bot, context, ac, profile, rng);
            case "HOTEL" -> buildHotel(bot, context, ac, profile, rng);
            default -> null;
        };
    }

    private static PlayActionRequest buildRentLike(
            AIPlayer bot,
            GameContext context,
            ActionCard ac,
            boolean doubleRent,
            AiStrategyProfile profile,
            Random rng) {
        String code = trim(ac.getEffectCode()).toUpperCase(Locale.ROOT);
        if (doubleRent && !"DOUBLE_RENT".equals(code)) {
            return null;
        }
        if (!doubleRent && !"RENT".equals(code)) {
            return null;
        }
        List<String> colors = new ArrayList<>(PropertySetCalculator.REQUIRED_BY_COLOR.keySet());
        List<Player> opps = opponentsExcluding(bot, context);
        if (opps.isEmpty()) {
            return null;
        }
        if (profile == AiStrategyProfile.EASY) {
            Collections.shuffle(colors, rng);
            Collections.shuffle(opps, rng);
        } else if (profile == AiStrategyProfile.NORMAL) {
            opps.sort(Comparator.comparingInt(Player::totalBankValueM).reversed());
        } else {
            opps.sort(Comparator.comparingInt(AiHeuristics::threatScore).reversed());
        }
        for (Player opp : opps) {
            List<String> colorIter = new ArrayList<>(colors);
            if (profile == AiStrategyProfile.EASY) {
                Collections.shuffle(colorIter, rng);
            }
            for (String color : colorIter) {
                ActionParamContext probe = new ActionParamContext(
                        ac.getId(),
                        null,
                        opp.getPlayerId(),
                        color,
                        null,
                        null,
                        null);
                if (!ac.canPlay(bot, probe, context)) {
                    continue;
                }
                int base = RentCalculator.computeRentForColor(bot, color);
                int due = doubleRent ? base * 2 : base;
                if (profile == AiStrategyProfile.NORMAL && due > 0) {
                    int payable = opp.totalBankValueM() + opp.totalPropertyPaymentValueM();
                    if (payable < due) {
                        continue;
                    }
                }
                PlayActionRequest req = new PlayActionRequest();
                req.setActionType("ACTION");
                req.setCardId(ac.getId());
                req.setTargetPlayerId(opp.getPlayerId());
                req.setTargetColorKey(color);
                return req;
            }
        }
        return null;
    }

    private static PlayActionRequest buildDebtCollector(
            AIPlayer bot,
            GameContext context,
            ActionCard ac,
            AiStrategyProfile profile,
            Random rng) {
        List<Player> opps = opponentsExcluding(bot, context);
        if (opps.isEmpty()) {
            return null;
        }
        if (profile == AiStrategyProfile.EASY) {
            Collections.shuffle(opps, rng);
        } else if (profile == AiStrategyProfile.NORMAL) {
            opps.sort(Comparator.comparingInt(Player::totalBankValueM).reversed());
        } else {
            opps.sort(Comparator.comparingInt(AiHeuristics::threatScore).reversed());
        }
        for (Player opp : opps) {
            ActionParamContext probe = new ActionParamContext(
                    ac.getId(), null, opp.getPlayerId(), null, null, null, null);
            if (!ac.canPlay(bot, probe, context)) {
                continue;
            }
            PlayActionRequest req = new PlayActionRequest();
            req.setActionType("ACTION");
            req.setCardId(ac.getId());
            req.setTargetPlayerId(opp.getPlayerId());
            return req;
        }
        return null;
    }

    private static PlayActionRequest buildForcedDeal(
            AIPlayer bot,
            GameContext context,
            ActionCard ac,
            AiStrategyProfile profile,
            Random rng) {
        if (bot.getPropertyCardCount() == 0) {
            return null;
        }
        List<Player> opps = opponentsExcluding(bot, context);
        opps.removeIf(p -> p.getPropertyCardCount() == 0);
        if (opps.isEmpty()) {
            return null;
        }
        if (profile == AiStrategyProfile.EASY) {
            Collections.shuffle(opps, rng);
        } else if (profile == AiStrategyProfile.NORMAL) {
            opps.sort(Comparator.comparingInt(Player::totalBankValueM).reversed());
        } else {
            opps.sort(Comparator.comparingInt(AiHeuristics::threatScore).reversed());
        }
        for (Player opp : opps) {
            PropertyCard mine = pickForcedDealMine(bot, profile);
            PropertyCard theirs = pickForcedDealTheirs(opp, profile);
            if (mine == null || theirs == null) {
                continue;
            }
            ActionParamContext probe = new ActionParamContext(
                    ac.getId(),
                    null,
                    opp.getPlayerId(),
                    null,
                    theirs.getId(),
                    mine.getId(),
                    null);
            if (!ac.canPlay(bot, probe, context)) {
                continue;
            }
            PlayActionRequest req = new PlayActionRequest();
            req.setActionType("ACTION");
            req.setCardId(ac.getId());
            req.setTargetPlayerId(opp.getPlayerId());
            req.setTargetCardId(theirs.getId());
            req.setActorCardId(mine.getId());
            return req;
        }
        return null;
    }

    private static PropertyCard pickForcedDealMine(AIPlayer bot, AiStrategyProfile profile) {
        List<PropertyCard> list = new ArrayList<>(bot.getPropertyCardsView());
        if (list.isEmpty()) {
            return null;
        }
        if (profile == AiStrategyProfile.NORMAL || profile == AiStrategyProfile.HARD) {
            list.sort(Comparator.comparingInt(PayableCards::valueOf));
            return list.get(0);
        }
        return list.get(0);
    }

    private static PropertyCard pickForcedDealTheirs(Player opp, AiStrategyProfile profile) {
        List<PropertyCard> list = new ArrayList<>(opp.getPropertyCardsView());
        if (list.isEmpty()) {
            return null;
        }
        if (profile == AiStrategyProfile.NORMAL || profile == AiStrategyProfile.HARD) {
            list.sort(Comparator.comparingInt(PayableCards::valueOf).reversed());
            return list.get(0);
        }
        return list.get(0);
    }

    private static PlayActionRequest buildSimpleAction(AIPlayer bot, GameContext context, ActionCard ac) {
        ActionParamContext probe = new ActionParamContext(ac.getId(), null, null, null, null, null, null);
        if (!ac.canPlay(bot, probe, context)) {
            return null;
        }
        PlayActionRequest req = new PlayActionRequest();
        req.setActionType("ACTION");
        req.setCardId(ac.getId());
        return req;
    }

    private static PlayActionRequest buildHouse(
            AIPlayer bot,
            GameContext context,
            ActionCard ac,
            AiStrategyProfile profile,
            Random rng) {
        List<PropertyCard> cands = new ArrayList<>();
        for (PropertyCard pc : bot.getPropertyCardsView()) {
            if (pc != null && pc.getBuildingLevel() == BuildingLevel.BASE) {
                cands.add(pc);
            }
        }
        if (cands.isEmpty()) {
            return null;
        }
        if (profile == AiStrategyProfile.EASY) {
            Collections.shuffle(cands, rng);
        } else {
            cands.sort(Comparator.<PropertyCard>comparingInt(pc -> deployCompletionScore(bot, pc)).reversed());
        }
        for (PropertyCard pc : cands) {
            ActionParamContext probe = new ActionParamContext(
                    ac.getId(), null, null, null, pc.getId(), null, null);
            if (!ac.canPlay(bot, probe, context)) {
                continue;
            }
            PlayActionRequest req = new PlayActionRequest();
            req.setActionType("ACTION");
            req.setCardId(ac.getId());
            req.setActorCardId(pc.getId());
            return req;
        }
        return null;
    }

    private static PlayActionRequest buildHotel(
            AIPlayer bot,
            GameContext context,
            ActionCard ac,
            AiStrategyProfile profile,
            Random rng) {
        List<PropertyCard> cands = new ArrayList<>();
        for (PropertyCard pc : bot.getPropertyCardsView()) {
            if (pc != null && pc.getBuildingLevel() == BuildingLevel.HOUSE) {
                cands.add(pc);
            }
        }
        if (cands.isEmpty()) {
            return null;
        }
        if (profile == AiStrategyProfile.EASY) {
            Collections.shuffle(cands, rng);
        } else {
            cands.sort(Comparator.<PropertyCard>comparingInt(pc -> deployCompletionScore(bot, pc)).reversed());
        }
        for (PropertyCard pc : cands) {
            ActionParamContext probe = new ActionParamContext(
                    ac.getId(), null, null, null, pc.getId(), null, null);
            if (!ac.canPlay(bot, probe, context)) {
                continue;
            }
            PlayActionRequest req = new PlayActionRequest();
            req.setActionType("ACTION");
            req.setCardId(ac.getId());
            req.setActorCardId(pc.getId());
            return req;
        }
        return null;
    }

    // --- deploy / deposit ---

    private static boolean tryDeployWild(AIPlayer bot, AiGameBridge bridge, AiStrategyProfile profile, Random rng) {
        for (Card c : bot.getHandCardsView()) {
            if (!(c instanceof PropertyWildCard wild)) {
                continue;
            }
            String color = pickWildColor(bot, profile, rng);
            PlayActionRequest req = new PlayActionRequest();
            req.setActionType("DEPLOY");
            req.setCardId(wild.getId());
            req.setTargetColorKey(color);
            try {
                bridge.submitPlayAction(req);
                trace(profile + " DEPLOY wild color=" + color);
                return true;
            } catch (RuntimeException ex) {
                // next wild
            }
        }
        return false;
    }

    private static boolean tryDeployProperty(AIPlayer bot, AiGameBridge bridge, AiStrategyProfile profile, Random rng) {
        List<PropertyCard> order = new ArrayList<>();
        for (Card c : bot.getHandCardsView()) {
            if (c instanceof PropertyWildCard) {
                continue;
            }
            if (c instanceof PropertyCard pc) {
                order.add(pc);
            }
        }
        if (order.isEmpty()) {
            return false;
        }
        if (profile == AiStrategyProfile.EASY) {
            Collections.shuffle(order, rng);
        } else {
            order.sort(Comparator.<PropertyCard>comparingInt(pc -> deployCompletionScore(bot, pc)).reversed());
        }
        for (PropertyCard pc : order) {
            PlayActionRequest req = new PlayActionRequest();
            req.setActionType("DEPLOY");
            req.setCardId(pc.getId());
            try {
                bridge.submitPlayAction(req);
                trace(profile + " DEPLOY property " + pc.getId());
                return true;
            } catch (RuntimeException ex) {
                // next
            }
        }
        return false;
    }

    private static boolean tryDeposit(AIPlayer bot, AiGameBridge bridge) {
        for (Card c : bot.getHandCardsView()) {
            if (c instanceof ActionCard || c instanceof PropertyCard) {
                continue;
            }
            PlayActionRequest req = new PlayActionRequest();
            req.setActionType("DEPOSIT");
            req.setCardId(c.getId());
            try {
                bridge.submitPlayAction(req);
                return true;
            } catch (RuntimeException ex) {
                // next
            }
        }
        return false;
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    static final class StealTarget {
        final String ownerPlayerId;
        final String propertyCardId;

        StealTarget(String ownerPlayerId, String propertyCardId) {
            this.ownerPlayerId = ownerPlayerId;
            this.propertyCardId = propertyCardId;
        }
    }
}
