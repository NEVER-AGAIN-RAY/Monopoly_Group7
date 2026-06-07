package com.monopoly.fx;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.monopoly.fx.presentation.CardDisplayData;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import static com.monopoly.fx.FxJson.jsonInt;
import static com.monopoly.fx.FxJson.jsonString;

/** Controls the explicit rent-payment picker shown during tenant response windows. */
final class RentPaymentPanelController {

    record Refs(
            VBox rentPaymentBox,
            Label rentPaymentHint,
            Label rentPaymentSumLabel,
            FlowPane rentPaymentPickPane,
            Button rentPaymentGreedyButton,
            Button rentPaymentSubmitButton,
            Button rentPaymentClearButton) {
    }

    private final FxClientState state;
    private final Refs refs;
    private final Supplier<String> playerId;
    private final BiFunction<JsonObject, String, JsonObject> findPlayerInState;
    private final Function<CardDisplayData, String> cardTitle;
    private final Consumer<String> showError;
    private final Consumer<List<String>> submitPayment;
    private final FxLocalizer i18n;

    RentPaymentPanelController(
            FxClientState state,
            Refs refs,
            Supplier<String> playerId,
            BiFunction<JsonObject, String, JsonObject> findPlayerInState,
            Function<CardDisplayData, String> cardTitle,
            Consumer<String> showError,
            Consumer<List<String>> submitPayment,
            FxLocalizer i18n) {
        this.state = state;
        this.refs = refs;
        this.playerId = playerId;
        this.findPlayerInState = findPlayerInState;
        this.cardTitle = cardTitle;
        this.showError = showError;
        this.submitPayment = submitPayment;
        this.i18n = i18n;
    }

    void selectRecommendedAndSubmit() {
        List<String> recommended = recommendedPaymentIds();
        state.selectedPaymentIds.clear();
        state.selectedPaymentIds.addAll(recommended);
        for (Node node : refs.rentPaymentPickPane().getChildren()) {
            if (node instanceof CheckBox cb) {
                Object id = cb.getUserData();
                cb.setSelected(id instanceof String s && state.selectedPaymentIds.contains(s));
            }
        }
        if (!recommended.isEmpty()) {
            refreshSumLabel();
            submitPayment.accept(recommended);
        } else {
            submitPayment.accept(null);
        }
    }

    void submitSelection() {
        if (state.selectedPaymentIds.isEmpty()) {
            showError.accept(i18n.get("error.selectAtLeast"));
            return;
        }
        int selected = computeSelectedPaymentSum();
        int total = totalPayableValue();
        if (selected < state.pendingRentPaymentM && selected < total) {
            showError.accept(i18n.get("error.insufficientValue", state.pendingRentPaymentM));
            return;
        }
        submitPayment.accept(new ArrayList<>(state.selectedPaymentIds));
    }

    void clearSelection() {
        state.selectedPaymentIds.clear();
        for (Node node : refs.rentPaymentPickPane().getChildren()) {
            if (node instanceof CheckBox cb) {
                cb.setSelected(false);
            }
        }
        refreshSumLabel();
    }

    void updatePanel() {
        JsonObject p = state.lastStatePayload;
        boolean show = p != null
                && "WAITING_FOR_RESPONSE".equals(jsonString(p, "turnPhase", ""))
                && playerId.get().equals(jsonString(p, "pendingResponsePlayerId", ""))
                && "TENANT".equals(jsonString(p, "pendingResponseRole", ""))
                && jsonInt(p, "pendingPaymentAmountM", 0) > 0;
        refs.rentPaymentBox().setVisible(show);
        refs.rentPaymentBox().setManaged(show);
        if (!show) {
            state.selectedPaymentIds.clear();
            refs.rentPaymentPickPane().getChildren().clear();
            state.pendingRentPaymentM = 0;
            return;
        }
        state.pendingRentPaymentM = jsonInt(p, "pendingPaymentAmountM", 0);
        refs.rentPaymentHint().setText(i18n.get("rent.hint", state.pendingRentPaymentM));
        state.selectedPaymentIds.clear();
        refs.rentPaymentPickPane().getChildren().clear();
        JsonObject self = findPlayerInState.apply(p, playerId.get());
        if (self != null) {
            addRentPaymentChoices(self.getAsJsonArray("bankCards"), i18n.get("rent.zoneBank"));
            addRentPaymentChoices(self.getAsJsonArray("propertyZoneCards"), i18n.get("rent.zoneProperty"));
        }
        state.selectedPaymentIds.addAll(recommendedPaymentIds());
        for (Node node : refs.rentPaymentPickPane().getChildren()) {
            if (node instanceof CheckBox cb) {
                Object id = cb.getUserData();
                cb.setSelected(id instanceof String s && state.selectedPaymentIds.contains(s));
            }
        }
        refreshSumLabel();
    }

    private void addRentPaymentChoices(JsonArray cards, String zone) {
        if (cards == null) {
            return;
        }
        for (JsonElement el : cards) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject card = el.getAsJsonObject();
            String id = jsonString(card, "id", "");
            if (id.isBlank()) {
                continue;
            }
            CardDisplayData data = CardDisplayData.fromHandCardJson(card);
            CheckBox checkBox = new CheckBox(zone + " · " + cardTitle.apply(data) + " · " + jsonInt(card, "valueM", 0) + "M");
            checkBox.setUserData(id);
            checkBox.getStyleClass().add("payment-check");
            checkBox.selectedProperty().addListener((obs, oldValue, selected) -> {
                if (selected) {
                    state.selectedPaymentIds.add(id);
                } else {
                    state.selectedPaymentIds.remove(id);
                }
                refreshSumLabel();
            });
            refs.rentPaymentPickPane().getChildren().add(checkBox);
        }
    }

    private void refreshSumLabel() {
        refs.rentPaymentSumLabel().setText(i18n.get(
                "rent.sumLabel",
                computeSelectedPaymentSum(),
                state.pendingRentPaymentM));
    }

    private int computeSelectedPaymentSum() {
        JsonObject self = findPlayerInState.apply(state.lastStatePayload, playerId.get());
        if (self == null) {
            return 0;
        }
        Map<String, Integer> values = paymentValues(self);
        int total = 0;
        for (String id : state.selectedPaymentIds) {
            total += values.getOrDefault(id, 0);
        }
        return total;
    }

    private int totalPayableValue() {
        JsonObject self = findPlayerInState.apply(state.lastStatePayload, playerId.get());
        if (self == null) {
            return 0;
        }
        return paymentValues(self).values().stream().mapToInt(Integer::intValue).sum();
    }

    private List<String> recommendedPaymentIds() {
        return RentPaymentAdvisor.recommendedPaymentIds(
                findPlayerInState.apply(state.lastStatePayload, playerId.get()),
                state.pendingRentPaymentM);
    }

    private static Map<String, Integer> paymentValues(JsonObject self) {
        Map<String, Integer> values = new HashMap<>();
        accumulateValues(self.getAsJsonArray("bankCards"), values);
        accumulateValues(self.getAsJsonArray("propertyZoneCards"), values);
        return values;
    }

    private static void accumulateValues(JsonArray cards, Map<String, Integer> values) {
        if (cards == null) {
            return;
        }
        for (JsonElement el : cards) {
            if (el.isJsonObject()) {
                JsonObject card = el.getAsJsonObject();
                values.put(jsonString(card, "id", ""), jsonInt(card, "valueM", 0));
            }
        }
    }
}
