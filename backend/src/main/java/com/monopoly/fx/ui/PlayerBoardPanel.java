package com.monopoly.fx.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.monopoly.fx.I18n;
import com.monopoly.fx.presentation.CardDisplayData;
import com.monopoly.fx.presentation.CardImageCache;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Compact public board for one player. It mirrors the web table: player summary,
 * bank strip, and property stacks grouped by effective color.
 */
public final class PlayerBoardPanel extends VBox {

    private static final double SMALL_CARD_W = 44;
    private static final double SMALL_CARD_H = 74;
    private static final double STACK_CARD_STEP_X = 10;
    private static final double STACK_CARD_STEP_Y = 3;
    private static final double STACK_CARD_AREA_H = 80;
    private static final Map<String, Integer> SET_NEEDS = Map.ofEntries(
            Map.entry("BROWN", 2),
            Map.entry("LIGHT_BLUE", 3),
            Map.entry("PINK", 3),
            Map.entry("ORANGE", 3),
            Map.entry("RED", 3),
            Map.entry("YELLOW", 3),
            Map.entry("GREEN", 3),
            Map.entry("DARK_BLUE", 2),
            Map.entry("RAILROAD", 4),
            Map.entry("UTILITY", 2)
    );

    public PlayerBoardPanel(JsonObject player, boolean activeTurn) {
        getStyleClass().add("player-board");
        boolean localLayout = player != null && "LOCAL".equals(jsonStr(player, "_layout", ""));
        if (localLayout) {
            getStyleClass().add("local-board");
        }
        if (activeTurn) {
            getStyleClass().add("active");
        }
        setSpacing(localLayout ? 6 : 8);
        setPadding(localLayout ? new Insets(8, 10, 8, 10) : new Insets(10, 12, 12, 12));

        String playerId = jsonStr(player, "playerId", "--");
        String displayName = jsonStr(player, "displayName", playerId);
        int hand = jsonInt(player, "handCount", 0);
        int bankValue = jsonInt(player, "bankTotalValueM", 0);
        int sets = jsonInt(player, "completePropertySets", 0);

        Label avatar = new Label(initials(displayName, playerId));
        avatar.getStyleClass().add("player-avatar");
        Label name = new Label(displayName);
        name.getStyleClass().add("player-name");
        Label id = new Label(playerId);
        id.getStyleClass().add("player-id");
        Label stats = new Label(I18n.get("board.compactStats", hand, bankValue, propertyCount(player), sets));
        stats.getStyleClass().add("board-stats");
        VBox titleBox = localLayout ? new VBox(1, name, stats) : new VBox(1, name, id);
        HBox.setHgrow(titleBox, Priority.ALWAYS);
        Label setBadge = new Label(sets + "/3");
        setBadge.getStyleClass().addAll("sets-badge", sets >= 3 ? "complete" : "incomplete");
        HBox header = new HBox(9, avatar, titleBox, setBadge);
        header.setAlignment(Pos.CENTER_LEFT);

        HBox bank = new HBox(localLayout ? -6 : 7);
        bank.getStyleClass().add("mini-card-row");
        bank.setAlignment(Pos.BOTTOM_LEFT);
        addCards(bank, player.getAsJsonArray("bankCards"), "bank-card");

        HBox propertyStacks = new HBox(localLayout ? 14 : 11);
        propertyStacks.getStyleClass().add("property-stacks");
        for (PropertyStack stack : propertyStacks(player.getAsJsonArray("propertyZoneCards"))) {
            propertyStacks.getChildren().add(propertyStackNode(stack));
        }
        if (propertyStacks.getChildren().isEmpty()) {
            propertyStacks.getChildren().add(emptyLabel(I18n.get("propertyEmpty")));
        }
        Region zones = localLayout ? new VBox(8) : new HBox(8);
        zones.getStyleClass().add("revealed-zones");
        if (localLayout) {
            zones.getStyleClass().add("local-zones");
        }
        VBox bankZone = zone(I18n.get("bank") + " " + bankValue + "M",
                bank.getChildren().isEmpty() ? emptyLabel(I18n.get("bankEmptyShort")) : horizontalScroll(bank, localLayout ? 94 : 78));
        VBox propertyZone = zone(I18n.get("property") + " " + propertyCount(player),
                horizontalScroll(propertyStacks, localLayout ? 122 : 112));
        if (localLayout) {
            VBox.setVgrow(propertyZone, Priority.ALWAYS);
        } else {
            HBox.setHgrow(bankZone, Priority.ALWAYS);
            HBox.setHgrow(propertyZone, Priority.ALWAYS);
        }
        bankZone.setMinWidth(0);
        propertyZone.setMinWidth(0);
        if (zones instanceof VBox vbox) {
            vbox.getChildren().addAll(bankZone, propertyZone);
        } else if (zones instanceof HBox hbox) {
            hbox.getChildren().addAll(bankZone, propertyZone);
        }

        if (localLayout) {
            getChildren().addAll(header, zones);
        } else {
            getChildren().addAll(header, stats, zones);
        }
    }

