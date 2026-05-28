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

#### 2026-05-28 — Repository layout cleanup and training-data split

- **摘要**：整理仓库目录：Java 后端、仿真工具与 JavaFX 客户端统一到 `backend/src/`；Web/Vite 前端从 `web-client/` 改为 `frontend/`；训练/评估 Python 与 shell 工具从 `scripts/` 改为 `training/scripts/`；运行时本地 ranker checkpoint 放在 `backend/models/`；训练 trace、jsonl、评估报告、handoff 压缩包和历史大实验产物本地保留到 `training/data/`，并通过 `.gitignore` 默认不再提交。根 `pom.xml` 保持 Maven 入口，显式指向 `backend/src`，所以 `mvn -q test`、`mvn -q exec:java`、`mvn javafx:run` 仍从仓库根目录运行；Web 构建命令改为 `npm run build --prefix frontend`。
- **领域**：仓库结构 / 构建 / AI 训练数据 / 文档

#### 2026-05-28 — Web lobby and mixed bot room setup

- **摘要**：Web 首屏改为大厅入口，支持开房间、加入房间、房间列表三类流程；开房配置可按席位选择真人、Hard、Strong、LLM、LLM Student 或空位，最终以 `CUSTOM` lineup 启动。Web 对局 HUD 新增每步决策倒计时：响应阶段使用服务端 deadline，普通回合决策使用前端配置的软倒计时显示。后端新增 `ROOM_LIST` / `ROOM_LIST_RESULT` 轻量协议，列出当前 session、席位数、真人席位、连接数与当前行动玩家；`CUSTOM` 新增 `student` / `llm_student` / `local_ranker` 别名，默认加载本地蒸馏 ranker 模型，缺失或加载失败时回退到 strong lookahead。
- **追加**：将大厅逻辑调整为更接近联机游戏等待厅：新增 `CREATE_ROOM` / `JOIN_ROOM` / `ROOM_SET_SEAT` / `LEAVE_ROOM` / `START_ROOM` 和 `ROOM_STATE`。创建/加入先登记昵称，昵称在房间内防重；真人席位只能从已加入成员昵称中选择，不能凭空创建“玩家”席位；房主开始后服务端将等待厅席位转换为 `CUSTOM` 对局并把昵称作为显示名映射到 `pvp-1`、`pvp-2` 等真实玩家 ID。
- **领域**：协议 / Web UI / AI / 文档 / 测试

#### 2026-05-27 — Payment DP fix and final strong local bot

- **摘要**：收尾审计新默认 `boardAwarePayment + boardAwareOverflowDiscard`。对 16 个由新默认造成的 W->L 翻局做 outcome trace，确认问题集中在辅助 payment/overflow 选择；其中 payment 选择发现真实程序性偏差：实际 board-aware payment 使用受 `MAX_PAYMENT_CANDIDATES` 截断的组合枚举，可能漏掉精确小额银行支付而先选大额付款。已改为按支付金额动态规划选择，保留原 `amountPaid + boardDamage` 目标，并补回归测试。DP 修复在 16 个负向 seed 中救回 2 局；完整 600 局 matched 复验仍为 402/600 = 67.0%，600/600 自然结束、0 forced/unknown；相对旧 `paymentOverflowA` 净 0、changedGames 4，相对 `buildingA` 保持 net +18（33 个 L->W、15 个 W->L，exact sign-test p≈0.0133）。`MixedAiBattleExperimentRunner` 增加 `monopoly.mixedBattle.seedList`、`progressEvery` 和 seedList/seeded 防护，便于复现实验。最终本轮推荐对战机器人为默认 `SearchLookaheadAiPlayStrategy`（即 `buildingA + board-aware payment/overflow + DP payment`），前端/协议继续通过 `STRONG` / `LOOKAHEAD` / `SEARCH` 别名使用。
- **追加**：对最终默认的剩余 198 个输局继续做近胜压力审计。`fixedPaymentDp` 600 局输局分析显示仍有 47 个 final target 为 2 套的 close loss；抽 20 个可复现 close-loss seed 采集 memento，20/20 仍为 hard 自然获胜。从 897 条 memento 行选 60 条高分差 PLAY_CARD 决策做 `CounterfactualReplayRunner`，340/700 snapshot 结果一致：sourceBetter 15、hardBetter 10、same 35，source natural wins 7 vs hard 8，不支持新增 hard fallback。严格 clean 标签只剩 10 条且分布在 `DEPOSIT`/`DEPLOY`/`FORCED_DEAL`；复测旧诊断开关 `monopoly.search.passGoDepositBonus=1600` 在这 20 个 close-loss seed 上仍 0/20 翻盘，且它已有 200 局负面记录。因此不再推广新参数，最终默认保持 `SearchLookaheadAiPlayStrategy`。
- **追加**：补跑两个全新 seed range 的 random-first 独立确认块，合计 200 局为 116/200 = 58.0%，200/200 自然结束、0 forced/unknown，Wilson CI 下界≈51.07%，仍通过 hard gate；但该结果显著低于旧 600 局的 67.0%（粗略两比例 p≈0.021），记录为方差/分布警告。自检没有发现 seed 重复、seed overlap、座位不均衡或 winner 解析缺失。旧 600 + 新 200 合并为 518/800 = 64.75%，800/800 自然结束、0 forced/unknown，CI≈61.38%-67.98%，gate 通过。新增 `effectiveLookaheadConfig` 报告字段，记录 `SearchLookaheadAiPlayStrategy` 的实际默认参数，避免后续只记录显式 `-D` 参数而无法排除配置漂移。
- **追加**：继续补一组带完整 `effectiveLookaheadConfig` 的确认块。原始新 100 局为 71/100，但 seat split 为 43/50 vs 28/50，触发自审；随后用同 100 个 deck seed 反转 lineup 做双座位 paired 复盘，四个 50 局块为 43/50、22/50、28/50、34/50，合并 paired 200 局为 127/200 = 63.5%，200/200 自然结束、0 forced/unknown，CI≈56.63%-69.86%，gate 通过。100 个 deck seed 中 lookahead 双座位全赢 29 个、拆分 69 个、双输 2 个，说明短块强烈受座位/先手组合影响，后续最终确认应优先使用 explicit seedList 的 same-seed dual-seat 设计。`MixedAiBattleExperimentRunner` 追加每局 `initialPlayer`、`initialPlayerIndex`、`initialPlayerRole`、`initialPlayerTeam`，并用 smoke report 确认字段与 62-key 有效配置快照同时落盘。合并旧 600、新 200、paired 200 后的鲁棒性口径为 645/1000 = 64.5%，1000/1000 自然结束、0 forced/unknown，CI≈61.48%-67.41%；该 1000 口径包含最后 100 个 deck seed 的刻意双座位复用，因此不是 1000 个独立 deck seed。
- **追加**：再补一组全新 50 个 deck seed 的 same-seed dual-seat confirmation。`finalconfirm5` 双座位 100 局为 59/100 = 59.0%，100/100 自然结束、0 forced/unknown；单独 gate 因 Wilson CI 下界≈49.20% 未超过 50% 而失败，记录为支持性但非独立证明块。该组座位分布较均衡（28/50 与 31/50），50 个 deck seed 中 lookahead 双座位全赢 11 个、拆分 37 个、双输 2 个。把 `finalconfirm4+5` 的 paired-only 确认合并后为 186/300 = 62.0%，300/300 自然结束、0 forced/unknown，CI≈56.39%-67.31%，paired-only gate 通过。最新总聚合为 704/1100 = 64.0%，1100/1100 自然结束、0 forced/unknown，CI≈61.12%-66.78%，aggregate gate 通过；该口径含 950 个唯一 deck seed，其中 150 个为刻意双座位复用。结论：最终默认 `SearchLookaheadAiPlayStrategy` 仍保持推荐，但正式 claim 应优先引用 300 局 paired-only 口径和 1100 局 aggregate 口径，同时承认单个 100 局 paired block 的 CI 可低于 50%。
- **追加**：新增 `training/scripts/summarize_paired_seat_reports.py`，把 same-seed dual-seat protocol 固化为 JSON 自检：检查每个 deck seed 是否有预期双座位记录、`effectiveLookaheadConfig` 是否存在且一致、自然结束率、目标队伍胜率、Wilson CI 下界、paired seed 全赢/拆分/全输分布、初始先手队伍分布等。`finalconfirm5-paired-seat-audit.json` 正确因 CI 下界 49.20% 未过而 fail；`finalconfirm45-paired-seat-audit.json` 对 150 个 seed / 300 局 pass，确认每个 seed 恰好 2 行、300/300 自然、配置 digest 一致、CI 下界 56.39%。该工具也暴露历史边界：`finalconfirm4` 报告生成早于 `initialPlayer` 字段，因此初始先手 bucket 为 unknown；`finalconfirm5` 已能记录平衡先手。
- **追加**：最后补跑第三个独立 same-seed dual-seat confirmation：`finalconfirm6` 使用 50 个全新 deck seed，双座位 100 局为 68/100 = 68.0%，100/100 自然结束、0 forced/unknown，Wilson CI≈58.34%-76.33%，标准 gate 与 paired-seat audit 均通过。审计确认每个 seed 恰好 2 行、先手队伍 `hard=50` / `lookahead=50`、62-key 有效配置 digest 一致，seed split 为 18 个双赢、32 个拆分、0 个双输。合并 `finalconfirm4+5+6` 的 paired-only 确认为 254/400 = 63.5%，400/400 自然结束、0 forced/unknown，CI≈58.67%-68.07%，paired audit 通过；最新总聚合为 772/1200 = 64.33%，1200/1200 自然结束、0 forced/unknown，CI≈61.58%-66.99%，aggregate gate 通过。最终收尾结论不变：最好用的本地对战机器人是默认 `SearchLookaheadAiPlayStrategy`（`buildingA + board-aware payment/overflow + DP payment`），正式说明优先引用 400 局 paired-only 与 1200 局 aggregate 两个口径。
- **领域**：AI / 评估 / 工具 / 测试

