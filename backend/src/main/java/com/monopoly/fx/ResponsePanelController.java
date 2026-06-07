package com.monopoly.fx;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import static com.monopoly.fx.FxJson.jsonInt;
import static com.monopoly.fx.FxJson.jsonLong;
import static com.monopoly.fx.FxJson.jsonString;

/** Controls the Just Say No / response panel and its countdown timer. */
final class ResponsePanelController {

    record Refs(
            VBox responseBox,
            Label responseTitleLabel,
            Label responseCountdownLabel,
            Label responseContextLabel,
            Button responseJsnButton,
            Button responsePassButton,
            Label eventLineLabel) {
    }

    private final FxClientState state;
    private final Refs refs;
    private final Supplier<String> playerId;
    private final Function<String, String> displayNameForPlayer;
    private final Function<String, String> colorName;
    private final Function<String, String> actionEffectLabel;
    private final Consumer<Map<String, Object>> submitPlay;
    private final Consumer<String> showError;
    private final FxLocalizer i18n;

    private Timeline responseCountdownTimer;

    ResponsePanelController(
            FxClientState state,
            Refs refs,
            Supplier<String> playerId,
            Function<String, String> displayNameForPlayer,
            Function<String, String> colorName,
            Function<String, String> actionEffectLabel,
            Consumer<Map<String, Object>> submitPlay,
            Consumer<String> showError,
            FxLocalizer i18n) {
        this.state = state;
        this.refs = refs;
        this.playerId = playerId;
        this.displayNameForPlayer = displayNameForPlayer;
        this.colorName = colorName;
        this.actionEffectLabel = actionEffectLabel;
        this.submitPlay = submitPlay;
        this.showError = showError;
        this.i18n = i18n;
    }

    void pass() {
        submitPlay.accept(WsJson.playResponsePass(playerId.get(), null));
    }

    void playJustSayNo() {
        JsonObject card = findJustSayNoCard();
        if (card == null) {
            showError.accept(i18n.get("error.noJsn"));
            return;
        }
        submitPlay.accept(Map.of(
                "actionType", "ACTION",
                "actingPlayerId", playerId.get(),
                "cardId", jsonString(card, "id", "")));
    }

    void updatePanel() {
        JsonObject p = state.lastStatePayload;
        boolean show = p != null
                && "WAITING_FOR_RESPONSE".equals(jsonString(p, "turnPhase", ""))
                && playerId.get().equals(jsonString(p, "pendingResponsePlayerId", ""));
        refs.responseBox().setVisible(show);
        refs.responseBox().setManaged(show);
        if (!show) {
            stopCountdown();
            return;
        }
        int due = jsonInt(p, "pendingPaymentAmountM", 0);
        String role = jsonString(p, "pendingResponseRole", "");
        if ("LANDLORD_COUNTER".equals(role)) {
            refs.responseTitleLabel().setText(i18n.get("response.counterTitle"));
        } else if (due > 0) {
            refs.responseTitleLabel().setText(i18n.get("response.paymentTitle", due));
        } else {
            refs.responseTitleLabel().setText(i18n.get("response.targetedTitle"));
        }
        refs.responseContextLabel().setText(responseContextText(p, due));
        refs.responseJsnButton().setDisable(findJustSayNoCard() == null);
        refs.responsePassButton().setText(due > 0 ? i18n.get("btn.autoPay") : i18n.get("btn.passResponse"));
        startCountdown(jsonLong(p, "responseDeadlineEpochMs", 0L));
    }

    String waitingResponseText() {
        JsonObject p = state.lastStatePayload;
        if (p == null || !"WAITING_FOR_RESPONSE".equals(jsonString(p, "turnPhase", ""))) {
            return "";
        }
        String pending = jsonString(p, "pendingResponsePlayerId", "");
        if (pending.isBlank()) {
            return "";
        }
        long deadline = jsonLong(p, "responseDeadlineEpochMs", 0L);
        String suffix = deadline > 0 ? " · " + Math.max(0L, (deadline - System.currentTimeMillis() + 999L) / 1000L) + "s" : "";
        return playerId.get().equals(pending)
                ? i18n.get("waitingYouResponse", suffix)
                : i18n.get("waitingResponse", displayNameForPlayer.apply(pending), suffix);
    }

