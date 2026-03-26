package com.monopoly;

import com.monopoly.controller.GameController;
import com.monopoly.network.GameServer;
import com.monopoly.pattern.observer.DefaultGameUpdateSubject;

/**
 * 组合根（骨架）：演示 DefaultGameUpdateSubject → GameController（Facade）→ 状态通知 → GameServer（Observer 实现）广播。
 * 实际启动 WebSocket 容器时在此类中完成初始化即可。
 */
public final class ServerBootstrap {

    private ServerBootstrap() {
    }

    public static void main(String[] args) {
        DefaultGameUpdateSubject subject = new DefaultGameUpdateSubject();
        GameController controller = new GameController(subject);
        GameServer server = new GameServer();
        server.attachTo(subject);
        server.wireController(controller);

        controller.startNewSession("demo-session");
    }
}
