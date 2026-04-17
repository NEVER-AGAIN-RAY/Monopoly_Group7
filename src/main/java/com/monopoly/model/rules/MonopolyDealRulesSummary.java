package com.monopoly.model.rules;

import com.monopoly.model.core.GameConstants;
import com.monopoly.model.settlement.PropertySetCalculator;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 与 {@code rules.md} 附录 A 及 {@link com.monopoly.model.card.MonopolyDealCardFactory} 同源的中文说明，
 * 供 JavaFX 规则面板等使用；与正文意译冲突时以附录 A + 工厂为准。
 */
public final class MonopolyDealRulesSummary {

    private MonopolyDealRulesSummary() {
    }

    public static String buildPlainTextChinese() {
        StringBuilder sb = new StringBuilder();
        sb.append("【牌堆】本客户端使用 ").append(GameConstants.STANDARD_DECK_SIZE)
                .append(" 张：房产 28、万能 11（2 任意色 + 9 印定双色）、钱币 20、行动 49（租金合计 13：8×RENT 含 3 张任意色展示 + 5×RENT_DUAL 双色 1v1；PASS_GO 12）。\n\n");

        sb.append("【胜利】先凑齐 3 套不同颜色的完整房产集。\n\n");

        sb.append("【回合】摸 2 张（手牌为空则摸 5）；最多打出 3 张；手牌超过 7 张须弃至 7。\n\n");

        sb.append("【支付】仅能从银行区与财产区支付，不能从手牌直接支付；多付不退。")
                .append(" 收租入栈后承租人放弃免租时，可在客户端勾选具体支付牌；不指定时由服务器按小面额优先自动凑额。\n\n");

        sb.append("【行动牌银行面值】存入银行时按角标 M 计（与下表一致）：\n");
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(MonopolyDealBankValues.actionBankValuesUnmodifiable().entrySet());
        entries.sort(Comparator.comparing(Map.Entry::getKey));
        for (Map.Entry<String, Integer> e : entries) {
            sb.append("  · ").append(e.getKey()).append(" → ").append(e.getValue()).append("M\n");
        }
        sb.append("\n【房产抵押价值】棕色/深蓝/公共 2M；多数颜色 3M；铁路 4M。\n\n");

        sb.append("【万能房产】作支付价值 0M。牌堆：2 张可声明任意标准色；9 张仅可声明为卡面印有的两色之一（见手牌 JSON wildKind / printedColors）。\n\n");

        sb.append("【向导化出牌】出牌阶段可先发 `PLAY_OPTIONS`（playerId + cardId + actionType：DEPOSIT/DEPLOY/DISCARD/ACTION），")
                .append("或仅用行动牌发 `ACTION_OPTIONS`；服务器返回选项后再 `PLAY`。\n\n");

        sb.append("【收租】须该颜色已形成完整套（含已声明该色的万能）；基础租按实体 Monopoly Deal 牌面阶梯（见 RentTierTable），")
                .append("多套同色分段累计；再加各张房屋 +3M、旅馆 +7M。铁路/公共事业不可加盖房/旅馆。")
                .append(" 默认牌堆中 RENT_DUAL 为卡面两色选一（须完整套）向一名对手收租；房规可启用「全员依次」双色租（见代码标记）。\n\n");

        sb.append("【套数需求】");
        PropertySetCalculator.REQUIRED_BY_COLOR.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> sb.append(e.getKey()).append("=").append(e.getValue()).append("张 "));
        sb.append("\n\n【效果金额】讨债向目标收 5M；生日每位其他玩家付 2M。\n");

        return sb.toString();
    }
}
