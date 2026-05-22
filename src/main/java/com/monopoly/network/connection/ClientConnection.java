package com.monopoly.network.connection;

import java.io.IOException;

/**
 * Transport abstraction; Tyrus Session adapter in MonopolyWebSocketEndpoint.
 */
public interface ClientConnection {

    boolean isOpen();

    void sendText(String text) throws IOException;
}
