# 项目结构与模块职责

本文档描述 Monopoly Deal 全栈仓库的**目录树**与各模块**职责边界**，重点对齐 Java 后端近期完成的 **Facade + 内聚服务** 与 **领域模型分包** 重构。

---

## 1. 核心目录树（Tree）

以下为仓库**核心**结构：`src`（Java）、`flutter_app/lib`（Dart 源码）、`docs`（文档）。平台工程目录（`flutter_app/android`、`ios`、`windows` 等）为 Flutter 标准脚手架，此处不逐层展开。

```text
Monopoly_Group7/
├── pom.xml                          # Maven：Java 17、JUnit、Gson、Jakarta WebSocket / Tyrus 等依赖
├── README.md
├── docs/
│   ├── architecture/
│   │   ├── project-structure.md     # 本文件
│   │   ├── uml_source.md            # PlantUML 源码与图说明
│   │   └── images/                  # 导出的 UML 图片（class / sequence / use-case）
│   ├── implementation/
│   │   └── requirement-trace-and-deviations.md
│   ├── interface/
│   │   └── websocket-protocol.md
│   └── requirements/
│       └── requirements.md
├── flutter_app/
│   ├── pubspec.yaml
│   └── lib/
│       ├── main.dart
│       └── app/
│           ├── app.dart
│           ├── core/
│           │   └── network/
│           │       ├── ws_client.dart
│           │       ├── ws_envelope.dart
│           │       ├── ws_message_codec.dart
│           │       ├── ws_traffic_message.dart
│           │       └── models/
│           │           ├── play_action_request.dart
│           │           ├── play_action_request_builder.dart
│           │           └── start_session_request.dart
│           └── feature/
│               ├── debug/
│               │   ├── debug_page.dart
│               │   └── debug_console_page.dart
│               ├── game/
│               │   ├── game_page.dart
│               │   ├── game_view_model.dart
│               │   ├── game_state.dart
│               │   └── action_toolbar.dart
│               └── lobby/
│                   ├── lobby_page.dart
│                   └── lobby_view_model.dart
└── src/
    ├── main/java/com/monopoly/
    │   ├── ServerBootstrap.java
    │   ├── controller/
    │   ├── dto/
    │   ├── model/
    │   │   ├── card/
    │   │   ├── core/
    │   │   ├── effects/
    │   │   ├── player/
    │   │   └── settlement/
    │   ├── network/
    │   │   ├── GameServer.java
    │   │   ├── connection/
    │   │   ├── endpoint/
    │   │   └── protocol/
    │   ├── pattern/
    │   │   ├── factory/
    │   │   ├── observer/
    │   │   ├── singleton/
    │   │   └── strategy/
    │   └── persistence/
    └── test/java/com/monopoly/
        ├── controller/
        ├── model/
        ├── network/
        ├── pattern/
        ├── performance/
        └── persistence/
```

> **说明**：根目录下的 `target/` 为 Maven 编译输出，版本控制中通常忽略；不在上表中列为“源码核心”。

---

## 2. Java 后端：`src/main/java/com/monopoly`

后端采用 **分层 + 领域分包**：网络与协议在 `network`，领域规则在 `model`，对外 API 组装在 `controller`（Facade），跨层载荷在 `dto`，存档在 `persistence`，可复用设计模式在 `pattern`。

### 2.1 根包 `com.monopoly`

| 文件 | 职责 |
|------|------|
| `ServerBootstrap.java` | 演示/骨架入口：构造 `DefaultGameUpdateSubject`、`GameController`，将 `GameServer` 注册为观察者并 `wireController`；可扩展为与 Tyrus `WsServerMain` 一致的启动路径。 |

### 2.2 `controller/` — Facade 与内聚服务（重构核心）

**定位**：`GameController` 是 **外观（Facade）**：向 Flutter / 测试暴露稳定 API，**不承载**长流程实现；具体逻辑下沉到 **单一职责服务类**，便于测试与演进。

