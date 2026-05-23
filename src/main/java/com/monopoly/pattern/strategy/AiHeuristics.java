package com.monopoly.pattern.strategy;

import com.monopoly.model.card.ActionCard;
import com.monopoly.model.player.AIPlayer;
import com.monopoly.model.core.AiGameBridge;
import com.monopoly.model.card.BuildingLevel;
import com.monopoly.model.card.Card;
import com.monopoly.model.core.GameContext;
import com.monopoly.model.card.PayableCards;
import com.monopoly.model.player.Player;
import com.monopoly.model.card.PropertyCard;
import com.monopoly.model.settlement.PropertySetCalculator;
import com.monopoly.model.settlement.PaymentSettlement;
import com.monopoly.model.card.PropertyWildCard;
import com.monopoly.model.effects.ActionEffectContext;
import com.monopoly.model.effects.RentEffect;
import com.monopoly.model.settlement.RentCalculator;
import com.monopoly.dto.ActionParamContext;
import com.monopoly.dto.PlayActionRequest;
import com.monopoly.pattern.singleton.GameEngineSingleton;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Shared AI heuristics for EASY/NORMAL/HARD. Flags: -Dmonopoly.ai.seed, -Dmonopoly.ai.trace.
 */
public final class AiHeuristics {

    private static final String[] ACTION_TRY_ORDER = {
            "DEAL_BREAKER",
            "RENT",
            "RENT_DUAL",
            "DOUBLE_RENT",
            "DEBT_COLLECTOR",
            "FORCED_DEAL",
            "PASS_GO",
            "BIRTHDAY",
            "HOUSE",
            "HOTEL"
    };
    private static final int HAND_LIMIT = 7;

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

    public static List<AiPlayCandidate> buildCandidates(
            AiStrategyProfile profile,
            AIPlayer bot,
            GameContext context) {
        if (bot == null || bot.getHandCardsView().isEmpty()) {
            return List.of();
        }
        GameContext ctx = context != null ? context : new GameContext();
        List<AiPlayCandidate> out = new ArrayList<>();
        int seq = 1;
        for (Card c : bot.getHandCardsView()) {
            if (c instanceof ActionCard ac) {
                List<PlayActionRequest> requests = buildActionCandidates(bot, ctx, ac, profile);
                for (PlayActionRequest req : requests) {
                    out.add(new AiPlayCandidate("c" + seq++, req, describeCandidate(bot, ctx, ac, req)));
                }
                if (shouldOfferActionDeposit(bot, ac, requests.isEmpty())) {
                    PlayActionRequest deposit = request("DEPOSIT", ac.getId());
                    out.add(new AiPlayCandidate("c" + seq++, deposit,
                            "Deposit action card " + ac.getEffectCode() + " for " + PayableCards.valueOf(ac) + "M."));
                }
                continue;
            }
            if (c instanceof PropertyWildCard wild) {
                for (String color : wildDeployColors(bot, wild, profile)) {
                    PlayActionRequest deploy = request("DEPLOY", wild.getId());
                    deploy.setTargetColorKey(color);
                    out.add(new AiPlayCandidate("c" + seq++, deploy,
                            "Deploy wild property as " + color + "."));
                }
                continue;
            }
            if (c instanceof PropertyCard pc) {
                PlayActionRequest deploy = request("DEPLOY", pc.getId());
                out.add(new AiPlayCandidate("c" + seq++, deploy,
                        "Deploy property " + normalizeColor(pc.getColorGroup())
                                + " completionScore=" + deployCompletionScore(bot, pc) + "."));
                continue;
            }
            PlayActionRequest deposit = request("DEPOSIT", c.getId());
            out.add(new AiPlayCandidate("c" + seq++, deposit,
                    "Deposit money/bankable card for " + PayableCards.valueOf(c) + "M."));
        }
        return dedupe(out);
    }

