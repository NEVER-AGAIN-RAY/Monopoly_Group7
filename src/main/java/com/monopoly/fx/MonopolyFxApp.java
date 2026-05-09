package com.monopoly.fx;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.io.IOException;
import java.util.Objects;

/**
 * Monopoly Deal 桌面客户端入口（JavaFX + FXML）。
 */
public class MonopolyFxApp extends Application {

    @Override
    public void start(Stage stage) throws IOException {
        FXMLLoader loader = new FXMLLoader(Objects.requireNonNull(
                MonopolyFxApp.class.getResource("/com/monopoly/fx/MainView.fxml")));
        Parent root = loader.load();
        MainController controller = loader.getController();
        stage.setTitle("Monopoly Deal — JavaFX 客户端");
        Scene scene = new Scene(root, 1280, 860);
        scene.getStylesheets().add(
                MonopolyFxApp.class.getResource("/com/monopoly/fx/styles.css").toExternalForm());
        stage.setScene(scene);
        stage.setMinWidth(1180);
        stage.setMinHeight(780);
        stage.setOnCloseRequest(e -> {
            if (controller != null) {
                controller.shutdown();
            }
        });
        stage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
