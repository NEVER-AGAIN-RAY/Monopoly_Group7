package com.monopoly.fx;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.io.IOException;
import java.util.Objects;

/**
 * JavaFX/FXML entry point for the Monopoly Deal desktop client.
 */
public class MonopolyFxApp extends Application {

    @Override
    public void start(Stage stage) throws IOException {
        FXMLLoader loader = new FXMLLoader(Objects.requireNonNull(
                MonopolyFxApp.class.getResource("/com/monopoly/fx/MainView.fxml")));
        Parent root = loader.load();
        MainController controller = loader.getController();
        stage.setTitle("Monopoly Deal");
        Scene scene = new Scene(root, 1360, 880);
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
