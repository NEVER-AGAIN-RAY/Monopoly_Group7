# 工程协作文档

本文件用于**团队协作**：记录对仓库有影响的变更、约定文档职责，避免「谁改了什么只有自己知道」。  
**与需求基线区分**：正式课程需求仍以 [`docs/requirements/requirements.md`](requirements/requirements.md) 为准；本文件不替代需求文档。

---

## 1. 文档地图（看什么去哪里）

| 文档 | 作用 |
|------|------|
| [`README.md`](../README.md) | 项目简介、里程碑、成员分工、运行命令、环境变量；**入口导航**。 |
| [`docs/requirements/requirements.md`](requirements/requirements.md) | 功能/非功能需求基线；**勿在未评审时擅自改写历史语义**。 |
| [`rules.md`](../rules.md) | Monopoly Deal 纸牌规则中文意译、附录 A（与实现对照）；牌堆/租金等**实现真源**之一。 |
| [`docs/architecture/uml_source.md`](architecture/uml_source.md) | **目录与模块职责** + **UML**（PlantUML 与导出图）；架构单一入口。 |
| [`docs/interface/websocket-protocol.md`](interface/websocket-protocol.md) | 客户端与服务端 **WebSocket JSON 协议**（消息类型、载荷、维护约定）。 |
| [`docs/implementation/requirement-trace-and-deviations.md`](implementation/requirement-trace-and-deviations.md) | 需求**实现状态**、与需求表述的**偏差**及后续跟进行动。 |
| **本文件 `docs/ENGINEERING.md`** | **变更记录**、协作约定、文档索引更新说明。 |
| [`docs/ai-delivery-checklist.md`](ai-delivery-checklist.md) | AI 训练交付状态、当前 artifact 路径、DeepSeek production gate 与 Windows 5090 执行清单。 |
| [`docs/ai-paper-outline.md`](ai-paper-outline.md) | AI 蒸馏方向的论文提纲、相关工作矩阵、实验表与 claim 边界。 |

---

## 2. 变更记录（Changelog）

约定：

- **何时写**：合并进主分支前，由作者或合并者补一条；**小改动可合并为一条**（同一 PR 内多 commit 可只记一次）。
- **写什么**：日期、作者（可选）、**摘要**、涉及的**领域**（协议 / 游戏规则 / 存档 / UI / 测试 / 文档等）、关联 PR 或 issue（如有）。
- **格式**：从新到旧排序（最新条目在最上）。

### 记录

#### 2026-05-24 — 固定牌堆 seed 与 paired policy 实验

- **摘要**：新增 `monopoly.deck.seed` 与 `monopoly.firstPlayer.seed`，支持固定初始牌堆、弃牌堆回洗与随机先手；新增 `PairedSeedPolicyExperimentRunner`，可在同一批 seed 下对照 `hard,hard` 与 `hard,llm` 等 lineup，衡量同一座位只替换策略后的结果差异，用于更严格地评估 DeepSeek 与 hard 的强弱。
- **领域**：AI / 测试 / 文档

#### 2026-05-24 — MDSP v1.0 与公开记牌

- **摘要**：DeepSeek 决策上下文正式命名为 `MDSP`（Monopoly Deal Strategy Protocol）`v1.0`；AI prompt 顶层增加协议名与版本；历史摘要新增 `publicCardMemory`，只基于公开可见区域与出牌事件统计 Just Say No、Deal Breaker、Sly Deal、Forced Deal 等重要行动牌的已见/已出/已银行/剩余估计，帮助 LLM 进行非作弊式记牌与长期规划。
- **领域**：AI / 测试 / 文档

#### 2026-05-24 — DeepSeek 结构化历史摘要

- **摘要**：新增 `AiHistoryTracker`，在服务端快照路径维护每局公开局势的压缩历史；DeepSeek prompt 增加私有 `memory` schema，包含最近关键事件、争夺颜色、玩家压力与战略提醒，帮助 LLM 处理长期规划、被抢后的反击优先级和现金饱和问题；付款/弃牌等非主出牌决策使用 compact memory 降低 token 成本。该记忆只进入 AI 内部决策 JSON，不改变 WebSocket `STATE_UPDATE` 协议。
- **领域**：AI / 测试 / 文档

