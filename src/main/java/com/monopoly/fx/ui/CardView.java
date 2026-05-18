package com.monopoly.fx.ui;

import com.monopoly.fx.I18n;
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

        Label title = new Label(data.getTitle());
        title.getStyleClass().add("card-title");
        title.setMaxWidth(Double.MAX_VALUE);
        title.setAlignment(Pos.CENTER);

        Label hint = new Label(data.getHint());
        hint.getStyleClass().add("card-hint");
        hint.setMaxWidth(Double.MAX_VALUE);
        hint.setAlignment(Pos.CENTER);

        Label rentDetail = null;
        if (data.getRentDetail() != null && !data.getRentDetail().isBlank()) {
            rentDetail = new Label(data.getRentDetail());
            rentDetail.getStyleClass().add("card-rent-detail");
            rentDetail.setMaxWidth(Double.MAX_VALUE);
            rentDetail.setAlignment(Pos.CENTER);
        }

        Label badge = new Label(kindBadge(data.getKind()));
        badge.getStyleClass().add("card-kind-badge");
        badge.setMaxWidth(Double.MAX_VALUE);
        badge.setAlignment(Pos.CENTER);

        VBox center = rentDetail == null
                ? new VBox(6, title, hint)
                : new VBox(4, title, hint, rentDetail);
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
            case "MONEY" -> I18n.get("card.money");
            case "PROPERTY" -> I18n.get("card.property");
            case "WILD" -> I18n.get("card.wild");
            case "ACTION" -> I18n.get("card.action");
            default -> kind;
        };
    }
}
