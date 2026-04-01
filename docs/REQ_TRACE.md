# 需求追溯与实现说明（REQ_TRACE）

## 2.1.5a 胜利条件（与 `requirements.md` 原文差异）

| 项目 | `docs/requirements.md` §2.1.5a 原文 | 本仓库实现 |
| --- | --- | --- |
| 胜利判定 | “three or more complete property sets **of the same color**” | 任意颜色合计 **≥ 3 套**完整地产集 |

**采用规则：** 与 **Monopoly Deal（Hasbro）常见规则** 一致：率先在面前凑齐 **3 套完整房产组合**（可跨不同颜色；万能牌按声明颜色计入对应色）。

**代码：** `GameController#checkWinCondition` → `Player#countCompletePropertySets()` → `PropertySetCalculator#countCompletePropertySets`（对各色 `floor(有效张数 / 该色需求张数)` 求和）。

**为何与需求文档表述不同：** 需求英文稿中 “of the same color” 易被理解为「同色三套」；标准盒装规则与多数玩家认知为「任意三套」。若课程/甲方坚持「同一颜色须凑满 3 套」，需改为按单色 `max(floor(eff/need))` 判定，并同步修改 `checkWinCondition` 与测试。

---

## 2.1.6 暂停与存档进度（`requirements.md` §2.1.6）

| 需求要点 | 实现类 / 入口 |
| --- | --- |
| 暂停 / 恢复 / PVP 暂停投票 | `GameController#requestPause`、`#acknowledgePause`、`#resume`、`#ensureNotPaused`；状态经 `pushSnapshot` 进入 `GameStateSnapshot`。 |
| 「一轮」与整轮计数 | `GameController#endTurn` → `TurnManager#advanceTurn`；回到 `sessionPlayers` 首位时 `GameController#fullRoundsCompleted` +1。 |
| 手动存档（导出 JSON） | `GameController#exportSessionJson` → `GameSessionMemento#capture`（反射抓取控制器与牌局状态）。 |
| 手动读档（恢复） | `GameController#importSessionJson` → `GameSessionMemento#applyToController`；网络层 `GameServer#handleLoadGame`（`LOAD_GAME`）。 |
| 自动存档（每 3 整轮） | `GameController#maybeAutosaveAfterFullRound`（`fullRoundsCompleted > 0` 且 `% 3 == 0` 时触发）；路径常量见 `GameConstants` 注释（`~/.monopoly-deal/autosave.json`）；开关 `GameConstants#AUTOSAVE_PROPERTY`（`-Dmonopoly.autosave=true` 才真正写盘）。 |
| 快照 JSON 结构（牌堆 / 玩家 / 回合 / 效果栈等） | `GameSessionMemento`；嵌套 `SessionPlayerMemento`、`PersistedCard`、`EffectStackEntryMemento`、`StackResponseStateMemento`。 |
| 最近操作简述（T3-7） | `GameStateSnapshot#lastActionSummary`：每次 `GameController#pushSnapshot` 写入；其余 phase 走 `fallbackActionSummary`。 |

---

## 2.2.1 性能约束（`requirements.md` §2.2.1）

| 需求要点 | 实现类 / 入口 |
| --- | --- |
| 抽牌 / 洗牌 / 租金计算本地单次 < 300ms | `src/test/java/com/monopoly/performance/PerformanceSmokeTest`：`drawOne+discard` 与 `RentCalculator.computeRentForColor` 各 1000 次，断言平均与 P99 在本地阈值内（当前阈值更严格：avg < 5ms，p99 < 50ms，避免 CI 抖动）。 |
| 可选调试耗时日志 | `GameController#computeRentDueForColor` 支持 `-Dmonopoly.perfLog=true` 输出耗时日志（微秒级）。 |

---

## 2.2.3 通信保密（`requirements.md` §2.2.3）

| 需求要点 | 实现类 / 入口 |
| --- | --- |
| 对手不能通过公共广播获得他人手牌明细 | `GameStateSnapshot` 公共视图仅包含 `handCount`，不包含手牌详情；广播由 `MessageDispatcher#toJsonBroadcast` 输出 `STATE_UPDATE`。 |
| 手牌明细仅定向下发给本人 | `GameServer#pushPrivateHands` 基于 `SessionRegistry` 的 playerId->连接映射发送 `MY_HAND`（含卡牌 `id/name`），仅发给已绑定该 playerId 的连接。 |
| 连接与身份绑定用于路由/鉴权基础 | `SessionRegistry`（`register/unregister/getPlayerId/connectionsOf`）与 `AUTH/JOIN_SESSION` 流程。 |

---

## 2.2.3（扩展）本地存档安全（实现补充）

| 需求要点 | 实现类 / 入口 |
| --- | --- |
| 进度文件轻量加密，防手工篡改 | `SaveEncryption`：AES-256-GCM，带 `ENC1:` 前缀 + Base64；密钥由 JVM 属性 `GameConstants#SAVE_KEY_PROPERTY`（`-Dmonopoly.saveKey=...`）提供；未设置密钥时 `encodeForStorage` / `decodeFromStorage` 透传明文 JSON。 |
| 写盘前加密 | 自动存档：`GameController#maybeAutosaveAfterFullRound` 内对 `exportSessionJson()` 结果调用 `SaveEncryption#encodeForStorage`。手动存档：`GameServer#handleSaveGame` 写文件前同样 `encodeForStorage`。 |
| 读盘时解密或明文兼容 | `GameController#importSessionJson` → `SaveEncryption#decodeFromStorage`；`GameServer#handleLoadGame` 将载荷交给 `importSessionJson`。 |

---

## 其他

- 单局时长上限：见 `GameConstants.DEFAULT_SESSION_LIMIT_MS`，可用 `-Dmonopoly.sessionLimitMs=...` 覆盖（阶段 2 D1）。
