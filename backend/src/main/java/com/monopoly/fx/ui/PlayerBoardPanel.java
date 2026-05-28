package com.monopoly.fx.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.monopoly.fx.I18n;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.Locale;

/**
 * A compact table mat for one player's public zones.
 */
public final class PlayerBoardPanel extends VBox {

    private static String colorName(String key) {
        return I18n.get("color." + key);
    }

    public PlayerBoardPanel(JsonObject playerObj, boolean activeTurn) {
        getStyleClass().add("player-panel");
        if (activeTurn) {
            getStyleClass().add("player-panel-active");
        }
        setSpacing(8);
        setPadding(new Insets(10, 12, 12, 12));

        String pid = jsonStr(playerObj, "playerId", "--");
        String pname = jsonStr(playerObj, "displayName", pid);

        Label avatar = new Label(initials(pname, pid));
        avatar.getStyleClass().add("player-avatar");

        Label title = new Label(pname);
        title.getStyleClass().add("player-title");
        Label id = new Label(pid);
        id.getStyleClass().add("player-id");
        VBox names = new VBox(1, title, id);
        HBox.setHgrow(names, Priority.ALWAYS);

        int hand = jsonInt(playerObj, "handCount", 0);
        int bank = jsonInt(playerObj, "bankCount", 0);
        int prop = jsonInt(playerObj, "propertyCount", 0);
        int sets = jsonInt(playerObj, "completePropertySets", 0);
        int act = jsonInt(playerObj, "actionZoneCount", 0);
        int bankVal = jsonInt(playerObj, "bankTotalValueM", 0);

        Label setsBadge = new Label(sets + "/3 SETS");
        setsBadge.getStyleClass().addAll("chip", sets >= 3 ? "color-GREEN" : "color-RAILROAD");

        HBox header = new HBox(9, avatar, names, setsBadge);
        header.setAlignment(Pos.CENTER_LEFT);

        Label stats = new Label(I18n.get("board.stats", hand, bank, bankVal, prop, act, sets));
        stats.getStyleClass().add("player-stats");

        FlowPane progressChips = progressChips(playerObj);
        FlowPane bankFlow = zoneCardFlow(playerObj.getAsJsonArray("bankCards"));
        FlowPane propFlow = zoneCardFlow(playerObj.getAsJsonArray("propertyZoneCards"));

        getChildren().addAll(header, stats);
        if (!progressChips.getChildren().isEmpty()) {
            getChildren().addAll(sectionTitle(I18n.get("board.setProgress")), progressChips);
        }
        getChildren().addAll(
                sectionTitle(I18n.get("board.bankCards")), bankFlow,
                sectionTitle(I18n.get("board.propertyCards")), propFlow
        );
    }

    private static Label sectionTitle(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("zone-section-title");
        return label;
    }

    private static FlowPane progressChips(JsonObject playerObj) {
        FlowPane flow = new FlowPane();
        flow.setHgap(6);
        flow.setVgap(6);
        if (!playerObj.has("propertyColorProgress") || !playerObj.get("propertyColorProgress").isJsonArray()) {
            return flow;
        }
        for (JsonElement el : playerObj.getAsJsonArray("propertyColorProgress")) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject row = el.getAsJsonObject();
            String ck = jsonStr(row, "colorKey", "");
            int eff = jsonInt(row, "effectiveCount", 0);
            int need = jsonInt(row, "need", 0);
            int completeSets = jsonInt(row, "completeSets", 0);
            if (ck.isEmpty()) {
                continue;
            }
            String text = need > 0 ? colorName(ck) + " " + eff + "/" + need : colorName(ck) + " x" + eff;
            if (completeSets > 0) {
                text += " " + I18n.get("board.complete", completeSets);
            }
            Label chip = new Label(text);
            chip.getStyleClass().addAll("chip", "color-" + ck);
            flow.getChildren().add(chip);
        }
        return flow;
    }

    private static FlowPane zoneCardFlow(JsonArray arr) {
        FlowPane flow = new FlowPane();
        flow.setHgap(6);
        flow.setVgap(6);
        if (arr == null || arr.isEmpty()) {
            Label empty = new Label(I18n.get("board.empty"));
            empty.getStyleClass().add("player-stats");
            flow.getChildren().add(empty);
            return flow;
        }
        for (JsonElement el : arr) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject c = el.getAsJsonObject();
            Label lab = new Label(shortZoneLabel(c));
            lab.setWrapText(true);
            lab.getStyleClass().addAll("zone-mini-card", miniStyle(c));
            flow.getChildren().add(lab);
        }
        if (flow.getChildren().isEmpty()) {
            Label empty = new Label(I18n.get("board.empty"));
            empty.getStyleClass().add("player-stats");
            flow.getChildren().add(empty);
        }
        return flow;
    }

    private static String miniStyle(JsonObject c) {
        String kind = jsonStr(c, "kind", "").toUpperCase(Locale.ROOT);
        return switch (kind) {
            case "MONEY" -> "mini-money";
            case "ACTION" -> "mini-action";
            case "PROPERTY" -> "mini-property";
            case "WILD" -> "mini-wild";
            default -> "mini-property";
        };
    }

    private static String shortZoneLabel(JsonObject c) {
        String kind = jsonStr(c, "kind", "").toUpperCase(Locale.ROOT);
        int vm = jsonInt(c, "valueM", 0);
        String title = jsonStr(c, I18n.isChinese() ? "titleZh" : "titleEn", "");
        if (title.isBlank()) {
            title = jsonStr(c, "name", kind);
        }
        if (title.length() > 16) {
            title = title.substring(0, 15) + "...";
        }
        String bl = jsonStr(c, "buildingLevel", "");
        String extra = "";
        if (!bl.isBlank() && !"BASE".equals(bl)) {
            extra = " " + bl;
        }
        return switch (kind) {
            case "MONEY", "ACTION" -> title + "\n" + vm + "M";
            case "PROPERTY", "WILD" -> title + extra + "\n" + I18n.get("board.pledge") + " " + vm + "M";
            default -> title + "\n" + vm + "M";
        };
    }

    private static String initials(String displayName, String playerId) {
        String s = (displayName == null || displayName.isBlank()) ? playerId : displayName;
        if (s == null || s.isBlank()) {
            return "?";
        }
        String trimmed = s.trim();
        return trimmed.substring(0, Math.min(2, trimmed.length())).toUpperCase(Locale.ROOT);
    }

    private static String jsonStr(JsonObject o, String k, String def) {
        if (o == null || !o.has(k) || o.get(k).isJsonNull()) {
            return def;
        }
        try {
            return o.get(k).getAsString();
        } catch (RuntimeException e) {
            return def;
        }
    }

    private static int jsonInt(JsonObject o, String k, int def) {
        if (o == null || !o.has(k) || o.get(k).isJsonNull()) {
            return def;
        }
        try {
            return o.get(k).getAsInt();
        } catch (RuntimeException e) {
            return def;
        }
    }
}
