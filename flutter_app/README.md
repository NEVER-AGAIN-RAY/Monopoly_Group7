# flutter_app

最小 Flutter WebSocket 客户端（控制台级 UI）。

## 功能

- 连接本地后端：`ws://localhost:8025/ws`
- 发送：`AUTH`、`START_SESSION`、`DRAW`、`PLAY(DEP0)`、`END_TURN`
- 展示：
  - `STATE_UPDATE`（公共状态 JSON）
  - `MY_HAND`（手牌独立页面）
  - 原始日志

## 运行步骤

1. 启动 Java WsServer（仓库根目录）：
   - `mvn -q exec:java`
2. 启动 Flutter App（本目录）：
   - `flutter pub get`
   - `flutter run`

## 快速联调

1. 点击 `Connect`
2. 点击 `AUTH`（默认 `pvp-1`）
3. 点击 `START_SESSION`
4. 依次点击 `DRAW` / `PLAY(DEP0)` / `END_TURN`
5. 在 `STATE_UPDATE` 与 `MY_HAND` 页查看变化
