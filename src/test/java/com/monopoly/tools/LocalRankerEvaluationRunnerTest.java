package com.monopoly.tools;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalRankerEvaluationRunnerTest {

    @TempDir
    Path tempDir;

    @Test
    void emitsEvaluationJsonForLocalLinearModel() throws Exception {
        Path model = tempDir.resolve("linear.json");
        Files.writeString(model, zeroModelJson(), StandardCharsets.UTF_8);

        String oldModel = System.getProperty("monopoly.localRanker.modelPath");
        String oldGames = System.getProperty("monopoly.localRankerEval.games");
        String oldPlayers = System.getProperty("monopoly.localRankerEval.players");
        String oldSnapshots = System.getProperty("monopoly.localRankerEval.maxSnapshots");
        String oldOpponent = System.getProperty("monopoly.localRankerEval.opponentStrategy");
        String oldRankerSeat = System.getProperty("monopoly.localRankerEval.rankerSeat");
        PrintStream oldOut = System.out;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            System.setProperty("monopoly.localRanker.modelPath", model.toString());
            System.setProperty("monopoly.localRankerEval.games", "1");
            System.setProperty("monopoly.localRankerEval.players", "2");
            System.setProperty("monopoly.localRankerEval.maxSnapshots", "8");
            System.setProperty("monopoly.localRankerEval.opponentStrategy", "hard");
            System.setProperty("monopoly.localRankerEval.rankerSeat", "1");
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));

            LocalRankerEvaluationRunner.main(new String[0]);
        } finally {
            restore("monopoly.localRanker.modelPath", oldModel);
            restore("monopoly.localRankerEval.games", oldGames);
            restore("monopoly.localRankerEval.players", oldPlayers);
            restore("monopoly.localRankerEval.maxSnapshots", oldSnapshots);
            restore("monopoly.localRankerEval.opponentStrategy", oldOpponent);
            restore("monopoly.localRankerEval.rankerSeat", oldRankerSeat);
            System.setOut(oldOut);
        }

        JsonObject json = JsonParser.parseString(lastJsonObject(out.toString(StandardCharsets.UTF_8)))
                .getAsJsonObject();
        assertEquals(1, json.get("gamesRequested").getAsInt());
        assertEquals(2, json.get("players").getAsInt());
        assertEquals("hard", json.get("opponentStrategy").getAsString());
        assertEquals(1, json.get("rankerSeat").getAsInt());
        assertTrue(json.has("rankerWinRate"));
        assertTrue(json.has("averageRankerBoardRank"));
        assertTrue(json.has("rankerBoardLeadRate"));
        assertTrue(json.getAsJsonArray("games").size() == 1);
        JsonObject game = json.getAsJsonArray("games").get(0).getAsJsonObject();
        assertTrue(game.has("rankerWon"));
        assertTrue(game.has("rankerBoardScore"));
        assertTrue(game.has("rankerBoardRank"));
        assertTrue(game.has("boardScores"));
    }

    private static void restore(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    private static String lastJsonObject(String raw) {
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end < start) {
            throw new IllegalArgumentException("No JSON object found in output: " + raw);
        }
        return raw.substring(start, end + 1);
    }

    private static String zeroModelJson() {
        StringBuilder weights = new StringBuilder();
        for (int i = 0; i < 119; i++) {
            if (i > 0) {
                weights.append(',');
            }
            weights.append('0');
        }
        return """
                {
                  "schema": "monopoly-deal-linear-ranker-v1",
                  "featureVersion": "candidate-ranker-features-v1",
                  "inputDim": 119,
                  "bias": 0.0,
                  "weights": [WEIGHTS]
                }
                """.replace("WEIGHTS", weights.toString());
    }
}