#### 2026-05-27 — BuildingA wild and remaining-turn rollout screens

- **摘要**：将辅助决策改进合并为新的默认本地 strong policy。单独的 `boardAwareOverflowDiscard` 600 局只比 `buildingA` 多 1 胜，单独 `boardAwarePayment` 前 100 局也只有 +1，因此先前均不推广；但组合 `paymentOverflowA-rfp`（`boardAwarePayment=true` + `boardAwareOverflowDiscard=true`，play-decision 参数保持 `buildingA`）在与 `buildingA` 完全 matched 的 600 个 seed 上为 402/600 = 67.0%，600/600 自然结束、0 forced/unknown，hard gate 通过，matched 对照为 net +18、changedGames 50。配对翻局为 34 个 L->W 对 16 个 W->L，exact sign-test p≈0.0153、McNemar continuity p≈0.0162；默认 smoke 40 个同 seed 与显式 `paymentOverflowA` 完全 0 改动。已将 `monopoly.search.boardAwarePayment` 和 `monopoly.search.boardAwareOverflowDiscard` 默认值升为 `true`，仍可用 `-D...=false` 做 ablation。自检记录：一次 gate 先于 summary 写入失败、一次并行 Maven smoke/test 失败，串行重跑均成功；后续最终验证避免并行 Maven。结论：新默认强 AI 是 `buildingA + board-aware payment/overflow`。
- **摘要**：继续优化当前默认 `buildingA`，先回到核心 `PLAY_CARD` 而不是继续使用已判弱的辅助标签。复查 `buildingA` 600 局输局与 120 局 outcome trace 后，否定了两个静态直觉：低分差 `DEPLOY->DEPLOY` 和“2 套后仍部署非完成牌”都大量出现在赢局中，不能做粗回退。筛查 `wildA-rfp`（wild 部署惩罚 300/700/400）matched 前 50+50 局为 70/100，且相对同 seed `buildingA` 完全 0 改动；不推广。筛查 `buildingA-rollremainsearch-rfp`（`rolloutRemainingTurn=true, rolloutPolicy=search`）为 71/100，相对 `buildingA` 净 +1、changedGames 11；抽样 trace 显示它既能把早期 `PASS_GO` 改成大幅部署而赢，也会用极小 margin 把默认 wild 部署改坏而输。再加 `hardMargin=500` 的门控版本降到 67/100、净 -3。结论：默认 `buildingA` 保持不变；remaining-turn rollout 是高成本高方差扰动，当前不扩跑、不推广。
- **追加**：进一步把 `DEPLOY_WILD` 缩成目标颜色问题。新增 `training/scripts/select_wild_color_replay_rows.py` 与 `training/scripts/analyze_wild_color_replay.py`，在 3351 条 memento trace 行中找到 771 条多 wild 候选行，其中 source/hard 颜色分歧仅 75 条；全量分歧 replay 为 75 decisions、73 informative、0 hard-choice resolution/candidate/incomplete error，但仍有 115 forced candidates，source 对 hard 为 34/20/21、自然胜 42 vs 37。loss-only 18 行则为 6/4/8、自然胜 0 vs 1，简单颜色启发式最高只命中 36/75，因此不做 hard fallback 或静态 wild 颜色规则。自检发现把 wild 进度字段直接写入候选摘要会改变默认运行结果：`completionScore=` 与 `wildProgressScore=` 两版都是 68/100，相对 `buildingA` 前 100 个 matched seed 为 net -2、changedGames 4。已改为默认关闭的 `monopoly.ai.includeWildProgressInSummary=false`，默认摘要恢复为 `Deploy wild property as COLOR.`；关闭后同 100 seed 为 70/100，相对 `buildingA` net +0、changedGames 0。结论：新工具和字段只作诊断，默认 `buildingA` 不变。
- **追加**：沿着 rollout-gate clean 标签里最集中的 `DEPLOY_WILD` 信号，新增默认关闭的 `monopoly.search.wildOverfullSetPenalty=0`，只惩罚把 wild 部署到已经完整/过满颜色的情况。自检发现第一组 `wildOverfull800A-rfp` 用错 seedBase（`267001/267051`，与 matched `buildingA` 无共同 seed），只保留为无效协议样本。修正 seed 后的 `wildOverfull800B-rfp`（penalty=800，buildingA 参数不变）前 50+50 局为 70/100，100/100 自然结束、0 forced/unknown；相对 matched `buildingA` 与旧 `wildA` 均为 net +0、changedGames 0。结论：该开关只保留为诊断钩子，不推广、不扩跑；wild 颜色选择需要更丰富的状态条件或 targeted replay。
- **追加**：用已修复的 `CounterfactualReplayRunner` 复跑核心 `PLAY_CARD` replay，强制要求候选带 `forceEndReason` 并排除任何 forced 候选。旧 `48-all` replay 暴露 115 个 forced candidates，严格只剩 6 条 clean 标签；旧 low-progress 40 行暴露 99 个 forced candidates，严格剩 0 条。再从现有 60 局 memento trace 扩出 70 条战术行和 33 条 low-progress 行：战术 replay 为 sourceBetter 14 / hardBetter 20 / same 36，160 个 forced candidates，严格 clean 只剩 7 条（6 条 beat hard）；low-progress replay 为 sourceBetter 5 / hardBetter 4 / same 24，71 个 forced candidates，严格 clean 0 条。把 snapshot 上限从 340 提到 700 完全不减少 forced 数量，单行 2000-snapshot probe 仍是 16/16 forced，说明这不是简单上限不足。结论：本轮没有足够干净、稳定的核心标签支撑新 corrector 或 gameplay 参数筛查；默认 `buildingA` 保持不变。
- **追加**：扩样本复验默认关闭的 `boardAwareOverflowDiscard`。在原 200 局 `overflowA-rfp` 已知只比 matched `buildingA` 多 2 局的基础上，补跑与 `buildingA` 600 后 400 局相同 seed 的两个 extra200 seat block。600 局合并结果为 385/600 = 64.17%，600/600 自然结束、0 forced/unknown，hard gate 通过；但逐 seed 对照 full `buildingA` 600 仅为 net +1，changedGames 11（6 个 L->W、5 个 W->L）。结论：该候选仍不推广，`monopoly.search.boardAwareOverflowDiscard` 保持默认关闭。
- **追加**：新增默认保持原行为的 `monopoly.search.propertyCountValue=120`，用于单独筛查原先硬编码的 raw property count 终局估值。`propertyCount180A-rfp` 与 `propertyCount260A-rfp` 均在 matched 前 50+50 局为 70/100，100/100 自然结束、0 forced/unknown，hard gate 通过；但相对同 seed `buildingA` 前 100 局均为 net +0、changedGames 0。结论：简单全局提高地块数量权重不会修复剩余 blowout loss，不扩跑、不推广；该参数保留为诊断开关。
- **追加**：补做胜/输对照，避免把输局高频动作误判为 bug。`buildingA` 120 局 default trace + 60 局 memento trace 显示高分差 `DEPLOY->DEPLOY` 覆盖其实偏赢（margin≥2000 时赢局 36 行、输局 8 行），`BIRTHDAY->DEPLOY`、`RENT_DUAL->DEPLOY` 也同时出现在赢局/输局，不能粗回退。基于仍略可疑的 `RENT/RENT_DUAL -> DEPLOY/PASS_GO` 模式，新增默认关闭的 `monopoly.search.hardRentFallbackMargin=-1`。筛查 `hardRentFallback3000A-rfp` 与 `hardRentFallback6000A-rfp` 均为 69/100，100/100 自然结束、0 forced/unknown，hard gate 通过；但相对 matched `buildingA` 前 100 局均为 net -1、changedGames 3，且翻转 seed 集相同。结论：不推广、不扩跑；保留为诊断开关。
- **追加**：参考不完全信息搜索与 DAgger 方向，继续收敛 `rolloutRemainingTurn=true, rolloutPolicy=search` 这个唯一能稳定翻转多局的候选。新增默认不改变行为的 `monopoly.search.rolloutOverrideMargin=0`，只在 rollout 开启且 margin>0 时计算 immediate best，并要求 rollout best 在同一 rollout 评分空间内领先 immediate-best 候选足够多才允许改决策。自检先后排除两版探索结果：`om3000/om6000` 混用了 rollout 与 immediate 两种 horizon，`om3000b` 又在 immediate baseline 中重复叠加了 candidate adjustment。最终有效的 `rollremainsearch-om3000c-rfp` 前 50+50 局为 70/100，100/100 自然结束、0 forced/unknown，hard gate 通过；相对 matched `buildingA` 为 net +0、changedGames 6，相对 plain rollout 为 net -1、changedGames 7。结论：全局 margin 门控没有收益，不推广、不扩跑；下一步需要状态条件或学习式 override。
- **追加**：补做 `om3000c` 六个翻转 seed 的 outcome trace，尝试把 rollout override 收窄成状态/动作条件。自检发现 `candidateEffect(...)` 未把 `Deploy ...` 摘要识别为 `DEPLOY`，会污染 effect-based gate；已修复并加单测。新增默认不改变行为的 `monopoly.search.rolloutOverrideAllowedEffects` 诊断开关。修复后重跑 `rollremainsearch-norent-om3000-v2-rfp` 为 70/100，和 `om3000c` 同 100 seed 完全 0 改动，相对 `buildingA` 仍 net +0；重跑受该 bug 影响的 `hardRentFallback3000B-rfp` 为 68/100，相对 `buildingA` net -2。结论：屏蔽租金或信任 hard 租金回退都不能分离正负翻转，不推广；保留开关用于后续 learned/replay-backed state gate。
- **追加**：继续验证 scalar margin 是否能过滤负向 rollout flip。`rollremainsearch-om9000-v2-rfp` 为 70/100，100/100 自然结束、0 forced/unknown；相对 matched `buildingA` 仍 net +0、changedGames 6，且相对 `om3000c` 完全 0 改动。结论：把 margin 从 3000 提到 9000 不能过滤任何实际翻转，margin 方向停止。为下一步 replay-backed state gate 增加 rollout gate trace 元数据（raw rollout best、immediate best、gate choice、score gap、是否实际 override 等），并修复普通存钱摘要在元数据中 effect 为空的问题，同时保留动作牌存钱的具体 effect（如 `PASS_GO`）。
- **追加**：把 rollout gate 元数据接入 memento replay 审计。新增 `training/scripts/select_rollout_gate_replay_rows.py` 与 `training/scripts/analyze_rollout_gate_replay.py`，采集 `rollout-gate-memento20-seat1` 20 局 trace（886/886 行带 memento，586 行有 rollout 元数据），选出 40 行 raw rollout best 与 immediate best 冲突的样本回放；结果 raw 对 immediate 为 11 胜 / 12 负 / 17 平，去掉 selected candidates 里触发 `COUNTERFACTUAL_SNAPSHOT_LIMIT` 的行后为 8 胜 / 11 负 / 17 平，说明不能直接信任 rollout。严格 counterfactual-label selection 只剩 10 条干净标签，且 best effect 分散，不能训练或启用新 gate。新增默认不改变行为的 `monopoly.search.rolloutOverrideAllowedTransitions`，用于诊断 `IMMEDIATE->ROLLOUT` 转换。自检发现第一次 `rollpassdeploy3000-rfp` 命令没有给 `PASS_GO->DEPLOY` 加引号，shell 把 `>` 当作重定向，报告实际参数为 `PASS_GO-` 并生成了误文件 `DEPLOY`，因此该报告作废。修正后重跑 `rollpassdeploy3000b-rfp`（真实只允许 `PASS_GO->DEPLOY`）前 50+50 局为 70/100，100/100 自然结束、0 forced/unknown；相对 `om3000c` 和 `om9000-v2` 均 net +0、changedGames 0。结论：该 transition gate 只是复现已有结果，不能改进 `buildingA`；下一步应做更严格的 clean replay selection 或学习式 state gate。
- **追加**：补采 seat2 的 rollout-gate memento trace（`rollout-gate-memento20-seat2`，11/20，仅采样），合并 seat1/seat2 共 1745 行 trace，其中 1161 行有 rollout 元数据、137 行 raw/immediate 冲突。全量 137 行 counterfactual replay 为 0 candidate errors / 0 incomplete，但有 190 个 forced candidates；clean selected-candidate 子集里 raw 对 immediate 为 31 胜 / 34 负 / 53 平。严格 clean label 只剩 31 条，best effect 以 `DEPLOY=22` 为主，其次 `DEPOSIT=4`、`STEAL_PROPERTY=3`。结论：remaining-turn rollout 仍不能直接作为 state gate 监督；下一步应转向更窄的 wild 部署目标/颜色和 swing-action 目标选择学习，而不是继续扩大 rollout whitelist。
- **领域**：AI / 评估 / 文档

