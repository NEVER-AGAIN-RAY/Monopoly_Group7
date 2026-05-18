package com.monopoly.fx;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 基于 JDK {@link HttpClient} 的最小 WebSocket 客户端，回调在 HttpClient 工作线程上触发；
 * UI 层需自行切到 JavaFX 线程。
 */
public final class FxWebSocketClient {

    public interface Listener {
        void onOpen();

        void onMessage(String text);

        void onError(Throwable error);

        void onClose(int code, String reason);
    }

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    private final AtomicReference<WebSocket> socketRef = new AtomicReference<>();

    public boolean isConnected() {
        return socketRef.get() != null;
    }

    /**
     * 异步连接；已连接时会先静默关闭旧连接再重连。
     */
    public void connect(String url, Listener listener) {
        closeQuietly();
        StringBuilder fragment = new StringBuilder();
        httpClient.newWebSocketBuilder()
                .buildAsync(URI.create(url), new WebSocket.Listener() {
                    @Override
                    public void onOpen(WebSocket webSocket) {
                        socketRef.set(webSocket);
                        listener.onOpen();
                        webSocket.request(1);
                    }

                    @Override
                    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                        fragment.append(data);
                        if (last) {
                            String full = fragment.toString();
                            fragment.setLength(0);
                            listener.onMessage(full);
                        }
                        webSocket.request(1);
                        return null;
                    }

                    @Override
                    public void onError(WebSocket webSocket, Throwable error) {
                        socketRef.compareAndSet(webSocket, null);
                        listener.onError(error);
                    }

                    @Override
                    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                        socketRef.compareAndSet(webSocket, null);
                        listener.onClose(statusCode, reason == null ? "" : reason);
                        return null;
                    }
                })
                .whenComplete((ws, ex) -> {
                    if (ex != null) {
                        listener.onError(ex);
                    }
                });
    }

    public void sendRaw(String json) {
        WebSocket ws = socketRef.get();
        if (ws == null) {
            throw new IllegalStateException("WebSocket not connected");
        }
        ws.sendText(json, true);
    }

    public void closeQuietly() {
        WebSocket ws = socketRef.getAndSet(null);
        if (ws != null) {
            try {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "client close")
                        .exceptionally(ex -> null);
            } catch (Exception ignored) {
                // ignore
            }
        }
    }
}
