package com.monopoly.fx;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Sends protocol envelopes and defers commands until the socket is connected. */
final class ClientCommandGateway {

    private final FxWebSocketClient ws;
    private final FxClientState state;
    private final Supplier<String> sessionId;
    private final Runnable connectAction;
    private final Consumer<String> showError;
    private final Consumer<String> appendTraffic;
    private final FxLocalizer i18n;

    ClientCommandGateway(
            FxWebSocketClient ws,
            FxClientState state,
            Supplier<String> sessionId,
            Runnable connectAction,
            Consumer<String> showError,
            Consumer<String> appendTraffic,
            FxLocalizer i18n) {
        this.ws = ws;
        this.state = state;
        this.sessionId = sessionId;
        this.connectAction = connectAction;
        this.showError = showError;
        this.appendTraffic = appendTraffic;
        this.i18n = i18n;
    }

    void runWhenConnected(Runnable action) {
        if (ws.isConnected()) {
            action.run();
            return;
        }
        state.postConnectAction = action;
        connectAction.run();
    }

    void sendEnvelope(String type, Map<String, Object> payload) {
        try {
            Map<String, Object> scoped = new LinkedHashMap<>();
            String sid = sessionId.get();
            if (sid != null && !sid.isBlank()) {
                scoped.put("sessionId", sid);
            }
            if (payload != null) {
                scoped.putAll(payload);
            }
            ws.sendRaw(WsJson.envelope(type, scoped));
            appendTraffic.accept("← " + type);
        } catch (Exception ex) {
            showError.accept(i18n.get("log.sendFailed", ex.getMessage()));
            appendTraffic.accept("« " + i18n.get("log.sendFailed", ex.getMessage()) + " »");
        }
    }

    Map<String, Object> scopedPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sessionId", sessionId.get());
        return payload;
    }
}