#### 2026-05-26 — BuildingA memento replay and target-feature screens

- **摘要**：继续审计当前默认 `buildingA`，采集 60 局带 memento 的自然 outcome trace（35/60 = 58.33%，60/60 自然结束，0 forced，0 unknown），再从输局高置信 override 中选 40 行做 `CounterfactualReplayRunner` 回放。回放未发现系统性局部 bug：source best count 14、hard best count 13、source better 6、hard better 7、same 27，hard-choice 解析错误和候选错误均为 0。严格筛出的 7 行 counterfactual 标签全部指向 `DEPOSIT`，其中 5 行是 `PASS_GO->DEPOSIT`，因此新增默认关闭的 `monopoly.search.passGoDepositBonus`，并修正 action-effect adjustment 使存入银行的动作牌不会误吃“打出动作牌”的奖励。筛选 `passGoDepositA`（bonus=1600）200 局为 117/200 = 58.5%，hard gate 通过但低于 `buildingA` 200（-6.0pp）和 `buildingA` 600（-5.5pp），不推广，默认 `buildingA` 保持不变。
- **追加**：继续抽取 65 行“同动作效果但目标/颜色不同”的输局 memento 行做 replay，hard 目标相对 source 为 hardBetter 20、sourceBetter 11、same 34，hard natural wins 4 vs source 1，说明确有目标选择局部信号。新增默认关闭的 `monopoly.search.sameEffectHardTargetMargin` 与 `monopoly.search.sameEffectHardTargetEffects`，用于只在同卡/同效果但目标字段不同且搜索分差不足时回退 hard 目标。自然筛查结果不推广：`targetGateA`（margin=3000，STEAL_PROPERTY/FORCED_DEAL）200 局为 120/200 = 60.0%，低于 `buildingA` 600 约 -4.0pp；`targetGateB`（margin=1000，仅 STEAL_PROPERTY）100 局为 58/100 = 58.0%，低于 `buildingA` 600 约 -6.0pp。结论：不能静态信任 hard 的目标选择，下一步应提取“是否成套/破套/拿 wild/牺牲高杠杆牌”等结构化目标特征。
- **追加**：为 `STEAL_PROPERTY` 候选摘要加入结构化目标字段（目标、卡牌、颜色、价值、我方成套增益、对手成套损失、是否 wild），并新增默认关闭的 `monopoly.search.stealCompletionGainMultiplier`、`monopoly.search.stealOppCompletionLossMultiplier`、`monopoly.search.stealTakeValueMultiplier`、`monopoly.search.stealWildBonus`。筛选 `stealFeatureA`（6/4/120/650）100 局为 58/100 = 58.0%，100/100 自然结束，0 forced，0 unknown；座位分裂明显（seat1 37/50，seat2 21/50），相对 `buildingA` 600 为 -6.0pp，promotion comparison failed。结论：强静态偷牌目标偏置不推广；结构化摘要和默认关闭开关保留给 trace、counterfactual 和后续条件/学习式目标排序。
- **追加**：继续测试“结构化目标是否在剪枝前丢失”。新增默认关闭的 `monopoly.search.structuredTacticalReservedCandidates` 与 `monopoly.search.structuredPrunePriorityWeight`：前者在正常剪枝后额外保留少量结构化高优先级的 `STEAL_PROPERTY`/`FORCED_DEAL`/`DEAL_BREAKER` 候选，后者只改变剪枝排序不增加搜索候选数。筛查均不推广：`structuredReserveA`（reserve=6）100 局为 63/100 = 63.0%，0 forced/unknown，但仍低于 `buildingA` 600 约 -1.0pp 且明显增加一层搜索成本；`structuredReserveB`（reserve=3）100 局为 58/100 = 58.0%；`structuredPruneA`（weight=20）100 局为 58/100 = 58.0%。结论：在线增加/重排战术候选没有带来稳健提升，下一步应转向 replay-trained 或条件化目标排序。
- **追加**：把 ranker/蒸馏候选特征升级为 `candidate-ranker-features-v4`：解析 `takeValue=`、`giveValue=`，并加入 `wild=true/false` 摘要特征；runtime 特征长度为 169，旧 v1/v2/v3 模型仍通过兼容截断加载。新增 `training/scripts/select_lookahead_replay_rows.py`，从带 memento 的 outcome trace 中筛选输局战术/高分差/同效果目标差异决策做 counterfactual replay。采集 40 局 v4 memento trace（27/40 胜，1112/1112 行带 memento），balanced 35 行 replay 与 all-eligible 48 行 replay 均无候选错误或 incomplete；但严格 counterfactual 标签只有 6/7 行，且 best effects 分别为 `DEPLOY`/`DEPOSIT`/`PASS_GO`，没有形成稳定的偷牌目标监督。结论：不训练/启用 v4 corrector，保留 v4 特征和筛选工具作诊断。
- **追加**：继续做三个默认关闭/参数化 screen。`wide24A`（`maxCandidates=24`）固定先后手 100 局为 61/100 = 61.0%，相对 `buildingA` 600 为 -3.0pp；`hardMargin100A`（`hardMargin=100`）固定先后手 100 局为 56/100 = 56.0%，hard gate 的 CI 下界失败，相对 `buildingA` 600 为 -8.0pp；均不推广。`buildingThreatA`（`threatWeight=1.35`、`maxOpponentWeight=1.02`）固定先后手扩到 600 局为 370/600 = 61.67%，600/600 自然结束、0 forced/unknown，但座位分裂明显（`lookahead,hard` 212/300，`hard,lookahead` 158/300），且低于 `buildingA` 600 的 384/600。自检发现这批固定先手协议与 `buildingA` 600 的 `randomizeFirstPlayer=true` 不一致，因此补跑 `buildingThreatA-rfp`：复用 `buildingA` 前 200 局 seedBase 且 `randomizeFirstPlayer=true`，结果为 129/200 = 64.5%，与 matched `buildingA` 前 200 局完全相同，promotion comparison 为 +0.0pp、p=1.0。结论：不改默认；后续 promotion 比较必须使用一致随机先手协议或显式 matched baseline。
- **追加**：继续审计 `buildingA` 剩余 tempo 输局。新增 `training/scripts/select_low_progress_deploy_replay_rows.py`，从带 memento 的输局 trace 中选择“lookahead 用低 `completionScore` 部署覆盖 hard 选择”的决策做 counterfactual replay。40 行 replay 干净完成（0 candidate error / 0 incomplete / 0 hard-choice resolution error），但结果 tied-heavy：source best 20、hard best 21、sourceBetter 6、hardBetter 6、same 28；严格筛出的 12 行标签中 10 行为 `DEPOSIT`、2 行为 `DEPLOY`，reward gap 均为 0，仅靠小 board-score gap 区分，因此不新增低进度部署惩罚规则。继续筛选两个已有参数：`rentNudgeA-rfp`（`rentActionBonus=450`）matched 200 局为 127/200 = 63.5%，相对 matched `buildingA` -1.0pp；`passGo3500A-rfp`（`passGoExpectedValue=3500`）matched 200 局为 129/200 = 64.5%，与 matched `buildingA` 完全持平。两者均不推广。自检记录：一次 `rentNudgeA` gate/comparison 因并行命令先于 summary 写入而失败，已确认文件存在后串行重跑，最终 artifact 有效。
- **追加**：针对非 play 决策补做 overflow discard 审计。新增默认关闭的 `monopoly.search.boardAwareOverflowDiscard`：启用时 `SearchLookaheadAiPlayStrategy` 用 board-aware retention score 选择超手牌丢弃，倾向保留成套属性、高影响动作和 `PASS_GO`，允许低进度首张属性早于节奏牌被丢弃；内部 `BlindHardRolloutStrategy` 仍返回 fallback，避免污染一层 rollout。筛选 `overflowA-rfp` 复用 matched `buildingA` 前 200 局随机先手 seed，结果为 131/200 = 65.5%，200/200 自然结束、0 forced/unknown，seat split 为 73/100 与 58/100；相对 matched `buildingA` 129/200 仅 +1.0pp，rough p≈0.834，promotion comparison failed。逐局配对 diff 显示 seat1 只翻 2 局、seat2 0 局变化，因此不推广，默认 `buildingA` 保持不变。
- **追加**：继续筛查 Just Say No 响应阈值。新增默认保持原行为的 `monopoly.search.responseTenantThreshold=3` 与 `monopoly.search.responseCounterThreshold=5`；默认值下 `SearchLookaheadAiPlayStrategy` 仍委托 `AiHeuristics`，只有显式非默认阈值才走 lookahead-local 响应判断，且 hard 对照不受影响。候选 `responseTenant4A-rfp`（tenant 阈值 4M，counter 仍 5M）matched 200 局为 128/200 = 64.0%，200/200 自然结束、0 forced/unknown，低于 matched `buildingA` 129/200；逐局 diff 为 seat1 0 局变化、seat2 仅 1 局从 lookahead 胜变 hard 胜，promotion comparison failed（-0.5pp，p≈0.917）。结论：不推广，简单提高 tenant Just Say No 阈值不是高杠杆方向。自检记录：gate/comparison 曾并行早于 summary 写入而失败，已在 summary 存在后串行重跑，最终 artifact 有效；后续 summary-dependent 工具必须串行。
- **追加**：继续筛查付款决策。新增默认关闭的 `monopoly.search.boardAwarePayment`：银行足够付款时仍只考虑银行牌，银行不足才枚举银行+地产合法支付组合，并用 `amountPaid + boardDamage` 惩罚拆完整/近完整套，验证“多付一点保套”是否有价值。粗筛 `paymentA-rfp` 只跑 matched 前 50+50 局：结果 71/100 = 71.0%，100/100 自然结束、0 forced/unknown，hard gate 通过；但同一前 100 局 `buildingA` 已是 70/100，逐局 paired lift 只有 +1（seat1 +1、seat2 +0），因此不扩到 200、不推广，默认 `buildingA` 保持不变。
- **追加**：为非 play 决策补齐 memento trace 覆盖。`GameController`/`GameContext`/`EffectStackOrchestrator`/`TurnFlowService` 在 `monopoly.search.trace.includeMemento=true` 时于 AI 响应、付款、overflow 弃牌前捕获 `GameSessionMemento`；`SearchLookaheadAiPlayStrategy` 记录 `JUST_SAY_NO`、`PAYMENT`、`OVERFLOW_DISCARD` 辅助 trace，并修正 JSN trace 候选，确保有可打 JSN 请求但最终选择 PASS 时仍包含 `PLAY_JSN` 候选。新增 `training/scripts/analyze_auxiliary_trace.py` 解析 memento 并静态审计支付/弃牌候选。10 局 smoke trace 377/377 行带 memento，包含 72 个 JSN、42 个 payment、3 个 overflow；payment fallback 与静态 best 不同 14/42 行（输局 6 行），overflow 不同 2/3 行（输局 1 行），JSN 中 62/72 为单候选 PASS。结论：trace 通路有效，下一步应优先做支付/overflow 的小规模 replay，而不是继续盲调默认参数；默认 `buildingA` 保持不变。
- **追加**：扩展 `CounterfactualReplayRunner` 支持辅助决策回放：`PAYMENT` 通过 `RESPONSE_PASS + paymentCardIds` 回放，`OVERFLOW_DISCARD` 通过候选 `DISCARD` 序列后结束回合，`JUST_SAY_NO` 支持打出免租或 PASS；新增 `monopoly.counterfactual.decisionKinds` 与 `maxCandidatesPerDecision` 控制回放范围。smoke 结果均干净：payment 3 行、overflow 全候选 3 行、JSN 10 行均为 0 candidateErrors / 0 incompleteCandidates。payment 高风险 7 行全候选 replay 中 source/hard best 只有 2/7，3 行 replay best 对 source/hard 有正 reward gap；overflow 3 行中 1 行 replay best 从 source loss 翻为 natural win。`select_counterfactual_training_rows.py` 同步识别辅助 `PAYMENT`/`OVERFLOW_DISCARD`/`JUST_SAY_NO`，并从 payment replay 选出 3 条 `PAYMENT->PAYMENT` counterfactual 标签。结论：辅助 replay 已可用于下一轮规则筛选或训练数据，不改默认 `buildingA`。
- **追加**：扩到 30 局辅助 memento trace（1336/1336 行带 memento，`PAYMENT=168`、`OVERFLOW_DISCARD=11`、`JUST_SAY_NO=276`），静态审计显示 payment fallback 与 best 不同 51/168 行（输局 25 行），overflow 不同 5/11 行（输局 2 行）。全候选 replay 干净完成：payment top25 为 0 candidateErrors / 0 incomplete，source/hard best 12/25，严格选出 5 条 `PAYMENT->PAYMENT` 标签；overflow top4 为 0 candidateErrors / 0 incomplete，source/hard best 2/4，严格选出 1 条标签且包含一次 source loss -> natural win 翻转。基于该信号筛查 `paymentB-rfp`（board-aware payment + complete-set penalty 8、near-set 1.5）的 matched 前 50+50 局，结果仍为 71/100，与 `paymentA-rfp` 完全相同，只比同 seed 前 100 局 `buildingA` 多 1 局且运行很慢；不推广。结论：辅助 replay 有价值，但当前严格标签太少，下一步应扩 counterfactual 标签或做学习式辅助 ranker，而不是继续静态支付惩罚调参。
- **追加**：补采 seat2 30 局辅助 trace（1274/1274 行带 memento，30/30 自然结束，lookahead 17/30），与 seat1 合并后得到 60 局、2610 行、`PAYMENT=316`、`OVERFLOW_DISCARD=25`、`JUST_SAY_NO=527`。seat2 风险行全候选 replay 干净：overflow top6 选出 2 条严格标签，payment top12 选出 5 条，payment tail9 再选出 5 条；合并旧标签后有 21 条严格辅助 counterfactual 标签（`PAYMENT=18`、`OVERFLOW_DISCARD=3`），无重复/冲突 decisionId。自检发现这些旧辅助标签虽然 replay-valid，但旧 trace context 不含运行时 ranker 需要的 MDSP `self`/`players`/`decision` 结构，不能直接喂给 `distill_dataset.py`；否则会训练在错误特征分布上。因此修正 `SearchLookaheadAiPlayStrategy` 的辅助 trace：响应、付款、overflow 现在使用和 `LocalRankerAiPlayStrategy` 一致的 DeepSeek/MDSP prompt JSON，再附加 memento。新增 `training/scripts/export_auxiliary_risk_rows.py` 标准化从 analysis 导出 replay 输入。3 局 trainable smoke 产出 31 条辅助行并通过 `distill_dataset.py --mode validate`。结论：旧 21 行只作为 replay 证据；后续训练必须基于新 train-valid trace 重新采样/回放。
- **追加**：基于修正后的 train-valid 辅助 trace 再采 30 局（seat1 15 局为 6/15，seat2 15 局为 8/15，30/30 自然结束；该小样本不作强度结论）。合并分析得到 1150 行，`PAYMENT=134`、`OVERFLOW_DISCARD=11`、`JUST_SAY_NO=223`，368 条辅助行全部通过 `distill_dataset.py --mode validate`。导出并回放 payment top20（2178 候选）和 overflow 全 4 行：两者均 0 candidateErrors / 0 incomplete；payment source/hard best 9/20，严格选出 4 条 train-valid `PAYMENT->PAYMENT` counterfactual 标签，overflow 严格选出 0 条。4 条 payment 标签通过 validator，并训练了一个 linear smoke 模型只验证导出/加载路径；样本过小且 reward gap 很弱，不作为 gameplay 候选。结论：新格式管线已经可训练，但当前 strict label pool 太小，下一步继续采 fresh train-valid payment 风险行，暂不推广辅助 ranker。
- **追加**：继续采第二批 fresh train-valid 辅助 trace（seat1 20 局为 13/20，seat2 20 局为 11/20，40/40 自然结束；仍只作数据采集，不作强度结论）。合并分析得到 1534 行，`PAYMENT=191`、`OVERFLOW_DISCARD=15`、`JUST_SAY_NO=296`，502 条辅助行全部通过 validator。新增 `training/scripts/compare_mixed_reports_by_seed.py` 做逐 `deckSeed` 对齐自检，并新增 `training/scripts/select_counterfactual_training_rows.py --require-reward-gap` 与 `training/scripts/export_auxiliary_risk_rows.py --all-kind-rows`。全量回放 191 条 PAYMENT 后，142 行 informative，source/hard best 为 156/191；8 个 candidate error / incomplete 集中在 2 个 direct-payment 决策，错误原因为 replay 分支用 `RESPONSE_PASS` 处理非响应窗口付款，选择器已按 `candidate_error` 排除，不污染训练标签。默认筛选得到 13 条 train-valid `PAYMENT->PAYMENT` 标签，严格 reward-gap 模式只剩 4 条；与上一批 4 条合并为 17 条并通过 validator。`LocalRankerAiPlayStrategy` 新增 `monopoly.localRanker.rankedDecisionKinds` allowlist，支持只在 `PAYMENT` 等辅助决策启用本地 ranker，默认仍只 rank `PLAY_CARD`，旧 `rankAuxiliaryDecisions=true` 仅在未配置 allowlist 时启用全部辅助决策。17 行 linear/MLP 模型只作训练/加载 smoke：样本过小且 MLP 明显过拟合，不做 gameplay 候选。参数筛查 `buildingB`（building 参数更激进）100 局为 70/100，但与 matched `buildingA` 前 100 局逐局完全相同（net +0），不扩跑、不推广。
- **追加**：修复 PAYMENT counterfactual replay 的最后一个状态缺口：当 tenant 已打出 Just Say No、landlord 正在 `LANDLORD_COUNTER` 时，旧 replay 错误地让 tenant 直接 `RESPONSE_PASS`，导致 2 个决策的 8 个候选报“当前未轮到该玩家响应”。新增 simulation-only 显式付款入口，回放时先按效果栈 active rent 结算指定 tenant 的 `paymentCardIds`，仍复用 `EffectStackResolver` 与显式付款验证。重跑 191 条 PAYMENT 得到 143 informative、0 candidateErrors、0 incomplete；标签数仍为默认 13 / 严格 reward 4，说明修复提升证据质量但没有扩大可训练池。新增 `HybridLookaheadPaymentRankerAiPlayStrategy` 与 runner 参数 `monopoly.mixedBattle.lookaheadPaymentRankerModel`，用于真实测试“`buildingA` 出牌 + PAYMENT ranker”。用 fixed 17 行 linear 模型跑 `paymentHybridLinearA` seat1 50 局，结果 22/50 = 44.0%，50/50 自然结束，hard gate 失败；与同 seedBase `buildingA` 前 30 局 matched diff 为 net -2（3 个 W->L、1 个 L->W），且耗时约 310 秒/50 局。不扩到 seat2 或 200 局，不推广；结论是 17 条 payment 标签太少且需要保守 override gate/更多 fresh 标签。
- **追加**：继续采第三批 fresh train-valid 辅助 trace（seat1 20 局为 14/20，seat2 20 局为 8/20，40/40 自然结束；仅作数据采集）。合并分析为 1560 行，`PAYMENT=188`、`OVERFLOW_DISCARD=17`、`JUST_SAY_NO=300`，全部带 memento。自检发现 decision trace 没有最终 outcome，旧 `analyze_auxiliary_trace.py` 把缺失 outcome 当 loss，因此升级为 schema v2：缺失 outcome 单独记 `outcomeMissing`；`export_auxiliary_risk_rows.py --natural-win false` 也不再匹配 unknown 行。第三批全量 replay 干净：PAYMENT 188 行为 0 candidateErrors / 0 incomplete，选出 21 条 `bestBeatsHard` 标签（严格 reward 6 条）；OVERFLOW 17 行为 0 candidateErrors / 0 incomplete，选出 6 条标签。与 fixed 17 行池合并为 44 行（`PAYMENT=38`、`OVERFLOW_DISCARD=6`）并通过 validator；但 44 行 linear/MLP 随机 session 验证 top1 仅 0.375，session-prefix block holdout 仅 0.300，29 行严格池 block holdout 也为 0.300。不做 gameplay screen，不推广；下一步仍是扩大 fresh 辅助标签或设计更窄的保守规则门。
- **追加**：进一步自审发现辅助 counterfactual 报告缺少每个候选的 `forceEndReason`，导致 `COUNTERFACTUAL_SNAPSHOT_LIMIT` 候选会被选择器当作完整候选按 `boardScore` 排序。`CounterfactualReplayRunner` 现在输出候选 `forceEndReason` 和 summary `forcedCandidates`；`select_counterfactual_training_rows.py` 默认拒绝缺少该字段的旧报告，并默认排除任何 forced 候选。用新报告复跑后暴露大量 forced 候选：auxtrain3 payment 675 个、auxtrain3 overflow 386 个、auxtrain2 payment 639 个，700 snapshot 上限也不能消除。旧 44/29 行辅助池因此标记为弃用。重建 clean-v2 池只剩 36 行（`PAYMENT=31`、`OVERFLOW_DISCARD=5`），prefix holdout linear top1 为 0.375；严格 reward-payment 池 11 行，holdout top1 为 0.000。组合 deterministic `boardAwarePayment+boardAwareOverflowDiscard` 在 matched 50+50 局为 71/100，但相对 `paymentA-rfp` 同种子净增益为 0，不扩跑、不推广。新增默认仍为 hard 的 `monopoly.counterfactual.rollForwardPolicy=hard|lookahead` 诊断开关；20 行 smoke 中 lookahead continuation 只把 forced 从 14 降到 13 且约 5 倍耗时，因此不扩跑。
- **领域**：AI / 评估 / 工具 / 测试 / 文档

