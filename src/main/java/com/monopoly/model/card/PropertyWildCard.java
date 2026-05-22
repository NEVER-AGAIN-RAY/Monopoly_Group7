package com.monopoly.model.card;

import com.monopoly.model.settlement.PropertySetCalculator;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Wild property: assign a color on deploy (ANY_COLOR or dual-print variants).
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

    /** 仅 WildPropertyKind.DUAL_COLOR 非空，长度 2。 */
    public List<String> getPrintedColorPairView() {
        return Collections.unmodifiableList(printedColorPair);
    }

    /**
     * Assign color when wild is deployed.
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
     * Validates color for wild kind before deploy/reassign.
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
     * Wild properties pay 0M when used as payment.
     */
    @Override
    public int getPaymentValue() {
        return 0;
    }
}
