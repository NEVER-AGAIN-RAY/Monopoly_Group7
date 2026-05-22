package com.monopoly.controller;

import com.monopoly.dto.PlayActionRequest;
import com.monopoly.dto.StartSessionRequest;
import com.monopoly.model.card.ActionCard;
import com.monopoly.model.card.MoneyCard;
import com.monopoly.model.card.PropertyCard;
import com.monopoly.model.effects.StackResponseState;
import com.monopoly.model.player.Player;
import com.monopoly.pattern.observer.DefaultGameUpdateSubject;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JustSayNoActionResponseTest {

    @Test
    void targetMayCancelStealPropertyWithJustSayNo() {
        GameController controller = newPvpControllerInPlayPhase();
        Player actor = controller.getSessionPlayersView().get(0);
        Player target = controller.getSessionPlayersView().get(1);
        PropertyCard targetProperty = new PropertyCard("target-brown", "Target Brown", "BROWN");
        ActionCard steal = new ActionCard("steal-action", "Sly Deal", "STEAL_PROPERTY");
        ActionCard targetNo = new ActionCard("target-no", "Just Say No", "RENT_WAIVER");
        target.addToPropertyZone(targetProperty);
        actor.receiveCardToHand(steal);
        target.receiveCardToHand(targetNo);

        playStealProperty(controller, actor, target, steal, targetProperty);

        assertAwaiting(controller, target, StackResponseState.Role.TENANT);
        assertTrue(target.getPropertyCardsView().contains(targetProperty));

        playJustSayNo(controller, target, targetNo);

        assertAwaiting(controller, actor, StackResponseState.Role.LANDLORD_COUNTER);

        passResponse(controller, actor);

        assertNull(controller.getGameContext().getResponseState());
        assertTrue(target.getPropertyCardsView().contains(targetProperty));
        assertFalse(actor.getPropertyCardsView().contains(targetProperty));
    }

    @Test
    void actorMayCounterTargetJustSayNoAndStealStillResolves() {
        GameController controller = newPvpControllerInPlayPhase();
        Player actor = controller.getSessionPlayersView().get(0);
        Player target = controller.getSessionPlayersView().get(1);
        PropertyCard targetProperty = new PropertyCard("target-blue", "Target Blue", "LIGHT_BLUE");
        ActionCard steal = new ActionCard("steal-action", "Sly Deal", "STEAL_PROPERTY");
        ActionCard targetNo = new ActionCard("target-no", "Just Say No", "RENT_WAIVER");
        ActionCard actorNo = new ActionCard("actor-no", "Just Say No", "RENT_WAIVER");
        target.addToPropertyZone(targetProperty);
        actor.receiveCardToHand(steal);
        target.receiveCardToHand(targetNo);
        actor.receiveCardToHand(actorNo);

        playStealProperty(controller, actor, target, steal, targetProperty);
        playJustSayNo(controller, target, targetNo);
        playJustSayNo(controller, actor, actorNo);

        assertNull(controller.getGameContext().getResponseState());
        assertFalse(target.getPropertyCardsView().contains(targetProperty));
        assertTrue(actor.getHandCardsView().contains(targetProperty));
        assertFalse(actor.getPropertyCardsView().contains(targetProperty));
    }

    @Test
    void birthdayGivesEachTargetAJustSayNoWindow() {
        GameController controller = newPvpControllerInPlayPhase();
        Player actor = controller.getSessionPlayersView().get(0);
        Player target = controller.getSessionPlayersView().get(1);
        ActionCard birthday = new ActionCard("birthday-action", "Birthday", "BIRTHDAY");
        ActionCard targetNo = new ActionCard("target-no", "Just Say No", "RENT_WAIVER");
        MoneyCard targetMoney = new MoneyCard("target-2m", "2M", 2);
        actor.receiveCardToHand(birthday);
        target.receiveCardToHand(targetNo);
        target.addToBank(targetMoney);

        PlayActionRequest req = new PlayActionRequest();
        req.setActionType("ACTION");
        req.setCardId(birthday.getId());
        controller.handlePlayActionRequest(req);

        assertAwaiting(controller, target, StackResponseState.Role.TENANT);
        assertTrue(target.getBankCardsView().contains(targetMoney));

        playJustSayNo(controller, target, targetNo);
        passResponse(controller, actor);

        assertNull(controller.getGameContext().getResponseState());
        assertTrue(target.getBankCardsView().contains(targetMoney));
        assertFalse(actor.getBankCardsView().contains(targetMoney));
        assertFalse(actor.getHandCardsView().contains(targetMoney));
    }

    @Test
    void birthdayResponsePassCollectsTwoMoney() {
        GameController controller = newPvpControllerInPlayPhase();
        Player actor = controller.getSessionPlayersView().get(0);
        Player target = controller.getSessionPlayersView().get(1);
        ActionCard birthday = new ActionCard("birthday-action", "Birthday", "BIRTHDAY");
        MoneyCard targetMoney = new MoneyCard("target-2m", "2M", 2);
        actor.receiveCardToHand(birthday);
        target.addToBank(targetMoney);

        PlayActionRequest req = new PlayActionRequest();
        req.setActionType("ACTION");
        req.setCardId(birthday.getId());
        controller.handlePlayActionRequest(req);
        passResponse(controller, target);

        assertNull(controller.getGameContext().getResponseState());
        assertFalse(target.getBankCardsView().contains(targetMoney));
        assertTrue(actor.getHandCardsView().contains(targetMoney));
        assertFalse(actor.getBankCardsView().contains(targetMoney));
    }

    private static GameController newPvpControllerInPlayPhase() {
        GameController controller = new GameController(new DefaultGameUpdateSubject());
        StartSessionRequest req = new StartSessionRequest();
        req.setSessionId("jsn-action-test");
        req.setPlayerCount(2);
        req.setGameMode("PVP");
        req.setRandomizeFirstPlayer(false);
        controller.startNewSession(req);
        controller.handleDrawCommand(2);
        return controller;
    }

    private static void playStealProperty(
            GameController controller,
            Player actor,
            Player target,
            ActionCard steal,
            PropertyCard targetProperty) {
        PlayActionRequest req = new PlayActionRequest();
        req.setActionType("ACTION");
        req.setCardId(steal.getId());
        req.setTargetPlayerId(target.getPlayerId());
        req.setTargetCardId(targetProperty.getId());
        req.setTargetZone("PROPERTY");
        controller.handlePlayActionRequest(req);
        assertFalse(actor.getHandCardsView().contains(steal));
        assertTrue(actor.getActionZoneCardsView().contains(steal));
    }

    private static void playJustSayNo(GameController controller, Player actor, ActionCard card) {
        PlayActionRequest req = new PlayActionRequest();
        req.setActionType("ACTION");
        req.setActingPlayerId(actor.getPlayerId());
        req.setCardId(card.getId());
        controller.handlePlayActionRequest(req);
        assertFalse(actor.getHandCardsView().contains(card));
        assertTrue(actor.getActionZoneCardsView().contains(card));
    }

    private static void passResponse(GameController controller, Player actor) {
        PlayActionRequest pass = new PlayActionRequest();
        pass.setActionType("RESPONSE_PASS");
        pass.setActingPlayerId(actor.getPlayerId());
        controller.handlePlayActionRequest(pass);
    }

    private static void assertAwaiting(
            GameController controller,
            Player player,
            StackResponseState.Role role) {
        StackResponseState state = controller.getGameContext().getResponseState();
        assertEquals(player.getPlayerId(), state.getAwaitingPlayerId());
        assertEquals(role, state.getRole());
    }
}
