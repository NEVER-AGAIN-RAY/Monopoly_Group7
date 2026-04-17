package com.monopoly.model.card;

import com.monopoly.model.settlement.PropertySetCalculator;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 万能房产牌：部署时声明 {@link #assignedColorKey}；实体近似为 2 张「任意色」+ 9 张「印定双色」。
 */
public class PropertyWildCard extends PropertyCard {

    public enum WildPropertyKind {
        /** 十种标准色任选其一声明 */
        ANY_COLOR,
        /** 仅可声明为卡面印有的两色之一 */
        DUAL_COLOR
    }

    private final WildPropertyKind wildKind;
    private final List<String> printedColorPair;

    /** 部署时由客户端/AI 传入并保存；未指定前不得计入任意颜色套数 */
    private String assignedColorKey;

    public PropertyWildCard(String id, String name) {
        this(id, name, WildPropertyKind.ANY_COLOR, List.of());
    }

    public PropertyWildCard(String id, String name, WildPropertyKind kind, List<String> printedPair) {
        super(id, name, null);
        this.wildKind = kind == null ? WildPropertyKind.ANY_COLOR : kind;
        if (this.wildKind == WildPropertyKind.DUAL_COLOR) {
            if (printedPair == null || printedPair.size() != 2) {
                throw new IllegalArgumentException("DUAL_COLOR 万能须恰好两色: " + id);
            }
            String a = printedPair.get(0).trim().toUpperCase(Locale.ROOT);
            String b = printedPair.get(1).trim().toUpperCase(Locale.ROOT);
            if (!PropertySetCalculator.REQUIRED_BY_COLOR.containsKey(a)
                    || !PropertySetCalculator.REQUIRED_BY_COLOR.containsKey(b)) {
                throw new IllegalArgumentException("万能印色须为标准色键: " + a + "," + b);
            }
            this.printedColorPair = List.of(a, b);
        } else {
            this.printedColorPair = List.of();
        }
    }

    public WildPropertyKind getWildPropertyKind() {
        return wildKind;
    }

    /** 仅 {@link WildPropertyKind#DUAL_COLOR} 非空，长度 2。 */
    public List<String> getPrintedColorPairView() {
        return Collections.unmodifiableList(printedColorPair);
    }

    /**
     * 部署到财产区时调用，声明本万能牌计入的颜色键（与 {@link PropertySetCalculator} 一致，大写）。
     */
    public void setAssignedColorKey(String colorKey) {
        if (colorKey == null || colorKey.isBlank()) {
            this.assignedColorKey = null;
            return;
        }
        String ck = colorKey.trim().toUpperCase(Locale.ROOT);
        validateAssignableColorKey(ck);
        this.assignedColorKey = ck;
    }

    /**
     * 校验声明色是否符合本张万能类型（部署 / 改色前调用）。
     */
    public void validateAssignableColorKey(String normalizedColorKey) {
        if (normalizedColorKey == null || normalizedColorKey.isBlank()) {
            throw new IllegalArgumentException("声明颜色不能为空。");
        }
        if (!PropertySetCalculator.REQUIRED_BY_COLOR.containsKey(normalizedColorKey)) {
            throw new IllegalArgumentException("无效颜色键: " + normalizedColorKey);
        }
        if (wildKind == WildPropertyKind.DUAL_COLOR) {
            boolean ok = printedColorPair.stream().anyMatch(normalizedColorKey::equals);
            if (!ok) {
                throw new IllegalArgumentException(
                        "该双色万能仅可声明为 " + printedColorPair.get(0) + " 或 " + printedColorPair.get(1)
                                + "，不能为 " + normalizedColorKey + "。");
            }
        }
    }

    public String getAssignedColorKey() {
        return assignedColorKey;
    }

    @Override
    public boolean isWildProperty() {
        return true;
    }

    /**
     * 作支付价值 0M（贴近实体万能房产不作现金支付）。
     */
    @Override
    public int getPaymentValue() {
        return 0;
    }
}
