package com.monopoly.fx;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.monopoly.fx.presentation.CardDisplayData;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.layout.StackPane;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

import static com.monopoly.fx.FxJson.blankToNull;
import static com.monopoly.fx.FxJson.jsonBool;
import static com.monopoly.fx.FxJson.jsonInt;
import static com.monopoly.fx.FxJson.jsonString;
import static com.monopoly.fx.FxJson.safeUpper;

/** Builds and handles action-parameter dialogs for playable card options. */
final class ActionOptionDialogService {

    @FunctionalInterface
    interface PlaySender {
        void send(
                String cardId,
                String actionType,
                String targetPlayerId,
                String targetColorKey,
                String targetCardId,
                String actorCardId,
                String targetZone);
    }

    private final StackPane root;
    private final FxClientState state;
    private final Supplier<String> playerId;
    private final BiFunction<String, String, String> publicCardLabel;
    private final Function<String, String> displayNameForPlayer;
    private final Function<String, String> colorName;
    private final FxLocalizer i18n;
    private final PlaySender playSender;

    ActionOptionDialogService(
            StackPane root,
            FxClientState state,
            Supplier<String> playerId,
            BiFunction<String, String, String> publicCardLabel,
            Function<String, String> displayNameForPlayer,
            Function<String, String> colorName,
            FxLocalizer i18n,
            PlaySender playSender) {
        this.root = root;
        this.state = state;
        this.playerId = playerId;
        this.publicCardLabel = publicCardLabel;
        this.displayNameForPlayer = displayNameForPlayer;
        this.colorName = colorName;
        this.i18n = i18n;
        this.playSender = playSender;
    }

