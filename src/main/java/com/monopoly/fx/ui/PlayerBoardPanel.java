package com.monopoly.fx.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.FlowPane;
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
        setSpacing(6);
        setPadding(new Insets(10));

        String pid = jsonStr(playerObj, "playerId", "—");
        String pname = jsonStr(playerObj, "displayName", pid);
        Label title = new Label(pname + "  (" + pid + ")");
        title.getStyleClass().add("player-title");

        int hand = jsonInt(playerObj, "handCount", 0);
        int bank = jsonInt(playerObj, "bankCount", 0);
        int prop = jsonInt(playerObj, "propertyCount", 0);
        int sets = jsonInt(playerObj, "completePropertySets", 0);
        int act = jsonInt(playerObj, "actionZoneCount", 0);
        int bankVal = jsonInt(playerObj, "bankTotalValueM", 0);

        Label stats = new Label(String.format(
                Locale.ROOT, "手牌 %d ｜ 银行 %d 张（共 %dM）｜ 房产 %d ｜ 行动区 %d ｜ 完整套 %d",
                hand, bank, bankVal, prop, act, sets));
        stats.getStyleClass().add("player-stats");

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
        bankPane.setText("银行牌（每张）");
        bankPane.setCollapsible(true);
        bankPane.setExpanded(bank > 0 && bank <= 12);
        FlowPane bankFlow = zoneCardFlow(playerObj.getAsJsonArray("bankCards"));
        bankPane.setContent(wrapScroll(bankFlow));

        TitledPane propPane = new TitledPane();
        propPane.setText("财产区（每张）");
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

        getChildren().addAll(title, stats);
        if (!progressChips.getChildren().isEmpty()) {
            Label pTitle = new Label("凑套进度");
            pTitle.getStyleClass().add("zone-section-title");
            getChildren().addAll(pTitle, progressChips);
        }
        getChildren().addAll(bankPane, propPane);
        if (!legacyChips.getChildren().isEmpty()) {
            Label oTitle = new Label("颜色张数（简）");
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
            flow.getChildren().add(new Label("（空）"));
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
            flow.getChildren().add(lab);
        }
        if (!any) {
            flow.getChildren().add(new Label("（空）"));
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
