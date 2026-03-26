package com.monopoly.network;

import java.io.IOException;

/**
 * WebSocket 会话抽象：实际部署时用 {@code jakarta.websocket.Session} 编写适配器实现本接口即可。
 */
public interface ClientConnection {

    boolean isOpen();

    void sendText(String text) throws IOException;
}
