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

    private static final String[] ACTION_EFFECT_CYCLE;
    /** 与 .ACTION_EFFECT_CYCLE 同下标；仅对效果码 RENT 有效。 */
    private static final boolean[] ACTION_RENT_IS_WILDCARD;

    /** 实体 10 张「双色租金」1v1 的卡面色对（每组 2 张）。 */
    private static final String[][] RENT_DUAL_1V1_PALETTES = {
            {"LIGHT_BLUE", "BROWN"},
            {"PINK", "ORANGE"},
            {"RED", "YELLOW"},
            {"DARK_BLUE", "GREEN"},
            {"RAILROAD", "UTILITY"}
    };

    /** WILD_2..WILD_10 共 9 张印定双色万能。 */
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
            throw new IllegalStateException("行动/租金牌槽位应为 47，当前=" + ACTION_COUNT);
        }
        List<String> codes = new ArrayList<>();
        boolean[] rentWild = new boolean[ACTION_COUNT];

        for (int i = 0; i < 3; i++) {
            rentWild[codes.size()] = true;
            codes.add("RENT");
        }
        addN(codes, "RENT_DUAL", 10);

        addN(codes, "DOUBLE_RENT", 2);
        addN(codes, "STEAL_PROPERTY", 3);
        addN(codes, "FORCED_DEAL", 3);
        addN(codes, "RENT_WAIVER", 3);
        addN(codes, "DEBT_COLLECTOR", 3);
        addN(codes, "BIRTHDAY", 3);
        addN(codes, "HOUSE", 3);
        addN(codes, "HOTEL", 2);
        addN(codes, "DEAL_BREAKER", 2);
        addN(codes, "PASS_GO", 10);

        if (codes.size() != ACTION_COUNT) {
            throw new IllegalStateException(
                    "行动卡效果码总数必须为 " + ACTION_COUNT + "，当前=" + codes.size());
        }
        ACTION_EFFECT_CYCLE = codes.toArray(new String[0]);
        ACTION_RENT_IS_WILDCARD = rentWild;
    }

    private static void addN(List<String> list, String effectCode, int n) {
        for (int i = 0; i < n; i++) {
            list.add(effectCode);
        }
    }

    @Override
    protected Card createCard(String specKey) {
        if (specKey != null && specKey.startsWith("PROP")) {
            int idx = parseSuffix(specKey);
            String color = PROPERTY_DEAL_ORDER[Math.floorMod(idx, PROPERTY_DEAL_ORDER.length)];
            return new PropertyCard(specKey, "property-" + color + "-" + idx, color);
        }
        if (specKey != null && specKey.startsWith("WILD")) {
            int wi = parseSuffix(specKey);
            String baseName = "wild-property-" + specKey;
            if (wi <= 1) {
                return new PropertyWildCard(specKey, baseName, WildPropertyKind.ANY_COLOR, List.of());
            }
            String[] pair = WILD_DUAL_PAIRS[(wi - 2) % WILD_DUAL_PAIRS.length];
            return new PropertyWildCard(specKey, baseName, WildPropertyKind.DUAL_COLOR,
                    List.of(pair[0], pair[1]));
        }
        if (specKey != null && specKey.startsWith("ACT_")) {
            int idx = parseSuffix(specKey);
            int ci = Math.floorMod(idx, ACTION_COUNT);
            String effectCode = ACTION_EFFECT_CYCLE[ci];
            String lowName = effectCode.toLowerCase(Locale.ROOT) + "-" + idx;
            if ("RENT".equals(effectCode)) {
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
            if ("RENT_DUAL".equals(effectCode)) {
                int dualOrdinal = 0;
                for (int j = 0; j < ci; j++) {
                    if ("RENT_DUAL".equals(ACTION_EFFECT_CYCLE[j])) {
                        dualOrdinal++;
                    }
                }
                String[] pal = RENT_DUAL_1V1_PALETTES[dualOrdinal % RENT_DUAL_1V1_PALETTES.length];
                return new ActionCard(
                        specKey,
                        lowName,
                        effectCode,
                        MonopolyDealBankValues.bankValueForActionEffect(effectCode),
                        List.of(pal[0], pal[1]),
                        false,
                        false);
            }
            return new ActionCard(specKey, lowName, effectCode);
        }
        return new ActionCard(specKey, "action-" + specKey, "BIRTHDAY");
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
            deck.add(createCard("PROP_" + i));
        }
        for (int i = 0; i < PROPERTY_WILD_COUNT; i++) {
            deck.add(createCard("WILD_" + i));
        }
        int[] moneyValues = {
                1, 1, 1, 1, 1, 1,
                2, 2, 2, 2, 2,
                3, 3, 3,
                4, 4, 4,
                5, 5,
                10
        };
        for (int i = 0; i < MONEY_COUNT; i++) {
            int m = moneyValues[i];
            deck.add(new MoneyCard("MONEY_" + i, m + "M", m));
        }
        for (int i = 0; i < ACTION_COUNT; i++) {
            deck.add(createCard("ACT_" + i));
        }

        if (deck.size() != GameConstants.STANDARD_DECK_SIZE) {
            throw new IllegalStateException(
                    "标准牌堆必须为 " + GameConstants.STANDARD_DECK_SIZE + " 张，当前=" + deck.size());
        }
        return deck;
    }
}
