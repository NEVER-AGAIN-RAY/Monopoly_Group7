package com.monopoly.controller;

import com.monopoly.dto.StartSessionRequest;
import com.monopoly.model.card.Card;
import com.monopoly.model.player.Player;
import com.monopoly.pattern.factory.CardFactory;
import com.monopoly.pattern.factory.MonopolyDealCardFactory;
import com.monopoly.pattern.singleton.GameEngineSingleton;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

final class SessionFactory {

    private final GameController controller;
    private final SeatAssembler seatAssembler;
    private final CardFactory cardFactory = new MonopolyDealCardFactory();

    SessionFactory(GameController controller, SeatAssembler seatAssembler) {
        this.controller = controller;
        this.seatAssembler = seatAssembler;
    }

    void startNewSession(StartSessionRequest req) {
        if (req == null) {
            throw new IllegalArgumentException("StartSessionRequest 不能为 null。");
        }
        if (controller.isPaused()) {
            controller.recordError("PAUSED", "游戏已暂停，无法开始或重开对局。");
            controller.pushSnapshot(controller.getCurrentSessionId(), "RULE_VIOLATION");
            return;
        }
        String mode = req.getGameMode() == null ? "" : req.getGameMode().trim().toUpperCase();
        if (mode.isBlank()) {
            mode = "HVM";
        }
        int requestedCount = req.getPlayerCount() <= 0 ? 2 : req.getPlayerCount();
        List<String> customRoles = "CUSTOM".equals(mode)
                ? SeatAssembler.parseCustomRoles(req)
                : List.of();
        int count = customRoles.isEmpty() ? requestedCount : customRoles.size();
        if (count < 2 || count > 5) {
            throw new IllegalArgumentException("playerCount 必须在 2–5 之间，当前为 " + count + "。");
        }
        if ("CUSTOM".equals(mode) && customRoles.isEmpty()) {
            customRoles = SeatAssembler.defaultCustomRoles(count);
        }
        if (!"HVM".equals(mode) && !"PVP".equals(mode)
                && !"LLM".equals(mode) && !"AI_VS_AI".equals(mode)
                && !"CUSTOM".equals(mode)) {
            throw new IllegalArgumentException(
                    "gameMode 必须为 HVM、PVP、LLM、AI_VS_AI 或 CUSTOM，当前为 " + req.getGameMode() + "。");
        }

        controller.resetRuntimeForNewSession(req.getSessionId(), mode);
        GameEngineSingleton engine = controller.getEngine();
        List<Card> deck = new ArrayList<>(cardFactory.createStandardDeck108());
        Long deckSeed = Long.getLong("monopoly.deck.seed");
        if (deckSeed != null) {
            Collections.shuffle(deck, new Random(deckSeed));
            engine.useDeterministicReshuffleSeed(reshuffleSeed(deckSeed));
        } else {
            Collections.shuffle(deck, ThreadLocalRandom.current());
            engine.clearDeterministicReshuffleSeed();
        }
        engine.attachDrawPile(deck);

        List<Player> players = seatAssembler.buildSeats(
                mode, count, customRoles, req, controller.getCurrentSessionId());
        controller.installPlayers(players);

        int initialEach = TurnFlowService.INITIAL_HAND_SIZE;
        dealInitialHands:
        for (int round = 0; round < initialEach; round++) {
            for (Player p : players) {
                Card card = engine.drawOne();
                if (card == null) {
                    break dealInitialHands;
                }
                p.receiveCardToHand(card);
            }
        }

        if (req.isRandomizeFirstPlayer()) {
            Long firstPlayerSeed = Long.getLong("monopoly.firstPlayer.seed");
            int firstIndex = firstPlayerSeed != null
                    ? new Random(firstPlayerSeed).nextInt(players.size())
                    : ThreadLocalRandom.current().nextInt(players.size());
            controller.getTurnManager().setCurrentIndex(firstIndex);
        }

        Player current = controller.getTurnManager().getCurrentPlayer();
        controller.turnFlowService().initForSession(current);
        controller.clearLastError();
        controller.assertDeckIntegrityOrLog();
        controller.pushSnapshot(
                controller.getCurrentSessionId(),
                "INIT",
                "新局已开始：牌堆已随机洗牌，起手按真人发牌方式轮流发 5 张。");
        controller.runAiTurnIfNeeded(current);
    }

    private static long reshuffleSeed(long deckSeed) {
        return deckSeed ^ 0x9E3779B97F4A7C15L;
    }
}
