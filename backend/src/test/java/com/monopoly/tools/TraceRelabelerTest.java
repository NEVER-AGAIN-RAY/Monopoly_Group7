package com.monopoly.tools;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TraceRelabelerTest {

    @TempDir
    Path tempDir;

    @Test
    void relabelsExistingRequestWithHeuristicTeacher() throws Exception {
        Path input = tempDir.resolve("input.jsonl");
        Path output = tempDir.resolve("output.jsonl");
        Files.writeString(input, inputRow(), StandardCharsets.UTF_8);

        TraceRelabeler.Summary summary = TraceRelabeler.run(new TraceRelabeler.Config(
                input,
                output,
                "heuristic",
                8,
                0L,
                "fail_if_exists",
                Set.of(),
                Map.of(),
                "",
                false));

        assertEquals(1L, summary.toJson().get("rowsRelabeled").getAsLong());
        List<String> lines = Files.readAllLines(output, StandardCharsets.UTF_8);
        assertEquals(1, lines.size());
        JsonObject row = JsonParser.parseString(lines.get(0)).getAsJsonObject();
        assertEquals("d1", row.getAsJsonObject("request").get("decisionId").getAsString());
        assertEquals("c2", row.getAsJsonObject("result").get("choiceId").getAsString());
        assertEquals("local_heuristic", row.getAsJsonObject("result")
                .getAsJsonObject("metadata")
                .get("source")
                .getAsString());
        JsonObject sourcePolicy = row.getAsJsonObject("request")
                .getAsJsonObject("context")
                .getAsJsonObject("sourcePolicy");
        assertEquals("c1", sourcePolicy.get("choiceId").getAsString());
        assertEquals("local_heuristic", sourcePolicy.getAsJsonObject("metadata")
                .get("source")
                .getAsString());
        assertTrue(row.getAsJsonObject("request").getAsJsonArray("candidates").size() == 2);
    }

    @Test
    void includeSourcesCanFilterRowsBeforeRelabeling() throws Exception {
        Path input = tempDir.resolve("input-filtered.jsonl");
        Path output = tempDir.resolve("output-filtered.jsonl");
        Files.writeString(input, inputRow(), StandardCharsets.UTF_8);

        TraceRelabeler.Summary summary = TraceRelabeler.run(new TraceRelabeler.Config(
                input,
                output,
                "heuristic",
                8,
                0L,
                "fail_if_exists",
                Set.of("deepseek"),
                Map.of(),
                "",
                false));

        assertEquals(0L, summary.toJson().get("rowsRelabeled").getAsLong());
        assertEquals(1L, summary.toJson().get("rowsSkipped").getAsLong());
        assertTrue(Files.exists(output));
        assertTrue(Files.readString(output, StandardCharsets.UTF_8).isBlank());
    }

    @Test
    void maxRowsByKindSkipsOverQuotaRows() throws Exception {
        Path input = tempDir.resolve("input-quota.jsonl");
        Path output = tempDir.resolve("output-quota.jsonl");
        Files.writeString(
                input,
                inputRow("d1", "PLAY_CARD") + inputRow("d2", "PLAY_CARD") + inputRow("d3", "PAYMENT"),
                StandardCharsets.UTF_8);

        TraceRelabeler.Summary summary = TraceRelabeler.run(new TraceRelabeler.Config(
                input,
                output,
                "heuristic",
                8,
                0L,
                "fail_if_exists",
                Set.of(),
                Map.of("PLAY_CARD", 1L, "PAYMENT", 1L),
                "",
                false));

        assertEquals(2L, summary.toJson().get("rowsRelabeled").getAsLong());
        assertEquals(1L, summary.toJson().get("rowsSkipped").getAsLong());
        List<String> lines = Files.readAllLines(output, StandardCharsets.UTF_8);
        assertEquals(2, lines.size());
        JsonObject row = JsonParser.parseString(lines.get(1)).getAsJsonObject();
        assertEquals("PAYMENT", row.getAsJsonObject("request").get("decisionKind").getAsString());
    }

    @Test
    void preservesExistingSourcePolicyWhenRelabelingReviewedRows() throws Exception {
        Path input = tempDir.resolve("input-reviewed.jsonl");
        Path output = tempDir.resolve("output-reviewed.jsonl");
        String context = """
                {
                  "sourcePolicy": {
                    "choiceId": "c2",
                    "metadata": {"source": "lookahead"}
                  }
                }
                """.replace("\n", "");
        Files.writeString(input, inputRow("d1", "PLAY_CARD", context), StandardCharsets.UTF_8);

        TraceRelabeler.Summary summary = TraceRelabeler.run(new TraceRelabeler.Config(
                input,
                output,
                "heuristic",
                8,
                0L,
                "fail_if_exists",
                Set.of(),
                Map.of(),
                "",
                false));

        assertEquals(1L, summary.toJson().get("rowsRelabeled").getAsLong());
        JsonObject row = JsonParser.parseString(Files.readString(output, StandardCharsets.UTF_8)).getAsJsonObject();
        JsonObject contextOut = row.getAsJsonObject("request").getAsJsonObject("context");
        assertEquals("c2", contextOut.getAsJsonObject("sourcePolicy").get("choiceId").getAsString());
        assertEquals("lookahead", contextOut.getAsJsonObject("sourcePolicy")
                .getAsJsonObject("metadata")
                .get("source")
                .getAsString());
        assertEquals("c1", contextOut.getAsJsonObject("relabelInputPolicy").get("choiceId").getAsString());
        assertEquals("local_heuristic", contextOut.getAsJsonObject("relabelInputPolicy")
                .getAsJsonObject("metadata")
                .get("source")
                .getAsString());
    }

    private static String inputRow() {
        return inputRow("d1", "PLAY_CARD");
    }

    private static String inputRow(String decisionId, String decisionKind) {
        return inputRow(decisionId, decisionKind, "{}");
    }

    private static String inputRow(String decisionId, String decisionKind, String contextJson) {
        return """
                {
                  "schema": "monopoly-deal-decision-v1",
                  "recordedAtEpochMs": 1,
                  "request": {
                    "decisionId": "DECISION_ID",
                    "sessionId": "s1",
                    "actorPlayerId": "ai-1",
                    "decisionKind": "DECISION_KIND",
                    "stateSequence": 2,
                    "createdAtEpochMs": 1,
                    "context": CONTEXT_JSON,
                    "candidates": [
                      {
                        "id": "c1",
                        "summary": "Deposit money/bankable card for 1M.",
                        "payload": {"actionType": "DEPOSIT", "amountM": 1}
                      },
                      {
                        "id": "c2",
                        "summary": "Action PASS_GO draw 2 cards.",
                        "payload": {"actionType": "PLAY_ACTION", "effectCode": "PASS_GO"}
                      }
                    ]
                  },
                  "result": {
                    "decisionId": "DECISION_ID",
                    "choiceId": "c1",
                    "rawResponse": null,
                    "metadata": {"source": "local_heuristic"}
                  }
                }
                """.replace("DECISION_ID", decisionId)
                .replace("DECISION_KIND", decisionKind)
                .replace("CONTEXT_JSON", contextJson)
                .replace("\n", "") + "\n";
    }
}
