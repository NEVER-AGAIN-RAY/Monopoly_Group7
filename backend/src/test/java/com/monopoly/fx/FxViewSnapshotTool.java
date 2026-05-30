package com.monopoly.fx;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.animation.PauseTransition;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;
import javafx.util.Duration;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Local visual QA helper for the JavaFX view. Run directly with a JavaFX
 * classpath to render MainView.fxml into a PNG without touching app behavior.
 */
public final class FxViewSnapshotTool extends Application {

    @Override
    public void start(Stage stage) throws Exception {
        FXMLLoader loader = new FXMLLoader(Objects.requireNonNull(
                MonopolyFxApp.class.getResource("/com/monopoly/fx/MainView.fxml")));
        Parent root = loader.load();
        MainController controller = loader.getController();
        Scene scene = new Scene(root, 1360, 880);
        scene.getStylesheets().add(Objects.requireNonNull(
                MonopolyFxApp.class.getResource("/com/monopoly/fx/styles.css")).toExternalForm());
        stage.setScene(scene);
        stage.show();

        Platform.runLater(() -> {
            try {
                injectEnvelope(controller, System.getProperty("fx.state"));
                injectEnvelope(controller, System.getProperty("fx.hand"));
                PauseTransition delay = new PauseTransition(Duration.millis(500));
                delay.setOnFinished(event -> {
                    try {
                        root.applyCss();
                        root.layout();
                        WritableImage image = root.snapshot(null, null);
                        Path out = Path.of(System.getProperty("fx.snapshot", "/tmp/monopoly-fx-start.png"));
                        ImageIO.write(toBufferedImage(image), "png", out.toFile());
                    } catch (Exception ex) {
                        ex.printStackTrace();
                    } finally {
                        Platform.exit();
                    }
                });
                delay.play();
            } catch (Exception ex) {
                ex.printStackTrace();
                Platform.exit();
            }
        });
    }

    public static void main(String[] args) {
        launch(args);
    }

    private static BufferedImage toBufferedImage(WritableImage image) {
        int width = (int) image.getWidth();
        int height = (int) image.getHeight();
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                out.setRGB(x, y, image.getPixelReader().getArgb(x, y));
            }
        }
        return out;
    }

    private static void injectEnvelope(MainController controller, String file) throws Exception {
        if (file == null || file.isBlank()) {
            return;
        }
        Method method = MainController.class.getDeclaredMethod("handleInbound", String.class);
        method.setAccessible(true);
        method.invoke(controller, Files.readString(Path.of(file)));
    }
}