    void showOptionDialog(String actionType, String cardId, JsonArray options) {
        if (options == null || options.size() == 0) {
            playSender.send(cardId, actionType, null, null, null, null, null);
            return;
        }
        Dialog<JsonObject> dialog = new Dialog<>();
        dialog.setTitle(i18n.get("dialog.chooseParam"));
        dialog.setHeaderText(i18n.get("dialog.playOptionHeader"));
        ButtonType cancelType = new ButtonType(i18n.get("dialog.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);
        ButtonType confirmType = new ButtonType(i18n.get("dialog.confirm"), ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(cancelType, confirmType);
        ListView<JsonObject> list = new ListView<>();
        list.setPrefSize(640, Math.min(420, Math.max(180, options.size() * 54)));
        for (JsonElement el : options) {
            if (el.isJsonObject()) {
                list.getItems().add(el.getAsJsonObject());
            }
        }
        list.setCellFactory(view -> new ListCell<>() {
            @Override
            protected void updateItem(JsonObject item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "" : optionLabel(item, actionType));
            }
        });
        if (!list.getItems().isEmpty()) {
            list.getSelectionModel().selectFirst();
        }
        dialog.getDialogPane().setContent(list);
        dialog.setResultConverter(button -> button == confirmType
                ? list.getSelectionModel().getSelectedItem()
                : null);
        if (root != null && root.getScene() != null) {
            dialog.initOwner(root.getScene().getWindow());
        }
        dialog.showAndWait().ifPresent(row -> sendPlayFromOptionRow(actionType, cardId, row));
    }

    void sendPlayFromOptionRow(String actionType, String cardId, JsonObject row) {
        boolean allOthers = row != null && jsonBool(row, "allOtherPlayers", false);
        playSender.send(
                cardId,
                actionType,
                allOthers ? null : blankToNull(jsonString(row, "targetPlayerId", "")),
                blankToNull(jsonString(row, "targetColorKey", "")),
                blankToNull(jsonString(row, "targetCardId", "")),
                blankToNull(jsonString(row, "actorCardId", "")),
                blankToNull(jsonString(row, "targetZone", ""))
        );
    }

    String optionLabel(JsonObject row, String actionType) {
        if (row == null) {
            return i18n.get("dialog.directPlay");
        }
        if ("DEPLOY".equals(actionType)) {
            String color = jsonString(row, "targetColorKey", "");
            if (!color.isBlank()) {
                return i18n.get("deployAsColor", colorName.apply(color));
            }
        }
        String effect = state.selectedCard == null ? "" : safeUpper(state.selectedCard.getEffectCode());
        String targetPlayer = jsonString(row, "targetPlayerId", "");
        String targetColor = jsonString(row, "targetColorKey", "");
        String targetCard = jsonString(row, "targetCardId", "");
        String actorCard = jsonString(row, "actorCardId", "");
        boolean allOthers = jsonBool(row, "allOtherPlayers", false);
        String targetName = targetPlayer.isBlank() ? i18n.get("player.fallback") : displayNameForPlayer.apply(targetPlayer);
        String targetColorName = targetColor.isBlank() ? "" : colorName.apply(targetColor);
        String targetCardName = targetCard.isBlank()
                ? i18n.get("dialog.unknownCard")
                : publicCardLabel.apply(targetPlayer, targetCard);
        String actorCardName = actorCard.isBlank()
                ? i18n.get("dialog.unknownCard")
                : publicCardLabel.apply(playerId.get(), actorCard);

        if ("ACTION".equals(actionType)) {
            switch (effect) {
                case "STEAL_PROPERTY" -> {
                    return i18n.get("dialog.stealOption", targetName, targetCardName);
                }
                case "FORCED_DEAL" -> {
                    return i18n.get("dialog.forcedDealOption", actorCardName, targetName, targetCardName);
                }
                case "DEAL_BREAKER" -> {
                    return i18n.get("dialog.dealBreakerOption", targetName, targetColorName);
                }
                case "DEBT_COLLECTOR" -> {
                    return i18n.get("dialog.debtOption", targetName);
                }
                case "RENT", "RENT_DUAL" -> {
                    String label = allOthers
                            ? i18n.get("dialog.allRentOption", targetColorName)
                            : i18n.get("dialog.rentOption", targetName, targetColorName);
                    String amountLabel = optionRentAmountLabel(row);
                    return amountLabel.isBlank() ? label : label + " · " + amountLabel;
                }
                case "DOUBLE_RENT" -> {
                    return i18n.get("dialog.doubleRentOption");
                }
                case "HOUSE" -> {
                    return i18n.get("dialog.houseOption", targetCardName);
                }
                case "HOTEL" -> {
                    return i18n.get("dialog.hotelOption", targetCardName);
                }
                default -> {
                    if (targetPlayer.isBlank() && targetColor.isBlank() && targetCard.isBlank() && actorCard.isBlank()) {
                        return i18n.get("dialog.directPlay");
                    }
                }
            }
        }

        String label = jsonString(row, I18n.isChinese() ? "labelZh" : "labelEn", "");
        if (I18n.isChinese() && !label.isBlank()) {
            return label;
        }
        List<String> pieces = new ArrayList<>();
        if (!targetPlayer.isBlank()) {
            pieces.add(i18n.get("dialog.targetPlayer") + ": " + targetName);
        }
        if (!targetColor.isBlank()) {
            pieces.add(i18n.get("dialog.targetColor") + ": " + targetColorName);
        }
        if (!targetCard.isBlank()) {
            pieces.add(i18n.get("dialog.targetCard") + ": " + targetCardName);
        }
        if (!actorCard.isBlank()) {
            pieces.add(i18n.get("dialog.actorCard") + ": " + actorCardName);
        }
        return pieces.isEmpty() ? i18n.get("dialog.directPlay") : String.join(" · ", pieces);
    }

    private String optionRentAmountLabel(JsonObject row) {
        int amount = firstPositiveJsonInt(row, "displayRentAmountM", "rentAmountM", "amountDueM", "amountDue");
        if (amount <= 0) {
            return "";
        }
        int base = firstPositiveJsonInt(row, "baseRentAmountM", "baseAmountM");
        return base > 0 && base != amount
                ? i18n.get("dialog.rentPreviewDouble", amount, base)
                : i18n.get("dialog.rentPreview", amount);
    }

    private static int firstPositiveJsonInt(JsonObject row, String... keys) {
        if (row == null || keys == null) {
            return 0;
        }
        for (String key : keys) {
            int value = jsonInt(row, key, 0);
            if (value > 0) {
                return value;
            }
        }
        return 0;
    }
}
