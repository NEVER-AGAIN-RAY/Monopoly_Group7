package com.monopoly.simulation;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HeuristicDecisionTeacherTest {

    @Test
    void playDecisionUsesCandidateScoreInsteadOfFirstCandidate() throws Exception {
        SimulationDecisionRequest request = new SimulationDecisionRequest(
                "d1",
                "s1",
                "ai-1",
                "PLAY_CARD",
                1L,
                new JsonObject(),
                List.of(
                        candidate("c1", "Deposit money/bankable card for 1M.", new JsonObject()),
                        candidate("c2", "Action PASS_GO draw 2 cards.", new JsonObject())));

        SimulationDecisionResult result = new HeuristicDecisionTeacher()
                .decideBatch(List.of(request))
                .get(0);

        assertEquals("c2", result.getChoiceId());
        assertEquals("local_heuristic", result.getMetadata().get("source").getAsString());
    }

    @Test
    void paymentDecisionPrefersBankOverPropertyWhenBothCover() throws Exception {
        JsonObject context = new JsonObject();
        JsonObject decision = new JsonObject();
        decision.addProperty("amountDueM", 2);
        JsonArray payable = new JsonArray();
        payable.add(card("money-2", "MONEY", "BANK", 2));
        payable.add(card("property-2", "PROPERTY", "PROPERTY", 2));
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

        SimulationDecisionResult result = new HeuristicDecisionTeacher()
                .decideBatch(List.of(request))
                .get(0);

        assertEquals("c2", result.getChoiceId());
    }

    @Test
    void justSayNoDecisionFollowsLocalRecommendation() throws Exception {
        JsonObject context = new JsonObject();
        JsonObject decision = new JsonObject();
        decision.addProperty("localRecommendationPlayJustSayNo", true);
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

        SimulationDecisionResult result = new HeuristicDecisionTeacher()
                .decideBatch(List.of(request))
                .get(0);

        assertEquals("PLAY_JSN", result.getChoiceId());
    }

    private static SimulationDecisionCandidate candidate(String id, String summary, JsonObject payload) {
        return new SimulationDecisionCandidate(id, summary, payload);
    }

    private static JsonObject card(String id, String kind, String zone, int value) {
        JsonObject card = new JsonObject();
        card.addProperty("id", id);
        card.addProperty("kind", kind);
        card.addProperty("zone", zone);
        card.addProperty("valueM", value);
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
