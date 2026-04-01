package com.monopoly.network;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.monopoly.controller.GameController;
import com.monopoly.model.dto.StartSessionRequest;
import com.monopoly.pattern.observer.DefaultGameUpdateSubject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameServerPvpSaveVoteTest {

    private static final Gson GSON = new Gson();

    @Test
    void saveVote_allAck_shouldCommit() {
        Fixture f = new Fixture();
        f.startPvp3();
        f.authAll();

        f.server.onMessage(f.c1, "{\"type\":\"SAVE_GAME\",\"payload\":{\"requestId\":\"req-ok\"}}");
        assertTrue(f.allOut().stream().anyMatch(s -> s.contains("\"type\":\"SAVE_GAME_REQUEST\"") && s.contains("\"req-ok\"")));

        f.server.onMessage(f.c1, "{\"type\":\"SAVE_GAME_ACK\",\"payload\":{\"requestId\":\"req-ok\",\"playerId\":\"pvp-1\"}}");
        f.server.onMessage(f.c2, "{\"type\":\"SAVE_GAME_ACK\",\"payload\":{\"requestId\":\"req-ok\",\"playerId\":\"pvp-2\"}}");
        assertFalse(f.allOut().stream().anyMatch(s -> s.contains("\"SAVE_GAME_RESULT\"") && s.contains("\"ok\":true")));
        f.server.onMessage(f.c3, "{\"type\":\"SAVE_GAME_ACK\",\"payload\":{\"requestId\":\"req-ok\",\"playerId\":\"pvp-3\"}}");
        assertTrue(f.allOut().stream().anyMatch(s -> s.contains("\"SAVE_GAME_RESULT\"") && s.contains("\"ok\":true")));
    }

    @Test
    void saveVote_anyReject_shouldCancel() {
        Fixture f = new Fixture();
        f.startPvp3();
        f.authAll();

        f.server.onMessage(f.c1, "{\"type\":\"SAVE_GAME\",\"payload\":{\"requestId\":\"req-reject\"}}");
        f.server.onMessage(f.c2, "{\"type\":\"SAVE_GAME_REJECT\",\"payload\":{\"requestId\":\"req-reject\",\"playerId\":\"pvp-2\"}}");
        assertTrue(f.allOut().stream().anyMatch(s -> s.contains("\"SAVE_GAME_RESULT\"") && s.contains("\"ok\":false")));
    }

    @Test
    void saveVote_timeout_shouldCancel() {
        Fixture f = new Fixture();
        f.startPvp3();
        f.authAll();
        long expired = System.currentTimeMillis() - 1000L;

        JsonObject payload = new JsonObject();
        payload.addProperty("requestId", "req-timeout");
        payload.addProperty("deadlineEpochMs", expired);
        JsonObject root = new JsonObject();
        root.addProperty("type", "SAVE_GAME");
        root.add("payload", payload);
        f.server.onMessage(f.c1, GSON.toJson(root));
        f.server.onMessage(f.c1, "{\"type\":\"SAVE_GAME_ACK\",\"payload\":{\"requestId\":\"req-timeout\",\"playerId\":\"pvp-1\"}}");

        assertTrue(f.allOut().stream().anyMatch(s -> s.contains("\"SAVE_GAME_RESULT\"") && s.contains("\"ok\":false")));
    }

    private static final class Fixture {
        private final DefaultGameUpdateSubject subject = new DefaultGameUpdateSubject();
        private final GameController controller = new GameController(subject);
        private final GameServer server = new GameServer();
        private final List<String> out1 = new ArrayList<>();
        private final List<String> out2 = new ArrayList<>();
        private final List<String> out3 = new ArrayList<>();
        private final ClientConnection c1 = recordingClient(out1);
        private final ClientConnection c2 = recordingClient(out2);
        private final ClientConnection c3 = recordingClient(out3);

        private Fixture() {
            server.wireController(controller);
            server.attachTo(subject);
            server.onClientConnected(c1);
            server.onClientConnected(c2);
            server.onClientConnected(c3);
        }

        private void startPvp3() {
            StartSessionRequest req = new StartSessionRequest();
            req.setSessionId("save-vote");
            req.setPlayerCount(3);
            req.setGameMode("PVP");
            req.setRandomizeFirstPlayer(false);
            controller.startNewSession(req);
        }

        private void authAll() {
            server.onMessage(c1, "{\"type\":\"AUTH\",\"payload\":{\"playerId\":\"pvp-1\"}}");
            server.onMessage(c2, "{\"type\":\"AUTH\",\"payload\":{\"playerId\":\"pvp-2\"}}");
            server.onMessage(c3, "{\"type\":\"AUTH\",\"payload\":{\"playerId\":\"pvp-3\"}}");
            out1.clear();
            out2.clear();
            out3.clear();
        }

        private List<String> allOut() {
            List<String> all = new ArrayList<>();
            all.addAll(out1);
            all.addAll(out2);
            all.addAll(out3);
            return all;
        }
    }

    private static ClientConnection recordingClient(List<String> sink) {
        return new ClientConnection() {
            @Override
            public boolean isOpen() {
                return true;
            }

            @Override
            public void sendText(String text) {
                sink.add(text);
            }
        };
    }
}
