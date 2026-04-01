package com.monopoly.network;

import com.monopoly.controller.GameController;
import com.monopoly.model.dto.StartSessionRequest;
import com.monopoly.model.persistence.GameSessionMemento;
import com.monopoly.pattern.observer.DefaultGameUpdateSubject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameServerPvpLoadVoteTest {

    @AfterEach
    void tearDown() {
        GameSessionMemento.resetSingletonEngineForTests();
    }

    @Test
    void loadVote_allAck_shouldCommit() {
        Fixture f = new Fixture();
        f.startSession("live-load");
        f.authAll();
        String targetMemento = f.createMementoOf("target-load");

        f.server.onMessage(f.c1, "{\"type\":\"LOAD_GAME\",\"payload\":{\"requestId\":\"load-ok\",\"mementoJson\":" + quote(targetMemento) + "}}");
        assertTrue(f.allOut().stream().anyMatch(s -> s.contains("\"LOAD_GAME_REQUEST\"") && s.contains("load-ok")));
        f.server.onMessage(f.c1, "{\"type\":\"LOAD_GAME_ACK\",\"payload\":{\"requestId\":\"load-ok\",\"playerId\":\"pvp-1\"}}");
        f.server.onMessage(f.c2, "{\"type\":\"LOAD_GAME_ACK\",\"payload\":{\"requestId\":\"load-ok\",\"playerId\":\"pvp-2\"}}");
        f.server.onMessage(f.c3, "{\"type\":\"LOAD_GAME_ACK\",\"payload\":{\"requestId\":\"load-ok\",\"playerId\":\"pvp-3\"}}");

        String current = GameSessionMemento.fromJson(f.controller.exportSessionJson()).getSessionId();
        assertEquals("target-load", current);
        assertTrue(f.allOut().stream().anyMatch(s -> s.contains("\"LOAD_GAME_RESULT\"") && s.contains("\"ok\":true")));
    }

    @Test
    void loadVote_anyReject_shouldCancel() {
        Fixture f = new Fixture();
        f.startSession("live-load");
        f.authAll();
        String targetMemento = f.createMementoOf("target-load");

        f.server.onMessage(f.c1, "{\"type\":\"LOAD_GAME\",\"payload\":{\"requestId\":\"load-reject\",\"mementoJson\":" + quote(targetMemento) + "}}");
        f.server.onMessage(f.c2, "{\"type\":\"LOAD_GAME_REJECT\",\"payload\":{\"requestId\":\"load-reject\",\"playerId\":\"pvp-2\"}}");

        String current = GameSessionMemento.fromJson(f.controller.exportSessionJson()).getSessionId();
        assertEquals("live-load", current);
        assertTrue(f.allOut().stream().anyMatch(s -> s.contains("\"LOAD_GAME_RESULT\"") && s.contains("\"ok\":false")));
    }

    @Test
    void loadVote_invalidMemento_shouldFail() {
        Fixture f = new Fixture();
        f.startSession("live-load");
        f.authAll();

        f.server.onMessage(f.c1, "{\"type\":\"LOAD_GAME\",\"payload\":{\"requestId\":\"load-invalid\",\"mementoJson\":\"{bad-json}\"}}");
        f.server.onMessage(f.c1, "{\"type\":\"LOAD_GAME_ACK\",\"payload\":{\"requestId\":\"load-invalid\",\"playerId\":\"pvp-1\"}}");
        f.server.onMessage(f.c2, "{\"type\":\"LOAD_GAME_ACK\",\"payload\":{\"requestId\":\"load-invalid\",\"playerId\":\"pvp-2\"}}");
        f.server.onMessage(f.c3, "{\"type\":\"LOAD_GAME_ACK\",\"payload\":{\"requestId\":\"load-invalid\",\"playerId\":\"pvp-3\"}}");

        String current = GameSessionMemento.fromJson(f.controller.exportSessionJson()).getSessionId();
        assertEquals("live-load", current);
        assertTrue(f.allOut().stream().anyMatch(s -> s.contains("\"LOAD_GAME_RESULT\"") && s.contains("\"ok\":false")));
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

        private void startSession(String sessionId) {
            StartSessionRequest req = new StartSessionRequest();
            req.setSessionId(sessionId);
            req.setPlayerCount(3);
            req.setGameMode("PVP");
            req.setRandomizeFirstPlayer(false);
            controller.startNewSession(req);
        }

        private String createMementoOf(String sessionId) {
            DefaultGameUpdateSubject s = new DefaultGameUpdateSubject();
            GameController c = new GameController(s);
            StartSessionRequest req = new StartSessionRequest();
            req.setSessionId(sessionId);
            req.setPlayerCount(3);
            req.setGameMode("PVP");
            req.setRandomizeFirstPlayer(false);
            c.startNewSession(req);
            return c.exportSessionJson();
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

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
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