#### 2026-05-26 — BuildingA outcome trace audit and cash-pressure screen

- **摘要**：对新默认 `buildingA` 做 120 局自然 outcome trace 审计，并增强 `training/scripts/analyze_lookahead_outcome_trace.py`：新增按 session 聚合的胜负、最终完成套数分桶、rows/session、输局末段动作分布和高置信 hard override 分布，避免被单局大量普通决策行误导。trace 样本为 68/120 = 56.67%，但 120/120 自然结束、0 forced、0 unknown，说明这批偏低不是终局解析或后端异常。输局 52 局中 45 局最终只有 0-1 套，高置信输局 override 多见 `BIRTHDAY->DEPLOY`、`RENT_DUAL->DEPLOY`、`RENT_DUAL->PASS_GO`。据此新增默认关闭的 `monopoly.search.birthdayExpectedPaidMultiplier`，筛选 `cashPressureA`（birthday=500、rent=720、debt=420）200 局为 124/200 = 62.0%，hard gate 通过但低于 `buildingA` 200/600，且座位差异为 70/100 vs 54/100；不推广，默认 `buildingA` 保持不变。
- **领域**：AI / 评估 / 工具 / 文档

#### 2026-05-26 — Natural win-rate promotion to buildingA local strong AI

- **摘要**：按当前实验周期将 AI 强度主口径回归为 seat-balanced 自然多局胜率，而不是 same-seed paired promotion；seed 字段和 paired 工具继续保留作复现/诊断。`SearchLookaheadAiPlayStrategy` 默认参数升级到 `buildingA`：`buildingActionBonus=900`、`buildingRentBonusValue=320`、`opponentBuildingThreatValue=260`。证据：`buildingA` 600 局对 `hard` 为 384/600 = 64.0%，95% CI 60.08%-67.74%，600/600 自然结束、0 forced、0 unknown，hard gate 通过；相对旧 default 600 为 +3.83pp、p≈0.171，相对 winnerparsefix 600 为 +2.83pp、p≈0.311，因此结论边界是“明确强于 hard，作为当前本地默认强 AI”，不是“统计显著强于所有旧 lookahead 样本”。同步保留 ablation 证据：`buildingActionOnlyA` 600 局为 367/600 = 61.17%，与 winnerparsefix 600 持平；`buildingValueOnlyA` 200 局为 112/200 = 56.0%，CI 下界 49.07%，hard gate 失败。paired 报告里的 tie 继续解释为同 seed 下 treatment/control 结果无差异，不是后端引擎平局。
- **领域**：AI / 评估 / 文档