| 类型 | 职责 |
|------|------|
| **`GameController`** | 实现 `AiGameBridge`；持有 `GameEngineSingleton`、`TurnManager`、`CardFactory`、`GameUpdateSubject`、`GameContext`、会话玩家列表；将摸牌/出牌/弃牌/行动卡/结束回合委托给 `TurnFlowService`；将租金委托给 `RentSettlementService`；将暂停委托给 `PauseVoteService`；将存档委托给 `SaveLoadService`；将效果栈与响应窗口委托给 `EffectStackOrchestrator`；将 AI 回合委托给 `AiTurnService`；统一 `pushSnapshot` → `notifyStateChanged`。 |
| **`TurnManager`** | 回合顺序：绑定 `List<Player>`、`advanceTurn()`、`getCurrentPlayer()`。 |
| **`TurnFlowService`** | **回合流程核心**：维护 `currentTurnPlayerId`、`TurnPhase`（DRAW / PLAY / WAITING_FOR_RESPONSE / END_TURN）、`currentTurnActionCount`；实现摸牌、DEPOSIT/DEPLOY/ACTION 出牌、弃牌、万能房产改色、结束回合、行动卡解析与 `playActionCard`（收租入栈走 `EffectStackOrchestrator`，其余走 `ActionEffectDispatcher`）；胜负条件（≥3 套房产）。 |
| **`EffectStackOrchestrator`** | **效果栈编排**：收租/双倍收租后的响应窗口、`StackResponseState`、15 秒超时定时器、免租（Just Say No）与房东反制链；结算时调用 `EffectStackResolver` + `PaymentSettlement`。 |
| **`AiTurnService`** | **AI 回合**：`drawCards` → 循环 `AiPlayStrategy.tryPlayOneCard` → 通过 `AiGameBridge.submitPlayAction` 复用人类同一校验链 → `endTurn`。 |
| **`RentSettlementService`** | **租金结算**：`requestRentPayment`、`collectRentForColor`、`computeRentDueForColor`（委托领域计算器与 `PaymentSettlement`）。 |
| **`PauseVoteService`** | **暂停 / PVP 投票**：`requestPause`、`acknowledgePause`、`resume`、`ensureNotPaused`。 |
| **`SaveLoadService`** | **存档/读档/自动保存**：`GameSessionMemento.capture` / `applyToController`、`SaveEncryption`、可选每三轮自动写盘（受 JVM 属性控制）。 |
| **`ProtocolErrors`** | 协议校验错误码常量；内部类 **`ProtocolValidationException`**（含 `code`）供 `GameController.validatePlayActionRequest` 与 `GameServer` 错误回包使用。 |

### 2.3 `model/` — 领域模型（按子域拆分）

**原则**：**纯领域** — 不依赖 WebSocket、不序列化 JSON（DTO 在 `dto`，存档镜像在 `persistence`）。

#### 2.3.1 `model.card/` — 卡牌体系

| 类型 | 职责 |
|------|------|
| `Playable` | 可打出校验：`canPlay(Player, ActionParamContext, GameContext)`。 |
| `Payable` | 可支付面值：`getPaymentValue()`。 |
| `Card` | 抽象基类；实现 `Playable`。 |
| `PropertyCard` | 房产；`BuildingLevel`；`Payable`。 |
| `PropertyWildCard` | 万能房产；可分配 `assignedColorKey`。 |
| `ActionCard` | 行动卡；`effectCode`；复杂 `canPlay` 与 `PropertySetCalculator` / `StealTargetZone` 联动。 |
| `MoneyCard` | 钱币卡；面值 `valueM`；`Payable`。 |
| `BuildingLevel` | 枚举：BASE / HOUSE / HOTEL。 |
| `PayableCards` | 工具：从 `Card` 解析支付价值。 |

#### 2.3.2 `model.player/` — 玩家实体

| 类型 | 职责 |
|------|------|
| `Player` | 抽象玩家；分区：`handCards`、`propertyCards`、`bankCards`、`actionZoneCards`；收牌、弃牌、存银行、部署房产、行动区、套数统计等。 |
| `HumanPlayer` | 人类玩家占位。 |
| `AIPlayer` | AI；组合 `AiPlayStrategy`。 |

#### 2.3.3 `model.core/` — 对局上下文与常量

| 类型 | 职责 |
|------|------|
| `GameContext` | 绑定玩家列表、效果栈 `List<EffectStackEntry>`、`StackResponseState`。 |
| `GameConstants` | 牌堆规模、会话时长 JVM 属性名、自动保存属性等。 |
| `AiGameBridge` | AI 与人类共用出牌入口：`submitPlayAction(PlayActionRequest)`。 |

#### 2.3.4 `model.settlement/` — 资产与租金清算

