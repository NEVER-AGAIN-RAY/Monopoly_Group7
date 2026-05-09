package com.monopoly.fx.ui;

import com.monopoly.fx.presentation.CardDisplayData;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * 单张手牌可视化控件（样式见 styles.css）。
 */
public class CardView extends ToggleButton {

    private final CardDisplayData data;

    public CardView(CardDisplayData data, String kindStyleClass) {
        this.data = data;
        getStyleClass().addAll("card", kindStyleClass);
        setWrapText(true);
        setMaxWidth(Region.USE_PREF_SIZE);
        setText(null);

        Region colorBar = new Region();
        colorBar.getStyleClass().add("color-bar");
        applyColorBar(colorBar, data);

        Label title = new Label(data.getTitleZh());
        title.getStyleClass().add("card-title");
        title.setMaxWidth(Double.MAX_VALUE);
        title.setAlignment(Pos.CENTER);

        Label hint = new Label(data.getHintZh());
        hint.getStyleClass().add("card-hint");
        hint.setMaxWidth(Double.MAX_VALUE);
        hint.setAlignment(Pos.CENTER);

        Label rentDetail = null;
        if (data.getRentDetailZh() != null && !data.getRentDetailZh().isBlank()) {
            rentDetail = new Label(data.getRentDetailZh());
            rentDetail.getStyleClass().add("card-rent-detail");
            rentDetail.setMaxWidth(Double.MAX_VALUE);
            rentDetail.setAlignment(Pos.CENTER);
        }

        Label meta = new Label(cardMeta(data));
        meta.getStyleClass().add("card-meta");
        meta.setMaxWidth(Double.MAX_VALUE);
        meta.setAlignment(Pos.CENTER);

        Label badge = new Label(kindBadge(data.getKind()));
        badge.getStyleClass().add("card-kind-badge");
        badge.setMaxWidth(Double.MAX_VALUE);
        badge.setAlignment(Pos.CENTER);

        VBox center = new VBox(4);
        center.getChildren().addAll(title, hint);
        if (rentDetail != null) {
            center.getChildren().add(rentDetail);
        }
        if (!meta.getText().isBlank()) {
            center.getChildren().add(meta);
        }
        center.setAlignment(Pos.CENTER);
        VBox.setVgrow(hint, Priority.ALWAYS);
        if (rentDetail != null) {
            VBox.setVgrow(rentDetail, Priority.SOMETIMES);
        }

        BorderPane bp = new BorderPane();
        BorderPane.setAlignment(colorBar, Pos.CENTER);
        bp.setTop(colorBar);
        bp.setCenter(center);
        BorderPane.setMargin(center, new Insets(6, 8, 6, 8));
        bp.setBottom(badge);
        BorderPane.setMargin(badge, new Insets(0, 6, 6, 6));

        StackPane root = new StackPane(bp);
        root.setMaxWidth(Region.USE_PREF_SIZE);
        if (data.getValueM() != null) {
            Label corner = new Label(data.getValueM() + "M");
            corner.getStyleClass().add("card-value-corner");
            StackPane.setAlignment(corner, Pos.TOP_RIGHT);
            StackPane.setMargin(corner, new Insets(4, 8, 0, 0));
            root.getChildren().add(corner);
        }

        setGraphic(root);
        setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
    }

    public CardDisplayData getCardData() {
        return data;
    }

    private static void applyColorBar(Region bar, CardDisplayData d) {
        String k = d.getKind();
        if ("PROPERTY".equals(k) && d.getColorGroup() != null && !d.getColorGroup().isBlank()) {
            bar.getStyleClass().add("color-" + d.getColorGroup());
        } else if ("WILD".equals(k)) {
            bar.getStyleClass().add("color-WILD_BAR");
        } else if ("MONEY".equals(k)) {
            bar.getStyleClass().add("color-YELLOW");
        } else if ("ACTION".equals(k)) {
            bar.getStyleClass().add("color-DARK_BLUE");
        } else {
            bar.getStyleClass().add("color-UTILITY");
        }
    }

    private static String kindBadge(String kind) {
        if (kind == null) {
            return "";
        }
        return switch (kind) {
            case "MONEY" -> "现金";
            case "PROPERTY" -> "房产";
            case "WILD" -> "万能";
            case "ACTION" -> "行动";
            default -> kind;
        };
    }

    private static String cardMeta(CardDisplayData data) {
        String kind = data.getKind();
        if ("PROPERTY".equals(kind)) {
            String color = colorName(data.getColorGroup());
            if (data.getSetNeed() != null && data.getSetNeed() > 0) {
                return color + " · 套装需 " + data.getSetNeed() + " 张";
            }
            return color;
        }
        if ("WILD".equals(kind)) {
            return "可补齐多色地产";
        }
        if ("ACTION".equals(kind)) {
            return actionName(data.getEffectCode());
        }
        if ("MONEY".equals(kind)) {
            return "存入银行支付费用";
        }
        return "";
    }

    private static String colorName(String colorGroup) {
        if (colorGroup == null || colorGroup.isBlank()) {
            return "地产";
        }
        return switch (colorGroup) {
            case "BROWN" -> "棕色";
            case "LIGHT_BLUE" -> "浅蓝";
            case "PINK" -> "粉色";
            case "ORANGE" -> "橙色";
            case "RED" -> "红色";
            case "YELLOW" -> "黄色";
            case "GREEN" -> "绿色";
            case "DARK_BLUE" -> "深蓝";
            case "RAILROAD" -> "铁路";
            case "UTILITY" -> "公共事业";
            default -> colorGroup;
        };
    }

    private static String actionName(String effectCode) {
        if (effectCode == null || effectCode.isBlank()) {
            return "行动效果";
        }
        return switch (effectCode) {
            case "RENT" -> "向一名玩家收租";
            case "RENT_DUAL" -> "按颜色收租";
            case "DOUBLE_RENT" -> "租金翻倍";
            case "STEAL_PROPERTY" -> "偷取房产";
            case "FORCED_DEAL" -> "交换房产";
            case "DEBT_COLLECTOR" -> "催债 5M";
            case "RENT_WAIVER" -> "免租响应";
            case "PASS_GO" -> "额外摸牌";
            case "HOUSE" -> "房屋升级";
            case "HOTEL" -> "酒店升级";
            case "BIRTHDAY" -> "生日收礼";
            case "DEAL_BREAKER" -> "夺取整套";
            default -> "行动效果";
        };
    }
}