#### 2026-05-26 — Natural outcome trace audit and building-action screen

- **摘要**：为避免继续盲调评分标量，给自然多局评估补上 lookahead 决策 outcome trace 能力。`GameController` 新增 `setLookaheadAiStrategyFactory(...)` 测试/仿真注入点；`MixedAiBattleExperimentRunner` 新增 `monopoly.mixedBattle.tracePath`、`traceMode`、`traceSchema=decision|outcome`，可在自然对局中记录 lookahead 的候选、选择、hard fallback、lookahead 分数和最终 outcome；`JsonlDecisionTraceSink` 修正双方都达到 3 套时的赢家解析，改为回退读取 `lastActionSummary`，并新增 `JsonlDecisionTraceSinkTest`。新增 `training/scripts/analyze_lookahead_outcome_trace.py` 审计输局决策。20 局 traceprobe 显示输局中有多次 `HOUSE->DEPLOY` / `HOUSE->DEPOSIT` hard override，因此筛选 `buildingA`（`buildingActionBonus=900`、`buildingRentBonusValue=320`、`opponentBuildingThreatValue=260`）。`buildingA` 200 局为 129/200 = 64.5%，hard gate 通过；相对 seeded default 200 为 +7.0pp 但 p≈0.151，未过推广比较；相对 winnerparsefix 200 为 -1.5pp、p≈0.753。结论：building 方向有信号但未证明更强，不改默认，后续需更大样本或更窄 building-only 参数复测。
- **追加复验**：扩到 600 局后，`buildingA` 为 384/600 = 64.0%，95% CI 60.08%-67.74%，600/600 自然结束且 hard gate 通过；相对 winnerparsefix 600 为 +2.83pp、p≈0.311，未达到 +3pp practical lift 和显著性门槛；相对 default 600 为 +3.83pp、p≈0.171，只有 practical lift 通过。结论维持：`buildingA` 明确强于 hard，但还不能作为新默认 champion；building 估值值得继续窄化复测。
- **领域**：AI / 评估 / 工具 / 测试 / 文档

#### 2026-05-26 — Seeded natural runner and set-tempo negative screen