| 类型 | 职责 |
|------|------|
| `PropertySetCalculator` | 各色所需张数、是否成套、总套数。 |
| `RentCalculator` | 按颜色/房产计算应收租金。 |
| `PaymentSettlement` | 玩家间支付结算（与 `GameEngineSingleton` 弃牌等协作）。 |
| `PropertyZoneSummary` | 财产区按色汇总 → 供快照 DTO（如 `PropertyColorCount`）。 |
| `StealTargetZone` | 偷牌目标区：PROPERTY / BANK。 |

#### 2.3.5 `model.effects/` — 行动牌效果机制

| 类型 | 职责 |
|------|------|
| `ActionEffect` | 策略接口：`execute(ActionEffectContext)` → `ActionEffectResult`。 |
| `ActionEffectContext` | Builder 构建的执行上下文（actor、target、engine、颜色、目标房产/银行卡等）。 |
| `ActionEffectResult` | SUCCESS / FAILED / COUNTERED。 |
| `ActionEffectDispatcher` | `effectCode` → 具体 `ActionEffect` 注册表。 |
| `EffectStackEntry` / `Kind` | 栈条目：RENT、DOUBLE_RENT、WAIVER 及工厂方法。 |
| `EffectStackResolver` | 解析栈并驱动租金支付结果。 |
| `StackResponseState` / `Role` | 响应窗口：TENANT / LANDLORD_COUNTER。 |
| `RentEffect`、`DoubleRentEffect`、`StealCardEffect`、`ForcedDealEffect`、`DebtCollectorEffect`、`RentWaiverEffect`、`PassGoEffect`、`HouseEffect`、`HotelEffect`、`BirthdayEffect`、`DealBreakerEffect` | 各行动卡效果实现。 |

### 2.4 `dto/` — 数据传输对象（线协议 / 快照）

与 **JSON 字段形状**、**广播快照** 对齐，避免领域类直接耦合 Gson/WebSocket。

| 类型 | 职责 |
|------|------|
| `PlayActionRequest` | 出牌/行动请求：cardId、handIndex、actionType、目标玩家/颜色/卡牌、actingPlayerId 等。 |
| `ActionParamContext` | 从 `PlayActionRequest` 派生的只读参数，供 `Card.canPlay` 与效果上下文。 |
| `StartSessionRequest` | 开局：sessionId、人数、模式 HVM/PVP、AI 难度、是否随机先手。 |
| `GameStateSnapshot` | 推送给客户端的全局状态；内含 `PlayerPublicSummary` 等。 |
| `PropertyColorCount` | 某颜色房产张数（快照用）。 |

### 2.5 `persistence/` — 持久化与存档加密

| 类型 | 职责 |
|------|------|
| `GameSessionMemento` | **Memento**：捕获/恢复整局（引擎牌堆、玩家分区、回合、效果栈、响应状态等）；`toJson` / `fromJson`；`applyToController`。 |
| `PersistedCard` | 卡牌 DTO ↔ 领域 `Card` 互转。 |
| `SessionPlayerMemento` | 玩家快照（含 `PlayerKind`、各分区牌列表）。 |
| `EffectStackEntryMemento`、`StackResponseStateMemento` | 效果栈与响应状态序列化镜像。 |
| `SaveEncryption` | 存储层编解码（可选密钥由 `GameConstants` 属性指定）。 |

### 2.6 `network/` — 网络接入与会话管理

| 路径 | 职责 |
|------|------|
| **`GameServer.java`** | WebSocket 业务枢纽：实现 `GameUpdateObserver`；路由消息类型到 `GameController`；广播快照；`SAVE_GAME` / `LOAD_GAME` 投票；人类玩家 `MY_HAND` 私推。 |
| **`connection/ClientConnection`** | 连接抽象：`sendText`、`isOpen`。 |
| **`connection/SessionRegistry`** | `ClientConnection` ↔ `playerId` 双向索引。 |
| **`endpoint/MonopolyWebSocketEndpoint`** | Jakarta `@ServerEndpoint`：适配 `Session` → `ClientConnection`。 |
| **`endpoint/WsServerMain`** | Tyrus `Server` 启动入口。 |
| **`protocol/MessageDispatcher`** | Gson 解析/构造：消息类型、`PlayActionRequest`、`StartSessionRequest`、广播信封、错误信封等。 |

### 2.7 `pattern/` — 设计模式集中体现

