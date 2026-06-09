package com.monopoly.fx;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
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
            MenuItem disconnectMenuItem) {
    }

    private static final int MAX_RECONNECT_ATTEMPTS = 3;
    private static final double[] RECONNECT_DELAYS = {2.0, 4.0, 8.0};

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
    private final Runnable afterReconnect;

    private PauseTransition connectionTimeout;
    private PauseTransition reconnectTimer;
    private int reconnectAttempts;
    private boolean intentionalDisconnect;

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
            Runnable clearPlayedEvents,
            Runnable afterReconnect) {
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
        this.afterReconnect = afterReconnect;
    }

    void connect(Runnable defaultAfterConnect) {
        intentionalDisconnect = false;
        clearError.run();
        refs.statusLabel().setText(i18n.get("status.connecting"));
        refs.connectionLabel().setText(i18n.get("status.connecting"));
        cancelConnectionTimeout();
        ws.connect(refs.wsUrlField().getText().trim(), new FxWebSocketClient.Listener() {
            @Override
            public void onOpen() {
                Platform.runLater(() -> {
                    cancelConnectionTimeout();
                    reconnectAttempts = 0;
                    refs.statusLabel().setText(i18n.get("status.connected"));
                    refs.connectionLabel().setText(i18n.get("status.connected"));
                    appendTraffic.accept("<< " + i18n.get("log.wsOpened") + " >>");
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
                    if (intentionalDisconnect) {
                        return;
                    }
                    if (reconnectAttempts < MAX_RECONNECT_ATTEMPTS && hadPreviousConnection()) {
                        scheduleReconnect();
                    } else {
                        refs.statusLabel().setText(i18n.get("status.connectFailed", error.getMessage()));
                        refs.connectionLabel().setText(i18n.get("status.disconnected"));
                        showError.accept(i18n.get("error.connectHint", refs.wsUrlField().getText().trim()));
                        appendTraffic.accept("<< " + i18n.get("log.error") + " >> " + error);
                        refreshButtons();
                    }
                });
            }

            @Override
            public void onClose(int code, String reason) {
                Platform.runLater(() -> {
                    cancelConnectionTimeout();
                    if (intentionalDisconnect) {
                        state.lastStatePayload = null;
                        clearPlayedEvents.run();
                        state.pendingOptionsResultHandler = null;
                        state.postConnectAction = null;
                        state.awaitingInitialState = false;
                        state.currentLobbyRoom = null;
                        switchToStartView.run();
                        refreshButtons();
                        updateLobbyControls.run();
                        return;
                    }
                    if (reconnectAttempts < MAX_RECONNECT_ATTEMPTS && hadPreviousConnection()) {
                        refs.statusLabel().setText(i18n.get("status.reconnecting"));
                        refs.connectionLabel().setText(i18n.get("status.reconnecting"));
                        appendTraffic.accept("<< " + i18n.get("log.closed", code, reason) + " >>");
                        scheduleReconnect();
                    } else {
                        refs.statusLabel().setText(i18n.get("status.closed", code));
                        refs.connectionLabel().setText(i18n.get("status.disconnected"));
                        appendTraffic.accept("<< " + i18n.get("log.closed", code, reason) + " >>");
                        state.lastStatePayload = null;
                        clearPlayedEvents.run();
                        state.pendingOptionsResultHandler = null;
                        state.postConnectAction = null;
                        state.awaitingInitialState = false;
                        state.currentLobbyRoom = null;
                        switchToStartView.run();
                        refreshButtons();
                        updateLobbyControls.run();
                    }
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

    private boolean hadPreviousConnection() {
        return state.lastStatePayload != null || state.currentLobbyRoom != null;
    }

    private void scheduleReconnect() {
        double delay = RECONNECT_DELAYS[Math.min(reconnectAttempts, RECONNECT_DELAYS.length - 1)];
        reconnectAttempts++;
        String url = refs.wsUrlField().getText().trim();
        if (url.isEmpty()) {
            url = "ws://localhost:8025/ws";
        }
        appendTraffic.accept("<< Reconnecting (" + reconnectAttempts + "/" + MAX_RECONNECT_ATTEMPTS + ")... >>");
        reconnectTimer = new PauseTransition(Duration.seconds(delay));
        final int attempt = reconnectAttempts;
        reconnectTimer.setOnFinished(e -> {
            if (reconnectAttempts != attempt) {
                return;
            }
            if (!ws.isConnected() && !intentionalDisconnect) {
                connect(this::onReconnectedRefresh);
            }
        });
        reconnectTimer.play();
    }

    private void onReconnectedRefresh() {
        appendTraffic.accept("<< " + i18n.get("log.wsOpened") + " >>");
        if (afterReconnect != null) {
            afterReconnect.run();
        }
    }

    void disconnect(Runnable switchToStartView, Runnable updateLobbyControls) {
        intentionalDisconnect = true;
        cancelReconnectTimer();
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
        intentionalDisconnect = true;
        cancelConnectionTimeout();
        cancelReconnectTimer();
        ws.closeQuietly();
    }

    void refreshButtons() {
        boolean connected = ws.isConnected();
        refs.connectButton().setDisable(connected);
        refs.disconnectMenuItem().setDisable(!connected);
    }

    private void cancelConnectionTimeout() {
        if (connectionTimeout != null) {
            connectionTimeout.stop();
            connectionTimeout = null;
        }
    }

    private void cancelReconnectTimer() {
        if (reconnectTimer != null) {
            reconnectTimer.stop();
            reconnectTimer = null;
        }
        reconnectAttempts = MAX_RECONNECT_ATTEMPTS + 1;
    }
}
