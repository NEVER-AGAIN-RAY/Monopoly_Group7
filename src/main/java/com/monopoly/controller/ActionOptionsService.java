package com.monopoly.controller;

import com.google.gson.JsonObject;
import com.monopoly.dto.ActionOptionRow;
import com.monopoly.dto.ActionOptionsResult;
import com.monopoly.model.card.ActionCard;
import com.monopoly.model.card.BuildingLevel;
import com.monopoly.model.card.Card;
import com.monopoly.model.card.PropertyCard;
import com.monopoly.model.card.PropertyWildCard;
import com.monopoly.model.effects.ActionEffectContext;
import com.monopoly.model.effects.DoubleRentEffect;
import com.monopoly.model.effects.RentEffect;
import com.monopoly.model.player.Player;
import com.monopoly.model.settlement.BuildingPlacementRules;
import com.monopoly.model.settlement.PropertySetCalculator;
import com.monopoly.model.settlement.PropertyStealRules;
import com.monopoly.pattern.singleton.GameEngineSingleton;
import com.monopoly.presentation.HandCardJson;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 为当前玩家打出的行动牌生成可选操作列表（供客户端点选，无需手填 id）。
 */
public final class ActionOptionsService {

    private static final int MAX_FORCED_DEAL = 72;

    private ActionOptionsService() {
    }

    public static ActionOptionsResult build(
            Player actor,
            ActionCard actionCard,
            List<Player> allPlayers,
            GameEngineSingleton engine) {
        ActionOptionsResult out = new ActionOptionsResult();
        if (actor == null || actionCard == null || allPlayers == null || engine == null) {
            out.setOk(false);
            out.setError("参数无效");
            return out;
        }
        String ec = actionCard.getEffectCode() == null ? "" : actionCard.getEffectCode().trim().toUpperCase(Locale.ROOT);
        out.setEffectCode(ec);
        out.setOk(true);

        List<Player> others = new ArrayList<>();
        for (Player p : allPlayers) {
            if (p != null && p != actor) {
                others.add(p);
            }
        }

        switch (ec) {
            case "RENT" -> buildRentOptions(actor, others, allPlayers, engine, out, false);
            case "DOUBLE_RENT" -> buildRentOptions(actor, others, allPlayers, engine, out, true);
            case "RENT_DUAL" -> buildRentDualOptions(actor, actionCard, others, allPlayers, engine, out);
            case "DEBT_COLLECTOR" -> buildDebtOptions(others, out);
            case "STEAL_PROPERTY" -> buildStealOptions(actor, others, out);
            case "FORCED_DEAL" -> buildForcedDealOptions(actor, others, out);
            case "DEAL_BREAKER" -> buildDealBreakerOptions(others, out);
            case "HOUSE" -> buildHouseOptions(actor, out);
            case "HOTEL" -> buildHotelOptions(actor, out);
            case "PASS_GO", "BIRTHDAY", "RENT_WAIVER" -> out.addOption(new ActionOptionRow(
                    "直接打出（无需选择目标）", null, null, null, null, null));
            default -> out.addOption(new ActionOptionRow(
                    "直接打出", null, null, null, null, null));
        }
        return out;
    }

    private static void buildRentDualOptions(
            Player actor,
            ActionCard card,
            List<Player> others,
            List<Player> allPlayers,
            GameEngineSingleton engine,
            ActionOptionsResult out) {
        if (card.isRentDualChargesEachOtherPlayer()) {
            buildRentDualAllOthersOptions(actor, card, others, allPlayers, engine, out);
        } else {
            buildRentDualOneVsOneOptions(actor, card, others, allPlayers, engine, out);
        }
    }

    /** 房规：双色全员依次收租。 */
    private static void buildRentDualAllOthersOptions(
            Player actor,
            ActionCard card,
            List<Player> others,
            List<Player> allPlayers,
            GameEngineSingleton engine,
            ActionOptionsResult out) {
        if (others.isEmpty()) {
            out.setOk(false);
            out.setError("没有其他玩家可收租。");
            return;
        }
        String paletteLabel = String.join(" / ", card.getRentPaletteView());
        for (String color : card.getRentPaletteView()) {
            if (!PropertySetCalculator.hasCompleteSetForColor(actor.getPropertyCardsView(), color)) {
                continue;
            }
            ActionEffectContext ctx = ActionEffectContext
                    .builder(actor, engine, Collections.unmodifiableList(allPlayers))
                    .target(null)
                    .colorKey(color)
                    .build();
            RentEffect.DueResult dueR = RentEffect.computeDueLandlordColorOnly(ctx);
            if (!dueR.isOk()) {
                continue;
            }
            int due = dueR.getAmountDue();
            String label = "双色收租（" + paletteLabel + "）选色 " + color
                    + " — 其余每名玩家依次付约 " + due + "M（每人单独可打免租）";
            out.addOption(new ActionOptionRow(label, null, color, null, null, null, true));
        }
        if (out.getOptions().isEmpty()) {
            out.setOk(false);
            out.setError("卡面色组中暂无你已凑齐完整套的颜色。");
        }
    }

