package com.monopoly.controller;

import com.monopoly.dto.ActionOptionRow;
import com.monopoly.dto.ActionOptionsResult;
import com.monopoly.model.core.GameContext;
import com.monopoly.model.card.ActionCard;
import com.monopoly.model.card.Card;
import com.monopoly.model.card.MoneyCard;
import com.monopoly.model.card.PropertyCard;
import com.monopoly.model.card.PropertyWildCard;
import com.monopoly.model.card.PropertyWildCard.WildPropertyKind;
import com.monopoly.model.player.Player;
import com.monopoly.pattern.singleton.GameEngineSingleton;

import java.util.List;
import java.util.Locale;

/**
 * PLAY_OPTIONS rows for DEPOSIT/DEPLOY/DISCARD/ACTION.
 */
public final class PlayOptionsService {

    private static final List<String> WILD_DEPLOY_COLORS = List.of(
            "BROWN", "LIGHT_BLUE", "PINK", "ORANGE", "RED",
            "YELLOW", "GREEN", "DARK_BLUE", "RAILROAD", "UTILITY");

    private PlayOptionsService() {
    }

    public static ActionOptionsResult build(
            Player actor,
            Card card,
            String actionType,
            List<Player> allPlayers,
            GameEngineSingleton engine) {
        return build(actor, card, actionType, allPlayers, engine, null);
    }

    public static ActionOptionsResult build(
            Player actor,
            Card card,
            String actionType,
            List<Player> allPlayers,
            GameEngineSingleton engine,
            GameContext gameContext) {
        ActionOptionsResult bad = new ActionOptionsResult();
        if (actor == null || card == null || allPlayers == null || engine == null) {
            bad.setOk(false);
            bad.setError("参数无效");
            return bad;
        }
        if (actionType == null || actionType.isBlank()) {
            bad.setOk(false);
            bad.setError("actionType 不能为空");
            return bad;
        }
        String at = actionType.trim().toUpperCase(Locale.ROOT);
        ActionOptionsResult out = new ActionOptionsResult();
        out.setEffectCode(at);
        switch (at) {
            case "DEPOSIT" -> {
                if (!(card instanceof MoneyCard) && !(card instanceof ActionCard)) {
                    out.setOk(false);
                    out.setError("仅有现金或行动牌可存入银行。");
                    return out;
                }
                out.setOk(true);
                out.addOption(new ActionOptionRow(
                        "存入银行（按牌角标面值）", null, null, null, null, null));
                return out;
            }
            case "DISCARD" -> {
                out.setOk(true);
                out.addOption(new ActionOptionRow("弃置该牌", null, null, null, null, null));
                return out;
            }
            case "DEPLOY" -> {
                if (!(card instanceof PropertyCard)) {
                    out.setOk(false);
                    out.setError("DEPLOY 仅适用于房产牌。");
                    return out;
                }
                out.setOk(true);
                if (card instanceof PropertyWildCard ww) {
                    String assigned = ww.getAssignedColorKey();
                    List<String> colors = assigned != null && !assigned.isBlank()
                            ? List.of(assigned.trim().toUpperCase(Locale.ROOT))
                            : (ww.getWildPropertyKind() == WildPropertyKind.DUAL_COLOR
                                    ? List.copyOf(ww.getPrintedColorPairView())
                                    : WILD_DEPLOY_COLORS);
                    for (String c : colors) {
                        out.addOption(new ActionOptionRow(
                                "部署万能房产，财产区计入颜色 " + c, null, c, null, null, null));
                    }
                } else {
                    out.addOption(new ActionOptionRow("部署该房产到财产区", null, null, null, null, null));
                }
                return out;
            }
            case "ACTION" -> {
                if (!(card instanceof ActionCard ac)) {
                    out.setOk(false);
                    out.setError("ACTION 仅适用于行动牌。");
                    return out;
                }
                return ActionOptionsService.build(actor, ac, allPlayers, engine, gameContext);
            }
            default -> {
                out.setOk(false);
                out.setError("不支持的 actionType: " + actionType);
                return out;
            }
        }
    }
}