    public static StackPane smallCardNode(CardDisplayData data, String extraClass) {
        StackPane wrapper = new StackPane();
        wrapper.getStyleClass().add("small-card");
        if (extraClass != null && !extraClass.isBlank()) {
            wrapper.getStyleClass().add(extraClass);
        }
        javafx.scene.image.Image cardImage = CardImageCache.image(data, SMALL_CARD_W * 2, SMALL_CARD_H * 2);
        if (cardImage != null) {
            ImageView image = new ImageView(cardImage);
            image.setFitWidth(SMALL_CARD_W);
            image.setFitHeight(SMALL_CARD_H);
            image.setPreserveRatio(false);
            image.setSmooth(true);
            wrapper.getChildren().add(image);
            Rectangle clip = new Rectangle(SMALL_CARD_W, SMALL_CARD_H);
            clip.setArcWidth(7);
            clip.setArcHeight(7);
            wrapper.setClip(clip);
            return wrapper;
        }
        Label fallback = new Label(shortTitle(data));
        fallback.setWrapText(true);
        fallback.getStyleClass().add("small-card-label");
        wrapper.getChildren().add(fallback);
        return wrapper;
    }

    private static void addCards(Pane flow, JsonArray cards, String extraClass) {
        if (cards == null) {
            return;
        }
        for (JsonElement el : cards) {
            if (!el.isJsonObject()) {
                continue;
            }
            CardDisplayData data = CardDisplayData.fromHandCardJson(el.getAsJsonObject());
            flow.getChildren().add(smallCardNode(data, extraClass));
        }
    }

    private static VBox propertyStackNode(PropertyStack stack) {
        VBox box = new VBox(3);
        box.getStyleClass().add("property-stack");
        if (stack.complete()) {
            box.getStyleClass().add("complete");
        }
        box.setAlignment(Pos.BOTTOM_LEFT);
        Label title = new Label(stack.label() + " " + stack.cards().size() + "/" + stack.need());
        title.getStyleClass().addAll("property-stack-title", "color-" + stack.color());
        title.setMaxWidth(Region.USE_PREF_SIZE);

        Pane cards = new Pane();
        cards.getStyleClass().add("stack-cards");
        double width = stackWidth(stack.cards().size());
        cards.setMinSize(width, STACK_CARD_AREA_H);
        cards.setPrefSize(width, STACK_CARD_AREA_H);
        cards.setMaxSize(width, STACK_CARD_AREA_H);
        for (int i = 0; i < stack.cards().size(); i++) {
            StackPane card = smallCardNode(stack.cards().get(i), "property-card");
            card.setLayoutX(i * STACK_CARD_STEP_X);
            card.setLayoutY(Math.max(0, STACK_CARD_AREA_H - SMALL_CARD_H - (i * STACK_CARD_STEP_Y)));
            cards.getChildren().add(card);
        }
        box.getChildren().addAll(title, cards);
        return box;
    }

    private static double stackWidth(int cardCount) {
        return SMALL_CARD_W + Math.max(0, cardCount - 1) * STACK_CARD_STEP_X + 8;
    }

