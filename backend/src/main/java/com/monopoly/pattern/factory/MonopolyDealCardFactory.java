package com.monopoly.pattern.factory;

import com.monopoly.model.card.ActionCard;
import com.monopoly.model.card.Card;
import com.monopoly.model.core.GameConstants;
import com.monopoly.model.card.MoneyCard;
import com.monopoly.model.card.PropertyCard;
import com.monopoly.model.card.PropertyWildCard;
import com.monopoly.model.card.PropertyWildCard.WildPropertyKind;
import com.monopoly.model.rules.MonopolyDealBankValues;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Builds the official {@link GameConstants#STANDARD_DECK_SIZE}-card Monopoly Deal deck.
 * The playable deck is properties, wilds, money, action cards, and rent cards.
 */
public class MonopolyDealCardFactory extends CardFactory {

    /** Property color distribution matching the retail box (28 total). */
    private static final String[] PROPERTY_DEAL_ORDER = {
            "BROWN", "BROWN",
            "LIGHT_BLUE", "LIGHT_BLUE", "LIGHT_BLUE",
            "PINK", "PINK", "PINK",
            "ORANGE", "ORANGE", "ORANGE",
            "RED", "RED", "RED",
            "YELLOW", "YELLOW", "YELLOW",
            "GREEN", "GREEN", "GREEN",
            "DARK_BLUE", "DARK_BLUE",
            "RAILROAD", "RAILROAD", "RAILROAD", "RAILROAD",
            "UTILITY", "UTILITY"
    };

    private static final int PROPERTY_WILD_COUNT = 11;
    private static final int MONEY_COUNT = 20;

    private static final int ACTION_COUNT = GameConstants.STANDARD_DECK_SIZE
            - PROPERTY_DEAL_ORDER.length - PROPERTY_WILD_COUNT - MONEY_COUNT;

    /** specKey prefixes that route to each card-creation branch. */
    private static final String PREFIX_PROP = "PROP";
    private static final String PREFIX_WILD = "WILD";
    private static final String PREFIX_ACT = "ACT_";

    /** Effect codes handled specially during card construction. */
    private static final String EFFECT_RENT = "RENT";
    private static final String EFFECT_RENT_DUAL = "RENT_DUAL";
    private static final String EFFECT_BIRTHDAY = "BIRTHDAY";

    /** Face values of the 20 money cards, in deal order. */
    private static final int[] MONEY_VALUES = {
            1, 1, 1, 1, 1, 1,
            2, 2, 2, 2, 2,
            3, 3, 3,
            4, 4, 4,
            5, 5,
            10
    };

    private static final String[] ACTION_EFFECT_CYCLE;
    /** Same indexes as ACTION_EFFECT_CYCLE; meaningful only for RENT entries. */
    private static final boolean[] ACTION_RENT_IS_WILDCARD;
    /** Same indexes as ACTION_EFFECT_CYCLE; running ordinal of each RENT_DUAL entry (else -1). */
    private static final int[] ACTION_RENT_DUAL_ORDINAL;

    /** Printed color pairs for the ten retail 1-vs-1 dual-rent cards, two of each pair. */
    private static final String[][] RENT_DUAL_1V1_PALETTES = {
            {"LIGHT_BLUE", "BROWN"},
            {"PINK", "ORANGE"},
            {"RED", "YELLOW"},
            {"DARK_BLUE", "GREEN"},
            {"RAILROAD", "UTILITY"}
    };

    /** The nine printed dual-color wilds, WILD_2 through WILD_10. */
    private static final String[][] WILD_DUAL_PAIRS = {
            {"LIGHT_BLUE", "BROWN"},
            {"LIGHT_BLUE", "RAILROAD"},
            {"PINK", "ORANGE"},
            {"PINK", "ORANGE"},
            {"RED", "YELLOW"},
            {"RED", "YELLOW"},
            {"DARK_BLUE", "GREEN"},
            {"GREEN", "RAILROAD"},
            {"RAILROAD", "UTILITY"}
    };

    static {
        if (ACTION_COUNT != 47) {
            throw new IllegalStateException("Action/rent card slot count must be 47, got " + ACTION_COUNT);
        }
        List<String> codes = new ArrayList<>();
        boolean[] rentWild = new boolean[ACTION_COUNT];

        for (int i = 0; i < 3; i++) {
            rentWild[codes.size()] = true;
            codes.add(EFFECT_RENT);
        }
        addN(codes, EFFECT_RENT_DUAL, 10);

        addN(codes, "DOUBLE_RENT", 2);
        addN(codes, "STEAL_PROPERTY", 3);
        addN(codes, "FORCED_DEAL", 3);
        addN(codes, "RENT_WAIVER", 3);
        addN(codes, "DEBT_COLLECTOR", 3);
        addN(codes, EFFECT_BIRTHDAY, 3);
        addN(codes, "HOUSE", 3);
        addN(codes, "HOTEL", 2);
        addN(codes, "DEAL_BREAKER", 2);
        addN(codes, "PASS_GO", 10);

        if (codes.size() != ACTION_COUNT) {
            throw new IllegalStateException(
                    "Action card effect code count must be " + ACTION_COUNT + ", got " + codes.size());
        }
        ACTION_EFFECT_CYCLE = codes.toArray(new String[0]);
        ACTION_RENT_IS_WILDCARD = rentWild;

        int[] rentDualOrdinal = new int[ACTION_COUNT];
        int nextDualOrdinal = 0;
        for (int i = 0; i < ACTION_COUNT; i++) {
            rentDualOrdinal[i] = EFFECT_RENT_DUAL.equals(ACTION_EFFECT_CYCLE[i]) ? nextDualOrdinal++ : -1;
        }
        ACTION_RENT_DUAL_ORDINAL = rentDualOrdinal;
    }

    private static void addN(List<String> list, String effectCode, int n) {
        for (int i = 0; i < n; i++) {
            list.add(effectCode);
        }
    }

    @Override
    protected Card createCard(String specKey) {
        if (specKey == null) {
            return fallbackCard(specKey);
        }
        if (specKey.startsWith(PREFIX_PROP)) {
            return createPropertyCard(specKey);
        }
        if (specKey.startsWith(PREFIX_WILD)) {
            return createWildCard(specKey);
        }
        if (specKey.startsWith(PREFIX_ACT)) {
            return createActionCard(specKey);
        }
        return fallbackCard(specKey);
    }

    private Card createPropertyCard(String specKey) {
        int idx = parseSuffix(specKey);
        String color = PROPERTY_DEAL_ORDER[Math.floorMod(idx, PROPERTY_DEAL_ORDER.length)];
        return new PropertyCard(specKey, "property-" + color + "-" + idx, color);
    }

    private Card createWildCard(String specKey) {
        int wi = parseSuffix(specKey);
        String baseName = "wild-property-" + specKey;
        if (wi <= 1) {
            return new PropertyWildCard(specKey, baseName, WildPropertyKind.ANY_COLOR, List.of());
        }
        String[] pair = WILD_DUAL_PAIRS[(wi - 2) % WILD_DUAL_PAIRS.length];
        return new PropertyWildCard(specKey, baseName, WildPropertyKind.DUAL_COLOR,
                List.of(pair[0], pair[1]));
    }

    private Card createActionCard(String specKey) {
        int idx = parseSuffix(specKey);
        int ci = Math.floorMod(idx, ACTION_COUNT);
        String effectCode = ACTION_EFFECT_CYCLE[ci];
        String lowName = effectCode.toLowerCase(Locale.ROOT) + "-" + idx;
        if (EFFECT_RENT.equals(effectCode)) {
            return createRentCard(specKey, lowName, effectCode, ci);
        }
        if (EFFECT_RENT_DUAL.equals(effectCode)) {
            return createRentDualCard(specKey, lowName, effectCode, ci);
        }
        return new ActionCard(specKey, lowName, effectCode);
    }

    private Card createRentCard(String specKey, String lowName, String effectCode, int ci) {
        boolean wild = ACTION_RENT_IS_WILDCARD[ci];
        return new ActionCard(
                specKey,
                lowName,
                effectCode,
                MonopolyDealBankValues.bankValueForActionEffect(effectCode),
                List.of(),
                false,
                wild);
    }

    private Card createRentDualCard(String specKey, String lowName, String effectCode, int ci) {
        String[] pal = RENT_DUAL_1V1_PALETTES[ACTION_RENT_DUAL_ORDINAL[ci] % RENT_DUAL_1V1_PALETTES.length];
        return new ActionCard(
                specKey,
                lowName,
                effectCode,
                MonopolyDealBankValues.bankValueForActionEffect(effectCode),
                List.of(pal[0], pal[1]),
                false,
                false);
    }

    /** Last-resort card for null/unrecognized spec keys; preserves legacy behavior. */
    private Card fallbackCard(String specKey) {
        return new ActionCard(specKey, "action-" + specKey, EFFECT_BIRTHDAY);
    }

    private static int parseSuffix(String specKey) {
        int u = specKey.lastIndexOf('_');
        if (u < 0 || u >= specKey.length() - 1) {
            return 0;
        }
        try {
            return Integer.parseInt(specKey.substring(u + 1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Deterministic 108-card list (property, wild, money, action) for tests.
     */
    @Override
    public List<Card> createStandardDeck108() {
        List<Card> deck = new ArrayList<>(GameConstants.STANDARD_DECK_SIZE);

        for (int i = 0; i < PROPERTY_DEAL_ORDER.length; i++) {
            deck.add(createCard(PREFIX_PROP + "_" + i));
        }
        for (int i = 0; i < PROPERTY_WILD_COUNT; i++) {
            deck.add(createCard(PREFIX_WILD + "_" + i));
        }
        for (int i = 0; i < MONEY_COUNT; i++) {
            int m = MONEY_VALUES[i];
            deck.add(new MoneyCard("MONEY_" + i, m + "M", m));
        }
        for (int i = 0; i < ACTION_COUNT; i++) {
            deck.add(createCard(PREFIX_ACT + i));
        }

        if (deck.size() != GameConstants.STANDARD_DECK_SIZE) {
            throw new IllegalStateException(
                    "Standard deck must be " + GameConstants.STANDARD_DECK_SIZE + " cards, got " + deck.size());
        }
        return deck;
    }
}
