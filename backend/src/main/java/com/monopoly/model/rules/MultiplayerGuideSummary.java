package com.monopoly.model.rules;

/**
 * Short classroom demo guide for the fixed local multiplayer room.
 */
public final class MultiplayerGuideSummary {

    private MultiplayerGuideSummary() {
    }

    public static String buildHtmlChinese() {
        return """
                <!DOCTYPE html><html lang="zh"><head><meta charset="UTF-8">
                <style>
                body{font-family:-apple-system,'Segoe UI','Microsoft YaHei',sans-serif;background:#f4f7f2;color:#253126;line-height:1.7;padding:32px}
                .hero,.section{background:white;border-radius:12px;padding:24px 28px;margin-bottom:18px;box-shadow:0 2px 12px rgba(0,0,0,.06)}
                .hero{background:#245338;color:#fff}.hero h1{font-size:28px;margin-bottom:6px}
                h2{color:#245338;font-size:18px}.cmd{background:#1f2923;color:#b8f2c6;padding:10px 14px;border-radius:8px;font-family:Menlo,Consolas,monospace}
                code{background:#edf1e8;color:#b3261e;padding:2px 6px;border-radius:4px}.note{background:#fff7df;border:1px solid #ead9a6;border-radius:8px;padding:10px 14px}
                </style></head><body>
                <div class="hero"><h1>固定联机演示房间</h1><p>所有 JavaFX 客户端进入同一个 demo-pvp，2 到 5 人稳定演示。</p></div>
                <div class="section"><h2>1. 启动后端</h2><p>在项目根目录运行：</p><div class="cmd">mvn -q compile exec:java</div><p>看到 <code>ws://localhost:8025/ws</code> 后保持终端运行。</p></div>
                <div class="section"><h2>2. 打开客户端</h2><p>每名真人玩家打开一个 JavaFX 窗口：</p><div class="cmd">mvn javafx:run</div><p>本机演示用 <code>ws://localhost:8025/ws</code>；局域网演示把 localhost 换成主机 IP。</p></div>
                <div class="section"><h2>3. 加入 demo-pvp</h2><p>每个窗口点击“连接”，再点击“加入联机演示”。服务器会按进入顺序分配 <code>human-1</code> 到 <code>human-5</code>。</p></div>
                <div class="section"><h2>4. 开始游戏</h2><p>人数达到 2 后，任意已加入玩家都可以点击“开始联机”。开局后锁定当前人数，不再接纳新玩家。</p><div class="note">这是固定本地 WebSocket 演示房间，流程稳定，课堂上容易解释。</div></div>
                <div class="section"><h2>排查</h2><ul><li>连接失败：确认后端仍在运行，端口 8025 未被旧进程占用。</li><li>局域网连不上：确认同一 Wi-Fi，主机防火墙放行 TCP 8025。</li><li>想重新演示：重启后端，然后重新打开客户端加入。</li></ul></div>
                </body></html>
                """;
    }

    public static String buildHtmlEnglish() {
        return """
                <!DOCTYPE html><html lang="en"><head><meta charset="UTF-8">
                <style>
                body{font-family:-apple-system,'Segoe UI',sans-serif;background:#f4f7f2;color:#253126;line-height:1.7;padding:32px}
                .hero,.section{background:white;border-radius:12px;padding:24px 28px;margin-bottom:18px;box-shadow:0 2px 12px rgba(0,0,0,.06)}
                .hero{background:#245338;color:#fff}.hero h1{font-size:28px;margin-bottom:6px}
                h2{color:#245338;font-size:18px}.cmd{background:#1f2923;color:#b8f2c6;padding:10px 14px;border-radius:8px;font-family:Menlo,Consolas,monospace}
                code{background:#edf1e8;color:#b3261e;padding:2px 6px;border-radius:4px}.note{background:#fff7df;border:1px solid #ead9a6;border-radius:8px;padding:10px 14px}
                </style></head><body>
                <div class="hero"><h1>Fixed Multiplayer Demo Room</h1><p>Every JavaFX client joins demo-pvp for a stable 2 to 5 player classroom demo.</p></div>
                <div class="section"><h2>1. Start Backend</h2><p>Run this from the project root:</p><div class="cmd">mvn -q compile exec:java</div><p>Keep the terminal open after it prints <code>ws://localhost:8025/ws</code>.</p></div>
                <div class="section"><h2>2. Open Clients</h2><p>Open one JavaFX window per human player:</p><div class="cmd">mvn javafx:run</div><p>For local demos use <code>ws://localhost:8025/ws</code>; for LAN demos replace localhost with the host IP.</p></div>
                <div class="section"><h2>3. Join demo-pvp</h2><p>Each window clicks Connect, then Join Multiplayer Demo. The server assigns <code>human-1</code> through <code>human-5</code> in join order.</p></div>
                <div class="section"><h2>4. Start</h2><p>Once at least 2 players have joined, any joined player may click Start Multiplayer. The player count is locked after start.</p><div class="note">This fixed local WebSocket demo room is stable and easy to explain in class.</div></div>
                <div class="section"><h2>Troubleshooting</h2><ul><li>Cannot connect: make sure the backend is still running and port 8025 is free.</li><li>LAN clients cannot connect: use the same Wi-Fi and allow TCP 8025 through the host firewall.</li><li>Need a fresh demo: restart the backend, then rejoin from each client.</li></ul></div>
                </body></html>
                """;
    }

    public static String buildPlainTextChinese() {
        return "固定联机演示房间：所有客户端加入 demo-pvp。\n"
                + "1. 后端：mvn -q compile exec:java。\n"
                + "2. 每名玩家打开一个 JavaFX：mvn javafx:run。\n"
                + "3. 点击连接，再点击加入联机演示，后端自动分配 human-1 到 human-5。\n"
                + "4. 至少 2 人后，任意已加入玩家点击开始联机。\n"
                + "开局后锁定人数；重新演示请重启后端。\n";
    }

    public static String buildPlainTextEnglish() {
        return "Fixed multiplayer demo room: every client joins demo-pvp.\n"
                + "1. Backend: mvn -q compile exec:java.\n"
                + "2. Open one JavaFX window per player: mvn javafx:run.\n"
                + "3. Click Connect, then Join Multiplayer Demo; the server assigns human-1 through human-5.\n"
                + "4. With at least 2 players, any joined player may click Start Multiplayer.\n"
                + "Player count is locked after start; restart the backend for a fresh demo.\n";
    }
}