    void stopCountdown() {
        if (responseCountdownTimer != null) {
            responseCountdownTimer.stop();
            responseCountdownTimer = null;
        }
        if (refs.responseCountdownLabel() != null) {
            refs.responseCountdownLabel().setText("");
        }
    }

    private String responseContextText(JsonObject state, int due) {
        JsonObject ctx = state.has("pendingResponseContext") && state.get("pendingResponseContext").isJsonObject()
                ? state.getAsJsonObject("pendingResponseContext")
                : null;
        if (ctx == null) {
            return due > 0 ? i18n.get("response.paymentBody") : i18n.get("response.defaultBody");
        }
        StringBuilder out = new StringBuilder();
        out.append(i18n.get("response.actionLine", responseActionTitle(ctx, "")));
        String actor = responsePlayerName(jsonString(ctx, "actorName", ""), jsonString(ctx, "actorPlayerId", ""));
        String target = responsePlayerName(jsonString(ctx, "targetName", ""), jsonString(ctx, "targetPlayerId", ""));
        if (!actor.isBlank() || !target.isBlank()) {
            out.append("\n").append(i18n.get("response.fromTo",
                    actor.isBlank() ? i18n.get("player.fallback") : actor,
                    target.isBlank() ? i18n.get("player.fallback") : target));
        }
        String color = jsonString(ctx, "colorKey", "");
        if (!color.isBlank()) {
            out.append(" · ").append(colorName.apply(color));
        }
        int amount = jsonInt(ctx, "amountDueM", due);
        if (amount > 0) {
            out.append(" · ").append(amount).append("M");
        }
        String original = responseActionTitle(ctx, "original");
        if (!original.isBlank()) {
            out.append("\n").append(i18n.get("response.originalLine", original));
        }
        return out.toString();
    }

    private String responseActionTitle(JsonObject ctx, String prefix) {
        String nameKey = prefix == null || prefix.isBlank() ? "actionCardName" : prefix + "ActionCardName";
        String codeKey = prefix == null || prefix.isBlank() ? "actionEffectCode" : prefix + "ActionEffectCode";
        String code = jsonString(ctx, codeKey, "");
        String localized = actionEffectLabel.apply(code);
        if (!localized.isBlank() && !localized.startsWith("!action.")) {
            return localized;
        }
        String name = jsonString(ctx, nameKey, "");
        return name.isBlank() ? localized : name;
    }

    private String responsePlayerName(String name, String id) {
        if (name != null && !name.isBlank()) {
            return name;
        }
        return id == null || id.isBlank() ? "" : displayNameForPlayer.apply(id);
    }

    private void startCountdown(long deadlineMs) {
        stopCountdown();
        if (deadlineMs <= 0) {
            refs.responseCountdownLabel().setText("");
            return;
        }
        updateCountdownLabel(deadlineMs);
        responseCountdownTimer = new Timeline(new KeyFrame(Duration.seconds(1), event -> {
            updateCountdownLabel(deadlineMs);
            refs.eventLineLabel().setText(waitingResponseText());
        }));
        responseCountdownTimer.setCycleCount(Timeline.INDEFINITE);
        responseCountdownTimer.play();
    }

    private void updateCountdownLabel(long deadlineMs) {
        long left = Math.max(0L, (deadlineMs - System.currentTimeMillis() + 999L) / 1000L);
        refs.responseCountdownLabel().setText(i18n.get("response.countdown", left));
    }

    JsonObject findJustSayNoCard() {
        if (state.latestHandCards == null) {
            return null;
        }
        for (JsonElement el : state.latestHandCards) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject card = el.getAsJsonObject();
            if ("RENT_WAIVER".equalsIgnoreCase(jsonString(card, "effectCode", ""))) {
                return card;
            }
        }
        return null;
    }
}
