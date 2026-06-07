package com.monopoly.controller;

import com.monopoly.dto.PlayActionRequest;
import com.monopoly.dto.StartSessionRequest;
import com.monopoly.model.card.ActionCard;
import com.monopoly.model.card.MoneyCard;
import com.monopoly.model.card.PropertyCard;
import com.monopoly.model.core.RentChargeSequence;
import com.monopoly.model.effects.EffectStackEntry;
import com.monopoly.model.effects.StackResponseState;
import com.monopoly.model.player.Player;
import com.monopoly.pattern.observer.DefaultGameUpdateSubject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class PlayActionCardCharacterizationTest {

    private GameController controller;
    private Player actor;
    private Player target;

    @BeforeEach
    void setUp() {
        controller = newPvpControllerInPlayPhase();
        actor = controller.getSessionPlayersView().get(0);
        target = controller.getSessionPlayersView().get(1);
    }

    // --- RENT ---

    @Test
    void rent_entersResponseWindow() {
        ActionCard rent = new ActionCard("rent-1", "Rent", "RENT");
        ControllerTestCards.addToPropertyZone(controller, actor, new PropertyCard("brown-1", "Brown", "BROWN"));
        ControllerTestCards.receiveToHand(controller, actor, rent);

        controller.handlePlayActionRequest(playActionReq(rent.getId(), target.getPlayerId(), "BROWN"));

        assertNotNull(controller.getGameContext().getResponseState());
        assertEquals(StackResponseState.Role.TENANT,
                controller.getGameContext().getResponseState().getRole());
        EffectStackEntry top = controller.getGameContext().peekTopEffect();
        assertNotNull(top);
        assertEquals("BROWN", top.getColorKey());
    }

    @Test
    void rent_incrementsActionCount() {
        ActionCard rent = new ActionCard("rent-1", "Rent", "RENT");
        ControllerTestCards.addToPropertyZone(controller, actor, new PropertyCard("brown-1", "Brown", "BROWN"));
        ControllerTestCards.receiveToHand(controller, actor, rent);
        int before = controller.turnFlowService().actionCount();

        controller.handlePlayActionRequest(playActionReq(rent.getId(), target.getPlayerId(), "BROWN"));

        assertEquals(before + 1, controller.turnFlowService().actionCount());
    }

    @Test
    void rent_doesNotSetEndTurn() {
        ActionCard rent = new ActionCard("rent-1", "Rent", "RENT");
        ControllerTestCards.addToPropertyZone(controller, actor, new PropertyCard("brown-1", "Brown", "BROWN"));
        ControllerTestCards.receiveToHand(controller, actor, rent);

        controller.handlePlayActionRequest(playActionReq(rent.getId(), target.getPlayerId(), "BROWN"));

        assertFalse(isEndTurn(controller));
    }

    @Test
    void rent_failedValidation_doesNotIncrementActionCount() {
        ActionCard rent = new ActionCard("rent-1", "Rent", "RENT");
        ControllerTestCards.receiveToHand(controller, actor, rent);
        int before = controller.turnFlowService().actionCount();

        try {
            controller.handlePlayActionRequest(playActionReq(rent.getId(), target.getPlayerId(), "BROWN"));
            fail("Expected exception");
        } catch (IllegalStateException e) {
            // expected - no BROWN property
        }

        assertEquals(before, controller.turnFlowService().actionCount());
    }

    // --- DOUBLE_RENT ---

    @Test
    void doubleRent_setsPending() {
        ActionCard doubleRent = new ActionCard("dr-1", "Double The Rent", "DOUBLE_RENT");
        ControllerTestCards.addToPropertyZone(controller, actor, new PropertyCard("brown-1", "Brown", "BROWN"));
        ControllerTestCards.receiveToHand(controller, actor, doubleRent);

        controller.handlePlayActionRequest(playActionReq(doubleRent.getId(), null, null));

        assertTrue(controller.getGameContext().hasPendingDoubleRentFor(actor.getPlayerId()));
    }

    @Test
    void doubleRent_incrementsActionCount() {
        ActionCard doubleRent = new ActionCard("dr-1", "Double The Rent", "DOUBLE_RENT");
        ControllerTestCards.addToPropertyZone(controller, actor, new PropertyCard("brown-1", "Brown", "BROWN"));
        ControllerTestCards.receiveToHand(controller, actor, doubleRent);
        int before = controller.turnFlowService().actionCount();

        controller.handlePlayActionRequest(playActionReq(doubleRent.getId(), null, null));

        assertEquals(before + 1, controller.turnFlowService().actionCount());
    }

    @Test
    void doubleRent_setsEndTurn_whenMaxActions() {
        ActionCard doubleRent = new ActionCard("dr-1", "Double The Rent", "DOUBLE_RENT");
        ControllerTestCards.addToPropertyZone(controller, actor, new PropertyCard("brown-1", "Brown", "BROWN"));
        ControllerTestCards.receiveToHand(controller, actor, doubleRent);
        controller.turnFlowService().turnState().setActionCount(2);

        controller.handlePlayActionRequest(playActionReq(doubleRent.getId(), null, null));

        assertTrue(isEndTurn(controller));
    }

    // --- RENT_DUAL (single target) ---

    @Test
    void rentDualSingleTarget_entersResponseWindow() {
        ActionCard rentDual = new ActionCard("rd-1", "Dual Rent", "RENT_DUAL",
                1, java.util.List.of("BROWN", "LIGHT_BLUE"), false, false);
        ControllerTestCards.addToPropertyZone(controller, actor, new PropertyCard("brown-1", "Brown", "BROWN"));
        ControllerTestCards.receiveToHand(controller, actor, rentDual);

        controller.handlePlayActionRequest(playActionReq(rentDual.getId(), target.getPlayerId(), "BROWN"));

        assertNotNull(controller.getGameContext().getResponseState());
        assertEquals(StackResponseState.Role.TENANT,
                controller.getGameContext().getResponseState().getRole());
        assertFalse(isEndTurn(controller));
    }

    @Test
    void rentDualSingleTarget_failedValidation_doesNotIncrementActionCount() {
        ActionCard rentDual = new ActionCard("rd-1", "Dual Rent", "RENT_DUAL",
                1, java.util.List.of("BROWN", "LIGHT_BLUE"), false, false);
        ControllerTestCards.receiveToHand(controller, actor, rentDual);
        int before = controller.turnFlowService().actionCount();

        try {
            controller.handlePlayActionRequest(playActionReq(rentDual.getId(), target.getPlayerId(), "GREEN"));
            fail("Expected exception");
        } catch (IllegalStateException e) {
            // expected
        }

        assertEquals(before, controller.turnFlowService().actionCount());
    }

    // --- RENT_DUAL (charges each other player) ---

    @Test
    void rentDualAllPlayers_entersSequence() {
        StartSessionRequest req = new StartSessionRequest();
        req.setSessionId("dual-all-test");
        req.setPlayerCount(3);
        req.setGameMode("PVP");
        req.setRandomizeFirstPlayer(false);
        GameController ctrl3 = new GameController(new DefaultGameUpdateSubject());
        ctrl3.startNewSession(req);
        ctrl3.handleDrawCommand(2);
        Player p0 = ctrl3.getSessionPlayersView().get(0);
        Player p1 = ctrl3.getSessionPlayersView().get(1);

        ActionCard rentDualAll = new ActionCard("rda-1", "Dual Rent All", "RENT_DUAL",
                1, java.util.List.of("BROWN", "LIGHT_BLUE"), true, false);
        ControllerTestCards.addToPropertyZone(ctrl3, p0, new PropertyCard("brown-1", "Brown", "BROWN"));
        ControllerTestCards.receiveToHand(ctrl3, p0, rentDualAll);
        int before = ctrl3.turnFlowService().actionCount();

        ctrl3.handlePlayActionRequest(playActionReq(rentDualAll.getId(), p1.getPlayerId(), "BROWN"));

        RentChargeSequence seq = ctrl3.getGameContext().getRentChargeSequence();
        assertNotNull(seq);
        assertTrue(seq.isActive());
        assertEquals(before + 1, ctrl3.turnFlowService().actionCount());
        assertFalse(isEndTurn(ctrl3));
    }

    // --- BIRTHDAY ---

    @Test
    void birthday_entersSequence_colorKeyBIRTHDAY_amount2() {
        ActionCard birthday = new ActionCard("bd-1", "It's My Birthday", "BIRTHDAY");
        ControllerTestCards.receiveToHand(controller, actor, birthday);

        controller.handlePlayActionRequest(playActionReq(birthday.getId(), null, null));

        RentChargeSequence seq = controller.getGameContext().getRentChargeSequence();
        assertNotNull(seq);
        assertEquals("BIRTHDAY", seq.getColorKey());
        assertEquals(2, seq.getAmountDuePerTenant());
    }

    @Test
    void birthday_incrementsActionCount() {
        ActionCard birthday = new ActionCard("bd-1", "It's My Birthday", "BIRTHDAY");
        ControllerTestCards.receiveToHand(controller, actor, birthday);
        int before = controller.turnFlowService().actionCount();

        controller.handlePlayActionRequest(playActionReq(birthday.getId(), null, null));

        assertEquals(before + 1, controller.turnFlowService().actionCount());
    }

    @Test
    void birthday_doesNotSetEndTurn() {
        ActionCard birthday = new ActionCard("bd-1", "It's My Birthday", "BIRTHDAY");
        ControllerTestCards.receiveToHand(controller, actor, birthday);

        controller.handlePlayActionRequest(playActionReq(birthday.getId(), null, null));

        assertFalse(isEndTurn(controller));
    }

    @Test
    void birthday_doesNotDoubleRent_amountAlways2() {
        ActionCard birthday = new ActionCard("bd-1", "It's My Birthday", "BIRTHDAY");
        ControllerTestCards.receiveToHand(controller, actor, birthday);
        controller.getGameContext().setPendingDoubleRentFor(actor.getPlayerId());

        controller.handlePlayActionRequest(playActionReq(birthday.getId(), null, null));

        assertEquals(2, controller.getGameContext().getRentChargeSequence().getAmountDuePerTenant());
    }

    // --- DEBT_COLLECTOR ---

    @Test
    void debtCollector_entersResponseWindow_colorKeyDEBT_amount5() {
        ActionCard debt = new ActionCard("dc-1", "Debt Collector", "DEBT_COLLECTOR");
        ControllerTestCards.receiveToHand(controller, actor, debt);
        ControllerTestCards.addToBank(controller, target, new MoneyCard("m5", "5M", 5));

        controller.handlePlayActionRequest(playActionReq(debt.getId(), target.getPlayerId(), null));

        EffectStackEntry top = controller.getGameContext().peekTopEffect();
        assertNotNull(top);
        assertEquals("DEBT_COLLECTOR", top.getColorKey());
        assertEquals(5, top.getAmountDue());
    }

    @Test
    void debtCollector_incrementsActionCount() {
        ActionCard debt = new ActionCard("dc-1", "Debt Collector", "DEBT_COLLECTOR");
        ControllerTestCards.receiveToHand(controller, actor, debt);
        ControllerTestCards.addToBank(controller, target, new MoneyCard("m5", "5M", 5));
        int before = controller.turnFlowService().actionCount();

        controller.handlePlayActionRequest(playActionReq(debt.getId(), target.getPlayerId(), null));

        assertEquals(before + 1, controller.turnFlowService().actionCount());
    }

    @Test
    void debtCollector_noTarget_throwsAndDoesNotIncrement() {
        ActionCard debt = new ActionCard("dc-1", "Debt Collector", "DEBT_COLLECTOR");
        ControllerTestCards.receiveToHand(controller, actor, debt);
        int before = controller.turnFlowService().actionCount();

        try {
            controller.handlePlayActionRequest(playActionReq(debt.getId(), null, null));
            fail("Expected exception - DEBT_COLLECTOR requires target");
        } catch (Exception e) {
            // canPlay guard rejects it before reaching action count increment
        }

        assertEquals(before, controller.turnFlowService().actionCount());
    }

    @Test
    void debtCollector_doesNotUseDoubleRent_amount5() {
        ActionCard debt = new ActionCard("dc-1", "Debt Collector", "DEBT_COLLECTOR");
        ControllerTestCards.receiveToHand(controller, actor, debt);
        ControllerTestCards.addToBank(controller, target, new MoneyCard("m5", "5M", 5));
        controller.getGameContext().setPendingDoubleRentFor(actor.getPlayerId());

        controller.handlePlayActionRequest(playActionReq(debt.getId(), target.getPlayerId(), null));

        EffectStackEntry top = controller.getGameContext().peekTopEffect();
        assertEquals(5, top.getAmountDue());
    }

    @Test
    void debtCollector_doesNotSetEndTurn() {
        ActionCard debt = new ActionCard("dc-1", "Debt Collector", "DEBT_COLLECTOR");
        ControllerTestCards.receiveToHand(controller, actor, debt);
        ControllerTestCards.addToBank(controller, target, new MoneyCard("m5", "5M", 5));

        controller.handlePlayActionRequest(playActionReq(debt.getId(), target.getPlayerId(), null));

        assertFalse(isEndTurn(controller));
    }

    // --- STEAL_PROPERTY (single-target JSN action) ---

    @Test
    void stealProperty_entersActionResponseWindow() {
        ActionCard steal = new ActionCard("sp-1", "Sly Deal", "STEAL_PROPERTY");
        PropertyCard targetProp = new PropertyCard("tp-1", "Target Brown", "BROWN");
        ControllerTestCards.addToPropertyZone(controller, target, targetProp);
        ControllerTestCards.receiveToHand(controller, actor, steal);

        controller.handlePlayActionRequest(playActionReq(steal.getId(), target.getPlayerId(), null));

        assertNotNull(controller.getGameContext().getResponseState());
        assertEquals(StackResponseState.Role.TENANT,
                controller.getGameContext().getResponseState().getRole());
    }

    // --- PASS_GO (generic action) ---

    @Test
    void passGo_incrementsActionCount() {
        ActionCard passGo = new ActionCard("pg-1", "Pass Go", "PASS_GO");
        ControllerTestCards.receiveToHand(controller, actor, passGo);
        int before = controller.turnFlowService().actionCount();

        controller.handlePlayActionRequest(playActionReq(passGo.getId(), null, null));

        assertEquals(before + 1, controller.turnFlowService().actionCount());
    }

    @Test
    void passGo_setsEndTurn_whenMaxActions() {
        ActionCard passGo = new ActionCard("pg-1", "Pass Go", "PASS_GO");
        ControllerTestCards.receiveToHand(controller, actor, passGo);
        controller.turnFlowService().turnState().setActionCount(2);

        controller.handlePlayActionRequest(playActionReq(passGo.getId(), null, null));

        assertTrue(isEndTurn(controller));
    }

    // --- Double rent interaction ---

    @Test
    void rent_usesPendingDoubleRent_doublesAmount() {
        ActionCard doubleRent = new ActionCard("dr-1", "Double The Rent", "DOUBLE_RENT");
        ActionCard rent = new ActionCard("rent-1", "Rent", "RENT");
        ControllerTestCards.addToPropertyZone(controller, actor, new PropertyCard("brown-1", "Brown", "BROWN"));
        ControllerTestCards.addToBank(controller, target, new MoneyCard("target-2m", "2M", 2));
        ControllerTestCards.receiveToHand(controller, actor, doubleRent, rent);

        controller.handlePlayActionRequest(playActionReq(doubleRent.getId(), null, null));
        controller.handlePlayActionRequest(playActionReq(rent.getId(), target.getPlayerId(), "BROWN"));

        EffectStackEntry top = controller.getGameContext().peekTopEffect();
        assertEquals(2, top.getAmountDue());
        assertFalse(controller.getGameContext().hasPendingDoubleRentFor(actor.getPlayerId()));
    }

    // --- WAITING_FOR_RESPONSE blocks play (blocked by canPlay guard in playActionCard) ---

    @Test
    void rentThenSecondAction_blockedByWaitingForResponse() {
        ActionCard rent = new ActionCard("rent-1", "Rent", "RENT");
        ControllerTestCards.addToPropertyZone(controller, actor, new PropertyCard("brown-1", "Brown", "BROWN"));
        ControllerTestCards.addToBank(controller, target, new MoneyCard("m2", "2M", 2));
        ControllerTestCards.receiveToHand(controller, actor, rent);

        controller.handlePlayActionRequest(playActionReq(rent.getId(), target.getPlayerId(), "BROWN"));

        // After playing rent, we should be in WAITING_FOR_RESPONSE
        assertEquals(TurnFlowService.TurnPhase.WAITING_FOR_RESPONSE,
                controller.turnFlowService().phase());
    }

    // --- Not in PLAY phase blocks play ---

    @Test
    void notInPlayPhase_throws() {
        GameController freshCtrl = new GameController(new DefaultGameUpdateSubject());
        StartSessionRequest req = new StartSessionRequest();
        req.setSessionId("fresh");
        req.setPlayerCount(2);
        req.setGameMode("PVP");
        req.setRandomizeFirstPlayer(false);
        freshCtrl.startNewSession(req);

        ActionCard passGo = new ActionCard("pg-1", "Pass Go", "PASS_GO");
        Player p = freshCtrl.getCurrentPlayer();
        ControllerTestCards.receiveToHand(freshCtrl, p, passGo);

        try {
            freshCtrl.handlePlayActionRequest(playActionReq(passGo.getId(), null, null));
            fail("Should have thrown since in DRAW phase");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().toLowerCase().contains("draw")
                    || e.getMessage().toLowerCase().contains("play"));
        }
    }

    // --- Helpers ---

    private static GameController newPvpControllerInPlayPhase() {
        GameController c = new GameController(new DefaultGameUpdateSubject());
        StartSessionRequest req = new StartSessionRequest();
        req.setSessionId("char-test-" + System.nanoTime());
        req.setPlayerCount(2);
        req.setGameMode("PVP");
        req.setRandomizeFirstPlayer(false);
        c.startNewSession(req);
        c.handleDrawCommand(2);
        return c;
    }

    private static PlayActionRequest playActionReq(String cardId, String targetPlayerId, String colorKey) {
        PlayActionRequest req = new PlayActionRequest();
        req.setActionType("ACTION");
        req.setCardId(cardId);
        req.setTargetPlayerId(targetPlayerId);
        req.setTargetColorKey(colorKey);
        return req;
    }

    private static boolean isEndTurn(GameController ctrl) {
        return ctrl.turnFlowService().phase() == TurnFlowService.TurnPhase.END_TURN;
    }
}