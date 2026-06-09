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
        /** May be declared as any of the ten standard color groups. */
        ANY_COLOR,
        /** May only be declared as one of the two printed colors. */
        DUAL_COLOR
    }

    private final WildPropertyKind wildKind;
    private final List<String> printedColorPair;

    /** Chosen at deploy time by the client or AI; unassigned wilds count toward no color. */
    private String assignedColorKey;

    public PropertyWildCard(String id, String name) {
        this(id, name, WildPropertyKind.ANY_COLOR, List.of());
    }

    public PropertyWildCard(String id, String name, WildPropertyKind kind, List<String> printedPair) {
        super(id, name, null);
        this.wildKind = kind == null ? WildPropertyKind.ANY_COLOR : kind;
        if (this.wildKind == WildPropertyKind.DUAL_COLOR) {
            if (printedPair == null || printedPair.size() != 2) {
                throw new IllegalArgumentException("DUAL_COLOR wild must have exactly 2 printed colors: " + id);
            }
            String a = printedPair.get(0).trim().toUpperCase(Locale.ROOT);
            String b = printedPair.get(1).trim().toUpperCase(Locale.ROOT);
            if (!PropertySetCalculator.REQUIRED_BY_COLOR.containsKey(a)
                    || !PropertySetCalculator.REQUIRED_BY_COLOR.containsKey(b)) {
                throw new IllegalArgumentException("Wild printed colors must be standard color keys: " + a + "," + b);
            }
            this.printedColorPair = List.of(a, b);
        } else {
            this.printedColorPair = List.of();
        }
    }

    public WildPropertyKind getWildPropertyKind() {
        return wildKind;
    }

    /** Non-empty only for DUAL_COLOR wilds, where it contains exactly two colors. */
    public List<String> getPrintedColorPairView() {
        return Collections.unmodifiableList(printedColorPair);
    }

    /**
     * Assign color when wild is deployed.
     */
    public void setAssignedColorKey(String colorKey) {
        if (colorKey == null || colorKey.isBlank()) {
            if (assignedColorKey != null) {
                throw new IllegalStateException("Wild property already assigned " + assignedColorKey + "; cannot clear or reassign.");
            }
            this.assignedColorKey = null;
            return;
        }
        String ck = colorKey.trim().toUpperCase(Locale.ROOT);
        validateAssignableColorKey(ck);
        if (assignedColorKey != null && !assignedColorKey.equals(ck)) {
            throw new IllegalStateException("Wild property already assigned " + assignedColorKey + "; cannot change to " + ck + ".");
        }
        this.assignedColorKey = ck;
    }

    public void reassignColorKey(String colorKey) {
        if (colorKey == null || colorKey.isBlank()) {
            throw new IllegalArgumentException("Reassign color must not be blank.");
        }
        String ck = colorKey.trim().toUpperCase(Locale.ROOT);
        validateAssignableColorKey(ck);
        this.assignedColorKey = ck;
    }

    /**
     * Validates color for wild kind before deploy.
     */
    public void validateAssignableColorKey(String normalizedColorKey) {
        if (normalizedColorKey == null || normalizedColorKey.isBlank()) {
            throw new IllegalArgumentException("Assigned color must not be blank.");
        }
        if (!PropertySetCalculator.REQUIRED_BY_COLOR.containsKey(normalizedColorKey)) {
            throw new IllegalArgumentException("Invalid color key: " + normalizedColorKey);
        }
        if (wildKind == WildPropertyKind.DUAL_COLOR) {
            boolean ok = printedColorPair.stream().anyMatch(normalizedColorKey::equals);
            if (!ok) {
                throw new IllegalArgumentException(
                        "This dual-color wild can only be assigned " + printedColorPair.get(0) + " or " + printedColorPair.get(1)
                                + ", not " + normalizedColorKey + ".");
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
     * Any-color wild has no monetary value; printed dual wilds use the face value shown on the card.
     */
    @Override
    public int getPaymentValue() {
        if (wildKind == WildPropertyKind.ANY_COLOR) {
            return 0;
        }
        return printedDualPaymentValue();
    }

    private int printedDualPaymentValue() {
        if (printedColorPair.size() != 2) {
            return 0;
        }
        String a = printedColorPair.get(0);
        String b = printedColorPair.get(1);
        if (pairEquals(a, b, "LIGHT_BLUE", "BROWN")) {
            return 1;
        }
        if (pairEquals(a, b, "PINK", "ORANGE")
                || pairEquals(a, b, "RAILROAD", "UTILITY")) {
            return 2;
        }
        if (pairEquals(a, b, "RED", "YELLOW")) {
            return 3;
        }
        if (pairEquals(a, b, "LIGHT_BLUE", "RAILROAD")
                || pairEquals(a, b, "DARK_BLUE", "GREEN")
                || pairEquals(a, b, "GREEN", "RAILROAD")) {
            return 4;
        }
        return Math.max(0, printedColorPair.stream()
                .mapToInt(PropertyWildCard::singleColorPaymentValue)
                .max()
                .orElse(0));
    }

    private static boolean pairEquals(String a, String b, String x, String y) {
        return (x.equals(a) && y.equals(b)) || (x.equals(b) && y.equals(a));
    }

    private static int singleColorPaymentValue(String colorKey) {
        if (colorKey == null || colorKey.isBlank()) {
            return 0;
        }
        return switch (colorKey.trim().toUpperCase(Locale.ROOT)) {
            case "BROWN", "DARK_BLUE", "UTILITY" -> 2;
            case "LIGHT_BLUE", "PINK", "ORANGE", "RED", "YELLOW", "GREEN" -> 3;
            case "RAILROAD" -> 4;
            default -> 0;
        };
    }
}