| 子包 | 模式与类型 | 职责 |
|------|------------|------|
| `factory/` | Factory Method | `CardFactory`、`MonopolyDealCardFactory`：生成标准 108 张牌。 |
| `observer/` | Observer | `GameUpdateSubject`、`GameUpdateObserver`、`DefaultGameUpdateSubject`。 |
| `singleton/` | Singleton | `GameEngineSingleton`：抽牌堆、弃牌堆、摸牌/洗牌/全场牌数统计。 |
| `strategy/` | Strategy | `AiPlayStrategy` 及 Easy/Normal/Hard 实现；`AiStrategyProfile`、`AiHeuristics`（AI 决策管线）。 |

### 2.8 测试代码：`src/test/java/com/monopoly`

| 目录 | 侧重点 |
|------|--------|
| `controller/` | 暂停恢复、存档读档、会话超时、PVP 暂停投票、自动保存、快照摘要等。 |
| `model/`、`model/effects/` | 领域规则与单效果单元测试。 |
| `network/` | 协议校验、PVP 存读档投票、错误码契约、集成测试。 |
| `persistence/` | Memento 与加密。 |
| `pattern/strategy/` | AI 策略差异化。 |
| `performance/` | 性能烟测。 |

---

## 3. Flutter 客户端：`flutter_app/lib`

客户端按 **core（基建）** 与 **feature（功能界面）** 划分，与后端 `dto` + `interface` 文档对齐。

### 3.1 `lib/app/core/network/` — 网络基建与协议模型

| 文件/目录 | 职责 |
|-----------|------|
| `ws_client.dart` | WebSocket 连接、发送/接收、与 UI 层协作的客户端封装。 |
| `ws_envelope.dart`、`ws_message_codec.dart` | 与后端 JSON 信封、编解码约定一致。 |
| `ws_traffic_message.dart` | 调试用或通用流量表示（如日志/控制台）。 |
| `models/play_action_request.dart`、`play_action_request_builder.dart` | 对应后端 `PlayActionRequest` 字段的 Dart 模型与构建器。 |
| `models/start_session_request.dart` | 对应 `StartSessionRequest`。 |

### 3.2 `lib/app/feature/` — UI 功能模块

| 目录 | 职责 |
|------|------|
| **`lobby/`** | `lobby_page.dart`、`lobby_view_model.dart`：连接、会话参数、进入对局前交互。 |
| **`game/`** | `game_page.dart`、`game_view_model.dart`、`game_state.dart`、`action_toolbar.dart`：对局中 UI、状态订阅、出牌工具条。 |
| **`debug/`** | `debug_page.dart`、`debug_console_page.dart`：开发调试页（如流量/日志）。 |

### 3.3 入口

| 文件 | 职责 |
|------|------|
| `lib/main.dart` | `runApp(MonopolyApp)`。 |
| `lib/app/app.dart` | `MaterialApp`、主导航壳（Lobby / Game / Debug 等 Tab 切换）、注入共享 `WsClient` 与各 `ViewModel`。 |

> **平台目录**：`android/`、`ios/`、`macos/`、`web/`、`windows/`、`linux/` 为 Flutter 多平台模板，一般无需在架构文档中逐项说明。

---

## 4. 文档：`docs/`

| 目录 | 内容概要 |
|------|----------|
| **`docs/architecture/`** | 架构说明：本文件 `project-structure.md`、`uml_source.md`（Class / Sequence / Use Case 的 PlantUML 与配图路径）、`images/` 下导出图。 |
| **`docs/implementation/`** | 实现层追溯：如需求覆盖与偏差说明（`requirement-trace-and-deviations.md`）。 |
| **`docs/interface/`** | 对外接口契约：WebSocket 消息类型与载荷约定（`websocket-protocol.md`）。 |
| **`docs/requirements/`** | 需求基线：功能与非功能需求（`requirements.md`）。 |

---

## 5. 小结

- **Java**：`GameController` 保持 **薄 Facade**；**回合与行动卡**在 `TurnFlowService`，**收租响应链**在 `EffectStackOrchestrator`，**领域规则**在 `model` 各子包，**线协议**在 `dto`，**存档**在 `persistence`，**入网**在 `network` 三层子包，**可复用模式**在 `pattern`。
- **Flutter**：`core/network` 对齐协议；`feature/*` 承载各业务界面与 ViewModel。
- **docs**：architecture / implementation / interface / requirements 四分，分别服务设计、实现追溯、协议与需求读者。

如需与类图、时序图、用例图对照阅读，请参阅同目录下的 `uml_source.md`。
