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

---

## 2. 变更记录（Changelog）

约定：

- **何时写**：合并进主分支前，由作者或合并者补一条；**小改动可合并为一条**（同一 PR 内多 commit 可只记一次）。
- **写什么**：日期、作者（可选）、**摘要**、涉及的**领域**（协议 / 游戏规则 / 存档 / UI / 测试 / 文档等）、关联 PR 或 issue（如有）。
- **格式**：从新到旧排序（最新条目在最上）。

### 记录

#### 2026-04-18 — 文档整理

- **摘要**：合并原 `docs/architecture/project-structure.md` 至 `docs/architecture/uml_source.md`，删除重复「包结构」段落；新增本工程协作文档 `docs/ENGINEERING.md`。未修改 `README.md` 与 `docs/requirements/requirements.md`。
- **领域**：文档结构

---

## 3. 维护约定

- **协议变更**：修改 `MessageDispatcher` / `GameServer` 等消息分支时，**同步更新** [`websocket-protocol.md`](interface/websocket-protocol.md)（与同一次变更一并提交）。
- **规则/牌堆真源**：以 [`rules.md`](../rules.md) 附录 A 与 `MonopolyDealCardFactory` 为准；课程需求与实现不一致时，在 [`requirement-trace-and-deviations.md`](implementation/requirement-trace-and-deviations.md) 中写明。
- **架构说明**：目录与 UML 只维护 **`uml_source.md` 一处**，避免双份漂移。
