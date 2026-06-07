package com.monopoly.fx;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.monopoly.fx.presentation.CardDisplayData;

import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import static com.monopoly.fx.FxJson.jsonBool;
import static com.monopoly.fx.FxJson.jsonInt;
import static com.monopoly.fx.FxJson.jsonString;
import static com.monopoly.fx.FxJson.safeUpper;

/** Coordinates local hand actions, option requests, and PLAY payload dispatch. */
final class PlayActionController {

    private final FxClientState state;
    private final Supplier<String> playerId;
    private final BooleanSupplier needsOverflowDiscard;
    private final Consumer<String> showError;
    private final Function<String, String> backendMessage;
    private final ClientCommandGateway gateway;
    private final ActionOptionDialogService optionDialogService;
    private final FxLocalizer i18n;

    PlayActionController(
            FxClientState state,
            Supplier<String> playerId,
            BooleanSupplier needsOverflowDiscard,
            Consumer<String> showError,
            Function<String, String> backendMessage,
            ClientCommandGateway gateway,
            ActionOptionDialogService optionDialogService,
            FxLocalizer i18n) {
        this.state = state;
        this.playerId = playerId;
        this.needsOverflowDiscard = needsOverflowDiscard;
        this.showError = showError;
        this.backendMessage = backendMessage;
        this.gateway = gateway;
        this.optionDialogService = optionDialogService;
        this.i18n = i18n;
    }

    void playSelected(String actionType) {
        if (state.selectedCard == null) {
            showError.accept(i18n.get("selectCardFirst"));
            return;
        }
        if (needsOverflowDiscard.getAsBoolean() && !"DISCARD".equals(actionType)) {
            showError.accept(i18n.get("needDiscard", jsonInt(state.lastStatePayload, "overflowDiscardCount", 0)));
            return;
        }
        Map<String, Object> direct = directPlayPayload(state.selectedCard, actionType);
        if (direct != null) {
            gateway.sendEnvelope("PLAY", direct);
            return;
        }
        requestPlayOptionsThenPlay(
                state.selectedCard.getId(),
                actionType,
                shouldAutoChooseOption(state.selectedCard, actionType));
    }

    void quickPlay(CardDisplayData card) {
        if (card == null) {
            return;
        }
        state.selectedCard = card;
        String actionType = defaultActionForCard(card);
        if (needsOverflowDiscard.getAsBoolean() && !"DISCARD".equals(actionType)) {
            showError.accept(i18n.get("needDiscard", jsonInt(state.lastStatePayload, "overflowDiscardCount", 0)));
            return;
        }
        Map<String, Object> direct = directPlayPayload(card, actionType);
        if (direct != null) {
            gateway.sendEnvelope("PLAY", direct);
            return;
        }
        requestPlayOptionsThenPlay(card.getId(), actionType, shouldAutoChooseOption(card, actionType));
    }

    void applyPendingOptionsResult(JsonObject payload) {
        var handler = state.pendingOptionsResultHandler;
        state.pendingOptionsResultHandler = null;
        if (handler != null) {
            handler.accept(payload);
        }
    }

    private void requestPlayOptionsThenPlay(String cardId, String actionType, boolean autoDefault) {
        if (state.pendingOptionsResultHandler != null) {
            showError.accept(i18n.get("error.waitOption"));
            return;
        }
        state.pendingOptionsResultHandler = payload -> handlePlayOptions(payload, cardId, actionType, autoDefault);
        gateway.sendEnvelope("PLAY_OPTIONS", Map.of(
                "playerId", playerId.get(),
                "cardId", cardId,
                "actionType", actionType));
    }

    private void handlePlayOptions(JsonObject payload, String cardId, String actionType, boolean autoDefault) {
        if (payload == null || !jsonBool(payload, "ok", false)) {
            showError.accept(backendMessage.apply(jsonString(payload, "error", "")));
            return;
        }
        JsonArray options = payload.has("options") && payload.get("options").isJsonArray()
                ? payload.getAsJsonArray("options")
                : new JsonArray();
        boolean mustChoose = mustChooseOption(state.selectedCard, actionType, options);
        if (!mustChoose && (autoDefault || options.size() <= 1)) {
            JsonObject row = options.size() == 0 ? new JsonObject() : options.get(0).getAsJsonObject();
            optionDialogService.sendPlayFromOptionRow(actionType, cardId, row);
            return;
        }
        optionDialogService.showOptionDialog(actionType, cardId, options);
    }

    static String defaultActionForCard(CardDisplayData card) {
        if ("MONEY".equals(card.getKind())) {
            return "DEPOSIT";
        }
        if ("PROPERTY".equals(card.getKind()) || "WILD".equals(card.getKind())) {
            return "DEPLOY";
        }
        if ("ACTION".equals(card.getKind())) {
            return "ACTION";
        }
        return "DISCARD";
    }

    static Map<String, Object> directPlayPayload(CardDisplayData card, String actionType) {
        if (card == null || card.getId().isBlank()) {
            return null;
        }
        if ("DEPOSIT".equals(actionType) || "DISCARD".equals(actionType)) {
            return WsJson.playPayload(actionType, card.getId(), null, null, null, null, null, null);
        }
        if ("DEPLOY".equals(actionType) && "PROPERTY".equals(card.getKind())) {
            return WsJson.playPayload(actionType, card.getId(), null, null, null, null, null, null);
        }
        return null;
    }

    static boolean shouldAutoChooseOption(CardDisplayData card, String actionType) {
        if ("DEPLOY".equals(actionType) && "WILD".equals(card.getKind())) {
            return false;
        }
        if (!"ACTION".equals(actionType)) {
            return true;
        }
        String effect = safeUpper(card.getEffectCode());
        return !"RENT".equals(effect)
                && !"RENT_DUAL".equals(effect)
                && !"DEBT_COLLECTOR".equals(effect)
                && !"STEAL_PROPERTY".equals(effect)
                && !"FORCED_DEAL".equals(effect)
                && !"DEAL_BREAKER".equals(effect);
    }

    static boolean mustChooseOption(CardDisplayData card, String actionType, JsonArray options) {
        if ("DEPLOY".equals(actionType) && card != null && "WILD".equals(card.getKind())) {
            return true;
        }
        if (!"ACTION".equals(actionType) || card == null) {
            return false;
        }
        String effect = safeUpper(card.getEffectCode());
        if ("RENT".equals(effect) || "RENT_DUAL".equals(effect)) {
            return true;
        }
        return ("DEBT_COLLECTOR".equals(effect)
                || "STEAL_PROPERTY".equals(effect)
                || "FORCED_DEAL".equals(effect)
                || "DEAL_BREAKER".equals(effect))
                && options != null && options.size() > 1;
    }
}
