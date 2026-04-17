# Monopoly_Group7

## Project Completion Plan (Weeks 1-15)

Phase-1 due Week 8 (2026-04-22). Phase-2 due Week 15 (2026-06-09). Demo on 2026-06-11/12.

| Week | Key Milestones (focus shifts from analysis/UML in Weeks 1-8 to implementation/testing in Weeks 9-15) |
| --- | --- |
| 1 | Understand the game，project kickoff, requirement elicitation. |
| 2 | Design the schedule plan and complete the team division of labor , Elicit functional/non-functional requirements. |
| 3 | Elicit functional/non-functional requirements. |
| 4 | Discussion, understanding, learning, designing system architecture (MVC). |
| 5 | Design system architecture , Draft UML diagrams (Class, Sequence, Use Case). |
| 6 | Draft UML diagrams (Class, Sequence, Use Case). |
| 7 | Implement basic Model classes (Card, Player). |
| 8 | Finalize UML & Phase-1 PDF Report , Submit Phase-1 to Brightspace. |
| 9 | Implement GameController and TurnManager. |
| 10 | Apply Factory (Deck creation) and Strategy (AI logic) , Write JUnit test cases for game rules (e.g., Rent calculation). |
| 11 | Implement GameUpdateSubject (Observer Pattern). |
| 12 | Setup WebSocket/Network dispatchers , Connect Java Backend with JavaFX (FXML) desktop UI. |
| 13 | Refine AI difficulties (Easy/Hard) , Code refactoring to eliminate "Code Smells" (Ensure no large classes/methods). |
| 14 | End-to-end system testing. |
| 15 | Write Phase-2 final PDF explanation , Code freeze and final submission. |

## Member Contributions

| Name | Role | Primary Tasks | Contribution (%) |
| --- | --- | --- | --- |
| Liu Zongrun| Architecture & Core Logic | Overall architecture, card dealing & turn loop core, UML class diagram, GitHub standards | 0% |
| Lei Yurun| GUI & Card Effects | GUI development, card effect logic, applying design patterns | 0% |
| Wang Tingdong | Data & Calculations | Player data structures, asset computation logic, sequence diagrams | 0% |
| Qiu Siqi | Documentation & Prep | Phase-1 requirements collation, PPT prep, basic black-box testing | 0% |
| Liu Yuhao | Testing & Formatting | Phase-2 test case authoring, documentation formatting and checks | 0% |

The contribution percentages differ because core code development (architecture, gameplay logic, GUI, design patterns) carries higher complexity and effort, while documentation and black-box testing require comparatively less specialized implementation work.

## 项目简介与运行说明

Monopoly Deal: Java WebSocket server plus a **JavaFX + FXML** desktop client (`mvn javafx:run`).

### 环境要求

- JDK: 17 (aligned with `pom.xml` compiler target)
- Maven: 3.9+
- Optional integration tool: `wscat`

### 快速开始

- Run backend tests: `mvn -q test`
- Start WebSocket server: `mvn -q exec:java`
- Start desktop client (after server is up): `mvn javafx:run`
- Default endpoint: `ws://localhost:8025/ws`
- Quick connectivity check:
  - `wscat -c ws://localhost:8025/ws`
  - Send: `{"type":"PING","payload":{}}`
  - Send: `{"type":"START_SESSION","payload":{"sessionId":"demo","playerCount":2,"gameMode":"PVP","randomizeFirstPlayer":false}}`
  - Expect: `STATE_UPDATE`

### JVM 参数

| 参数 | 默认值 | 说明 |
| --- | --- | --- |
| `-Dmonopoly.verifyDeck=true/false` | `false` | 开启后在关键流程校验全场牌数守恒（108），用于开发期排查。 |
| `-Dmonopoly.autosave=true/false` | `false` | 开启后每 3 个整轮自动写入 `~/.monopoly-deal/autosave.json`。 |
| `-Dmonopoly.saveKey=...` | 未设置 | 设置后存档使用 AES-GCM 加密；未设置时按明文 JSON 存储。 |
| `-Dmonopoly.sessionLimitMs=...` | `GameConstants.DEFAULT_SESSION_LIMIT_MS` | 覆盖单局超时上限（毫秒）。 |

## Documentation 导航

- Requirements: [`docs/requirements/requirements.md`](docs/requirements/requirements.md)
- UML Source: [`docs/architecture/uml_source.md`](docs/architecture/uml_source.md)
- WebSocket Protocol: [`docs/interface/websocket-protocol.md`](docs/interface/websocket-protocol.md)
- Requirement Trace and Deviations: [`docs/implementation/requirement-trace-and-deviations.md`](docs/implementation/requirement-trace-and-deviations.md)

## Notes

- 性能测试说明：`PerformanceSmokeTest` 属于本地抽检（smoke），用于快速发现明显退化；阈值偏宽以降低 CI 抖动误报，不作为严格压测结论。
