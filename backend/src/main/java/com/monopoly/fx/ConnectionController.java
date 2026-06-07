package com.monopoly.fx;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.util.Duration;

import java.util.function.Consumer;

/** Owns WebSocket lifecycle and connection controls for the JavaFX client. */
final class ConnectionController {

    record Refs(
            TextField wsUrlField,
            Label statusLabel,
            Label connectionLabel,
            Button connectButton,
            Button disconnectButton) {
    }

    private final FxWebSocketClient ws;
    private final FxClientState state;
    private final Refs refs;
    private final FxLocalizer i18n;
    private final Consumer<String> appendTraffic;
    private final Consumer<String> handleInbound;
    private final Consumer<String> showError;
    private final Runnable clearError;
    private final Runnable switchToStartView;
    private final Runnable updateLobbyControls;
    private final Runnable clearPlayedEvents;

    private PauseTransition connectionTimeout;

    ConnectionController(
            FxWebSocketClient ws,
            FxClientState state,
            Refs refs,
            FxLocalizer i18n,
            Consumer<String> appendTraffic,
            Consumer<String> handleInbound,
            Consumer<String> showError,
            Runnable clearError,
            Runnable switchToStartView,
            Runnable updateLobbyControls,
            Runnable clearPlayedEvents) {
        this.ws = ws;
        this.state = state;
        this.refs = refs;
        this.i18n = i18n;
        this.appendTraffic = appendTraffic;
        this.handleInbound = handleInbound;
        this.showError = showError;
        this.clearError = clearError;
        this.switchToStartView = switchToStartView;
        this.updateLobbyControls = updateLobbyControls;
        this.clearPlayedEvents = clearPlayedEvents;
    }

    void connect(Runnable defaultAfterConnect) {
        clearError.run();
        refs.statusLabel().setText(i18n.get("status.connecting"));
        refs.connectionLabel().setText(i18n.get("status.connecting"));
        cancelConnectionTimeout();
        ws.connect(refs.wsUrlField().getText().trim(), new FxWebSocketClient.Listener() {
            @Override
            public void onOpen() {
                Platform.runLater(() -> {
                    cancelConnectionTimeout();
                    refs.statusLabel().setText(i18n.get("status.connected"));
                    refs.connectionLabel().setText(i18n.get("status.connected"));
                    appendTraffic.accept("« " + i18n.get("log.wsOpened") + " »");
                    refreshButtons();
                    if (state.postConnectAction != null) {
                        Runnable action = state.postConnectAction;
                        state.postConnectAction = null;
                        action.run();
                    } else {
                        defaultAfterConnect.run();
                    }
                });
            }

            @Override
            public void onMessage(String text) {
                Platform.runLater(() -> handleInbound.accept(text));
            }

            @Override
            public void onError(Throwable error) {
                Platform.runLater(() -> {
                    cancelConnectionTimeout();
                    state.postConnectAction = null;
                    refs.statusLabel().setText(i18n.get("status.connectFailed", error.getMessage()));
                    refs.connectionLabel().setText(i18n.get("status.disconnected"));
                    showError.accept(i18n.get("error.connectHint", refs.wsUrlField().getText().trim()));
                    appendTraffic.accept("« " + i18n.get("log.error") + " » " + error);
                    refreshButtons();
                });
            }

            @Override
            public void onClose(int code, String reason) {
                Platform.runLater(() -> {
                    cancelConnectionTimeout();
                    refs.statusLabel().setText(i18n.get("status.closed", code));
                    refs.connectionLabel().setText(i18n.get("status.disconnected"));
                    appendTraffic.accept("« " + i18n.get("log.closed", code, reason) + " »");
                    state.lastStatePayload = null;
                    clearPlayedEvents.run();
                    state.pendingOptionsResultHandler = null;
                    state.postConnectAction = null;
                    state.awaitingInitialState = false;
                    state.currentLobbyRoom = null;
                    switchToStartView.run();
                    refreshButtons();
                    updateLobbyControls.run();
                });
            }
        });
        connectionTimeout = new PauseTransition(Duration.seconds(8));
        connectionTimeout.setOnFinished(e -> {
            if (!ws.isConnected()) {
                state.postConnectAction = null;
                refs.statusLabel().setText(i18n.get("status.timeout"));
                refs.connectionLabel().setText(i18n.get("status.disconnected"));
                showError.accept(i18n.get("error.connectHint", refs.wsUrlField().getText().trim()));
                refreshButtons();
            }
        });
        connectionTimeout.play();
    }

    void disconnect(Runnable switchToStartView, Runnable updateLobbyControls) {
        ws.closeQuietly();
        refs.statusLabel().setText(i18n.get("status.disconnected"));
        refs.connectionLabel().setText(i18n.get("status.disconnected"));
        state.clearGameState();
        state.clearLobbyState();
        switchToStartView.run();
        refreshButtons();
        updateLobbyControls.run();
    }

    void shutdown() {
        cancelConnectionTimeout();
        ws.closeQuietly();
    }

    void refreshButtons() {
        boolean connected = ws.isConnected();
        refs.connectButton().setDisable(connected);
        refs.disconnectButton().setDisable(!connected);
    }

    private void cancelConnectionTimeout() {
        if (connectionTimeout != null) {
            connectionTimeout.stop();
            connectionTimeout = null;
        }
    }
}
