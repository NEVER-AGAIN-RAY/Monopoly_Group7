package com.monopoly.controller;

import com.monopoly.dto.ActionOptionsResult;
import com.monopoly.model.card.MoneyCard;
import com.monopoly.model.card.PropertyWildCard;
import com.monopoly.model.card.PropertyWildCard.WildPropertyKind;
import com.monopoly.model.player.HumanPlayer;
import com.monopoly.persistence.GameSessionMemento;
import com.monopoly.pattern.singleton.GameEngineSingleton;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
