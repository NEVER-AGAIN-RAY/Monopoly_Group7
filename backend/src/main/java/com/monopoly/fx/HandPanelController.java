package com.monopoly.fx;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.monopoly.fx.presentation.CardDisplayData;
import com.monopoly.fx.ui.CardView;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Toggle;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

import static com.monopoly.fx.FxJson.jsonString;

/** Renders and manages the local player's hand strip and selected-card preview. */
final class HandPanelController {

    record Refs(
            ScrollPane handScroll,
            HBox handStrip,
            Label selectedCardLabel,
            StackPane selectedPreviewPane) {
    }

    private final FxClientState state;
    private final Refs refs;
    private final Function<CardDisplayData, String> cardKindClass;
    private final FxLocalizer i18n;

    HandPanelController(
            FxClientState state,
            Refs refs,
            Function<CardDisplayData, String> cardKindClass,
            FxLocalizer i18n) {
        this.state = state;
        this.refs = refs;
        this.cardKindClass = cardKindClass;
        this.i18n = i18n;
    }

    void installSelectionHandling(
            ToggleGroup handToggleGroup,
            Consumer<CardDisplayData> quickPlay,
            Runnable syncActionButtons) {
        handToggleGroup.selectedToggleProperty().addListener((obs, oldToggle, newToggle) -> {
            if (newToggle instanceof CardView cardView) {
                state.selectedCard = cardView.getCardData();
                refs.selectedCardLabel().setText(i18n.get("label.selected", state.selectedCard.getTitle()));
            } else {
                state.selectedCard = null;
                refs.selectedCardLabel().setText(i18n.get("label.selectCardHint"));
            }
            updateSelectedPreview();
            syncActionButtons.run();
        });

        if (refs.handScroll() != null) {
            refs.handScroll().viewportBoundsProperty().addListener((obs, oldB, newB) -> recomputeHandSpacing());
        }

        this.quickPlay = quickPlay;
        this.handToggleGroup = handToggleGroup;
        this.syncActionButtons = syncActionButtons;
    }

    private ToggleGroup handToggleGroup;
    private Consumer<CardDisplayData> quickPlay;
    private Runnable syncActionButtons;

    void rebuildHand() {
        refs.handStrip().getChildren().clear();
        for (Toggle toggle : new ArrayList<>(handToggleGroup.getToggles())) {
            handToggleGroup.getToggles().remove(toggle);
        }
        state.selectedCard = null;
        if (state.latestHandCards == null || state.latestHandCards.isEmpty()) {
            refs.selectedCardLabel().setText(i18n.get("emptyHand"));
            updateSelectedPreview();
            syncActionButtons.run();
            return;
        }
        int index = 0;
        int total = state.latestHandCards.size();
        for (JsonElement el : state.latestHandCards) {
            if (!el.isJsonObject()) {
                continue;
            }
            CardDisplayData data = CardDisplayData.fromHandCardJson(el.getAsJsonObject());
            CardView view = new CardView(data, cardKindClass.apply(data));
            view.setToggleGroup(handToggleGroup);
            view.setRotate(Math.max(-6.0, Math.min(6.0, (index - (total - 1) / 2.0) * 1.2)));
            view.setOnMouseClicked(event -> {
                if (event.getClickCount() >= 2) {
                    quickPlay.accept(data);
                }
            });
            refs.handStrip().getChildren().add(view);
            index++;
        }
        recomputeHandSpacing();
        refs.selectedCardLabel().setText(i18n.get("label.selectCardHint"));
        updateSelectedPreview();
        syncActionButtons.run();
    }

    void recomputeHandSpacing() {
        int n = refs.handStrip().getChildren().size();
        if (n <= 1) {
            refs.handStrip().setSpacing(-32);
            return;
        }
        double cardW = CardView.CARD_WIDTH;
        double padding = 96;
        double spacing = -32;
        double avail = refs.handScroll() != null && refs.handScroll().getViewportBounds() != null
                ? refs.handScroll().getViewportBounds().getWidth()
                : 0;
        if (avail > cardW + padding) {
            double step = (avail - padding - cardW) / (n - 1);
            spacing = step - cardW;
        }
        spacing = Math.max(-86, Math.min(-12, spacing));
        refs.handStrip().setSpacing(spacing);
    }

    void updateSelectedPreview() {
        refs.selectedPreviewPane().getChildren().clear();
        if (state.selectedCard == null) {
            Label placeholder = new Label(i18n.get("preview.selectCard"));
            placeholder.setWrapText(true);
            placeholder.getStyleClass().add("selected-preview-empty");
            refs.selectedPreviewPane().getChildren().add(placeholder);
            return;
        }
        CardView preview = new CardView(state.selectedCard, cardKindClass.apply(state.selectedCard));
        preview.getStyleClass().add("selected-preview-card");
        preview.setMouseTransparent(true);
        refs.selectedPreviewPane().getChildren().add(preview);
    }

    static String handSignature(JsonArray cards) {
        if (cards == null || cards.isEmpty()) {
            return "";
        }
        List<String> ids = new ArrayList<>();
        for (JsonElement el : cards) {
            if (el.isJsonObject()) {
                ids.add(jsonString(el.getAsJsonObject(), "id", ""));
            }
        }
        return String.join("|", ids);
    }
}
