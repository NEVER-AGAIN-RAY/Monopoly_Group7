package com.monopoly.pattern.factory;

import com.monopoly.model.card.ActionCard;
import com.monopoly.model.card.Card;
import com.monopoly.model.card.MoneyCard;
import com.monopoly.model.card.PropertyCard;
import com.monopoly.model.card.PropertyWildCard;
import com.monopoly.model.card.PropertyWildCard.WildPropertyKind;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 标准 108 张牌包含房产、万能、现金、行动四类（与工厂实现一致）。
 */
class DeckCompositionTest {

    @Test
    void standardDeck_hasExpectedTypeCounts() {
        List<Card> deck = new MonopolyDealCardFactory().createStandardDeck108();
        assertEquals(108, deck.size());

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
        assertEquals(49, action, "行动牌张数（含收租、房屋、旅馆等）");

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
        assertEquals(8, rent, "收租 RENT（含任意色租金牌）");
        assertEquals(3, rentWildcard, "任意色租金牌（展示用标记）");
        assertEquals(5, rentDual, "双色收租 RENT_DUAL（实体 1v1）");
        assertEquals(13, rent + rentDual, "租金类行动牌合计 13");
        assertEquals(12, passGo, "PASS_GO");

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
    }
}
