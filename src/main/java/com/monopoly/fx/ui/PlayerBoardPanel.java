package com.monopoly.fx.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.Locale;

/**
 * 单名玩家公开状态展示块（对局桌面）：银行/财产明细、凑套进度。
 */
public final class PlayerBoardPanel extends VBox {

    private static final java.util.Map<String, String> COLOR_ZH = java.util.Map.ofEntries(
            java.util.Map.entry("BROWN", "棕"),
            java.util.Map.entry("LIGHT_BLUE", "浅蓝"),
            java.util.Map.entry("PINK", "粉"),
            java.util.Map.entry("ORANGE", "橙"),
            java.util.Map.entry("RED", "红"),
            java.util.Map.entry("YELLOW", "黄"),
            java.util.Map.entry("GREEN", "绿"),
            java.util.Map.entry("DARK_BLUE", "深蓝"),
            java.util.Map.entry("RAILROAD", "铁路"),
            java.util.Map.entry("UTILITY", "公共"),
            java.util.Map.entry("WILD_UNASSIGNED", "万能未分配")
    );

    public PlayerBoardPanel(JsonObject playerObj, boolean activeTurn) {
        getStyleClass().add("player-panel");
        if (activeTurn) {
            getStyleClass().add("player-panel-active");
        }
        setSpacing(8);
        setPadding(new Insets(12));
        setPrefWidth(296);
        setMinWidth(272);
        setMaxWidth(328);

        String pid = jsonStr(playerObj, "playerId", "—");
        String pname = jsonStr(playerObj, "displayName", pid);
        Label avatar = new Label(avatarText(pname, pid));
        avatar.getStyleClass().add("player-avatar");

        Label title = new Label(pname);
        title.getStyleClass().add("player-title");
        title.setWrapText(true);
        title.setMaxWidth(Double.MAX_VALUE);

        Label idLabel = new Label(pid);
        idLabel.getStyleClass().add("player-id");

        VBox nameBox = new VBox(1, title, idLabel);
        HBox.setHgrow(nameBox, Priority.ALWAYS);

        HBox header = new HBox(8, avatar, nameBox);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("player-header");
        if (activeTurn) {
            Label turnBadge = new Label("行动中");
            turnBadge.getStyleClass().add("player-turn-badge");
            header.getChildren().add(turnBadge);
        }

        int hand = jsonInt(playerObj, "handCount", 0);
        int bank = jsonInt(playerObj, "bankCount", 0);
        int prop = jsonInt(playerObj, "propertyCount", 0);
        int sets = jsonInt(playerObj, "completePropertySets", 0);
        int act = jsonInt(playerObj, "actionZoneCount", 0);
        int bankVal = jsonInt(playerObj, "bankTotalValueM", 0);

        HBox statRow = new HBox(6,
                statTile(String.valueOf(hand), "手牌"),
                statTile(bankVal + "M", "金库"),
                statTile(String.valueOf(prop), "地产"),
                statTile(sets + "/3", "套装"));
        statRow.getStyleClass().add("player-stats-row");

        Label stats = new Label(String.format(Locale.ROOT, "金库 %d 张 ｜ 行动区 %d 张", bank, act));
        stats.getStyleClass().add("player-stats");
        stats.setWrapText(true);
        stats.setMaxWidth(Double.MAX_VALUE);

        FlowPane progressChips = new FlowPane();
        progressChips.setHgap(6);
        progressChips.setVgap(6);
        if (playerObj.has("propertyColorProgress") && playerObj.get("propertyColorProgress").isJsonArray()) {
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
                String cz = COLOR_ZH.getOrDefault(ck, ck);
                String txt = need > 0 ? (cz + " " + eff + "/" + need) : (cz + " ×" + eff);
                if (completeSets > 0) {
                    txt += " 满×" + completeSets;
                }
                Label chip = new Label(txt);
                chip.getStyleClass().addAll("chip", "color-" + ck);
                progressChips.getChildren().add(chip);
            }
        }

        TitledPane bankPane = new TitledPane();
        bankPane.setText("金库");
        bankPane.setCollapsible(true);
        bankPane.setExpanded(bank > 0 && bank <= 12);
        FlowPane bankFlow = zoneCardFlow(playerObj.getAsJsonArray("bankCards"));
        bankPane.setContent(wrapScroll(bankFlow));