- **摘要**：为自然多局评估补强自检能力，`MixedAiBattleExperimentRunner` 现在默认使用独立 per-game 种子并在报告中记录 `deckSeed`、`firstPlayerSeed`、`aiSeed`、`seedBase` 与 `seedPolicy`，同时在运行前后恢复 `monopoly.deck.seed`、`monopoly.firstPlayer.seed`、`monopoly.ai.seed`，便于后续异常局面回放；这不是 same-seed paired promotion，而是自然局可复现日志。seeded default sanity 200 局为 115/200 = 57.5%，hard gate 通过。筛选 `settempoA`（`completeSetValue=14500`、`nearCompleteValue=1150`、`cappedProgressValue=720`）200 局为 108/200 = 54.0%，hard gate 失败；相对 winnerparsefix 200 为 -12.0pp、p≈0.0143，相对 seeded default 为 -3.5pp、p≈0.481。结论：简单提高成套/近成套标量会让策略更脆，不推广；后续应做更窄的动作级规则或可回放输局审计。
- **领域**：AI / 评估 / 工具 / 文档

#### 2026-05-26 — Pass Go tempo screen and natural-game evaluation policy

- **摘要**：按当前实验周期继续使用自然多局胜率作为 AI promotion 主口径，same-seed/paired-seed 对照暂时保留为未来诊断方法，不作为当前主要强度结论来源；paired 报告中的 tie 也明确不是引擎平局，而是同 seed 下 treatment/control 的 focus-seat 自然胜负/排名无法分出差异。筛选 Pass Go 节奏候选：`passgoA`（`monopoly.search.passGoExpectedValue=2500`）200 局为 114/200 = 57.0%，hard gate 通过但相对 winnerparsefix 200 为 -9.0pp、p≈0.0644，不推广；`passgoB`（3200）200 局为 131/200 = 65.5%，相对 winnerparsefix 200 为 -0.5pp、p≈0.916，也不推广。默认 `passGoExpectedValue=4000` 保持不变；实验数据、gate、comparison、loss-analysis 均保留在 `training/data/models/evaluation/winrate/`。
- **领域**：AI / 评估 / 文档

#### 2026-05-26 — Tactical candidate reservation screen

- **摘要**：在防守标量实验失败后，改测动作级候选保留方向。`SearchLookaheadAiPlayStrategy` 新增默认关闭的 `monopoly.search.tacticalReservedCandidates`，用于在正常 top candidates 外额外保留 `DEAL_BREAKER`、`FORCED_DEAL`、`STEAL_PROPERTY` 和高进度 `DEPLOY` 候选，验证是否因为 pruning 丢掉关键 swing action。候选 `tacticalReserveA`（保留 6 个额外战术候选）200 局为 123/200 = 61.5%，虽然 hard gate 通过，但相对 winnerparsefix 200 为 -4.5pp、p≈0.349，且搜索成本更高。结论：不推广；保留默认关闭开关作为后续 ablation/debug 工具，下一步应改进特定 swing action 的评分/摘要或第三套完成/阻断规则，而不是简单评估更多动作。
- **领域**：AI / 评估 / 工具 / 文档

#### 2026-05-26 — Conditional threat AI screen negative result

- **摘要**：为避免继续全局提高防守权重，`SearchLookaheadAiPlayStrategy` 新增默认关闭的条件威胁实验开关：`monopoly.search.conditionalThreatWeight`、`conditionalThreatSelfMaxSets`、`conditionalThreatOpponentMinSets`、`conditionalThreatOpponentMinAlmost`。默认权重为 0，不改变当前 promoted lookahead 行为。候选 `condThreatA`（weight 0.45、我方最多 1 套、对手至少 2 套或 2 个 near-complete）200 局自然胜率仅 109/200 = 54.5%，95% CI 47.58%-61.25%，hard gate 失败；相对 winnerparsefix 200 为 -11.5pp、p≈0.0188。结论：当前条件威胁公式过于粗糙，会压制本应推进的局面，不推广；后续应转向动作级完成/偷取/阻断战术或候选排序，而不是继续堆威胁标量。
- **领域**：AI / 评估 / 工具 / 文档

#### 2026-05-26 — Defensive threat-weight AI screens

- **摘要**：增强 `training/scripts/analyze_mixed_winrate_losses.py`，为自然胜率输局分析增加胜/负局聚合特征、完成套数分桶和精确总表 median，用于避免只凭少量样例调参。winnerparsefix 600 局复查显示剩余 233 个输局中 199 个是 blowout，输局平均只有 0.67 套、7.39 张地产，而 hard 赢家平均 3.11 套、14.55 张地产。基于此筛选两个防守压力候选：ThreatA（`threatWeight=1.35`, `maxOpponentWeight=1.02`）200 局为 132/200 = 66.0%，与 winnerparsefix 200 完全持平，虽减少 0 套崩盘但无胜率提升；ThreatB（仅 `threatWeight=1.35`）为 122/200 = 61.0%，相对 winnerparsefix 200 为 -5.0pp。两者均不推广；保留诊断工具增强，后续若继续做防守应使用更有条件的威胁项，而不是继续全局提高 threat weight。
- **领域**：AI / 评估 / 工具 / 文档

#### 2026-05-26 — Lookahead 终局赢家识别修正与 600 局复验

- **摘要**：自检发现评估脚本已能在最终快照双方均达到 3 套时解析 `lastActionSummary` 的真实引擎赢家，但 `SearchLookaheadAiPlayStrategy.stateValue()` 仍只接受唯一 3 套玩家，导致模拟评分在少数终局候选上看不懂真实赢家。`SearchLookaheadAiPlayStrategy.naturalWinnerId()` 现已回退解析 `lastActionSummary`，并新增 `SearchLookaheadAiPlayStrategyTest` 覆盖唯一赢家、双方 3 套但 summary 指明赢家、无匹配 summary 仍 unknown 三种情况。多局胜率复验：200 局 winnerparsefix 为 132/200 = 66.0%，但扩到 600 局为 367/600 = 61.17%，95% CI 57.21%-64.98%；相对旧默认 600 局仅 +1.0pp、p≈0.723，不作为显著更强的新模型推广。该修正保留为正确性/一致性修复，默认 `SearchLookaheadAiPlayStrategy` 继续是当前 strong/local_strong。
- **领域**：AI / 评估 / 测试 / 文档

#### 2026-05-26 — 多局自然胜率作为当前 AI 主评估口径

- **摘要**：收尾当前 AI 实验周期并将强度评估回归为“足够多局自然对局胜率”。Same-seed / paired seed 对照暂时降级为诊断工具，用于解释具体回归或未来窄口径实验，不再作为当前 promotion 主指标。修正 `training/scripts/summarize_mixed_winrate.py` 的自然赢家判定：旧报告若最终快照出现双方均达到 3 套，优先解析 `lastActionSummary` 中的真实引擎赢家；默认 lookahead 旧 200 局基线因此从 120/199 + 1 unknown 修正为 121/200、0 unknown。`MixedAiBattleExperimentRunner` 增加自然赢家解析、运行参数记录和输局诊断字段；新增 `training/scripts/analyze_mixed_winrate_losses.py`。默认 lookahead 600 局自然胜率为 361/600 = 60.17%，95% CI 56.20%-64.01%，gate 通过；propertyTempoA 参数候选为 118/200 = 59.0%，虽通过 hard gate，但相对默认 200 局为 -1.5pp、相对默认 600 局为 -1.17pp，不推广。默认 `SearchLookaheadAiPlayStrategy` 继续作为当前 strong/local_strong。
- **领域**：AI / 评估 / 工具 / 文档

#### 2026-05-26 — 多局胜率 gate 与 remaining-turn rollout 筛选

- **摘要**：完善 `MixedAiBattleExperimentRunner` 的多局胜率报告：记录 `randomizeFirstPlayer`、运行时 `monopoly.search.*` / `monopoly.mixedBattle.*` 参数、每局唯一自然赢家，并在 `quiet=true` 时只打印摘要。新增 `training/scripts/check_mixed_winrate_gate.py` 和 `training/scripts/compare_mixed_winrate_summaries.py`，把“强于 hard”的自然胜率门槛与“是否值得替换默认 lookahead”的候选对比拆开判断。200 局自然胜率 gate 结果：默认 lookahead 120/199 = 60.3% 通过；cf72 MLP 123/200 = 61.5% 通过但相对默认仅 +1.2pp、p≈0.806，不推广；`rolloutRemainingTurn=true, rolloutPolicy=search` 为 122/200 = 61.0%，相对默认 +0.7pp、p≈0.886，不推广；`rolloutPolicy=hard` 为 117/200 = 58.5%，相对默认 -1.8pp，不推广。默认 `SearchLookaheadAiPlayStrategy` 仍是当前 strong/local_strong。
- **领域**：AI / 评估 / 工具 / 文档

#### 2026-05-26 — Lossprobe v2 与多局胜率评估口径切换

- **摘要**：完成 29 个 core loss seed 的 train/holdout 拆分，采集 15 个训练输局的 431 条 memento trace，并通过 `CounterfactualReplayRunner` 生成 72 条高置信 counterfactual label。训练 cf72 MLP/linear corrector 后，MLP 在 14 个 holdout loss 上相对默认 lookahead 为 7/0/7，但对 hard 的绝对结果仍只有 0/7/7；线性模型无实际改善。80 对 broad same-seed AB 中，cf72 MLP 对默认为 5/7/68，不能推广。新增 `training/scripts/summarize_mixed_winrate.py`，把后续直观强度评估切回多局自然胜率；修正 `MixedAiBattleExperimentRunner` 将 `AI-Lookahead-*` 标成 `lookahead`，并新增 `monopoly.mixedBattle.quiet` 以便长跑评估。多局胜率 200 局结果：默认 lookahead 120/199 = 60.3%，cf72 MLP 123/200 = 61.5%，差距太小且 corrector 更慢，不推广，默认 `SearchLookaheadAiPlayStrategy` 仍是当前 strong/local_strong。
- **领域**：AI / 评估 / 工具 / 文档

#### 2026-05-26 — Lookahead 失败样本审计与 correction 负结果