    public static AiResponseDecision chooseResponse(
            AIPlayer bot,
            GameContext context,
            boolean counterRole) {
        if (bot == null || context == null || context.getResponseState() == null) {
            return AiResponseDecision.pass();
        }
        ActionCard waiver = firstJustSayNo(bot);
        if (waiver == null) {
            return AiResponseDecision.unavailable("no Just Say No in hand");
        }
        int impact = estimateResponseImpact(bot, context, counterRole);
        PlayActionRequest req = request("ACTION", waiver.getId());
        req.setActingPlayerId(bot.getPlayerId());
        int threshold = counterRole ? 5 : 3;
        if (impact < threshold) {
            return AiResponseDecision.hold(req, "Hold Just Say No, estimated impact " + impact + "M.");
        }
        return AiResponseDecision.play(req, "Protect/counter high-impact effect, estimated impact " + impact + "M.");
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
            case "RENT_DUAL" -> buildRentDual(bot, context, ac, profile, rng);
            case "DOUBLE_RENT" -> buildDoubleRent(bot, context, ac);
            case "DEBT_COLLECTOR" -> buildDebtCollector(bot, context, ac, profile, rng);
            case "FORCED_DEAL" -> buildForcedDeal(bot, context, ac, profile, rng);
            case "DEAL_BREAKER" -> buildDealBreaker(bot, context, ac, profile, rng);
            case "PASS_GO" -> buildSimpleAction(bot, context, ac);
            case "BIRTHDAY" -> buildBirthday(bot, context, ac);
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
            return bestRentLikeRequest(bot, context, ac, colors, opps, doubleRent);
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
                if (expectedAutomaticPayment(opp, due) <= 0) {
                    continue;
                }
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

    private static PlayActionRequest buildRentDual(
            AIPlayer bot,
            GameContext context,
            ActionCard ac,
            AiStrategyProfile profile,
            Random rng) {
        List<String> colors = new ArrayList<>(ac.getRentPaletteView());
        List<Player> opps = opponentsExcluding(bot, context);
        if (colors.isEmpty() || opps.isEmpty()) {
            return null;
        }
        if (profile == AiStrategyProfile.EASY) {
            Collections.shuffle(colors, rng);
            Collections.shuffle(opps, rng);
        } else if (profile == AiStrategyProfile.NORMAL) {
            opps.sort(Comparator.comparingInt(Player::totalBankValueM).reversed());
        } else {
            return bestRentLikeRequest(bot, context, ac, colors, opps, false);
        }
        for (Player opp : opps) {
            for (String color : colors) {
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
                int due = RentCalculator.computeRentForColor(bot, color);
                if (expectedAutomaticPayment(opp, due) <= 0) {
                    continue;
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

    private static PlayActionRequest buildDoubleRent(AIPlayer bot, GameContext context, ActionCard ac) {
        if (context == null || context.remainingTurnActions() < 2) {
            return null;
        }
        if (!hasFollowUpRentCandidate(bot, context)) {
            return null;
        }
        ActionParamContext probe = new ActionParamContext(
                ac.getId(), null, null, null, null, null, null);
        if (!ac.canPlay(bot, probe, context)) {
            return null;
        }
        return request("ACTION", ac.getId());
    }

    private static boolean hasFollowUpRentCandidate(AIPlayer bot, GameContext context) {
        if (bot == null) {
            return false;
        }
        for (Card c : bot.getHandCardsView()) {
            if (!(c instanceof ActionCard ac)) {
                continue;
            }
            String code = trim(ac.getEffectCode()).toUpperCase(Locale.ROOT);
            List<PlayActionRequest> out = new ArrayList<>();
            if ("RENT".equals(code)) {
                addRentCandidates(bot, context, ac, out, false);
            } else if ("RENT_DUAL".equals(code)) {
                addRentDualCandidates(bot, context, ac, out);
            }
            if (!out.isEmpty()) {
                return true;
            }
        }
        return false;
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
        opps.removeIf(opp -> payableM(opp) < 5);
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
        List<ForcedDealOption> options = forcedDealOptions(bot, context, ac);
        if (profile == AiStrategyProfile.EASY) {
            Collections.shuffle(options, rng);
        } else {
            options.sort(Comparator
                    .comparingInt(ForcedDealOption::score)
                    .reversed());
        }
        for (ForcedDealOption option : options) {
            ActionParamContext probe = new ActionParamContext(
                    ac.getId(),
                    null,
                    option.targetPlayerId(),
                    null,
                    option.take().getId(),
                    option.give().getId(),
                    null);
            if (!ac.canPlay(bot, probe, context)) {
                continue;
            }
            PlayActionRequest req = new PlayActionRequest();
            req.setActionType("ACTION");
            req.setCardId(ac.getId());
            req.setTargetPlayerId(option.targetPlayerId());
            req.setTargetCardId(option.take().getId());
            req.setActorCardId(option.give().getId());
            return req;
        }
        return null;
    }

    private static PlayActionRequest buildDealBreaker(
            AIPlayer bot,
            GameContext context,
            ActionCard ac,
            AiStrategyProfile profile,
            Random rng) {
        List<DealBreakerTarget> targets = dealBreakerTargets(bot, context);
        if (targets.isEmpty()) {
            return null;
        }
        if (profile == AiStrategyProfile.EASY) {
            Collections.shuffle(targets, rng);
        } else {
            targets.sort(Comparator
                    .comparingInt(DealBreakerTarget::score)
                    .reversed());
        }
        for (DealBreakerTarget target : targets) {
            ActionParamContext probe = new ActionParamContext(
                    ac.getId(), null, target.playerId(), target.colorKey(), null, null, null);
            if (!ac.canPlay(bot, probe, context)) {
                continue;
            }
            PlayActionRequest req = request("ACTION", ac.getId());
            req.setTargetPlayerId(target.playerId());
            req.setTargetColorKey(target.colorKey());
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

    private static PlayActionRequest buildBirthday(AIPlayer bot, GameContext context, ActionCard ac) {
        if (birthdayCollectibleM(bot, context) <= 0) {
            return null;
        }
        return buildSimpleAction(bot, context, ac);
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
            String color = pickWildDeployColor(bot, wild, profile, rng);
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

    private static String pickWildDeployColor(
            AIPlayer bot,
            PropertyWildCard wild,
            AiStrategyProfile profile,
            Random rng) {
        String assigned = trim(normalizeColor(wild.getAssignedColorKey()));
        if (!assigned.isEmpty()) {
            return assigned;
        }
        List<String> printed = new ArrayList<>(wild.getPrintedColorPairView());
        if (printed.isEmpty()) {
            return pickWildColor(bot, profile, rng);
        }
        if (profile == AiStrategyProfile.EASY) {
            Collections.shuffle(printed, rng);
            return printed.get(0);
        }
        String best = printed.get(0);
        int bestScore = -1;
        for (String color : printed) {
            String key = color == null ? "" : color.trim().toUpperCase(Locale.ROOT);
            int eff = PropertySetCalculator.effectiveCountForColor(bot.getPropertyCardsView(), key);
            int need = PropertySetCalculator.REQUIRED_BY_COLOR.getOrDefault(key, 3);
            int score = eff * 100 / Math.max(1, need);
            if (score > bestScore) {
                bestScore = score;
                best = key;
            }
        }
        return best;
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
            if (c instanceof PropertyCard) {
                continue;
            }
            if (c instanceof ActionCard ac && !shouldOfferActionDeposit(bot, ac, true)) {
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

    private static boolean shouldOfferActionDeposit(
            AIPlayer bot,
            ActionCard ac,
            boolean noPlayableActionUse) {
        String effect = trim(ac.getEffectCode()).toUpperCase(Locale.ROOT);
        if ("RENT_WAIVER".equals(effect)) {
            return false;
        }
        if (!isProtectedActionForDeposit(effect)) {
            return true;
        }
        if (!noPlayableActionUse) {
            return false;
        }
        return bot != null
                && bot.getHandCardCount() > HAND_LIMIT
                && !hasLowerRiskDepositCard(bot, ac);
    }

    private static boolean isProtectedActionForDeposit(String effect) {
        return switch (effect) {
            case "DEAL_BREAKER", "STEAL_PROPERTY", "FORCED_DEAL" -> true;
            default -> false;
        };
    }

    private static boolean hasLowerRiskDepositCard(AIPlayer bot, Card excluding) {
        if (bot == null) {
            return false;
        }
        for (Card c : bot.getHandCardsView()) {
            if (c == null || c == excluding || c instanceof PropertyCard) {
                continue;
            }
            if (!(c instanceof ActionCard ac)) {
                return true;
            }
            String effect = trim(ac.getEffectCode()).toUpperCase(Locale.ROOT);
            if (!"RENT_WAIVER".equals(effect) && !isProtectedActionForDeposit(effect)) {
                return true;
            }
        }
        return false;
    }

    private static List<PlayActionRequest> buildActionCandidates(
            AIPlayer bot,
            GameContext context,
            ActionCard ac,
            AiStrategyProfile profile) {
        String code = trim(ac.getEffectCode()).toUpperCase(Locale.ROOT);
        List<PlayActionRequest> out = new ArrayList<>();
        switch (code) {
            case "RENT" -> addRentCandidates(bot, context, ac, out, false);
            case "DOUBLE_RENT" -> {
                PlayActionRequest req = buildDoubleRent(bot, context, ac);
                if (req != null) {
                    out.add(req);
                }
            }
            case "RENT_DUAL" -> addRentDualCandidates(bot, context, ac, out);
            case "DEBT_COLLECTOR" -> addDebtCollectorCandidates(bot, context, ac, out);
            case "STEAL_PROPERTY" -> addStealCandidates(bot, context, ac, out);
            case "FORCED_DEAL" -> addForcedDealCandidates(bot, context, ac, out);
            case "DEAL_BREAKER" -> addDealBreakerCandidates(bot, context, ac, out);
            case "PASS_GO" -> {
                PlayActionRequest req = buildSimpleAction(bot, context, ac);
                if (req != null) {
                    out.add(req);
                }
            }
            case "BIRTHDAY" -> {
                PlayActionRequest req = buildBirthday(bot, context, ac);
                if (req != null) {
                    out.add(req);
                }
            }
            case "HOUSE" -> addHouseCandidates(bot, context, ac, out);
            case "HOTEL" -> addHotelCandidates(bot, context, ac, out);
            default -> {
                PlayActionRequest req = buildSimpleAction(bot, context, ac);
                if (req != null) {
                    out.add(req);
                }
            }
        }
        return out;
    }

    private static void addRentCandidates(
            AIPlayer bot,
            GameContext context,
            ActionCard ac,
            List<PlayActionRequest> out,
            boolean doubleRent) {
        for (Player opp : opponentsExcluding(bot, context)) {
            for (String color : PropertySetCalculator.REQUIRED_BY_COLOR.keySet()) {
                ActionParamContext probe = new ActionParamContext(
                        ac.getId(), null, opp.getPlayerId(), color, null, null, null);
                if (!ac.canPlay(bot, probe, context)) {
                    continue;
                }
                int base = RentCalculator.computeRentForColor(bot, color);
                int due = doubleRent ? base * 2 : base;
                if (expectedAutomaticPayment(opp, due) <= 0) {
                    continue;
                }
                PlayActionRequest req = request("ACTION", ac.getId());
                req.setTargetPlayerId(opp.getPlayerId());
                req.setTargetColorKey(color);
                out.add(req);
            }
        }
    }

    private static void addRentDualCandidates(
            AIPlayer bot,
            GameContext context,
            ActionCard ac,
            List<PlayActionRequest> out) {
        if (ac.isRentDualChargesEachOtherPlayer()) {
            for (String color : ac.getRentPaletteView()) {
                ActionParamContext probe = new ActionParamContext(
                        ac.getId(), null, null, color, null, null, null);
                if (!ac.canPlay(bot, probe, context)) {
                    continue;
                }
                int due = RentCalculator.computeRentForColor(bot, color);
                if (rentExpectedPaidForAllOpponents(bot, context, due) <= 0) {
                    continue;
                }
                PlayActionRequest req = request("ACTION", ac.getId());
                req.setTargetColorKey(color);
                out.add(req);
            }
            return;
        }
        for (Player opp : opponentsExcluding(bot, context)) {
            for (String color : ac.getRentPaletteView()) {
                ActionParamContext probe = new ActionParamContext(
                        ac.getId(), null, opp.getPlayerId(), color, null, null, null);
                if (!ac.canPlay(bot, probe, context)) {
                    continue;
                }
                int due = RentCalculator.computeRentForColor(bot, color);
                if (expectedAutomaticPayment(opp, due) <= 0) {
                    continue;
                }
                PlayActionRequest req = request("ACTION", ac.getId());
                req.setTargetPlayerId(opp.getPlayerId());
                req.setTargetColorKey(color);
                out.add(req);
            }
        }
    }

    private static void addDebtCollectorCandidates(
            AIPlayer bot,
            GameContext context,
            ActionCard ac,
            List<PlayActionRequest> out) {
        for (Player opp : opponentsExcluding(bot, context)) {
            if (payableM(opp) < 5) {
                continue;
            }
            ActionParamContext probe = new ActionParamContext(
                    ac.getId(), null, opp.getPlayerId(), null, null, null, null);
            if (!ac.canPlay(bot, probe, context)) {
                continue;
            }
            PlayActionRequest req = request("ACTION", ac.getId());
            req.setTargetPlayerId(opp.getPlayerId());
            out.add(req);
        }
    }

    private static int payableM(Player p) {
        return p == null ? 0 : p.totalBankValueM() + p.totalPropertyPaymentValueM();
    }

    private static void addStealCandidates(
            AIPlayer bot,
            GameContext context,
            ActionCard ac,
            List<PlayActionRequest> out) {
        for (Player opp : opponentsExcluding(bot, context)) {
            for (PropertyCard pc : opp.getPropertyCardsView()) {
                ActionParamContext probe = new ActionParamContext(
                        ac.getId(), null, opp.getPlayerId(), null, pc.getId(), null, "PROPERTY");
                if (!ac.canPlay(bot, probe, context)) {
                    continue;
                }
                PlayActionRequest req = request("ACTION", ac.getId());
                req.setTargetPlayerId(opp.getPlayerId());
                req.setTargetCardId(pc.getId());
                req.setTargetZone("PROPERTY");
                out.add(req);
            }
        }
    }

    private static void addForcedDealCandidates(
            AIPlayer bot,
            GameContext context,
            ActionCard ac,
            List<PlayActionRequest> out) {
        if (bot.getPropertyCardCount() == 0) {
            return;
        }
        for (ForcedDealOption option : forcedDealOptions(bot, context, ac)) {
            PlayActionRequest req = request("ACTION", ac.getId());
            req.setTargetPlayerId(option.targetPlayerId());
            req.setTargetCardId(option.take().getId());
            req.setActorCardId(option.give().getId());
            out.add(req);
        }
    }

    private static void addDealBreakerCandidates(
            AIPlayer bot,
            GameContext context,
            ActionCard ac,
            List<PlayActionRequest> out) {
        for (DealBreakerTarget target : dealBreakerTargets(bot, context)) {
            ActionParamContext probe = new ActionParamContext(
                    ac.getId(), null, target.playerId(), target.colorKey(), null, null, null);
            if (!ac.canPlay(bot, probe, context)) {
                continue;
            }
            PlayActionRequest req = request("ACTION", ac.getId());
            req.setTargetPlayerId(target.playerId());
            req.setTargetColorKey(target.colorKey());
            out.add(req);
        }
    }

    private static void addHouseCandidates(
            AIPlayer bot,
            GameContext context,
            ActionCard ac,
            List<PlayActionRequest> out) {
        for (PropertyCard pc : bot.getPropertyCardsView()) {
            ActionParamContext probe = new ActionParamContext(
                    ac.getId(), null, null, null, pc.getId(), pc.getId(), null);
            if (!ac.canPlay(bot, probe, context)) {
                continue;
            }
            PlayActionRequest req = request("ACTION", ac.getId());
            req.setActorCardId(pc.getId());
            out.add(req);
        }
    }

    private static void addHotelCandidates(
            AIPlayer bot,
            GameContext context,
            ActionCard ac,
            List<PlayActionRequest> out) {
        addHouseCandidates(bot, context, ac, out);
    }

    private static PlayActionRequest bestRentLikeRequest(
            AIPlayer bot,
            GameContext context,
            ActionCard ac,
            List<String> colors,
            List<Player> opponents,
            boolean doubleRent) {
        PlayActionRequest best = null;
        int bestScore = Integer.MIN_VALUE;
        for (Player opp : opponents) {
            for (String color : colors) {
                ActionParamContext probe = new ActionParamContext(
                        ac.getId(), null, opp.getPlayerId(), color, null, null, null);
                if (!ac.canPlay(bot, probe, context)) {
                    continue;
                }
                int base = RentCalculator.computeRentForColor(bot, color);
                int due = doubleRent ? base * 2 : base;
                if (due <= 0) {
                    continue;
                }
                int expectedPaid = expectedAutomaticPayment(opp, due);
                if (expectedPaid <= 0) {
                    continue;
                }
                int score = expectedPaid * 100 + threatScore(opp);
                if (score <= bestScore) {
                    continue;
                }
                PlayActionRequest req = request("ACTION", ac.getId());
                req.setTargetPlayerId(opp.getPlayerId());
                req.setTargetColorKey(color);
                best = req;
                bestScore = score;
            }
        }
        return best;
    }

    private static List<DealBreakerTarget> dealBreakerTargets(AIPlayer bot, GameContext context) {
        List<DealBreakerTarget> targets = new ArrayList<>();
        for (Player p : opponentsExcluding(bot, context)) {
            for (String color : PropertySetCalculator.REQUIRED_BY_COLOR.keySet()) {
                if (!PropertySetCalculator.hasCompleteSetForColor(p.getPropertyCardsView(), color)) {
                    continue;
                }
                int value = 0;
                for (PropertyCard pc : p.getPropertyCardsView()) {
                    if (color.equals(propertyColorForSet(pc))) {
                        value += Math.max(1, PayableCards.valueOf(pc));
                    }
                }
                targets.add(new DealBreakerTarget(p.getPlayerId(), color, value + threatScore(p)));
            }
        }
        return targets;
    }

    private static String propertyColorForSet(PropertyCard pc) {
        if (pc == null) {
            return null;
        }
        if (pc instanceof PropertyWildCard wild) {
            return normalizeColor(wild.getAssignedColorKey());
        }
        return normalizeColor(pc.getColorGroup());
    }

    private static ActionCard firstJustSayNo(AIPlayer bot) {
        for (Card c : bot.getHandCardsView()) {
            if (c instanceof ActionCard ac
                    && "RENT_WAIVER".equalsIgnoreCase(trim(ac.getEffectCode()))) {
                return ac;
            }
        }
        return null;
    }

    private static int estimateResponseImpact(AIPlayer bot, GameContext context, boolean counterRole) {
        int best = 0;
        for (com.monopoly.model.effects.EffectStackEntry entry : context.getEffectStackView()) {
            if (entry == null) {
                continue;
            }
            if (entry.isRentLike()) {
                String relevant = counterRole ? entry.getActorPlayerId() : entry.getTenantPlayerId();
                if (bot.getPlayerId().equals(relevant)) {
                    best = Math.max(best, entry.getAmountDue());
                }
            }
            if (entry.isActionLike()) {
                String relevant = counterRole ? entry.getActorPlayerId() : entry.getTenantPlayerId();
                if (bot.getPlayerId().equals(relevant)) {
                    best = Math.max(best, 5);
                }
            }
        }
        return best;
    }

    private static String describeCandidate(
            AIPlayer bot,
            GameContext context,
            ActionCard ac,
            PlayActionRequest req) {
        String effect = trim(ac.getEffectCode()).toUpperCase(Locale.ROOT);
        if ("RENT".equals(effect) || "RENT_DUAL".equals(effect)) {
            int due = estimateRentDue(bot, context, req);
            if (context.hasPendingDoubleRentFor(bot.getPlayerId())) {
                due *= 2;
            }
            String target = req.getTargetPlayerId() == null ? "all opponents" : req.getTargetPlayerId();
            int expectedPaid = rentExpectedPaid(bot, context, req, due);
            return "Action " + effect + " target=" + target
                    + " color=" + req.getTargetColorKey()
                    + " due=" + due + "M expectedPaid=" + expectedPaid + "M.";
        }
        if ("DEBT_COLLECTOR".equals(effect)) {
            Player target = context.findPlayer(req.getTargetPlayerId());
            int expectedPaid = expectedAutomaticPayment(target, 5);
            return "Action DEBT_COLLECTOR target=" + req.getTargetPlayerId()
                    + " due=5M expectedPaid=" + expectedPaid + "M.";
        }
        if ("BIRTHDAY".equals(effect)) {
            return "Action BIRTHDAY expectedPaid=" + birthdayCollectibleM(bot, context) + "M.";
        }
        if ("STEAL_PROPERTY".equals(effect)) {
            return "Action STEAL_PROPERTY target=" + req.getTargetPlayerId()
                    + " card=" + req.getTargetCardId() + ".";
        }
        if ("FORCED_DEAL".equals(effect)) {
            ForcedDealOption option = forcedDealOptionFor(bot, context, req);
            if (option != null) {
                return "Action FORCED_DEAL netScore=" + option.score()
                        + " materialGain=" + option.materialGain()
                        + " completionGain=" + option.completionGain()
                        + " oppCompletionLoss=" + option.oppCompletionLoss()
                        + " target=" + req.getTargetPlayerId()
                        + " take=" + option.take().getId() + ":" + option.takeColor()
                        + " give=" + option.give().getId() + ":" + option.giveColor()
                        + " takeValue=" + option.takeValueM() + "M"
                        + " giveValue=" + option.giveValueM() + "M.";
            }
            return "Action FORCED_DEAL target=" + req.getTargetPlayerId()
                    + " take=" + req.getTargetCardId() + " give=" + req.getActorCardId() + ".";
        }
        if ("DEAL_BREAKER".equals(effect)) {
            return "Action DEAL_BREAKER target=" + req.getTargetPlayerId()
                    + " completeSet=" + req.getTargetColorKey() + ".";
        }
        return "Action " + effect + ".";
    }

    private static int estimateRentDue(AIPlayer bot, GameContext context, PlayActionRequest req) {
        ActionEffectContext ctx = ActionEffectContext
                .builder(bot, GameEngineSingleton.getInstance(), context.getPlayers())
                .target(context.findPlayer(req.getTargetPlayerId()))
                .colorKey(req.getTargetColorKey())
                .build();
        RentEffect.DueResult due = req.getTargetPlayerId() == null
                ? RentEffect.computeDueLandlordColorOnly(ctx)
                : RentEffect.computeDue(ctx);
        return due.isOk() ? due.getAmountDue() : 0;
    }

    private static int birthdayCollectibleM(AIPlayer bot, GameContext context) {
        int total = 0;
        for (Player opp : opponentsExcluding(bot, context)) {
            total += expectedAutomaticPayment(opp, 2);
        }
        return total;
    }

    private static int rentExpectedPaid(
            AIPlayer bot,
            GameContext context,
            PlayActionRequest req,
            int due) {
        if (req.getTargetPlayerId() != null) {
            return expectedAutomaticPayment(context.findPlayer(req.getTargetPlayerId()), due);
        }
        int total = 0;
        for (Player opp : opponentsExcluding(bot, context)) {
            total += expectedAutomaticPayment(opp, due);
        }
        return total;
    }

    private static int rentExpectedPaidForAllOpponents(
            AIPlayer bot,
            GameContext context,
            int due) {
        int total = 0;
        for (Player opp : opponentsExcluding(bot, context)) {
            total += expectedAutomaticPayment(opp, due);
        }
        return total;
    }

    private static int expectedAutomaticPayment(Player payer, int amountDue) {
        return PaymentSettlement.estimateAutomaticAmountPaid(payer, amountDue);
    }

    private static List<ForcedDealOption> forcedDealOptions(
            AIPlayer bot,
            GameContext context,
            ActionCard ac) {
        List<ForcedDealOption> options = new ArrayList<>();
        if (bot == null || context == null || ac == null || bot.getPropertyCardCount() == 0) {
            return options;
        }
        for (Player opp : opponentsExcluding(bot, context)) {
            for (PropertyCard theirs : opp.getPropertyCardsView()) {
                for (PropertyCard mine : bot.getPropertyCardsView()) {
                    ActionParamContext probe = new ActionParamContext(
                            ac.getId(), null, opp.getPlayerId(), null, theirs.getId(), mine.getId(), null);
                    if (!ac.canPlay(bot, probe, context)) {
                        continue;
                    }
                    ForcedDealOption option = scoreForcedDeal(bot, opp, theirs, mine);
                    if (option.materialGain() > 0
                            && (option.completionGain() > 0 || option.oppCompletionLoss() > 0)) {
                        options.add(option);
                    }
                }
            }
        }
        options.sort(Comparator
                .comparingInt(ForcedDealOption::score)
                .reversed());
        return options;
    }

    private static ForcedDealOption forcedDealOptionFor(
            AIPlayer bot,
            GameContext context,
            PlayActionRequest req) {
        if (req == null) {
            return null;
        }
        Player target = context == null ? null : context.findPlayer(req.getTargetPlayerId());
        PropertyCard take = findPropertyById(target, req.getTargetCardId());
        PropertyCard give = findPropertyById(bot, req.getActorCardId());
        if (target == null || take == null || give == null) {
            return null;
        }
        return scoreForcedDeal(bot, target, take, give);
    }

    private static PropertyCard findPropertyById(Player player, String cardId) {
        if (player == null || cardId == null || cardId.isBlank()) {
            return null;
        }
        for (PropertyCard card : player.getPropertyCardsView()) {
            if (card != null && cardId.equals(card.getId())) {
                return card;
            }
        }
        return null;
    }

    private static ForcedDealOption scoreForcedDeal(
            AIPlayer bot,
            Player target,
            PropertyCard take,
            PropertyCard give) {
        String takeColor = propertyColorForSet(take);
        String giveColor = propertyColorForSet(give);
        int takeValue = Math.max(1, PayableCards.valueOf(take));
        int giveValue = Math.max(1, PayableCards.valueOf(give));
        int completionGain = completionDeltaAfterTrade(bot, takeColor, giveColor);
        int oppCompletionLoss = -completionDeltaAfterTrade(target, giveColor, takeColor);
        int valueGain = takeValue - giveValue;
        int materialGain = (completionGain * 90)
                + (oppCompletionLoss * 55)
                + (valueGain * 12);
        int score = (completionGain * 90)
                + (oppCompletionLoss * 55)
                + (valueGain * 12)
                + threatScore(target);
        return new ForcedDealOption(
                target.getPlayerId(),
                take,
                give,
                takeColor,
                giveColor,
                takeValue,
                giveValue,
                completionGain,
                oppCompletionLoss,
                materialGain,
                score);
    }

    private static int completionDeltaAfterTrade(Player player, String addedColor, String removedColor) {
        if (player == null) {
            return 0;
        }
        List<String> colors = new ArrayList<>();
        if (addedColor != null) {
            colors.add(addedColor);
        }
        if (removedColor != null && !colors.contains(removedColor)) {
            colors.add(removedColor);
        }
        int before = 0;
        int after = 0;
        for (String color : colors) {
            int need = PropertySetCalculator.REQUIRED_BY_COLOR.getOrDefault(color, 3);
            int count = PropertySetCalculator.effectiveCountForColor(player.getPropertyCardsView(), color);
            int afterCount = count;
            if (color.equals(addedColor)) {
                afterCount++;
            }
            if (color.equals(removedColor)) {
                afterCount--;
            }
            before += completionPoints(count, need);
            after += completionPoints(Math.max(0, afterCount), need);
        }
        return after - before;
    }

    private static int completionPoints(int count, int need) {
        if (need <= 0) {
            return 0;
        }
        if (count >= need) {
            return 300 + (count - need) * 10;
        }
        return count * 100 / need;
    }

    private static List<String> wildDeployColors(AIPlayer bot, PropertyWildCard wild, AiStrategyProfile profile) {
        String assigned = trim(normalizeColor(wild.getAssignedColorKey()));
        if (!assigned.isEmpty()) {
            return List.of(assigned);
        }
        List<String> colors = new ArrayList<>(wild.getPrintedColorPairView());
        if (colors.isEmpty()) {
            colors.addAll(PropertySetCalculator.REQUIRED_BY_COLOR.keySet());
        }
        colors.replaceAll(AiHeuristics::normalizeColor);
        if (profile == AiStrategyProfile.EASY) {
            return colors;
        }
        colors.sort(Comparator
                .comparingInt((String color) -> {
                    int eff = PropertySetCalculator.effectiveCountForColor(bot.getPropertyCardsView(), color);
                    int need = PropertySetCalculator.REQUIRED_BY_COLOR.getOrDefault(color, 3);
                    return eff * 100 / Math.max(1, need);
                })
                .reversed());
        return colors;
    }

    private static List<AiPlayCandidate> dedupe(List<AiPlayCandidate> candidates) {
        Map<String, AiPlayCandidate> byKey = new LinkedHashMap<>();
        for (AiPlayCandidate c : candidates) {
            byKey.putIfAbsent(requestKey(c.request()), c);
        }
        List<AiPlayCandidate> out = new ArrayList<>();
        int seq = 1;
        for (AiPlayCandidate c : byKey.values()) {
            out.add(new AiPlayCandidate("c" + seq++, c.request(), c.summary()));
        }
        return out;
    }

    private static String requestKey(PlayActionRequest req) {
        return String.join("|",
                trim(req.getActionType()),
                trim(req.getCardId()),
                trim(req.getTargetPlayerId()),
                trim(req.getTargetColorKey()),
                trim(req.getTargetCardId()),
                trim(req.getActorCardId()),
                trim(req.getTargetZone()));
    }

    private static PlayActionRequest request(String actionType, String cardId) {
        PlayActionRequest req = new PlayActionRequest();
        req.setActionType(actionType);
        req.setCardId(cardId);
        return req;
    }

    private static String normalizeColor(String color) {
        return color == null ? null : color.trim().toUpperCase(Locale.ROOT);
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

    private record DealBreakerTarget(String playerId, String colorKey, int score) {
    }

    private record ForcedDealOption(
            String targetPlayerId,
            PropertyCard take,
            PropertyCard give,
            String takeColor,
            String giveColor,
            int takeValueM,
            int giveValueM,
            int completionGain,
            int oppCompletionLoss,
            int materialGain,
            int score) {
    }

    public record AiPlayCandidate(String id, PlayActionRequest request, String summary) {
    }

    public record AiResponseDecision(
            boolean playWaiver,
            PlayActionRequest request,
            String reason,
            boolean modelWorthAsking) {
        static AiResponseDecision pass() {
            return new AiResponseDecision(false, null, "pass", false);
        }

        static AiResponseDecision unavailable(String reason) {
            return new AiResponseDecision(false, null, reason, false);
        }

        static AiResponseDecision hold(PlayActionRequest request, String reason) {
            return new AiResponseDecision(false, request, reason, true);
        }

        static AiResponseDecision play(PlayActionRequest request, String reason) {
            return new AiResponseDecision(true, request, reason, true);
        }
    }
}
