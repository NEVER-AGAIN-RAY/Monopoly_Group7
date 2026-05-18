package com.monopoly.network;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.monopoly.controller.GameController;
import com.monopoly.network.connection.ClientConnection;
import com.monopoly.pattern.observer.DefaultGameUpdateSubject;
import com.monopoly.persistence.GameSessionMemento;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 无 UI 端到端冒烟：通过 {@link GameServer#onMessage} 模拟 WebSocket 客户端（人机局人类玩家 human-1），
 * 验证 START_SESSION → STATE_UPDATE → DRAW/PLAY/END_TURN 后 {@code AiTurnService} 驱动 AI 摸牌与出牌。
 */
class GameServerEndToEndSmokeTest {

    private static final Gson GSON = new Gson();

    private String prevAiSeed;
    private String prevAiTrace;

    @BeforeEach
    void setUpAiTraceAndResetEngine() {
        prevAiSeed = System.getProperty("monopoly.ai.seed");
        prevAiTrace = System.getProperty("monopoly.ai.trace");
        System.setProperty("monopoly.ai.seed", "4242424242");
        System.setProperty("monopoly.ai.trace", "true");
        GameSessionMemento.resetSingletonEngineForTests();
    }

    @AfterEach
    void tearDown() {
        restoreProperty("monopoly.ai.seed", prevAiSeed);
        restoreProperty("monopoly.ai.trace", prevAiTrace);
        GameSessionMemento.resetSingletonEngineForTests();
    }

    private static void restoreProperty(String key, String previous) {
        if (previous == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, previous);
        }
    }

    @Test
    void hvmSession_humanDrawPlayEndTurn_triggersAiDrawAndPlay() {
        DefaultGameUpdateSubject subject = new DefaultGameUpdateSubject();
        GameController controller = new GameController(subject);
        GameServer server = new GameServer();
        server.wireController(controller);
        server.attachTo(subject);

        List<String> out = new ArrayList<>();
        ClientConnection client = recordingClient(out);
        server.onClientConnected(client);

        JsonObject authPayload = new JsonObject();
        authPayload.addProperty("playerId", "human-1");
        JsonObject auth = new JsonObject();
        auth.addProperty("type", "AUTH");
        auth.add("payload", authPayload);
        server.onMessage(client, GSON.toJson(auth));

        out.clear();

        JsonObject startPayload = new JsonObject();
        startPayload.addProperty("sessionId", "e2e-hvm-smoke");
        startPayload.addProperty("playerCount", 2);
        startPayload.addProperty("gameMode", "HVM");
        startPayload.addProperty("aiDifficulty", "EASY");
        startPayload.addProperty("randomizeFirstPlayer", false);
        JsonObject start = new JsonObject();
        start.addProperty("type", "START_SESSION");
        start.add("payload", startPayload);
        server.onMessage(client, GSON.toJson(start));

        List<JsonObject> afterStart = stateUpdatePayloads(out);
        assertTrue(
                afterStart.stream().anyMatch(p -> "INIT".equals(jsonString(p, "phase"))
                        && "e2e-hvm-smoke".equals(jsonString(p, "sessionId"))),
                "应在开局后收到带 INIT 相位的 STATE_UPDATE");

        assertTrue(
                afterStart.stream().anyMatch(p ->
                        "human-1".equals(jsonString(p, "currentPlayerId"))
                                && "DRAW".equals(jsonString(p, "turnPhase"))),
                "初始化后当前行动玩家应为 human-1 且处于摸牌阶段");

        out.clear();
        server.onMessage(client, "{\"type\":\"DRAW\",\"payload\":{\"count\":2}}");

        assertTrue(
                stateUpdatePayloads(out).stream().anyMatch(p -> "DRAW".equals(jsonString(p, "phase"))),
                "摸牌后应收到 phase=DRAW 的 STATE_UPDATE");

        out.clear();
        server.onMessage(client,
                "{\"type\":\"PLAY\",\"requestId\":\"r-deposit\",\"payload\":{\"actionType\":\"DEPOSIT\",\"handIndex\":0}}");

        assertTrue(
                stateUpdatePayloads(out).stream().anyMatch(p -> "DEPOSIT".equals(jsonString(p, "phase"))),
                "存钱后应收到 phase=DEPOSIT 的 STATE_UPDATE");

        out.clear();
        server.onMessage(client, "{\"type\":\"END_TURN\",\"payload\":{}}");

        List<JsonObject> afterEndTurn = stateUpdatePayloads(out);
        boolean sawAiDraw = afterEndTurn.stream()
                .map(p -> jsonString(p, "lastActionSummary"))
                .anyMatch(s -> s != null && s.contains("AI-") && s.contains("drew"));
        assertTrue(sawAiDraw, "END_TURN 后应观察到 AI 摸牌摘要（AiTurnService → drawCards）");

        boolean sawAiPlay = afterEndTurn.stream()
                .map(p -> jsonString(p, "lastActionSummary"))
                .anyMatch(s -> s != null && s.contains("AI-") && s.contains("played"));
        assertTrue(sawAiPlay, "END_TURN 后应观察到 AI 出牌摘要（启发式打牌轨迹）");

        Optional<JsonObject> aiTurnSnapshot = afterEndTurn.stream()
                .filter(p -> "ai-1".equals(jsonString(p, "currentPlayerId")))
                .reduce((a, b) -> b);
        assertTrue(aiTurnSnapshot.isPresent(), "AI 回合期间应至少有一条快照 currentPlayerId=ai-1");
    }

    private static List<JsonObject> stateUpdatePayloads(List<String> messages) {
        return messages.stream()
                .filter(s -> s.contains("\"type\":\"STATE_UPDATE\""))
                .map(GameServerEndToEndSmokeTest::parsePayload)
                .collect(Collectors.toList());
    }

    private static JsonObject parsePayload(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        return root.getAsJsonObject("payload");
    }

    private static String jsonString(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return null;
        }
        return obj.get(key).getAsString();
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