    /** 实体默认：双色租金 1 对 1。 */
    private static void buildRentDualOneVsOneOptions(
            Player actor,
            ActionCard card,
            List<Player> others,
            List<Player> allPlayers,
            GameEngineSingleton engine,
            ActionOptionsResult out) {
        if (others.isEmpty()) {
            out.setOk(false);
            out.setError("没有其他玩家可收租。");
            return;
        }
        String paletteLabel = String.join(" / ", card.getRentPaletteView());
        for (String color : card.getRentPaletteView()) {
            if (!PropertySetCalculator.hasCompleteSetForColor(actor.getPropertyCardsView(), color)) {
                continue;
            }
            for (Player tenant : others) {
                ActionEffectContext ctx = ActionEffectContext
                        .builder(actor, engine, Collections.unmodifiableList(allPlayers))
                        .target(tenant)
                        .colorKey(color)
                        .build();
                RentEffect.DueResult dueR = RentEffect.computeDue(ctx);
                if (!dueR.isOk()) {
                    continue;
                }
                int due = dueR.getAmountDue();
                String label = "双色收租（" + paletteLabel + "）" + color + " → "
                        + tenant.getDisplayName() + "（应付约 " + due + "M）";
                out.addOption(new ActionOptionRow(label, tenant.getPlayerId(), color, null, null, null, false));
            }
        }
        if (out.getOptions().isEmpty()) {
            out.setOk(false);
            out.setError("卡面色组中暂无你已凑齐完整套的颜色与对手组合。");
        }
    }

    private static void buildRentOptions(
            Player actor,
            List<Player> others,
            List<Player> allPlayers,
            GameEngineSingleton engine,
            ActionOptionsResult out,
            boolean doubled) {
        for (String color : PropertySetCalculator.REQUIRED_BY_COLOR.keySet()) {
            if (!PropertySetCalculator.hasCompleteSetForColor(actor.getPropertyCardsView(), color)) {
                continue;
            }
            for (Player tenant : others) {
                ActionEffectContext ctx = ActionEffectContext
                        .builder(actor, engine, Collections.unmodifiableList(allPlayers))
                        .target(tenant)
                        .colorKey(color)
                        .build();
                RentEffect.DueResult dueR = doubled
                        ? DoubleRentEffect.computeDue(ctx)
                        : RentEffect.computeDue(ctx);
                if (!dueR.isOk()) {
                    continue;
                }
                int due = dueR.getAmountDue();
                String label = (doubled ? "双倍收租 " : "收租 ")
                        + color + " → " + tenant.getDisplayName() + "（应付约 " + due + "M）";
                out.addOption(new ActionOptionRow(label, tenant.getPlayerId(), color, null, null, null));
            }
        }
        if (out.getOptions().isEmpty()) {
            out.setOk(false);
            out.setError("当前没有可收租的颜色与对手组合。");
        }
    }

    private static void buildDebtOptions(List<Player> others, ActionOptionsResult out) {
        for (Player t : others) {
            out.addOption(new ActionOptionRow(
                    "向 " + t.getDisplayName() + " 讨债 5M", t.getPlayerId(), null, null, null, null));
        }
        if (out.getOptions().isEmpty()) {
            out.setOk(false);
            out.setError("没有其他玩家可指定。");
        }
    }

    private static void buildStealOptions(Player actor, List<Player> others, ActionOptionsResult out) {
        for (Player t : others) {
            for (PropertyCard prop : t.getPropertyCardsView()) {
                if (prop == null || !PropertyStealRules.mayStealPropertyFromTarget(t, prop)) {
                    continue;
                }
                String label = "偷财产：" + t.getDisplayName() + " · " + shortCardLabel(prop);
                out.addOption(new ActionOptionRow(
                        label, t.getPlayerId(), null, prop.getId(), null, "PROPERTY"));
            }
            for (Card bc : t.getBankCardsView()) {
                if (bc == null) {
                    continue;
                }
                String label = "偷银行：" + t.getDisplayName() + " · " + shortCardLabel(bc);
                out.addOption(new ActionOptionRow(
                        label, t.getPlayerId(), null, bc.getId(), null, "BANK"));
            }
        }
        if (out.getOptions().isEmpty()) {
            out.setOk(false);
            out.setError("没有可偷的房产或银行牌。");
        }
    }

