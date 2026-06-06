package com.monopoly.controller;

import com.monopoly.dto.ActionOptionsResult;
import com.monopoly.model.card.ActionCard;
import com.monopoly.model.card.MoneyCard;
import com.monopoly.model.card.PropertyCard;
import com.monopoly.model.card.PropertyWildCard;
import com.monopoly.model.card.PropertyWildCard.WildPropertyKind;
import com.monopoly.model.core.GameContext;
import com.monopoly.model.player.HumanPlayer;
import com.monopoly.persistence.GameSessionMemento;
import com.monopoly.pattern.singleton.GameEngineSingleton;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayOptionsServiceTest {

    @AfterEach
    void tearDown() {
        GameSessionMemento.resetSingletonEngineForTests();
    }

    @Test
    void deposit_money_returnsSingleRow() {
        HumanPlayer p = new HumanPlayer("p1", "P1");
        MoneyCard m = new MoneyCard("m1", "1M", 1);
        ActionOptionsResult r = PlayOptionsService.build(
                p, m, "DEPOSIT", List.of(p), GameEngineSingleton.getInstance());
        assertTrue(r.isOk());
        assertEquals(1, r.getOptions().size());
    }

    @Test
    void deploy_wild_includesColorChoices() {
        HumanPlayer p = new HumanPlayer("p1", "P1");
        PropertyWildCard w = new PropertyWildCard("w1", "wild");
        ActionOptionsResult r = PlayOptionsService.build(
                p, w, "DEPLOY", List.of(p), GameEngineSingleton.getInstance());
        assertTrue(r.isOk());
        assertEquals(10, r.getOptions().size());
    }

    @Test
    void deploy_dualWild_onlyTwoColors() {
        HumanPlayer p = new HumanPlayer("p1", "P1");
        PropertyWildCard w = new PropertyWildCard(
                "w1", "wild", WildPropertyKind.DUAL_COLOR, List.of("RED", "YELLOW"));
        ActionOptionsResult r = PlayOptionsService.build(
                p, w, "DEPLOY", List.of(p), GameEngineSingleton.getInstance());
        assertTrue(r.isOk());
        assertEquals(2, r.getOptions().size());
    }

    @Test
    void deploy_preassignedWild_onlyOffersLockedColor() {
        HumanPlayer p = new HumanPlayer("p1", "P1");
        PropertyWildCard w = new PropertyWildCard(
                "w1", "wild", WildPropertyKind.DUAL_COLOR, List.of("RED", "YELLOW"));
        w.setAssignedColorKey("YELLOW");

        ActionOptionsResult r = PlayOptionsService.build(
                p, w, "DEPLOY", List.of(p), GameEngineSingleton.getInstance());

        assertTrue(r.isOk());
        assertEquals(1, r.getOptions().size());
        assertEquals("YELLOW", r.getOptions().get(0).getTargetColorKey());
    }

    @Test
    void forcedDealOptionsExcludeCompleteSetProperties() {
        HumanPlayer actor = new HumanPlayer("p1", "P1");
        HumanPlayer target = new HumanPlayer("p2", "P2");
        ActionCard forcedDeal = new ActionCard("fd", "Forced Deal", "FORCED_DEAL");
        actor.addToPropertyZone(new PropertyCard("actor-red", "Actor Red", "RED"));
        actor.addToPropertyZone(new PropertyCard("actor-brown-1", "Actor Brown 1", "BROWN"));
        actor.addToPropertyZone(new PropertyCard("actor-brown-2", "Actor Brown 2", "BROWN"));
        target.addToPropertyZone(new PropertyCard("target-green", "Target Green", "GREEN"));
        target.addToPropertyZone(new PropertyCard("target-blue-1", "Target Blue 1", "DARK_BLUE"));
        target.addToPropertyZone(new PropertyCard("target-blue-2", "Target Blue 2", "DARK_BLUE"));

        ActionOptionsResult r = PlayOptionsService.build(
                actor, forcedDeal, "ACTION", List.of(actor, target), GameEngineSingleton.getInstance());

        assertTrue(r.isOk());
        assertEquals(1, r.getOptions().size());
        assertEquals("target-green", r.getOptions().get(0).getTargetCardId());
        assertEquals("actor-red", r.getOptions().get(0).getActorCardId());
    }

    @Test
    void forcedDealOptionsIncludeAllLegalCombinationsWithoutTruncation() {
        HumanPlayer actor = new HumanPlayer("p1", "P1");
        HumanPlayer target = new HumanPlayer("p2", "P2");
        ActionCard forcedDeal = new ActionCard("fd", "Forced Deal", "FORCED_DEAL");
        for (int i = 0; i < 9; i++) {
            actor.addToPropertyZone(new PropertyWildCard("actor-wild-" + i, "Actor Wild " + i));
            target.addToPropertyZone(new PropertyWildCard("target-wild-" + i, "Target Wild " + i));
        }

        ActionOptionsResult r = PlayOptionsService.build(
                actor, forcedDeal, "ACTION", List.of(actor, target), GameEngineSingleton.getInstance());

        assertTrue(r.isOk());
        assertEquals(81, r.getOptions().size());
        assertFalse(r.isTruncated());
    }

    @Test
    void debtCollectorOptionsIncludeEveryOpponentInThreePlayerGame() {
        HumanPlayer actor = new HumanPlayer("p1", "P1");
        HumanPlayer targetA = new HumanPlayer("p2", "P2");
        HumanPlayer targetB = new HumanPlayer("p3", "P3");
        ActionCard debt = new ActionCard("debt", "Debt Collector", "DEBT_COLLECTOR");

        ActionOptionsResult r = PlayOptionsService.build(
                actor, debt, "ACTION", List.of(actor, targetA, targetB), GameEngineSingleton.getInstance());

        assertTrue(r.isOk());
        assertEquals(2, r.getOptions().size());
        assertEquals("p2", r.getOptions().get(0).getTargetPlayerId());
        assertEquals("p3", r.getOptions().get(1).getTargetPlayerId());
    }

    @Test
    void rentOptionsShowPendingDoubleRentAmount() {
        HumanPlayer actor = new HumanPlayer("p1", "P1");
        HumanPlayer target = new HumanPlayer("p2", "P2");
        actor.addToPropertyZone(new PropertyCard("brown", "Brown", "BROWN"));
        ActionCard rent = new ActionCard("rent", "Rent", "RENT");
        GameContext context = new GameContext();
        context.bindPlayers(List.of(actor, target));
        context.setPendingDoubleRentFor(actor.getPlayerId());

        ActionOptionsResult r = PlayOptionsService.build(
                actor, rent, "ACTION", List.of(actor, target), GameEngineSingleton.getInstance(), context);

        assertTrue(r.isOk());
        assertTrue(r.getOptions().stream()
                .anyMatch(o -> o.getLabelZh().contains("双倍后 2M")));
        assertTrue(r.getOptions().stream()
                .anyMatch(o -> Integer.valueOf(1).equals(o.getBaseRentAmountM())
                        && Integer.valueOf(2).equals(o.getDisplayRentAmountM())));
    }
}