- **摘要**：新增 `training/scripts/analyze_paired_report_failures.py`，用于审计 paired report 中的 rank-only 输局、自然胜负签名、end reason 与大分差样本。复查 lookahead rent600 核心 600 对：rank-only 为 92/29/479，1200/1200 `NATURAL_WIN`，说明 29 个 `W->L` 输局不是 snapshot 预算问题。对 8 个可复现固定先手输局做 outcome trace 与 counterfactual replay，187 个决策中 hard 选择略优（hardBetterThanSource 19 vs sourceBetterThanHard 13），未发现单一明显局部 bug。`SearchLookaheadAiPlayStrategy` 新增默认关闭的 tactical/wild correction JVM knobs，以及默认仍为 `ACTION` 的 `monopoly.search.correctorAllowedActionTypes`。`hardMargin=100`、tacticalA、wildA 都未能修复 stress seeds，`hardMargin=100` 在 40 对同 seed 直比仅 1/0/39，暂不推广。复查旧 learned corrector 时，直接对默认 lookahead 的同 seed treatment 直比为 0/0/80、0/0/80、5/9/86、3/5/72，说明旧 corrector 对 hard 的间接好成绩不能证明强于默认 lookahead。新训练的 40 行 lossprobe cf40 MLP corrector 在训练来源 seat2 输局上为 4/0/4，但未训练 seat1 输局仅 1/0/3 且有更差分差，记录为探索性信号，不推广。
- **领域**：AI / 测试 / 工具 / 文档

#### 2026-05-26 — Lookahead remaining-turn rollout 与 snapshot 预算审计

- **摘要**：新增 `training/scripts/compare_paired_treatments.py`，用于在同一批 seed 上直接比较两个 paired report 的 treatment focus-seat rank-only 结果。审计确认 `maxSnapshotsPerGame=340` 下 default lookahead 的 fixed/random expanded baselines 均为 80/80 `NATURAL_WIN`，因此当前大量 paired tie 不是预算不足，而是同 seed 下 focus seat 自然胜负状态和 board rank 没变；分数继续只作诊断。测试 `monopoly.search.rolloutRemainingTurn=true` + `monopoly.search.rolloutPolicy=search` 后，虽然 40 对 smoke 为 10/1/29，但 expanded 80 对 versus hard 仅 11/5/64；再与 default lookahead 做同 seed treatment 直比，fixed 为 2/5/33、random-first 为 2/1/37，合计 4/6/70，不推广。默认 `SearchLookaheadAiPlayStrategy` 仍是当前唯一可作为 strong/local_strong 的稳健本地 AI。
- **领域**：AI / 测试 / 工具 / 文档

#### 2026-05-26 — Action-gate DAgger v13/v14 负结果与 sourcePolicy 特征审计

- **摘要**：采集 v8 action-gate A 的 fixed/random 各 20 对 memento trace（共 1169 条 `PLAY_CARD`，全部含 memento），用 lookahead teacher 重标为 `lookahead_dagger_actiongateA`；v13 直接 MLP（8569 行、listwise one-hot、actiongateA multiplier 3）离线 top-1/MRR 达 0.765/0.858，但 40 对 fixed+random rank-only smoke 仅 3/5/32，不推广。审计发现 DAgger traces 带有运行时本地 ranker 不存在的 `sourcePolicy` 特征，故 `training/scripts/distill_dataset.py` 新增 `--drop-source-policy-features` 并训练 v14；v14 离线 top-1/MRR 0.767/0.859，但 40 对 smoke 为 1/5/34，也不推广。结论：最终分数继续只作诊断，下一步不再单纯堆 DAgger MLP，应转向 learned override gate、lookahead residual/corrector 或轻量搜索。
- **领域**：AI / 训练 / 测试 / 工具 / 文档

#### 2026-05-26 — Local ranker action-specific hybrid gate 初筛

- **摘要**：`LocalRankerAiPlayStrategy` 新增按动作类别配置的 hybrid margin（global/deploy/deposit/passGo/swingAction/cashAction 等），允许低置信动作回退 hard，同时保留高价值偷/换/抢动作的覆盖能力；新增 `training/scripts/analyze_local_ranker_trace.py` 审计 hard override 分布，并为 paired runner 增加 `monopoly.pairedBattle.progressEvery` 进度输出。基于 v6 trace，local ranker 覆盖 hard 205/562 次，override margin 中位数 1.350，低 margin 覆盖多集中在部署颜色、Pass Go、偷牌目标等噪声决策。v8 action-gate A（global 0.5、deploy/deposit 1.0、passGo 1.5、swingAction 0.1、cashAction 0.5）80 对 smoke 为 4/1/75，fixed 1/0/39、random-first 3/1/36；方向健康但 decisive pair 太少，不能推广为最终强模型。
- **领域**：AI / 测试 / 工具 / 文档

#### 2026-05-26 — Action-gate B/C 手调筛选

- **摘要**：继续筛选 v8 action-gate：B（global 0.35、deploy 0.8、passGo 1.2、swingAction 0.0、cashAction 0.35）fixed 20 对为 2/0/18，但 random-first 20 对为 1/2/17、平均分差 -205.05，说明放宽过多会重新伤随机先手；C（沿用 A，仅 swingAction 0.0）combined 40 对为 1/0/39，安全但比 A 更 tie-heavy。结论：A 仍是当前最稳的 runtime gate，但手调 margin 已接近边际，下一步应采集 A 的 memento trace 交给 lookahead teacher 重标，而不是继续静态调参。
- **领域**：AI / 测试 / 文档

#### 2026-05-26 — V10/V11 distilled student 筛选

- **摘要**：训练 `lookahead-student-v10-dagger1956-mid-softscore-listwise-mlp`，使用 soft-label mix 0.55 与 random-first DAgger multiplier 3.3；虽然离线 top-1/MRR 升至 0.725/0.833，但 fixed-seat 20 对 rank-only 结果为 2/5/13、自然胜 9/12，不推广。随后生成 `lookahead-student-v11-v6v8-ensemble`（v6 权重 0.55、v8 权重 0.45），40 对 smoke 汇总为 5/2/33、decisive rate 0.714；但 expanded fixed-seat 40 对为 2/4/34，fixed 60 对汇总为 3/4/53、自然胜 25/26，不推广。该块再次确认 board score 只能作诊断：v11 fixed 40 平均分差 +151.375，但 rank-only paired margin 为 -2。
- **领域**：AI / 训练 / 测试 / 文档

#### 2026-05-26 — V12 conservative ensemble 未通过固定先手筛选

- **摘要**：新增 `lookahead-student-v12-v6v8-ensemble`（v6 权重 0.70、v8 权重 0.30），尝试更保守地借用 v8 的 random-first 修复。fixed-seat 20 对 smoke 为 2/3/15、自然胜 11/12、paired margin -1；虽然平均分差 +68.15、中位分差 +85.0，但 rank-only 仍为负，因此不继续 random-first 扩样本。结论是 v8 的修复不能通过静态线性 ensemble 安全合入 v6。
- **领域**：AI / 测试 / 文档

#### 2026-05-26 — AI paired gate 移除同排名分数 tie-break

- **摘要**：回应最终分数被 Deal Breaker / 整套被偷放大的高方差问题，`PairedSeedPolicyExperimentRunner` 改为同 natural-win 状态且同 board rank 时直接判 tie，board score 只保留为诊断字段；`training/scripts/summarize_paired_reports.py` 与 `training/scripts/summarize_ai_robustness.py` 可对旧报告重算 rank-only paired outcome，并保留 legacy score-tiebreak 计数；`training/scripts/check_ai_robustness_gate.py` 改用 decisive rank-only pairs 的胜率、CI、margin 和样本数做硬门槛。重算后 lookahead/search champion 仍以 357/134/1029、decisive rate 0.727 通过 gate；v6 student 降为 33/19/108 且 gate 失败；v7 DAgger student 虽离线 top-1 0.743，但 random-first 40 对为 5/9/26，不推广。
- **领域**：AI / 测试 / 文档

#### 2026-05-26 — V8 soft-score student 修复 random-first 但固定先手退化

- **摘要**：训练 `lookahead-student-v8-dagger1956-randomfirst-w4-softscore-listwise-mlp`，使用与 v7 相同的 7400 条数据，但 listwise 目标改为 lookahead `candidateScores` 软标签（temperature 4000、mix 0.7）。v8 在 random-first 80 对 rank-only 评估中达到 12/2/66、decisive rate 0.857，且全部自然结束；但 fixed-seat 40 对降为 4/7/29、自然胜 16/19，说明软标签修复了随机先手弱点但过度偏向该分布。v8 不推广，下一步应降低 random-first DAgger 权重或 soft-label mix 后同时筛选 random-first 与 fixed-seat。
- **领域**：AI / 训练 / 测试 / 文档

#### 2026-05-26 — V9 balanced soft-score student 折中失败

- **摘要**：训练 `lookahead-student-v9-dagger1956-balanced-softscore-listwise-mlp`，将 soft-label mix 从 0.7 降到 0.35，并把两批 random-first DAgger source multiplier 从 4.0 降到 2.5。v9 fixed-seat 20 对 smoke 为 2/1/17、自然胜 9/8，较 v8 的 fixed-seat 退化有所缓解；但 random-first 20 对降为 2/3/15、自然胜 11/12，丢掉 v8 的 random-first 修复。v9 不推广；下一步应做 mix 0.45-0.6、random-first multiplier 3.0-3.5 的小网格，并要求每个候选同时过 fixed-seat 与 random-first smoke。
- **领域**：AI / 训练 / 测试 / 文档

#### 2026-05-26 — AI robustness gate 改以 same-seed paired 结果为主指标

- **摘要**：针对 Monopoly Deal 末局 Deal Breaker / 整套被偷导致 final board score 重尾波动的问题，`training/scripts/summarize_ai_robustness.py` 明确输出旧版 `same_seed_paired_focus_result` 为主指标，并补充 paired treatment rate、paired Wilson interval、paired margin、score/rank delta 分布；`training/scripts/check_ai_robustness_gate.py` 默认只用 paired outcome/rate/margin/CI 作为硬门槛，自然胜率与平均分差降级为显式开启的辅助检查。该旧版仍用 board score 打破同排名平局，已被更新的 rank-only gate 取代；保留本条用于历史追踪。
- **领域**：AI / 测试 / 文档

