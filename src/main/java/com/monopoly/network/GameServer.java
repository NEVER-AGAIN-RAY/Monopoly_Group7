package com.monopoly.network;

import com.monopoly.controller.GameController;
import com.monopoly.model.dto.GameStateSnapshot;
import com.monopoly.pattern.observer.GameUpdateObserver;
import com.monopoly.pattern.observer.GameUpdateSubject;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket 服务端骨架：维护已连接客户端抽象，在状态更新时向所有连接广播 JSON。
 * 具体容器（Tyrus / Jetty / Netty）在 @ServerEndpoint 中把原生 Session 适配为 {@link ClientConnection}。
 */
public class GameServer implements GameUpdateObserver {

    private final MessageDispatcher dispatcher = new MessageDispatcher();
    private final Set<ClientConnection> clients = ConcurrentHashMap.newKeySet();

    private GameController gameController;

    public void wireController(GameController controller) {
        this.gameController = controller;
    }

    /** 新客户端连接时注册（由 WebSocket 端点回调调用） */
    public void onClientConnected(ClientConnection client) {
        clients.add(client);
    }

    public void onClientDisconnected(ClientConnection client) {
        clients.remove(client);
    }

    /** 收到客户端 JSON 文本：转交控制器（骨架） */
    public void onMessage(ClientConnection from, String json) {
        String type = dispatcher.extractMessageType(json);
        if (gameController != null && "PING".equals(type)) {
            // 占位
        }
    }

    @Override
    public void onGameStateChanged(GameStateSnapshot snapshot) {
        String payload = dispatcher.toJsonBroadcast(snapshot);
        for (ClientConnection client : clients) {
            if (client.isOpen()) {
                try {
                    client.sendText(payload);
                } catch (IOException e) {
                    clients.remove(client);
                }
            }
        }
    }

    /** 将本服务器注册为观察者，复用 GameUpdateSubject */
    public void attachTo(GameUpdateSubject subject) {
        subject.registerObserver(this);
    }
}
