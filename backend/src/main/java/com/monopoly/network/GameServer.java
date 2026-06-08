package com.monopoly.network;

import com.google.gson.JsonObject;
import com.monopoly.controller.GameController;
import com.monopoly.dto.GameStateSnapshot;
import com.monopoly.model.card.Card;
import com.monopoly.model.player.HumanPlayer;
import com.monopoly.model.player.Player;
import com.monopoly.network.connection.ClientConnection;
import com.monopoly.network.protocol.MessageDispatcher;
import com.monopoly.pattern.observer.GameUpdateObserver;
import com.monopoly.pattern.observer.GameUpdateSubject;
import com.monopoly.presentation.HandCardJson;

import java.io.IOException;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * WebSocket server facade: routes JSON to collaborators and broadcasts snapshots.
 */
public class GameServer implements GameUpdateObserver {

    private final MessageDispatcher dispatcher = new MessageDispatcher();
    private final AtomicLong requestCounter = new AtomicLong(1);
    private final SessionHub hub;
    private final SaveLoadVoteCoordinator saveLoad;
    private final LobbyService lobby;
    private final MessageRouter router;

    public GameServer() {
        this.hub = new SessionHub(dispatcher, this);
        this.saveLoad = new SaveLoadVoteCoordinator(hub, dispatcher, requestCounter);
        this.lobby = new LobbyService(hub, dispatcher, this::pushPrivateHands);
        this.router = new MessageRouter(hub, dispatcher, lobby, saveLoad);
    }

    /**
     * Legacy test hook: binds a default controller for messages without sessionId.
     */
    public void wireController(GameController controller) {
        hub.setGameController(controller);
        if (controller != null) {
            String sessionId = SessionHub.normalizeSessionId(controller.getCurrentSessionIdPublic());
            if (!"unknown".equals(sessionId)) {
                hub.registerControllerSession(controller);
            }
        }
    }

    /** Called when a WebSocket session opens */
    public void onClientConnected(ClientConnection client) {
        hub.addClient(client);
    }

    public void onClientDisconnected(ClientConnection client) {
        hub.unregister(client);
        hub.removeClient(client);
        lobby.onClientDisconnected(client);
        lobby.broadcastRoomList();
    }

    /** Inbound JSON dispatcher */
    public void onMessage(ClientConnection from, String json) {
        router.route(from, json);
    }

    @Override
    public void onGameStateChanged(GameStateSnapshot snapshot) {
        String payload = dispatcher.toJsonBroadcast(snapshot);
        hub.sendToSessionTargets(snapshot.getSessionId(), payload);
        pushPrivateHands(snapshot.getSessionId());
    }

    /** Registers as GameUpdateObserver */
    public void attachTo(GameUpdateSubject subject) {
        subject.registerObserver(this);
    }

    public Optional<String> getPlayerIdOf(ClientConnection client) {
        return hub.sessionRegistry().getPlayerId(client);
    }

    public Set<ClientConnection> connectionsOf(String playerId) {
        return hub.sessionRegistry().connectionsOf(playerId);
    }

    private void pushPrivateHands(String sessionId) {
        GameController controller = hub.controllerForSession(sessionId);
        if (controller == null) {
            return;
        }
        for (Player player : controller.getSessionPlayersView()) {
            if (!(player instanceof HumanPlayer)) {
                continue;
            }
            Set<ClientConnection> targets = hub.connectionsOfPlayerInSession(player.getPlayerId(), sessionId);
            if (targets.isEmpty()) {
                continue;
            }
            String msg = buildMyHandMessage(player);
            for (ClientConnection conn : targets) {
                if (!conn.isOpen()) {
                    continue;
                }
                try {
                    conn.sendText(msg);
                } catch (IOException e) {
                    hub.unregister(conn);
                    hub.removeClient(conn);
                }
            }
        }
    }

    private String buildMyHandMessage(Player player) {
        JsonObject payload = new JsonObject();
        payload.addProperty("playerId", player.getPlayerId());
        com.google.gson.JsonArray cards = new com.google.gson.JsonArray();
        for (Card card : player.getHandCardsView()) {
            cards.add(HandCardJson.toHandCardObject(card));
        }
        payload.add("cards", cards);
        return dispatcher.toJsonEnvelope("MY_HAND", payload);
    }
}