#### 2026-05-24 — DeepSeek 节奏决策与响应栈元数据增强

- **摘要**：DeepSeek 决策 prompt 升级到 `v5-tempo`，强化三套胜利优先、现金饱和时降低存钱/纯收钱优先级，并在有抢地/换地/抢整套候选时过滤低节奏候选；Just Say No 响应栈为 ACTION 增加行动牌名称与 effectCode 元数据，AI 可区分 Deal Breaker、Sly Deal、Forced Deal 等高威胁行动；付款 prompt 标记拆完整套与 wild 风险，并在模型付款方案明显更伤局面时回退到本地兜底。
- **领域**：AI / 效果栈 / 存档 / 测试

#### 2026-05-24 — 自定义真人/LLM 混合对局与 DeepSeek live smoke

- **摘要**：新增 `CUSTOM` 开局模式，支持 `human,human,llm,llm` 等席位配置，普通 `PVP` 保持全真人；JavaFX 快速开局增加自定义席位输入；DeepSeek 决策 prompt 升级到 v3，加入结构化风险评估与长期规划优先级；新增 `MixedAiBattleExperimentRunner`，可跑 `hard,hard,llm,llm` 本体短局并输出本地评估 JSON。
- **领域**：协议 / UI / AI / 测试

#### 2026-05-24 — 多会话隔离、训练样本平衡与缩放实验

- **摘要**：`GameServer` 将保存/加载确认状态改为按 `sessionId` 隔离，加载跨 `sessionId` 存档时会迁移连接并刷新状态；训练脚本默认对 MLP/linear 学生启用决策类型 loss balancing，降低 `PLAY_CARD` 对稀有决策的淹没；新增 player-count 泛化切分（如 2/3 人训练、4/5 人验证）；新增 deterministic scaling subsets、缩放曲线训练脚本、gameplay matrix 评估脚本、forest-style 离线 baseline、付费 trace 审计脚本、多 trace 合并/去重脚本、训练交付 readiness gate、训练 run summary、多 seed replicate 工具、已有 trace 离线 DeepSeek relabel/选择工具、relabel paid probe 包装脚本与带显式付费确认的一键 DeepSeek production wrapper；补充多会话加载投票回归测试与训练日志。
- **领域**：协议 / 多会话 / AI 训练 / 测试 / 文档

#### 2026-05-24 — AI 批量模拟与蒸馏数据管线

- **摘要**：新增 `simulation/` 包与 `SimulationBatchRunner`，支持多局真实后端规则并发推进、AI 决策进入 `DecisionBroker` 微批、DeepSeek 批量标注、JSONL 样本落盘；`AI_VS_AI` 增加可注入策略工厂以便模拟时替换为 `BrokeredAiPlayStrategy`；新增 `scripts/distill_dataset.py`、采集脚本、训练计划与训练日志；新增纯 Java `LocalLinearRankerAiPlayStrategy` 与本地学生模型对战评估脚本。
- **领域**：AI / 模拟采集 / 测试 / 架构文档

#### 2026-04-18 — 文档整理

- **摘要**：合并原 `docs/architecture/project-structure.md` 至 `docs/architecture/uml_source.md`，删除重复「包结构」段落；新增本工程协作文档 `docs/ENGINEERING.md`。未修改 `README.md` 与 `docs/requirements/requirements.md`。
- **领域**：文档结构

---

## 3. 维护约定

- **协议变更**：修改 `MessageDispatcher` / `GameServer` 等消息分支时，**同步更新** [`websocket-protocol.md`](interface/websocket-protocol.md)（与同一次变更一并提交）。
- **规则/牌堆真源**：以 [`rules.md`](../rules.md) 附录 A 与 `MonopolyDealCardFactory` 为准；课程需求与实现不一致时，在 [`requirement-trace-and-deviations.md`](implementation/requirement-trace-and-deviations.md) 中写明。
- **架构说明**：目录与 UML 只维护 **`uml_source.md` 一处**，避免双份漂移。
