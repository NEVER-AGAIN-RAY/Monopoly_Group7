package com.monopoly.simulation;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StrategicHeuristicDecisionTeacherTest {

    @Test
    void playDecisionUsesStrategicSourceAndBlocksOpponentThreat() throws Exception {
        JsonObject context = boardContext(1, 1, 0, 2);
        SimulationDecisionRequest request = new SimulationDecisionRequest(
                "d1",
                "s1",
                "ai-1",
                "PLAY_CARD",
                1L,
                context,
                List.of(
                        candidate("c1", "Action PASS_GO draw 2 cards.", new JsonObject()),
                        candidate("c2", "Action DEAL_BREAKER steal complete set.", new JsonObject())));

        SimulationDecisionResult result = new StrategicHeuristicDecisionTeacher()
                .decideBatch(List.of(request))
                .get(0);

        assertEquals("c2", result.getChoiceId());
        assertEquals("strategic_heuristic", result.getMetadata().get("source").getAsString());
    }

    @Test
    void justSayNoPlaysOnLargeChargeEvenWithoutLocalRecommendation() throws Exception {
        JsonObject context = boardContext(4, 2, 1, 1);
        JsonObject decision = new JsonObject();
        decision.addProperty("amountDueM", 4);
        decision.addProperty("localRecommendationPlayJustSayNo", false);
        context.add("decision", decision);
        SimulationDecisionRequest request = new SimulationDecisionRequest(
                "d1",
                "s1",
                "ai-1",
                "JUST_SAY_NO",
                1L,
                context,
                List.of(
                        candidate("PASS", "Pass Just Say No response.", new JsonObject()),
                        candidate("PLAY_JSN", "Play Just Say No.", new JsonObject())));

        SimulationDecisionResult result = new StrategicHeuristicDecisionTeacher()
                .decideBatch(List.of(request))
                .get(0);

        assertEquals("PLAY_JSN", result.getChoiceId());
    }

    @Test
    void paymentProtectsPropertiesWhenBankCanCover() throws Exception {
        JsonObject context = boardContext(3, 2, 2, 0);
        JsonObject decision = new JsonObject();
        decision.addProperty("amountDueM", 2);
        JsonArray payable = new JsonArray();
        payable.add(card("money-2", "MONEY", "BANK", 2, ""));
        payable.add(card("property-2", "PROPERTY", "PROPERTY", 2, ""));
        decision.add("payableCards", payable);
        context.add("decision", decision);
        SimulationDecisionRequest request = new SimulationDecisionRequest(
                "d1",
                "s1",
                "ai-1",
                "PAYMENT",
                1L,
                context,
                List.of(
                        candidate("c1", "Pay 2M with property-2.", paymentPayload("property-2", 2)),
                        candidate("c2", "Pay 2M with money-2.", paymentPayload("money-2", 2))));

        SimulationDecisionResult result = new StrategicHeuristicDecisionTeacher()
                .decideBatch(List.of(request))
                .get(0);

        assertEquals("c2", result.getChoiceId());
    }

    private static JsonObject boardContext(int bankM, int selfSets, int opponentOneSets, int opponentTwoSets) {
        JsonObject context = new JsonObject();
        JsonObject meta = new JsonObject();
        meta.addProperty("playerCount", 3);
        meta.addProperty("roundNumber", 4);
        context.add("gameMeta", meta);

        JsonObject self = new JsonObject();
        self.addProperty("id", "ai-1");
        self.addProperty("bankM", bankM);
        self.addProperty("completeSets", selfSets);
        context.add("self", self);

        JsonArray players = new JsonArray();
        players.add(player("ai-1", true, selfSets));
        players.add(player("ai-2", false, opponentOneSets));
        players.add(player("ai-3", false, opponentTwoSets));
        context.add("players", players);
        return context;
    }

    private static JsonObject player(String id, boolean self, int completeSets) {
        JsonObject player = new JsonObject();
        player.addProperty("id", id);
        player.addProperty("isSelf", self);
        player.addProperty("completeSets", completeSets);
        return player;
    }

    private static SimulationDecisionCandidate candidate(String id, String summary, JsonObject payload) {
        return new SimulationDecisionCandidate(id, summary, payload);
    }

    private static JsonObject card(String id, String kind, String zone, int value, String effect) {
        JsonObject card = new JsonObject();
        card.addProperty("id", id);
        card.addProperty("kind", kind);
        card.addProperty("zone", zone);
        card.addProperty("valueM", value);
        if (!effect.isBlank()) {
            card.addProperty("effectCode", effect);
        }
        return card;
    }

    private static JsonObject paymentPayload(String cardId, int amountPaid) {
        JsonObject payload = new JsonObject();
        JsonArray ids = new JsonArray();
        ids.add(cardId);
        payload.add("cardIds", ids);
        payload.addProperty("amountPaidM", amountPaid);
        return payload;
    }
}