        TitledPane propPane = new TitledPane();
        propPane.setText("地产");
        propPane.setCollapsible(true);
        propPane.setExpanded(prop > 0 && prop <= 10);
        FlowPane propFlow = zoneCardFlow(playerObj.getAsJsonArray("propertyZoneCards"));
        propPane.setContent(wrapScroll(propFlow));

        FlowPane legacyChips = new FlowPane();
        legacyChips.setHgap(6);
        legacyChips.setVgap(6);
        if (playerObj.has("propertyCountsByColor") && playerObj.get("propertyCountsByColor").isJsonArray()) {
            JsonArray arr = playerObj.getAsJsonArray("propertyCountsByColor");
            for (JsonElement el : arr) {
                if (!el.isJsonObject()) {
                    continue;
                }
                JsonObject c = el.getAsJsonObject();
                String ck = jsonStr(c, "colorKey", "");
                int cnt = jsonInt(c, "count", 0);
                if (cnt <= 0 || ck.isEmpty()) {
                    continue;
                }
                Label chip = new Label(COLOR_ZH.getOrDefault(ck, ck) + " ×" + cnt);
                chip.getStyleClass().addAll("chip", "color-" + ck);
                legacyChips.getChildren().add(chip);
            }
        }

        getChildren().addAll(header, statRow, stats);
        if (!progressChips.getChildren().isEmpty()) {
            Label pTitle = new Label("凑套进度");
            pTitle.getStyleClass().add("zone-section-title");
            getChildren().addAll(pTitle, progressChips);
        }
        getChildren().addAll(bankPane, propPane);
        if (!legacyChips.getChildren().isEmpty()) {
            Label oTitle = new Label("颜色张数");
            oTitle.getStyleClass().add("zone-section-title");
            getChildren().addAll(oTitle, legacyChips);
        }
    }

    private static VBox wrapScroll(FlowPane flow) {
        VBox v = new VBox(flow);
        v.setPadding(new Insets(4, 0, 4, 0));
        return v;
    }

    private static FlowPane zoneCardFlow(JsonArray arr) {
        FlowPane flow = new FlowPane();
        flow.setHgap(6);
        flow.setVgap(6);
        if (arr == null) {
            flow.getChildren().add(new Label("空"));
            return flow;
        }
        boolean any = false;
        for (JsonElement el : arr) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject c = el.getAsJsonObject();
            any = true;
            Label lab = new Label(shortZoneLabel(c));
            lab.setWrapText(true);
            lab.setMaxWidth(108);
            lab.getStyleClass().add("zone-mini-card");
            lab.getStyleClass().add(zoneMiniKindClass(jsonStr(c, "kind", "")));
            flow.getChildren().add(lab);
        }
        if (!any) {
            flow.getChildren().add(new Label("空"));
        }
        return flow;
    }

    private static String shortZoneLabel(JsonObject c) {
        String kind = jsonStr(c, "kind", "").toUpperCase(Locale.ROOT);
        int vm = jsonInt(c, "valueM", 0);
        String title = jsonStr(c, "titleZh", "");
        if (title.length() > 18) {
            title = title.substring(0, 17) + "…";
        }
        String bl = jsonStr(c, "buildingLevel", "");
        String extra = "";
        if (!bl.isBlank() && !"BASE".equals(bl)) {
            extra = " " + bl;
        }
        return switch (kind) {
            case "MONEY", "ACTION" -> (title.isBlank() ? kind : title) + " · " + vm + "M";
            case "PROPERTY", "WILD" -> (title.isBlank() ? kind : title) + extra + " ·抵" + vm + "M";
            default -> title + " ·" + vm + "M";
        };
    }

    private static VBox statTile(String value, String label) {
        Label v = new Label(value);
        v.getStyleClass().add("stat-value");
        Label l = new Label(label);
        l.getStyleClass().add("stat-label");
        VBox box = new VBox(1, v, l);
        box.setAlignment(Pos.CENTER);
        box.getStyleClass().add("stat-tile");
        return box;
    }

    private static String avatarText(String name, String pid) {
        String source = name == null || name.isBlank() ? pid : name;
        if (source == null || source.isBlank()) {
            return "?";
        }
        return source.substring(0, Math.min(source.length(), 1)).toUpperCase(Locale.ROOT);
    }

    private static String zoneMiniKindClass(String kind) {
        String k = kind == null ? "" : kind.toUpperCase(Locale.ROOT);
        return switch (k) {
            case "MONEY" -> "zone-mini-money";
            case "PROPERTY", "WILD" -> "zone-mini-property";
            case "ACTION" -> "zone-mini-action";
            default -> "zone-mini-card";
        };
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
