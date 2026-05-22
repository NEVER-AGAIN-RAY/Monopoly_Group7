package com.monopoly.pattern.factory;

import com.monopoly.model.card.ActionCard;
import com.monopoly.model.card.Card;
import com.monopoly.model.card.MoneyCard;
import com.monopoly.model.card.PropertyCard;
import com.monopoly.model.card.PropertyWildCard;
import com.monopoly.model.card.PropertyWildCard.WildPropertyKind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 标准盒装 108 张中有 2 张规则卡；可游戏牌堆包含 106 张房产、万能、现金、行动/租金牌。
 */
class DeckCompositionTest {

    @Test
    void standardDeck_hasExpectedTypeCounts() {
        List<Card> deck = new MonopolyDealCardFactory().createStandardDeck108();
        assertEquals(106, deck.size());

        int prop = 0;
        int wild = 0;
        int money = 0;
        int action = 0;
        for (Card c : deck) {
            if (c instanceof PropertyWildCard) {
                wild++;
            } else if (c instanceof PropertyCard) {
                prop++;
            } else if (c instanceof MoneyCard) {
                money++;
            } else if (c instanceof ActionCard) {
                action++;
            }
        }
        assertEquals(28, prop, "房产牌张数");
        assertEquals(11, wild, "万能房产张数");
        assertEquals(20, money, "现金牌张数");
        assertEquals(47, action, "行动/租金牌张数");

        int rent = 0;
        int rentDual = 0;
        int rentWildcard = 0;
        int passGo = 0;
        for (Card c : deck) {
            if (c instanceof ActionCard ac) {
                String ec = ac.getEffectCode() == null ? "" : ac.getEffectCode().toUpperCase();
                if ("RENT".equals(ec)) {
                    rent++;
                    if (ac.isWildcardRentCard()) {
                        rentWildcard++;
                    }
                } else if ("RENT_DUAL".equals(ec)) {
                    rentDual++;
                } else if ("PASS_GO".equals(ec)) {
                    passGo++;
                }
            }
        }
        assertEquals(3, rent, "任意色收租 RENT");
        assertEquals(3, rentWildcard, "任意色租金牌（展示用标记）");
        assertEquals(10, rentDual, "双色收租 RENT_DUAL（实体 1v1）");
        assertEquals(13, rent + rentDual, "租金类行动牌合计 13");
        assertEquals(10, passGo, "PASS_GO");

        int wildAny = 0;
        int wildDual = 0;
        for (Card c : deck) {
            if (c instanceof PropertyWildCard w) {
                if (w.getWildPropertyKind() == WildPropertyKind.ANY_COLOR) {
                    wildAny++;
                } else {
                    wildDual++;
                }
            }
        }
        assertEquals(2, wildAny, "任意色万能");
        assertEquals(9, wildDual, "印定双色万能");

        Map<String, Long> propertyColors = deck.stream()
                .filter(c -> c instanceof PropertyCard && !(c instanceof PropertyWildCard))
                .map(c -> ((PropertyCard) c).getColorGroup())
                .collect(Collectors.groupingBy(c -> c, Collectors.counting()));
        assertEquals(Map.ofEntries(
                Map.entry("BROWN", 2L),
                Map.entry("LIGHT_BLUE", 3L),
                Map.entry("PINK", 3L),
                Map.entry("ORANGE", 3L),
                Map.entry("RED", 3L),
                Map.entry("YELLOW", 3L),
                Map.entry("GREEN", 3L),
                Map.entry("DARK_BLUE", 2L),
                Map.entry("RAILROAD", 4L),
                Map.entry("UTILITY", 2L)
        ), propertyColors, "基础房产颜色分布");

        Map<Integer, Long> moneyValues = deck.stream()
                .filter(MoneyCard.class::isInstance)
                .map(c -> ((MoneyCard) c).getValueM())
                .collect(Collectors.groupingBy(v -> v, Collectors.counting()));
        assertEquals(Map.of(
                1, 6L,
                2, 5L,
                3, 3L,
                4, 3L,
                5, 2L,
                10, 1L
        ), moneyValues, "现金面额分布");

        Map<String, Long> wildPairs = deck.stream()
                .filter(PropertyWildCard.class::isInstance)
                .map(PropertyWildCard.class::cast)
                .filter(w -> w.getWildPropertyKind() == WildPropertyKind.DUAL_COLOR)
                .map(w -> String.join("/", w.getPrintedColorPairView()))
                .collect(Collectors.groupingBy(p -> p, Collectors.counting()));
        assertEquals(Map.ofEntries(
                Map.entry("LIGHT_BLUE/BROWN", 1L),
                Map.entry("LIGHT_BLUE/RAILROAD", 1L),
                Map.entry("PINK/ORANGE", 2L),
                Map.entry("RED/YELLOW", 2L),
                Map.entry("DARK_BLUE/GREEN", 1L),
                Map.entry("GREEN/RAILROAD", 1L),
                Map.entry("RAILROAD/UTILITY", 1L)
        ), wildPairs, "双色万能组合分布");

        Map<String, Long> rentPairs = deck.stream()
                .filter(ActionCard.class::isInstance)
                .map(ActionCard.class::cast)
                .filter(a -> "RENT_DUAL".equals(a.getEffectCode()))
                .map(a -> String.join("/", a.getRentPaletteView()))
                .collect(Collectors.groupingBy(p -> p, Collectors.counting()));
        assertEquals(Map.ofEntries(
                Map.entry("LIGHT_BLUE/BROWN", 2L),
                Map.entry("PINK/ORANGE", 2L),
                Map.entry("RED/YELLOW", 2L),
                Map.entry("DARK_BLUE/GREEN", 2L),
                Map.entry("RAILROAD/UTILITY", 2L)
        ), rentPairs, "双色租金组合分布");
    }
}