    private static List<PropertyStack> propertyStacks(JsonArray cards) {
        Map<String, List<CardDisplayData>> groups = new LinkedHashMap<>();
        if (cards != null) {
            for (JsonElement el : cards) {
                if (!el.isJsonObject()) {
                    continue;
                }
                CardDisplayData data = CardDisplayData.fromHandCardJson(el.getAsJsonObject());
                String color = effectiveColor(data);
                groups.computeIfAbsent(color, ignored -> new ArrayList<>()).add(data);
            }
        }
        List<PropertyStack> stacks = new ArrayList<>();
        for (Map.Entry<String, List<CardDisplayData>> entry : groups.entrySet()) {
            String color = entry.getKey();
            int need = SET_NEEDS.getOrDefault(color, entry.getValue().isEmpty() ? 3 : fallbackNeed(entry.getValue().get(0)));
            stacks.add(new PropertyStack(color, colorName(color), need, entry.getValue()));
        }
        stacks.sort((a, b) -> colorOrder(a.color()) - colorOrder(b.color()));
        return stacks;
    }

    private static String effectiveColor(CardDisplayData data) {
        if ("PROPERTY".equals(data.getKind())) {
            return safe(data.getColorGroup(), "WILD");
        }
        if ("WILD".equals(data.getKind())) {
            if (data.getAssignedColorKey() != null && !data.getAssignedColorKey().isBlank()) {
                return safe(data.getAssignedColorKey(), "WILD");
            }
            if (data.getColorGroup() != null && !data.getColorGroup().isBlank()) {
                return safe(data.getColorGroup(), "WILD");
            }
            if (!data.getPrintedColors().isEmpty()) {
                return safe(data.getPrintedColors().get(0), "WILD");
            }
        }
        return "WILD";
    }

    private static int fallbackNeed(CardDisplayData data) {
        return data.getSetNeed() == null || data.getSetNeed() <= 0 ? 3 : data.getSetNeed();
    }

    private static int colorOrder(String color) {
        return switch (safe(color, "")) {
            case "BROWN" -> 0;
            case "LIGHT_BLUE" -> 1;
            case "PINK" -> 2;
            case "ORANGE" -> 3;
            case "RED" -> 4;
            case "YELLOW" -> 5;
            case "GREEN" -> 6;
            case "DARK_BLUE" -> 7;
            case "RAILROAD" -> 8;
            case "UTILITY" -> 9;
            default -> 10;
        };
    }

    private static Label section(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("board-section-title");
        return label;
    }

    private static VBox zone(String title, Node body) {
        VBox box = new VBox(6);
        box.getStyleClass().add("revealed-zone");
        Label label = section(title);
        VBox.setVgrow(body, Priority.ALWAYS);
        box.getChildren().addAll(label, body);
        return box;
    }

    private static ScrollPane horizontalScroll(Node body, double prefHeight) {
        ScrollPane scroll = new ScrollPane(body);
        scroll.getStyleClass().add("zone-scroll");
        scroll.setFitToHeight(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setMinHeight(0);
        scroll.setPrefHeight(prefHeight);
        scroll.setMaxHeight(prefHeight);
        return scroll;
    }

    private static Label emptyLabel(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.getStyleClass().add("board-empty");
        return label;
    }

    private static int propertyCount(JsonObject player) {
        JsonArray cards = player == null ? null : player.getAsJsonArray("propertyZoneCards");
        return cards == null ? 0 : cards.size();
    }

    private static String initials(String displayName, String playerId) {
        String source = displayName == null || displayName.isBlank() ? playerId : displayName;
        if (source == null || source.isBlank()) {
            return "?";
        }
        String trimmed = source.trim();
        return trimmed.substring(0, Math.min(2, trimmed.length())).toUpperCase(Locale.ROOT);
    }

    private static String shortTitle(CardDisplayData data) {
        String title = data == null ? "" : data.getTitle();
        if (title.length() > 18) {
            return title.substring(0, 17) + "...";
        }
        if (data != null && data.getValueM() != null) {
            return title + "\n" + data.getValueM() + "M";
        }
        return title;
    }

    private static String colorName(String key) {
        String value = I18n.get("color." + safe(key, ""));
        return value.startsWith("!color.") ? key : value;
    }

    private static String safe(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private static String jsonStr(JsonObject obj, String key, String fallback) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return fallback;
        }
        try {
            return obj.get(key).getAsString();
        } catch (RuntimeException ex) {
            return fallback;
        }
    }

    private static int jsonInt(JsonObject obj, String key, int fallback) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return fallback;
        }
        try {
            return obj.get(key).getAsInt();
        } catch (RuntimeException ex) {
            return fallback;
        }
    }

    private record PropertyStack(String color, String label, int need, List<CardDisplayData> cards) {
        boolean complete() {
            return cards.size() >= need;
        }
    }
}
