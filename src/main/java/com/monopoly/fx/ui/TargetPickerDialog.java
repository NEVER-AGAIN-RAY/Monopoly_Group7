package com.monopoly.fx.ui;

import javafx.geometry.Insets;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ChoiceDialog;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.SelectionMode;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Window;

import java.util.List;
import java.util.Optional;

/**
 * 非技术用户向：从列表中选择对手、颜色等。
 */
public final class TargetPickerDialog {

    private TargetPickerDialog() {
    }

    public static Optional<String> pickPlayer(Window owner, String title, List<String> playerIds, String currentPlayerId) {
        List<String> choices = playerIds.stream()
                .filter(p -> currentPlayerId == null || !p.equals(currentPlayerId))
                .toList();
        if (choices.isEmpty()) {
            return Optional.empty();
        }
        ChoiceDialog<String> d = new ChoiceDialog<>(choices.get(0), choices);
        d.initOwner(owner);
        d.initModality(Modality.WINDOW_MODAL);
        d.setTitle(title);
        d.setHeaderText("选择一名玩家");
        return d.showAndWait();
    }

    public static Optional<String> pickColor(Window owner, List<String> colorKeys) {
        if (colorKeys.isEmpty()) {
            return Optional.empty();
        }
        ChoiceDialog<String> d = new ChoiceDialog<>(colorKeys.get(0), colorKeys);
        d.initOwner(owner);
        d.initModality(Modality.WINDOW_MODAL);
        d.setTitle("选择颜色");
        d.setHeaderText("选择房产颜色（与后端 colorKey 一致）");
        return d.showAndWait();
    }

    public static Optional<String> pickFromList(Window owner, String title, String header, List<String> items) {
        if (items.isEmpty()) {
            return Optional.empty();
        }
        Dialog<String> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.initModality(Modality.WINDOW_MODAL);
        dialog.setTitle(title);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        ListView<String> list = new ListView<>();
        list.getItems().setAll(items);
        list.getSelectionModel().setSelectionMode(SelectionMode.SINGLE);
        list.getSelectionModel().selectFirst();

        Label head = new Label(header);
        head.setWrapText(true);
        VBox box = new VBox(10, head, list);
        box.setPadding(new Insets(12));
        dialog.getDialogPane().setContent(box);

        dialog.setResultConverter(btn -> {
            if (btn == ButtonType.OK) {
                return list.getSelectionModel().getSelectedItem();
            }
            return null;
        });
        return dialog.showAndWait();
    }
}
