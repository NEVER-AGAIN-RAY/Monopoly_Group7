package com.monopoly.model.effects;

import com.monopoly.model.card.BuildingLevel;
import com.monopoly.model.player.Player;
import com.monopoly.model.card.PropertyCard;
import com.monopoly.model.settlement.BuildingPlacementRules;
import com.monopoly.model.settlement.PropertySetCalculator;

/**
 * Hotel：将已有房子的房产升级为酒店（{@link BuildingLevel#HOTEL}）。
 */
public final class HotelEffect implements ActionEffect {

    @Override
    public ActionEffectResult execute(ActionEffectContext ctx) {
        Player actor = ctx.getActor();
        PropertyCard target = ctx.getActorProperty();
        if (actor == null) {
            return ActionEffectResult.failed("缺少行动玩家。");
        }
        if (target == null) {
            return ActionEffectResult.failed("请指定财产区内要升级酒店的房产（actorCardId 或 targetCardId）。");
        }
        if (!actor.getPropertyCardsView().contains(target)) {
            return ActionEffectResult.failed("指定房产不在你的财产区。");
        }
        String colorKey = HouseEffect.resolveColorKey(target);
        if (colorKey == null) {
            return ActionEffectResult.failed("万能房产需先声明颜色。");
        }
        if (!PropertySetCalculator.hasCompleteSetForColor(actor.getPropertyCardsView(), colorKey)) {
            return ActionEffectResult.failed("该颜色未形成完整套，无法升级酒店。");
        }
        if (!BuildingPlacementRules.allowsHouseHotel(colorKey)) {
            return ActionEffectResult.failed("铁路与公共事业套不能加盖旅馆。");
        }
        if (target.getBuildingLevel() != BuildingLevel.HOUSE) {
            return ActionEffectResult.failed("必须先在该房产上加盖房子，才能再升级为酒店。");
        }
        target.setBuildingLevel(BuildingLevel.HOTEL);
        return ActionEffectResult.success("已将房子升级为酒店。");
    }
}
