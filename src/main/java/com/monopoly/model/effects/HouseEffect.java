package com.monopoly.model.effects;

import com.monopoly.model.card.BuildingLevel;
import com.monopoly.model.player.Player;
import com.monopoly.model.card.PropertyCard;
import com.monopoly.model.settlement.BuildingPlacementRules;
import com.monopoly.model.settlement.PropertySetCalculator;
import com.monopoly.model.card.PropertyWildCard;

import java.util.Locale;

/**
 * House：在己方财产区指定房产上加盖房子（{@link BuildingLevel#HOUSE}），需该颜色已形成完整套且当前为基础建筑。
 */
public final class HouseEffect implements ActionEffect {

    @Override
    public ActionEffectResult execute(ActionEffectContext ctx) {
        Player actor = ctx.getActor();
        PropertyCard target = ctx.getActorProperty();
        if (actor == null) {
            return ActionEffectResult.failed("缺少行动玩家。");
        }
        if (target == null) {
            return ActionEffectResult.failed("请指定财产区内要加盖房子的房产（actorCardId 或 targetCardId 指向己方房产）。");
        }
        if (!actor.getPropertyCardsView().contains(target)) {
            return ActionEffectResult.failed("指定房产不在你的财产区。");
        }
        String colorKey = resolveColorKey(target);
        if (colorKey == null) {
            return ActionEffectResult.failed("万能房产需先声明颜色后才能加盖房子。");
        }
        if (!PropertySetCalculator.hasCompleteSetForColor(actor.getPropertyCardsView(), colorKey)) {
            return ActionEffectResult.failed("该颜色尚未形成完整地产集，无法加盖房子。");
        }
        if (!BuildingPlacementRules.allowsHouseHotel(colorKey)) {
            return ActionEffectResult.failed("铁路与公共事业套不能加盖房屋或旅馆。");
        }
        if (target.getBuildingLevel() != BuildingLevel.BASE) {
            return ActionEffectResult.failed("该房产已有建筑，请使用酒店卡将房子升级为酒店。");
        }
        target.setBuildingLevel(BuildingLevel.HOUSE);
        return ActionEffectResult.success("已在该房产上加盖房子。");
    }

    static String resolveColorKey(PropertyCard card) {
        if (card == null) {
            return null;
        }
        if (card.isWildProperty() && card instanceof PropertyWildCard w) {
            String a = w.getAssignedColorKey();
            return a == null || a.isBlank() ? null : a.trim().toUpperCase(Locale.ROOT);
        }
        String cg = card.getColorGroup();
        if (cg == null || cg.isBlank()) {
            return null;
        }
        return cg.trim().toUpperCase(Locale.ROOT);
    }
}