    private static void buildForcedDealOptions(Player actor, List<Player> others, ActionOptionsResult out) {
        List<PropertyCard> mine = actor.getPropertyCardsView();
        int count = 0;
        boolean truncated = false;
        outer:
        for (Player t : others) {
            for (PropertyCard tp : t.getPropertyCardsView()) {
                if (tp == null) {
                    continue;
                }
                for (PropertyCard ap : mine) {
                    if (ap == null) {
                        continue;
                    }
                    if (count >= MAX_FORCED_DEAL) {
                        truncated = true;
                        break outer;
                    }
                    count++;
                    String label = "换入 " + shortCardLabel(tp) + "（" + t.getDisplayName()
                            + "）↔ 交出 " + shortCardLabel(ap);
                    out.addOption(new ActionOptionRow(
                            label, t.getPlayerId(), null, tp.getId(), ap.getId(), null));
                }
            }
        }
        out.setTruncated(truncated);
        if (out.getOptions().isEmpty()) {
            out.setOk(false);
            out.setError("没有可行的强制交换组合。");
        }
    }

    private static void buildDealBreakerOptions(List<Player> others, ActionOptionsResult out) {
        for (Player t : others) {
            for (String color : PropertySetCalculator.REQUIRED_BY_COLOR.keySet()) {
                if (!PropertySetCalculator.hasCompleteSetForColor(t.getPropertyCardsView(), color)) {
                    continue;
                }
                String label = "夺取 " + t.getDisplayName() + " 的 " + color + " 完整套";
                out.addOption(new ActionOptionRow(label, t.getPlayerId(), color, null, null, null));
            }
        }
        if (out.getOptions().isEmpty()) {
            out.setOk(false);
            out.setError("没有对手拥有可夺取的完整套。");
        }
    }

    private static void buildHouseOptions(Player actor, ActionOptionsResult out) {
        for (PropertyCard pc : actor.getPropertyCardsView()) {
            if (pc == null || pc.getBuildingLevel() != BuildingLevel.BASE) {
                continue;
            }
            String ck = actorPropertyColor(pc);
            if (ck == null) {
                continue;
            }
            if (!BuildingPlacementRules.allowsHouseHotel(ck)) {
                continue;
            }
            if (!PropertySetCalculator.hasCompleteSetForColor(actor.getPropertyCardsView(), ck)) {
                continue;
            }
            String label = "房屋 → " + shortCardLabel(pc);
            out.addOption(new ActionOptionRow(label, null, null, pc.getId(), pc.getId(), null));
        }
        if (out.getOptions().isEmpty()) {
            out.setOk(false);
            out.setError("没有可加盖房屋的房产（需已声明颜色的完整套且为平地）。");
        }
    }

    private static void buildHotelOptions(Player actor, ActionOptionsResult out) {
        for (PropertyCard pc : actor.getPropertyCardsView()) {
            if (pc == null || pc.getBuildingLevel() != BuildingLevel.HOUSE) {
                continue;
            }
            String ck = actorPropertyColor(pc);
            if (ck == null) {
                continue;
            }
            if (!BuildingPlacementRules.allowsHouseHotel(ck)) {
                continue;
            }
            if (!PropertySetCalculator.hasCompleteSetForColor(actor.getPropertyCardsView(), ck)) {
                continue;
            }
            String label = "旅馆 → " + shortCardLabel(pc);
            out.addOption(new ActionOptionRow(label, null, null, pc.getId(), pc.getId(), null));
        }
        if (out.getOptions().isEmpty()) {
            out.setOk(false);
            out.setError("没有可升级为旅馆的房产（需先有房屋且套完整）。");
        }
    }

    private static String actorPropertyColor(PropertyCard pc) {
        if (pc.isWildProperty() && pc instanceof PropertyWildCard w) {
            String a = w.getAssignedColorKey();
            if (a == null || a.isBlank()) {
                return null;
            }
            return a.trim().toUpperCase(Locale.ROOT);
        }
        String cg = pc.getColorGroup();
        if (cg == null || cg.isBlank()) {
            return null;
        }
        return cg.trim().toUpperCase(Locale.ROOT);
    }

    private static String shortCardLabel(Card c) {
        try {
            JsonObject o = HandCardJson.toHandCardObject(c);
            if (o.has("titleZh")) {
                return o.get("titleZh").getAsString();
            }
        } catch (RuntimeException ignored) {
            // fall through
        }
        return c.getName();
    }
}