#### 2026-05-26 — Strong 本地 AI 成为正式 HVM 难度入口

- **摘要**：`HVM` 的 `aiDifficulty` 新增 `STRONG` / `LOOKAHEAD` / `SEARCH` 别名，统一映射到 `SearchLookaheadAiPlayStrategy`；CUSTOM 错误提示、Web 客户端下拉、JavaFX 下拉和 WebSocket 协议文档同步暴露 strong/local_strong/lookahead/search 入口，方便演示和本地对战直接使用当前通过 paired robustness gate 的强本地 AI。
- **领域**：AI / 协议 / UI / 测试 / 文档

#### 2026-05-26 — V6 listwise student 成为新的最佳 distilled 候选

- **摘要**：使用与 v5 相同的 6838 条 lookahead/DAgger/counterfactual `PLAY_CARD` 数据，改用 listwise cross-entropy 训练 `lookahead-student-v6-dagger1394-randomfirst-w4-listwise-mlp`。离线 validation top-1 提升到 0.714、MRR 0.828；按当时旧 score-tiebreak 口径，random-first 80 paired seeds 汇总为 44/33/3，fixed seat 40 对为 23/17，4-player sanity 40 对为 25/15。后续 rank-only gate 复算后 v6 降为 random-first 10/9/61、multiscenario 33/19/108，仍是最佳 distilled student 候选但不能支撑“确定强于 hard”的说法；该说法仍保留给 `SearchLookaheadAiPlayStrategy`。
- **领域**：AI / 训练 / 测试 / 文档

#### 2026-05-26 — DAgger 重标注产出当前最强 distilled student 候选

- **摘要**：为解决 pure lookahead self-play 数据没有覆盖 student 实战状态的问题，`LocalRankerAiPlayStrategy` 新增显式开关 `monopoly.localRanker.trace.includeMemento`，可在本地 ranker trace 中写入完整 memento；`TraceRelabeler` 新增 `teacher=lookahead`，通过 `LookaheadDecisionTeacher` 恢复 memento 并用 `SearchLookaheadAiPlayStrategy` 对原 legal candidate envelope 重标。采集 v3 student 的 795 条实战状态后，lookahead 重标 795/795 成功，student/teacher 分歧 199/795。训练 `lookahead-student-v4-dagger795-w4-mlp` 后，120 个固定 seat2 paired seeds 汇总为 treatment/control/tie = 77/38/5，paired treatment rate 0.642（95% CI 0.553-0.722），median score delta +90；随机先手 40 对为 20/19/1 但自然胜 29/22，seat1 40 对为 28/10/2。该模型升级为当前最强 distilled student 候选，但最终“强于 hard”仍需更广 robustness gate。
- **领域**：AI / 训练 / 测试 / 文档

#### 2026-05-26 — Random-first DAgger v5 未修复随机先手弱点

- **摘要**：补跑 v4 random-first 第二批 40 paired seeds 后，80 对汇总为 treatment/control/tie = 34/39/7，paired margin -5，说明 v4 在随机先手场景不稳。随后采集 random-first 下 v4 student 的 599 条 memento trace，lookahead 重标 599/599 成功，分歧率 28.9%，训练 `lookahead-student-v5-dagger1394-randomfirst-w4-mlp`。但 v5 random-first 40 对评估为 17/22/1，median score delta -30.5，10% trimmed score delta -32.22，未过 paired gate；v5 不推广，v4 仍是当前最佳 distilled student，但“确定强于 hard”的最终说法仍应保留给通过完整 robustness gate 的 lookahead/search champion。
- **领域**：AI / 训练 / 测试 / 文档

#### 2026-05-26 — Fresh lookahead 数据暴露随机 session 验证偏差

- **摘要**：采集当前代码版 lookahead outcome trace（60 paired seeds、1646 条 `PLAY_CARD`、无 force-end、teacher 对 hard 为 37/21/2），并训练 `lookahead-student-v3-cf-nodeposit-w8-fresh60-mlp`。随机 session validation top-1 虽升至 0.781，但 paired gameplay 只有 13/13/4、自然胜 11/14、10% 截尾分差 -132.25，说明离线指标被同采集块分布偏差高估。`distill_dataset.py` 新增 `--split-by session-prefix`，按采集 run 前缀留出整块验证；fresh60 prefix holdout top-1 仅 0.513。该候选不升级，后续混合多批 trace 必须使用 block/prefix holdout 再过 paired gate。
- **领域**：AI / 训练 / 测试

#### 2026-05-26 — Paired AI 评估加入稳健分差指标

- **摘要**：针对 Monopoly Deal 末局偷整套等高方差事件会放大平均分差的问题，`PairedSeedPolicyExperimentRunner` 新增中位数分差、10% 截尾均值、P10/P90 与名次差统计；`training/scripts/summarize_paired_reports.py` 可对历史 paired 报告重算这些稳健指标，并支持 paired treatment rate、median score delta、trimmed score delta gate。重新汇总 `lookahead-student-v3-cf-nodeposit-w8` 的 130 paired seeds 后，paired treatment rate = 0.569、自然胜 66/55、median score delta +30，说明该 student 仍是最佳候选但证据未达到“稳健强于 hard”。
- **领域**：AI / 测试 / 文档

#### 2026-05-26 — Distillation 支持 listwise 排序损失

- **摘要**：`distill_dataset.py` 新增 `--loss-type listwise` 与可选 `--soft-label-source teacher_scores`，支持按同一决策的 legal candidates 做 softmax 排序训练，同时保持导出的 MLP JSON 兼容现有本地 ranker。`lookahead-student-v3-cf-nodeposit-w8-listwise-mlp` 离线 top-1 提升到 0.709，但 80 个 paired seeds 汇总为 treatment/control/tie = 41/36/3、paired treatment rate = 0.5125，未过轻量 gate；先记录为训练目标 ablation，不替换当前 BCE W8 best。
- **领域**：AI / 训练 / 测试

#### 2026-05-25 — Counterfactual 加权混合 student 实验

- **摘要**：`distill_dataset.py` 新增通用 `--source-multiplier SOURCE=WEIGHT`，并把 `counterfactual_replay` 设为最高 duplicate priority，支持少量高置信 counterfactual 标签在 lookahead imitation 数据中作为加权纠偏样本。训练 `lookahead-student-v3-cf-nodeposit-w8-mlp` 后，100 个 paired seeds 汇总为 treatment/control/tie = 56/37/7、平均分差 +226.61；该 student 是当前最佳 distilled 候选，但尚未达到 lookahead champion 的 robustness 证明强度。
- **领域**：AI / 测试 / 文档

#### 2026-05-25 — Counterfactual replay 修复旧存档超时污染

- **摘要**：修复离线 counterfactual 回放恢复旧 memento 后触发玩家对局 wall-clock 超时的问题；回放工具现在在恢复存档后重置 simulation clock，避免候选动作被错误标记为失败。补充 20 行 smoke 报告，确认 `candidateErrors=0`、`incompleteCandidates=0`，并记录该数据源目前信号较稀疏，不能盲目作为强训练标签。
- **领域**：AI / 测试 / 文档

#### 2026-05-25 — Lookahead 设为当前强本地 AI 基线

- **摘要**：刷新 `lookahead` / `search` 本地策略的 robustness gate，并在 AI 交付文档中明确当前可安全宣称强于 `hard` 的本地对手是 `SearchLookaheadAiPlayStrategy`，不是 distilled MLP student；新增 v3 tactics student 训练与实战复验记录，保留其作为实验候选。`LocalRankerAiPlayStrategy` 默认只接管已训练的 `PLAY_CARD`，`PAYMENT` / `JUST_SAY_NO` / `OVERFLOW_DISCARD` 回退 hard，辅助决策 ranking 需显式开启 `-Dmonopoly.localRanker.rankAuxiliaryDecisions=true`。
- **领域**：AI / 测试 / 文档

#### 2026-05-25 — Paired runner 支持冠军策略直接对照

- **摘要**：`PairedSeedPolicyExperimentRunner` 保持旧的 `monopoly.pairedBattle.llmStrategy` 兼容，同时新增 `controlLlmStrategy` / `treatmentLlmStrategy` 与对应本地 ranker 模型路径参数，支持在同一批 seed、同一席位下直接比较 `hard,llm(lookahead)` 与新的 DeepSeek/local ranker 候选，而不是只分别对 hard 做间接比较。
- **领域**：AI / 测试

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

- **摘要**：新增 `simulation/` 包与 `SimulationBatchRunner`，支持多局真实后端规则并发推进、AI 决策进入 `DecisionBroker` 微批、DeepSeek 批量标注、JSONL 样本落盘；`AI_VS_AI` 增加可注入策略工厂以便模拟时替换为 `BrokeredAiPlayStrategy`；新增 `training/scripts/distill_dataset.py`、采集脚本、训练计划与训练日志；新增纯 Java `LocalLinearRankerAiPlayStrategy` 与本地学生模型对战评估脚本。
- **领域**：AI / 模拟采集 / 测试 / 架构文档

#### 2026-04-18 — 文档整理

- **摘要**：合并原 `docs/architecture/project-structure.md` 至 `docs/architecture/uml_source.md`，删除重复「包结构」段落；新增本工程协作文档 `docs/ENGINEERING.md`。未修改 `README.md` 与 `docs/requirements/requirements.md`。
- **领域**：文档结构

---

## 3. 维护约定

- **协议变更**：修改 `MessageDispatcher` / `GameServer` 等消息分支时，**同步更新** [`websocket-protocol.md`](interface/websocket-protocol.md)（与同一次变更一并提交）。
- **规则/牌堆真源**：以 [`rules.md`](../rules.md) 附录 A 与 `MonopolyDealCardFactory` 为准；课程需求与实现不一致时，在 [`requirement-trace-and-deviations.md`](implementation/requirement-trace-and-deviations.md) 中写明。
- **架构说明**：目录与 UML 只维护 **`uml_source.md` 一处**，避免双份漂移。
