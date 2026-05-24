# AI Training Log

This log records reproducible distillation runs and their evidence.

## 2026-05-24 Local Smoke

Purpose: prove the end-to-end no-cost pipeline works on the Mac.

Command:

```bash
scripts/run_distillation_smoke.sh
```

Evidence:

- Dataset: `data/distillation/smoke.jsonl` (ignored by Git)
- Rows: 91 decisions
- Sessions: 4
- Teacher source: `local_heuristic`
- Decision mix:
  - `PLAY_CARD`: 71
  - `PAYMENT`: 9
  - `JUST_SAY_NO`: 6
  - `OVERFLOW_DISCARD`: 5
- Average legal candidates: 7.60
- Model artifact: `models/distillation/smoke/candidate_ranker.pt` (ignored by Git)
- Device: Apple `mps`
- Feature dimension: 119
- Validation top-1: 0.722 on a tiny heuristic-teacher smoke set
- Validation MRR: 0.800
- First-candidate baseline: 0.278
- Random expected top-1: 0.184
- Linear JSON ranker export: passed.
- Java local-ranker battle smoke: passed with `scripts/evaluate_local_ranker.sh`.
- JSON gameplay evaluation runner: passed with `scripts/evaluate_distilled_ranker.sh`.
- Quality report generation: passed with `scripts/report_distillation_quality.py`.

Interpretation:

This is not a quality model. It proves that legal-state generation, JSONL validation, feature extraction, MPS training, metrics, and artifact writing all work.
The local heuristic teacher avoids the earlier first-candidate label bias, but it is still only a baseline.

## 2026-05-24 DeepSeek Probe

Purpose: verify the paid teacher path before running a larger overnight collection.

Command:

```bash
mvn -q exec:java \
  -Dexec.mainClass=com.monopoly.tools.SimulationBatchRunner \
  -Dmonopoly.ai.decisionDelayMs=0 \
  -Dmonopoly.simulation.teacher=deepseek \
  -Dmonopoly.simulation.games=1 \
  -Dmonopoly.simulation.parallel=1 \
  -Dmonopoly.simulation.playerCounts=2 \
  -Dmonopoly.simulation.maxSnapshots=4 \
  -Dmonopoly.simulation.batchSize=2 \
  -Dmonopoly.simulation.batchWaitMs=10 \
  -Dmonopoly.simulation.runtimeSeconds=20 \
  -Dmonopoly.deepseek.maxTokens=2048 \
  -Dmonopoly.simulation.tracePath=/tmp/monopoly-deepseek-probe.jsonl
```

Evidence:

- Rows: 1 decision
- Dataset validation: pass
- Teacher source: `deepseek`
- Teacher model metadata: `deepseek-v4-flash`
- Candidate count: 8
- Raw response shape: `{"decisions":[{"decisionId":"...","choiceId":"c2"}]}`

Interpretation:

DeepSeek batch labeling is wired correctly. Larger collection can use `scripts/collect_deepseek_distillation.sh`; train with `--include-sources deepseek` so transient fallback rows are excluded from the primary student model.
The DeepSeek API key must come from `DEEPSEEK_API_KEY` or `-Dmonopoly.deepseek.apiKey=...`; source code intentionally has no embedded key.

## Next Overnight Run

Recommended first paid run:

```bash
DEEPSEEK_API_KEY=... \
scripts/overnight_distillation_run.sh \
  data/distillation/deepseek-run1.jsonl \
  models/distillation/deepseek-run1
```

Equivalent manual collection command:

```bash
MONOPOLY_SIM_GAMES=200 \
MONOPOLY_SIM_PARALLEL=8 \
MONOPOLY_SIM_PLAYER_COUNTS=2,3,4,5 \
MONOPOLY_SIM_BATCH_SIZE=24 \
MONOPOLY_SIM_MAX_BY_KIND=PLAY_CARD:20000,PAYMENT:5000,JUST_SAY_NO:5000,OVERFLOW_DISCARD:5000 \
MONOPOLY_SIM_MAX_SNAPSHOTS=240 \
DEEPSEEK_API_KEY=... \
scripts/collect_deepseek_distillation.sh data/distillation/deepseek-run1.jsonl
```

Trace paths are protected by default. If `data/distillation/deepseek-run1.jsonl` already exists, collection exits before spending tokens. Set `MONOPOLY_TRACE_MODE=append` only when intentionally resuming the same run, or `MONOPOLY_TRACE_MODE=overwrite` when intentionally replacing it.

Then train:

```bash
scripts/train_distilled_rankers.sh \
  data/distillation/deepseek-run1.jsonl \
  models/distillation/deepseek-run1
```

Primary report paths:

- `models/distillation/deepseek-run1-dataset_manifest.json`
- `models/distillation/deepseek-run1-quality_report.md`
- `models/distillation/deepseek-run1-mlp/metrics.json`
- `models/distillation/deepseek-run1-mlp/gameplay_vs_hard.json`
- `models/distillation/deepseek-run1-linear/candidate_ranker_linear.json`

Minimum quality gates before trusting the student:

- Dataset validation passes.
- `byTeacherSource.deepseek` is at least 95% of rows.
- At least 5k labeled decisions for the first meaningful model.
- All four decision kinds appear.
- Rare decision kinds should each have enough rows for validation; use `MONOPOLY_SIM_MAX_BY_KIND` to avoid `PLAY_CARD` dominating paid collection.
- Validation top-1 beats first-candidate baseline and random baseline.
- Java local-ranker battle smoke completes without invalid-action loops.
- Gameplay win-rate evaluation is added before replacing the current AI in normal play.

## 2026-05-24 Local Baseline Run

Purpose: create a larger no-cost baseline dataset and verify trainer/evaluator behavior without a DeepSeek key.

Command:

```bash
mvn -q compile exec:java \
  -Dexec.mainClass=com.monopoly.tools.SimulationBatchRunner \
  -Dmonopoly.deepseek.enabled=false \
  -Dmonopoly.ai.decisionDelayMs=0 \
  -Dmonopoly.simulation.teacher=heuristic \
  -Dmonopoly.simulation.games=48 \
  -Dmonopoly.simulation.parallel=8 \
  -Dmonopoly.simulation.playerCounts=2,3,4,5 \
  -Dmonopoly.simulation.maxSnapshots=100 \
  -Dmonopoly.simulation.batchSize=24 \
  -Dmonopoly.simulation.batchWaitMs=50 \
  -Dmonopoly.simulation.runtimeSeconds=20 \
  -Dmonopoly.simulation.tracePath=/tmp/monopoly-local-baseline.jsonl

MONOPOLY_MIN_TRAIN_ROWS=2000 \
MONOPOLY_TRAIN_SOURCES=local_heuristic \
MONOPOLY_TRAIN_EPOCHS=8 \
MONOPOLY_EVAL_GAMES=4 \
MONOPOLY_EVAL_PLAYERS=3 \
MONOPOLY_EVAL_SNAPSHOTS=100 \
scripts/train_distilled_rankers.sh \
  /tmp/monopoly-local-baseline.jsonl \
  /tmp/monopoly-local-baseline-model
```

Evidence:

- Dataset: `/tmp/monopoly-local-baseline.jsonl`
- Rows: 2453 decisions
- Sessions: 48
- Teacher source: `local_heuristic`
- Decision mix:
  - `PLAY_CARD`: 1916
  - `PAYMENT`: 409
  - `JUST_SAY_NO`: 93
  - `OVERFLOW_DISCARD`: 35
- Average legal candidates: 8.75
- MLP validation top-1: 0.764
- MLP validation MRR: 0.865
- First-candidate baseline: 0.299
- Random expected top-1: 0.212
- Linear validation top-1: 0.650
- Java linear ranker battle smoke: passed.
- JSON gameplay evaluation: 4/4 games hit the 100-snapshot limit; natural win rate 0.0.
- Quality report: `/tmp/monopoly-local-baseline-model-quality_report.md`
- MLP model: `/tmp/monopoly-local-baseline-model-mlp/candidate_ranker.pt`
- Java-loadable MLP model: `/tmp/monopoly-local-baseline-model-mlp/candidate_ranker_mlp.json`
- Java-loadable linear model: `/tmp/monopoly-local-baseline-model-linear/candidate_ranker_linear.json`

Interpretation:

This is a useful baseline and pipeline stress test, not a production opponent. The imitation metrics are strong against the heuristic teacher. The Java-loadable MLP export runs in backend battles, but the local student still did not finish games within the small 100-snapshot evaluation window. DeepSeek labels and stronger evaluation are still required before replacing the normal AI.

## 2026-05-24 Java MLP Runtime

Purpose: avoid being limited to the weaker linear JSON model.

Evidence:

- `scripts/distill_dataset.py` now exports `candidate_ranker_mlp.json`.
- `LocalRankerAiPlayStrategy` loads both `monopoly-deal-mlp-ranker-v1` and `monopoly-deal-linear-ranker-v1`.
- `LocalRankerAiPlayStrategy` implements `AiChoiceAdvisor`, so the same Java model now ranks `PLAY_CARD`, `PAYMENT`, `JUST_SAY_NO`, and `OVERFLOW_DISCARD` candidates at runtime.
- MLP JSON smoke artifact: `/tmp/monopoly-mlp-json-check/candidate_ranker_mlp.json`
- MLP JSON shape: `119 -> 238 -> 119 -> 1`
- Java battle smoke with MLP JSON: passed.
- JSON gameplay evaluation with MLP JSON: passed; 2/2 games hit the 40-snapshot limit, natural win rate 0.0.
- Targeted Java tests for play, payment, overflow discard, and Just Say No local-ranker entry points: passed.
- Full Maven test suite: passed at `2026-05-24 06:20 CST`.

Interpretation:

The backend can now use the stronger MLP student format without Python at runtime, and the runtime decision surface matches the four decision kinds collected by the simulator. The next important quality step is paid DeepSeek collection and longer gameplay evaluation; the current local baseline is still heuristic-labeled.

## 2026-05-24 Multi-Session Backend Check

Purpose: confirm that one backend process can host multiple independent games, which is required for efficient offline collection and possible future server-side multi-room play.

Evidence:

- `SimulationBatchRunner` runs many `SimulationWorker` instances in one JVM. Each worker owns a distinct `GameController`; every controller owns an isolated `GameEngineSingleton.createIsolated()` deck engine.
- A shared `DecisionBroker` collects decisions from those independent games and batches them into one teacher call stream.
- `GameServer` keeps a `sessions` map keyed by `sessionId` and routes commands to the matching controller.
- `GameServerMultiSessionIsolationTest`: passed. It verifies that `STATE_UPDATE` messages for `room-a` are not sent to `room-b`, and vice versa.
- Network regression group: passed after the broadcast fallback was tightened for real multi-controller sessions.

Interpretation:

The data collector should use one backend/JVM with many independent controllers plus one broker, not many backend processes. This keeps all legal-state generation inside the same Java rule engine while still allowing DeepSeek requests to be batched across games.

## 2026-05-24 Overnight Script Smoke

Purpose: verify the one-command overnight pipeline without a DeepSeek key.

Command:

```bash
MONOPOLY_OVERNIGHT_MODE=local \
MONOPOLY_SIM_GAMES=4 \
MONOPOLY_SIM_PARALLEL=2 \
MONOPOLY_SIM_MAX_SNAPSHOTS=40 \
MONOPOLY_SIM_RUNTIME_SECONDS=10 \
MONOPOLY_MIN_TRAIN_ROWS=20 \
MONOPOLY_TRAIN_EPOCHS=2 \
MONOPOLY_EVAL_GAMES=1 \
MONOPOLY_EVAL_SNAPSHOTS=20 \
MONOPOLY_EVAL_OPPONENT_STRATEGY=hard \
scripts/overnight_distillation_run.sh \
  /tmp/monopoly-overnight-smoke.jsonl \
  /tmp/monopoly-overnight-smoke-model
```

Evidence:

- Dataset rows: 82
- Sessions: 4
- Teacher source: `local_heuristic`
- Decision mix:
  - `PLAY_CARD`: 60
  - `PAYMENT`: 15
  - `OVERFLOW_DISCARD`: 4
  - `JUST_SAY_NO`: 3
- MLP device: `mps`
- MLP validation top-1: 0.688
- MLP validation MRR: 0.818
- First-candidate baseline: 0.375
- Random expected top-1: 0.206
- Quality report: `/tmp/monopoly-overnight-smoke-model-quality_report.md`
- Java-loadable MLP: `/tmp/monopoly-overnight-smoke-model-mlp/candidate_ranker_mlp.json`
- Ranker-seat vs Hard smoke: `/tmp/monopoly-overnight-smoke-model-mlp/gameplay_vs_hard.json`, 1/1 game hit the 20-snapshot smoke limit.

Interpretation:

The overnight wrapper is runnable end-to-end. This smoke is intentionally tiny and local-teacher only, so it is not a model-quality claim. It proves that, once a DeepSeek key is present, the same script can produce a trace, train Java-loadable students, run ranker-seat gameplay evaluation, and write the quality report.

## 2026-05-24 Dataset Manifest and Session Split

Purpose: make dataset quality and validation metrics more trustworthy for overnight runs.

Changes:

- `scripts/distill_dataset.py` now defaults to `--split-by session`, so train/validation are separated by whole game session instead of only by decision id.
- `scripts/dataset_manifest.py` writes a dataset manifest with SHA-256, row count, source mix, decision coverage, player-count coverage, round coverage, and candidate-count percentiles.
- `scripts/train_distilled_rankers.sh` and `scripts/overnight_distillation_run.sh` write `<output-prefix>-dataset_manifest.json`.

Evidence:

- Python unit test: `python3 -m unittest tests.test_distill_dataset` passed.
- Manifest smoke on `/tmp/monopoly-overnight-smoke.jsonl`: SHA-256 `a96f700f5d8611893536e8d3adc6fc9aab17920db104a4c1597221f233107670`, 82 rows, 4 sessions, all four decision kinds covered.
- Session-split training smoke: `splitBy=session`, 510 train examples, 141 validation examples, validation top-1 0.737 on the tiny local heuristic dataset.

Interpretation:

Future DeepSeek metrics should be treated as more credible than the earlier decision-level split, because adjacent states from the same simulated game no longer leak across train and validation by default.

## 2026-05-24 KNN Offline Baseline

Purpose: add a no-dependency non-neural baseline for model-family comparisons and possible paper ablations.

Changes:

- `scripts/distill_dataset.py --model-type knn` evaluates a nearest-neighbor candidate ranker over the same 119-dim candidate features.
- `scripts/train_distilled_rankers.sh` now writes `<output-prefix>-knn/metrics.json`.
- `scripts/report_distillation_quality.py` accepts multiple `--metrics` files and renders a model-comparison table.

Evidence:

- KNN smoke on `/tmp/monopoly-overnight-smoke.jsonl`: passed, `splitBy=session`, no sklearn dependency required.
- Multi-metrics report smoke: passed with MLP + KNN metrics and rendered a `Model Comparison` table.
- Full `scripts/train_distilled_rankers.sh` smoke on `/tmp/monopoly-overnight-smoke.jsonl`: passed with MLP, linear, KNN, dataset manifest, Java gameplay vs Hard, and multi-model quality report.
- Example comparison from the smoke report: MLP validation top-1 0.684, linear 0.263, KNN 0.579.

Interpretation:

The KNN baseline is not intended for Java gameplay runtime. It gives us a traditional-model comparator against MLP and linear students, useful for deciding whether feature quality or neural capacity is the bottleneck.

## 2026-05-24 Collection Quotas

Purpose: control label mix during paid collection.

Changes:

- `DecisionBroker` now accepts per-decision-kind record quotas.
- `SimulationBatchRunner` reads `-Dmonopoly.simulation.maxByKind=PLAY_CARD:...,PAYMENT:...,JUST_SAY_NO:...,OVERFLOW_DISCARD:...`.
- `collect_deepseek_distillation.sh` and `overnight_distillation_run.sh` expose this as `MONOPOLY_SIM_MAX_BY_KIND`.
- `SimulationBatchRunner` defaults `-Dmonopoly.simulation.traceMode=fail_if_exists`; wrappers expose it as `MONOPOLY_TRACE_MODE`.

Interpretation:

This is mainly a cost-control feature. When one kind reaches its quota, the broker uses a local fallback to keep the game moving but does not record or send that decision to DeepSeek. This should improve the paid dataset's balance without corrupting game legality.

Trace path protection is a data-quality feature. It prevents a repeated command from appending new rows into an old JSONL file and making quota/manifest numbers look larger than the current run actually produced.

## 2026-05-24 Strict Trace Validation

Purpose: fail before training if a trace contains structurally valid JSON but inconsistent game context.

Changes:

- `scripts/distill_dataset.py --mode validate` now checks game meta against the request, self/player identity consistency, candidate-id consistency between `request.candidates` and `context.decision.legalCandidates`, duplicate visible card ids, and basic payload legality for play/payment/discard decisions.
- `scripts/train_distilled_rankers.sh` already runs this validation first, so strict trace validation is now part of every training run.

Evidence:

- Python validation unit tests cover valid rows, duplicate visible cards, missing hand cards in play candidates, and legal-candidate mismatch.
- Fresh quota smoke trace `/tmp/monopoly-quota-fresh-1779580305.jsonl` passed strict validation with 12 rows and all four decision kinds covered.

## 2026-05-24 Local Baseline Artifact

Command:

```bash
MONOPOLY_OVERNIGHT_MODE=local \
MONOPOLY_SIM_GAMES=60 \
MONOPOLY_SIM_PARALLEL=8 \
MONOPOLY_SIM_PLAYER_COUNTS=2,3,4,5 \
MONOPOLY_SIM_MAX_SNAPSHOTS=160 \
MONOPOLY_SIM_RUNTIME_SECONDS=25 \
MONOPOLY_MIN_TRAIN_ROWS=500 \
MONOPOLY_TRAIN_EPOCHS=5 \
MONOPOLY_EVAL_GAMES=5 \
MONOPOLY_EVAL_PLAYERS=3 \
MONOPOLY_EVAL_SNAPSHOTS=160 \
MONOPOLY_EVAL_OPPONENT_STRATEGY=hard \
scripts/overnight_distillation_run.sh \
  data/distillation/local-baseline-20260524-080604.jsonl \
  models/distillation/local-baseline-20260524-080604
```

Artifacts:

- Trace: `data/distillation/local-baseline-20260524-080604.jsonl`
- Manifest: `models/distillation/local-baseline-20260524-080604-dataset_manifest.json`
- Report: `models/distillation/local-baseline-20260524-080604-quality_report.md`
- MLP: `models/distillation/local-baseline-20260524-080604-mlp/candidate_ranker_mlp.json`
- Linear: `models/distillation/local-baseline-20260524-080604-linear/candidate_ranker_linear.json`

Result:

- 4774 rows from 60 real backend sessions.
- Decision mix: `PLAY_CARD=3690`, `PAYMENT=840`, `JUST_SAY_NO=178`, `OVERFLOW_DISCARD=66`.
- Player-count mix covers 2, 3, 4, and 5 players.
- MLP validation top-1 against the local heuristic teacher: 0.827.
- Linear validation top-1: 0.659.
- KNN validation top-1: 0.393.
- Gameplay vs hard after longer evaluation: 12 games, 12 natural finishes within 400 snapshots, ranker seat won 4/12, natural win rate 1.0 for completed games overall, average snapshots 204.9, average ranker board rank 2.17, board lead rate 0.333.
- Gameplay artifact: `models/distillation/local-baseline-20260524-080604-mlp/gameplay_vs_hard_12x400.json`.
- The refreshed manifest/report include token usage fields. This local baseline has no DeepSeek usage metadata, so `rowsWithUsage=0`; paid traces should populate these fields.
- Artifact package smoke passed with `scripts/package_distillation_artifacts.sh models/distillation/local-baseline-20260524-080604 /tmp/local-baseline-artifacts.tar.gz`.
- Cost estimator smoke passed on the local manifest; it reports zero usage for local labels as expected.
- Paid preflight missing-key check passed: it exits with code 2 before running collection when no DeepSeek key is configured.
- Paid probe missing-key check passed: `scripts/run_paid_probe.sh` exits with code 2 through the preflight step before spending tokens.
- Windows/5090 handoff package smoke passed: `scripts/package_training_handoff.sh data/distillation/local-baseline-20260524-080604.jsonl models/distillation/local-baseline-20260524-080604 /tmp/local-baseline-training-handoff.tar.gz`.

Interpretation:

This is a pipeline artifact, not a DeepSeek-quality dataset. It proves collection, strict validation, session split, MLP/linear/KNN training, Java-loadable model export, gameplay evaluation, manifest generation, and quality reporting all work on the Mac.

## 2026-05-24 Kind-Balanced Local Baseline

Purpose: keep rare but strategically important decision kinds from being drowned by common `PLAY_CARD` rows during student training.

Command:

```bash
MONOPOLY_TRAIN_SOURCES=local_heuristic \
MONOPOLY_MIN_TRAIN_ROWS=1000 \
MONOPOLY_TRAIN_EPOCHS=5 \
MONOPOLY_TRAIN_BALANCE_BY_KIND=true \
MONOPOLY_EVAL_GAMES=6 \
MONOPOLY_EVAL_PLAYERS=3 \
MONOPOLY_EVAL_SNAPSHOTS=300 \
MONOPOLY_EVAL_OPPONENT_STRATEGY=hard \
scripts/train_distilled_rankers.sh \
  data/distillation/local-baseline-20260524-080604.jsonl \
  models/distillation/local-baseline-20260524-080604-balanced
```

Evidence:

- Training now records `balanceByKind`, `kindBalanceMax`, and `kindWeights` in `metrics.json`.
- Default weights on the 4774-row local baseline: `PLAY_CARD=1.0`, `PAYMENT=2.095914`, `JUST_SAY_NO=4.0`, `OVERFLOW_DISCARD=4.0`.
- MLP validation top-1: `0.830`, MRR: `0.904`.
- Per-kind MLP validation top-1:
  - `PLAY_CARD`: `0.831` on 720 validation decisions.
  - `PAYMENT`: `0.922` on 167 validation decisions.
  - `JUST_SAY_NO`: `0.543` on 35 validation decisions.
  - `OVERFLOW_DISCARD`: `0.471` on 17 validation decisions.
- Compared with the unbalanced local MLP on the same split, `OVERFLOW_DISCARD` improved from `0.412` to `0.471`; `PAYMENT` improved from `0.916` to `0.922`; overall top-1 stayed essentially flat (`0.827` to `0.830`).
- Gameplay vs hard: 6 games, all natural/limit-normal end reason, average 213.7 snapshots, average ranker board rank 2.00, board lead rate 0.333.
- Quality report: `models/distillation/local-baseline-20260524-080604-balanced-quality_report.md`.

Interpretation:

Kind balancing is now a sensible default for paid training. It slightly improves rare-kind imitation without harming common play-card accuracy on the local baseline. Keep `MONOPOLY_TRAIN_BALANCE_BY_KIND=false` available for paper-style ablations.

## 2026-05-24 Multi-Session Save/Load Isolation

Purpose: close the remaining gap in one-backend/many-room support.

Changes:

- `GameServer` now stores pending save and load votes by normalized `sessionId`, instead of using one global pending vote.
- Explicit `payload.sessionId` that does not map to an active room no longer silently falls back to the default controller.
- `SessionRegistry` can rebind all connections from the old room id to the loaded save's `sessionId` after `LOAD_GAME`.
- `GameController.pushCurrentState(...)` gives the server a public way to refresh the loaded room after rebinding.

Evidence:

- `mvn -q test -Dtest=GameServerSaveLoadIntegrationTest,GameServerMultiSessionIsolationTest` passed.
- New regression: two rooms can start independent `LOAD_GAME` votes; completing room A does not send `LOAD_GAME_RESULT` to room B or complete room B's pending vote.

Interpretation:

One JVM is now a better fit for batched data collection and future multi-room play. The collection runner already used isolated controllers; this fixes the normal WebSocket server's pending confirmation state to match that model.

## 2026-05-24 Player-Count Generalization Split

Purpose: add a stronger evaluation mode for paper-style experiments.

Changes:

- `scripts/distill_dataset.py --split-by player-count` now holds out configured player counts for validation.
- `--validation-player-counts` defaults to `4,5`, so the intended experiment is 2/3-player training and 4/5-player validation.
- `scripts/train_distilled_rankers.sh` exposes this via `MONOPOLY_TRAIN_SPLIT_BY=player-count` and `MONOPOLY_VALIDATION_PLAYER_COUNTS=4,5`.
- Metrics and quality reports now record the split type and held-out player counts.

Evidence:

```bash
python3 scripts/distill_dataset.py \
  data/distillation/local-baseline-20260524-080604.jsonl \
  --output-dir /tmp/player-count-split-smoke \
  --include-sources local_heuristic \
  --min-rows 1000 \
  --epochs 1 \
  --split-by player-count \
  --validation-player-counts 4,5 \
  --balance-by-kind
```

Result:

- Train decisions: 2322 from 2/3-player games.
- Validation decisions: 2452 from 4/5-player games.
- One-epoch MLP smoke validation top-1: `0.696`, MRR: `0.815`.
- Per-kind validation top-1: `PLAY_CARD=0.669`, `PAYMENT=0.839`, `JUST_SAY_NO=0.618`, `OVERFLOW_DISCARD=0.410`.

Interpretation:

This mode is not a production model run; it proves the evaluation split works. Use it after the first DeepSeek run to measure whether the distilled model learned general Monopoly Deal principles or only memorized table-size-specific patterns.

## 2026-05-24 Scaling Subset Tool

Purpose: make label-efficiency curves reproducible without paying the teacher multiple times.

Changes:

- Added `scripts/make_scaling_subsets.py`.
- Added `scripts/run_scaling_curve.sh`.
- Subsets are deterministic, cumulative, and stratified by `decisionKind,playerCount` by default.
- Each subset keeps the original JSONL row payload unchanged.

Evidence:

```bash
python3 scripts/make_scaling_subsets.py \
  data/distillation/local-baseline-20260524-080604.jsonl \
  --output-dir /tmp/monopoly-scaling-subsets \
  --sizes 100,500,1200 \
  --prefix local-baseline
```

Result:

- Source rows: 4774.
- Generated subsets: 100, 500, and 1200 rows.
- The 100-row subset covered all four decision kinds and all four player counts.
- The 500-row subset manifest passed `scripts/dataset_manifest.py`, with all decision kinds covered.
- Python tests: `python3 -m unittest tests.test_distill_dataset` passed.

Interpretation:

After a paid DeepSeek run, use this tool to produce 1k/5k/20k/100k cumulative subsets and train the scaling curve from one paid trace. This is both cheaper and more defensible than recollecting separate traces for each curve point.

## 2026-05-24 Gameplay Matrix Evaluation

Purpose: move from one-off gameplay checks to a reusable evaluation matrix across player counts and opponent strengths.

Changes:

- Added `scripts/evaluate_gameplay_matrix.sh`.
- Added `scripts/summarize_gameplay_matrix.py`.
- Training handoff packages now include matrix evaluation and scaling-curve scripts.

Smoke command:

```bash
MONOPOLY_MATRIX_GAMES=1 \
MONOPOLY_MATRIX_SNAPSHOTS=80 \
MONOPOLY_MATRIX_PLAYERS=2,3 \
MONOPOLY_MATRIX_OPPONENTS=easy,hard \
MONOPOLY_MATRIX_SEATS=1 \
scripts/evaluate_gameplay_matrix.sh \
  models/distillation/local-baseline-20260524-080604-balanced-mlp/candidate_ranker_mlp.json \
  /tmp/monopoly-gameplay-matrix-smoke
```

Evidence:

- Generated 4 condition JSON files and `/tmp/monopoly-gameplay-matrix-smoke/summary.md`.
- Summary grouped results by opponent and player count.
- Smoke used 80-snapshot games; all 4 hit the snapshot limit, which is expected for this tiny runtime check.

Interpretation:

This is the evaluation harness to use after paid DeepSeek training. Reportable runs should use at least 50 games per condition for cheap checkpoints and 200+ games per condition for stronger claims.

## 2026-05-24 Forest-Style Offline Baseline

Purpose: add a non-neural model-family comparator without requiring sklearn.

Changes:

- `scripts/distill_dataset.py --model-type forest` trains a small bagged linear ensemble over random feature subsets.
- `scripts/train_distilled_rankers.sh` trains forest metrics by default; set `MONOPOLY_TRAIN_FOREST=false` to skip it.
- Artifact packaging includes `<output-prefix>-forest/metrics.json` when present.

Smoke command:

```bash
python3 scripts/distill_dataset.py \
  data/distillation/local-baseline-20260524-080604.jsonl \
  --output-dir /tmp/forest-smoke-stable3 \
  --include-sources local_heuristic \
  --min-rows 1000 \
  --model-type forest \
  --forest-trees 4 \
  --forest-features-per-tree 16 \
  --balance-by-kind
```

Result:

- Validation top-1: `0.491`.
- Validation MRR: `0.664`.
- Per-kind validation top-1: `PLAY_CARD=0.436`, `PAYMENT=0.731`, `JUST_SAY_NO=0.457`, `OVERFLOW_DISCARD=0.529`.
- No sklearn dependency.

Interpretation:

This is not intended for Java runtime play. It gives the report a traditional-model comparator beyond linear and KNN, which helps diagnose whether gains come from neural capacity or from feature/label quality.

## 2026-05-24 Trace Audit

Purpose: fail fast after the first paid DeepSeek probe if the trace is not worth scaling.

Changes:

- Added `scripts/audit_distillation_trace.py`.
- `scripts/run_paid_probe.sh` writes `<output-prefix>-trace_audit.json` and requires token usage metadata.
- `scripts/overnight_distillation_run.sh` writes `<output-prefix>-trace_audit.json`.
- Paid preflight now compiles the audit script.

Checks:

- minimum row count
- preferred teacher-source ratio
- all decision kinds covered
- rare decision kind row counts
- valid `choiceId`
- duplicate `decisionId`
- first-choice bias
- token usage metadata

Smoke command:

```bash
python3 scripts/audit_distillation_trace.py \
  data/distillation/local-baseline-20260524-080604.jsonl \
  --preferred-source local_heuristic \
  --min-rows 1000 \
  --min-rare-kind-rows 1 \
  --output /tmp/local-baseline-trace-audit.json
```

Result:

- Audit passed on the local baseline.
- Warning: no token usage metadata, expected for local heuristic labels.
- First-choice ratio: `34.3%`, below the default bias gate.

Interpretation:

For a paid DeepSeek probe, treat trace-audit failure as a stop signal. Fix prompt parsing, source ratio, token usage, or collection quotas before spending on overnight-scale labels.

## 2026-05-24 Trace Merge Gate

Purpose: support multiple probes, overnight runs, machines, or accounts without training on duplicate or contradictory labels.

Changes:

- Added `scripts/merge_distillation_traces.py`.
- Paid preflight now compiles the merge script.
- Training handoff packages include the merge script.

Behavior:

- Preserves raw JSONL rows in first-seen order.
- Filters teacher source with `--include-sources`, for example `deepseek`.
- Deduplicates by `request.decisionId`.
- Skips canonical JSON-equivalent duplicate decisions.
- Fails by default when the same `decisionId` has different JSON content.
- Writes a merge report with source/kind/player-count/session coverage and output SHA-256.

Smoke command:

```bash
python3 scripts/merge_distillation_traces.py \
  data/distillation/local-baseline-20260524-080604.jsonl \
  data/distillation/local-baseline-20260524-080604.jsonl \
  --output /tmp/local-baseline-merged.jsonl \
  --report /tmp/local-baseline-merged-report.json
```

Interpretation:

Run merge before audit and training whenever there is more than one trace file. Do not override conflicts during paid-data training unless the exact source of the disagreement has been inspected.

## 2026-05-24 Training Readiness Gate

Purpose: make the final handoff status explicit instead of relying on scattered artifacts.

Changes:

- Added `scripts/check_training_readiness.py`.
- `scripts/train_distilled_rankers.sh` now writes `<output-prefix>-trace_audit.json` before `<output-prefix>-readiness.json`.
- Artifact and training handoff packages now include readiness reports and carry trace audits when available.
- Paid preflight compiles the readiness script.

Gate semantics:

- `--mode local` accepts local heuristic labels and verifies trace coverage, manifest, quality report, metrics, Java JSON model, and gameplay evaluation.
- `--mode production` additionally requires 95%+ `deepseek` rows, token usage metadata, production quality report status, trace audit success, and completed gameplay evaluation.
- Gameplay readiness now checks `evaluatedGames` or the `games[]` length, not natural-win `completedGames`. Use `--require-natural-gameplay` or `MONOPOLY_READINESS_REQUIRE_NATURAL_GAMEPLAY=true` when natural finishes should be mandatory.

Local baseline check:

```bash
python3 scripts/check_training_readiness.py \
  data/distillation/local-baseline-20260524-080604.jsonl \
  models/distillation/local-baseline-20260524-080604-balanced \
  --mode local \
  --min-rows 1000 \
  --min-rare-kind-rows 1 \
  --min-gameplay-games 6 \
  --output models/distillation/local-baseline-20260524-080604-balanced-readiness.json
```

Result:

- Local readiness passed: 4774 rows, all four decision kinds, MLP validation top-1 `0.830`, completed 6 gameplay games.
- Production readiness on the same trace failed as intended: `deepseek_source_ratio=0.0%`, no token usage, no production trace audit.
- A follow-up smoke run with `MONOPOLY_TRAIN_FOREST=false`, 1 epoch, and 1 gameplay evaluation passed end-to-end, proving the train script now writes trace audit before readiness even when the Java evaluation hits the snapshot limit. It also fixed Bash 3.2 empty-array handling in the training and DeepSeek collection wrappers.

Interpretation:

Use readiness as the last check before saying a dataset/model package is deliverable. Local readiness proves the Mac pipeline works; production readiness proves the requested DeepSeek-quality training dataset is actually present.

## 2026-05-24 Guarded DeepSeek Production Wrapper

Purpose: reduce the chance of accidentally spending on a large paid collection before inspecting the probe.

Changes:

- Added `scripts/run_deepseek_production_pipeline.sh`.
- The wrapper requires a DeepSeek key and starts with `scripts/run_paid_probe.sh`.
- Default mode stops after the probe and prints the exact resume command.
- Large collection requires `MONOPOLY_PAID_CONFIRM=run-paid-overnight`.
- Confirmed mode collects the main trace, merges probe and main DeepSeek rows, audits the merged trace, trains students, runs production readiness, and writes artifact plus Windows handoff archives.
- Paid preflight and Windows handoff packages now include the wrapper.

Validation:

- Missing-key guard exits before collection.
- Shell syntax is covered by `bash -n scripts/*.sh`.

Interpretation:

This is the command to use when the API key is available and the goal is a production-ready package. It is intentionally conservative: a run is not deliverable unless `<output-prefix>-readiness.json` has `"mode": "production"` and `"ready": true`.

## 2026-05-24 Training Run Summary

Purpose: make a completed run easy to inspect without opening every JSON artifact by hand.

Changes:

- Added `scripts/summarize_training_run.py`.
- `scripts/train_distilled_rankers.sh` now writes `<output-prefix>-run_summary.md` and `<output-prefix>-run_summary.json` after readiness passes.
- Artifact packages and Windows/5090 handoff packages include the run summary.
- Paid preflight compiles the summary script.

Summary contents:

- readiness status and failed checks
- trace audit status and failed checks
- row/session/source/kind/player-count coverage
- validation top-1, MRR, first-candidate baseline, and random baseline
- model type, device, input dimension, epochs, split, and kind balancing
- gameplay requested/evaluated/natural games, board-rank metrics, and end reasons
- token usage and artifact paths

Interpretation:

Read `<output-prefix>-run_summary.md` first after any overnight or production run. For local heuristic data it can be `local-ready`, but production claims still require `production-ready`.

## 2026-05-24 Seed Replicate Utilities

Purpose: make paper-style claims less dependent on one random train/validation split or one neural initialization.

Changes:

- `scripts/train_distilled_rankers.sh` now accepts `MONOPOLY_TRAIN_SEED` and passes it through to MLP, linear, KNN, and forest training.
- Added `scripts/run_seed_replicates.sh` to run the standard training pipeline across comma-separated `MONOPOLY_SEEDS`.
- Added `scripts/summarize_seed_runs.py` to aggregate run summaries into `<output-prefix>-seed_summary.md` and `.json`.
- Paid preflight and Windows/5090 handoff packages include the seed scripts.

Example:

```bash
MONOPOLY_SEEDS=11,42,73 \
MONOPOLY_TRAIN_SOURCES=deepseek \
MONOPOLY_MIN_TRAIN_ROWS=5000 \
scripts/run_seed_replicates.sh \
  data/distillation/deepseek-run1-merged.jsonl \
  models/distillation/deepseek-run1
```

Interpretation:

Use the seed summary for paper/report tables when the trace is production-ready. If time only allows one seed, mark the result as a single-seed checkpoint rather than a robust comparison.

## 2026-05-24 Local Multi-Seed Baseline On Mac

Purpose: produce a real Mac-side training/evaluation baseline while no DeepSeek API key is configured.

Command:

```bash
MONOPOLY_SEEDS=11,42,73 \
MONOPOLY_TRAIN_SOURCES=local_heuristic \
MONOPOLY_MIN_TRAIN_ROWS=1000 \
MONOPOLY_TRAIN_EPOCHS=6 \
MONOPOLY_TRAIN_BATCH_SIZE=256 \
MONOPOLY_TRAIN_FOREST=false \
MONOPOLY_EVAL_GAMES=4 \
MONOPOLY_EVAL_PLAYERS=3 \
MONOPOLY_EVAL_SNAPSHOTS=260 \
scripts/run_seed_replicates.sh \
  data/distillation/local-baseline-20260524-080604.jsonl \
  models/distillation/local-baseline-20260524-080604-multiseed
```

Dataset:

- Trace: `data/distillation/local-baseline-20260524-080604.jsonl`
- Rows: 4774
- Sessions: 60
- Teacher source: `local_heuristic`
- Decision kinds: `PLAY_CARD` 3690, `PAYMENT` 840, `JUST_SAY_NO` 178, `OVERFLOW_DISCARD` 66
- Player counts: 2/3/4/5 all present

Artifacts:

- `models/distillation/local-baseline-20260524-080604-multiseed-seed_summary.md`
- `models/distillation/local-baseline-20260524-080604-multiseed-seed_summary.json`
- `models/distillation/local-baseline-20260524-080604-multiseed-gameplay_matrix.md`
- `models/distillation/local-baseline-20260524-080604-multiseed-gameplay_matrix.json`
- Per-seed prefixes: `models/distillation/local-baseline-20260524-080604-multiseed-seed11`, `seed42`, `seed73`

Result:

- All 3 seed runs passed local readiness.
- Production-ready runs: 0, because the trace is not DeepSeek-labeled and has no token usage metadata.
- MLP validation top-1 mean/std: 0.810 / 0.016.
- MLP validation MRR mean/std: 0.892 / 0.009.
- First-candidate baseline mean: 0.332.
- Random expected baseline mean: 0.218.
- Gameplay vs hard, 3-player, ranker seat 1: 12 games requested, 9 natural completions, ranker win rate 0.250, average ranker board rank 1.83, board lead rate 0.333.

Interpretation:

The Mac can train the current 119-feature MLP and export Java-loadable models. The local teacher is learnable in imitation metrics, but gameplay strength remains mixed and should not be presented as a DeepSeek-quality or robust strategic result. The next production step is still to provide a DeepSeek key, run the guarded production wrapper, then repeat the multi-seed summary on the merged DeepSeek trace.

## 2026-05-24 Offline Trace Relabel Tool

Purpose: reuse stored backend-legal candidate states when a DeepSeek key becomes available, so a paid probe can start without rerunning simulations.

Changes:

- Added `com.monopoly.tools.TraceRelabeler`.
- Added `scripts/relabel_distillation_trace.sh`.
- Added `scripts/run_relabel_paid_probe.sh` for the guarded paid-probe path from an existing trace.
- Paid preflight and Windows/5090 handoff packages include the relabel scripts.
- The DeepSeek mode refuses to start without a key and requires every output row to have `result.metadata.source=deepseek`; local fallback rows are treated as a failed relabel run.

Example:

```bash
DEEPSEEK_API_KEY=... \
scripts/run_relabel_paid_probe.sh \
  data/distillation/local-enhanced-20260524.jsonl \
  data/distillation/deepseek-relabel-probe.jsonl \
  models/distillation/deepseek-relabel-probe
```

Lower-level equivalent:

```bash
DEEPSEEK_API_KEY=... \
MONOPOLY_RELABEL_SELECT=true \
MONOPOLY_RELABEL_MAX_BY_KIND=PLAY_CARD:250,PAYMENT:120,JUST_SAY_NO:80,OVERFLOW_DISCARD:50 \
scripts/relabel_distillation_trace.sh \
  data/distillation/local-enhanced-20260524.jsonl \
  data/distillation/deepseek-relabel-probe.jsonl \
  models/distillation/deepseek-relabel-probe
```

No-cost smoke:

```bash
MONOPOLY_RELABEL_TEACHER=heuristic \
MONOPOLY_RELABEL_SELECT=true \
MONOPOLY_RELABEL_MAX_BY_KIND=PLAY_CARD:5,PAYMENT:5,JUST_SAY_NO:5,OVERFLOW_DISCARD:5 \
scripts/relabel_distillation_trace.sh \
  data/distillation/local-enhanced-20260524.jsonl \
  /tmp/monopoly-relabel-smoke.jsonl \
  /tmp/monopoly-relabel-smoke
```

Interpretation:

This is a collection accelerator, not a shortcut around the production gate. Use the enhanced local trace when it is available because it has better row count and rare-kind coverage than the older first baseline. The relabeled trace must still pass DeepSeek source ratio, token-usage, row-count, rare-kind coverage, training readiness, and gameplay evaluation before it can be called production data.

Selector smoke evidence:

- Command: `MONOPOLY_RELABEL_SELECT=true MONOPOLY_RELABEL_TEACHER=heuristic MONOPOLY_RELABEL_MAX_BY_KIND=PLAY_CARD:5,PAYMENT:5,JUST_SAY_NO:5,OVERFLOW_DISCARD:5 scripts/relabel_distillation_trace.sh data/distillation/local-baseline-20260524-080604.jsonl /tmp/monopoly-selected-relabel-smoke.jsonl /tmp/monopoly-selected-relabel-smoke`
- Selection report: `/tmp/monopoly-selected-relabel-smoke-selection_report.json`
- Rows selected: 20
- Decision mix: `PLAY_CARD` 5, `PAYMENT` 5, `JUST_SAY_NO` 5, `OVERFLOW_DISCARD` 5
- Player counts selected: 2-player 4, 3-player 5, 4-player 6, 5-player 5
- Sessions selected: 18
- Average legal candidates: 23.15

## 2026-05-24 Enhanced Local Baseline On Mac

Purpose: improve the no-cost local handoff while no DeepSeek API key is configured, especially rare `OVERFLOW_DISCARD` coverage.

Supplement collection command:

```bash
mvn -q compile exec:java \
  -Dexec.mainClass=com.monopoly.tools.SimulationBatchRunner \
  -Dmonopoly.deepseek.enabled=false \
  -Dmonopoly.ai.decisionDelayMs=0 \
  -Dmonopoly.simulation.teacher=heuristic \
  -Dmonopoly.simulation.games=80 \
  -Dmonopoly.simulation.parallel=8 \
  -Dmonopoly.simulation.playerCounts=2,3,4,5 \
  -Dmonopoly.simulation.maxSnapshots=180 \
  -Dmonopoly.simulation.batchSize=32 \
  -Dmonopoly.simulation.batchWaitMs=40 \
  -Dmonopoly.simulation.runtimeSeconds=25 \
  -Dmonopoly.simulation.maxByKind=PLAY_CARD:800,PAYMENT:500,JUST_SAY_NO:200,OVERFLOW_DISCARD:120 \
  -Dmonopoly.simulation.traceMode=fail_if_exists \
  -Dmonopoly.simulation.tracePath=data/distillation/local-supplement-20260524-overflow.jsonl
```

Merge and training commands:

```bash
python3 scripts/merge_distillation_traces.py \
  data/distillation/local-baseline-20260524-080604.jsonl \
  data/distillation/local-supplement-20260524-overflow.jsonl \
  --include-sources local_heuristic \
  --output data/distillation/local-enhanced-20260524.jsonl \
  --report models/distillation/local-enhanced-20260524-trace_merge.json

MONOPOLY_TRAIN_SOURCES=local_heuristic \
MONOPOLY_MIN_TRAIN_ROWS=5000 \
MONOPOLY_TRAIN_EPOCHS=6 \
MONOPOLY_TRAIN_BATCH_SIZE=256 \
MONOPOLY_TRAIN_FOREST=false \
MONOPOLY_EVAL_GAMES=4 \
MONOPOLY_EVAL_PLAYERS=3 \
MONOPOLY_EVAL_SNAPSHOTS=260 \
MONOPOLY_EVAL_OPPONENT_STRATEGY=hard \
scripts/train_distilled_rankers.sh \
  data/distillation/local-enhanced-20260524.jsonl \
  models/distillation/local-enhanced-20260524
```

Dataset:

- Trace: `data/distillation/local-enhanced-20260524.jsonl`
- Rows: 6372
- Sessions: 134
- Teacher source: `local_heuristic`
- Decision kinds: `PLAY_CARD` 4490, `PAYMENT` 1340, `JUST_SAY_NO` 378, `OVERFLOW_DISCARD` 164
- Player counts: 2-player 1417, 3-player 1670, 4-player 1628, 5-player 1657
- Merge report: `models/distillation/local-enhanced-20260524-trace_merge.json`
- Trace audit: `models/distillation/local-enhanced-20260524-trace_audit.json`

Result:

- Status: `local-ready`
- Production-ready: no, because every row is local heuristic and no token usage metadata is present.
- Single-run checkpoint: MLP validation top-1 0.815, MRR 0.896.
- Single-run per-kind validation top-1: `PLAY_CARD=0.804`, `PAYMENT=0.927`, `JUST_SAY_NO=0.609`, `OVERFLOW_DISCARD=0.792`.
- Single-run gameplay vs hard: 4 games requested/evaluated, 4 natural completions, ranker win rate 0.500, average ranker board rank 1.75, board lead rate 0.500.
- Artifact archive: `models/distillation/local-enhanced-20260524-artifacts.tar.gz`
- Windows handoff archive: `models/distillation/local-enhanced-20260524-training-handoff.tar.gz`

Interpretation:

This is now the preferred local baseline and the best current relabel source. It is good enough to prove collection, merge, audit, training, Java-loadable MLP export, gameplay evaluation, and handoff packaging on the Mac. It is still not DeepSeek-quality data and should not be used for production or paper strength claims.

## 2026-05-24 Enhanced Local Multi-Seed Baseline On Mac

Purpose: turn the enhanced local checkpoint into a more defensible Mac-side baseline by training three seeds and evaluating each Java-loadable MLP against the hard heuristic opponent.

Command:

```bash
MONOPOLY_SEEDS=11,42,73 \
MONOPOLY_TRAIN_SOURCES=local_heuristic \
MONOPOLY_MIN_TRAIN_ROWS=5000 \
MONOPOLY_TRAIN_EPOCHS=6 \
MONOPOLY_TRAIN_BATCH_SIZE=256 \
MONOPOLY_TRAIN_FOREST=false \
MONOPOLY_EVAL_GAMES=6 \
MONOPOLY_EVAL_PLAYERS=3 \
MONOPOLY_EVAL_SNAPSHOTS=300 \
MONOPOLY_EVAL_OPPONENT_STRATEGY=hard \
scripts/run_seed_replicates.sh \
  data/distillation/local-enhanced-20260524.jsonl \
  models/distillation/local-enhanced-20260524-multiseed
```

Artifacts:

- `models/distillation/local-enhanced-20260524-multiseed-seed_summary.md`
- `models/distillation/local-enhanced-20260524-multiseed-seed_summary.json`
- Per-seed prefixes: `models/distillation/local-enhanced-20260524-multiseed-seed11`, `seed42`, `seed73`
- Representative Java-loadable MLP: `models/distillation/local-enhanced-20260524-multiseed-seed73-mlp/candidate_ranker_mlp.json`

Result:

- Runs: 3
- Ready runs: 3
- Production-ready runs: 0
- Rows per run: 6372
- MLP validation top-1 mean/std: 0.814 / 0.005
- MLP validation MRR mean/std: 0.894 / 0.004
- First-candidate baseline mean/std: 0.378 / 0.018
- Random expected baseline mean/std: 0.220 / 0.009
- Gameplay vs hard: 18 games requested/evaluated, 16 natural completions, 2 snapshot-limit games.
- Ranker win rate mean/std: 0.167 / 0.000
- Average ranker board rank mean/std: 1.889 / 0.079
- Board lead rate mean/std: 0.222 / 0.079

Interpretation:

The enhanced local trace is stable for imitation learning, and Mac MPS training is reproducible enough for iteration. The hard-opponent gameplay result is still weak, so this should be presented as a pipeline and local-teacher baseline rather than a strong AI. The next quality jump requires DeepSeek labels or a stronger teacher/self-play loop, then the same multi-seed summary should be rerun on the production trace.

## 2026-05-24 Local Gameplay Matrix Pilot

Purpose: verify the gameplay-matrix evaluator across player counts and opponent strengths, and expose where the current local-teacher student is weak.

Command:

```bash
MONOPOLY_MATRIX_GAMES=2 \
MONOPOLY_MATRIX_SNAPSHOTS=260 \
MONOPOLY_MATRIX_PLAYERS=2,3,4 \
MONOPOLY_MATRIX_OPPONENTS=easy,normal,hard \
MONOPOLY_MATRIX_SEATS=1 \
scripts/evaluate_gameplay_matrix.sh \
  models/distillation/local-enhanced-20260524-multiseed-seed73-mlp/candidate_ranker_mlp.json \
  models/distillation/local-enhanced-20260524-multiseed-gameplay-matrix-smoke
```

Artifacts:

- `models/distillation/local-enhanced-20260524-multiseed-gameplay-matrix-smoke/summary.md`
- `models/distillation/local-enhanced-20260524-multiseed-gameplay-matrix-smoke/summary.json`
- Per-condition JSON files under `models/distillation/local-enhanced-20260524-multiseed-gameplay-matrix-smoke/`

Result:

- Conditions: 9
- Games requested: 18
- Natural completions: 14
- End reasons: `NATURAL_OR_LIMIT=14`, `LOCAL_RANKER_EVAL_SNAPSHOT_LIMIT=4`
- Ranker win rate: 0.278
- Average ranker board rank: 1.89
- Board lead rate: 0.389
- By opponent: easy win rate 0.333, normal 0.333, hard 0.167
- By player count: 2-player win rate 0.667, 3-player 0.000, 4-player 0.167

Interpretation:

The evaluator now produces clean JSON with `MONOPOLY_EVAL_QUIET=true`, and the matrix summary is usable for future reports. The result is not reportable strength evidence because it uses only 2 games per cell and the local heuristic teacher is not strategic enough. It does show that 3/4-player behavior is the current weak point to target with DeepSeek labels, stronger teacher data, or player-count-specific ablations.

## 2026-05-24 Consolidated Next-Day Handoff

Purpose: make the current Mac deliverable easy to inspect tomorrow without hunting through separate manifest, seed, matrix, readiness, and archive files.

Command:

```bash
python3 scripts/summarize_ai_handoff.py

scripts/package_training_handoff.sh \
  data/distillation/local-enhanced-20260524.jsonl \
  models/distillation/local-enhanced-20260524-multiseed \
  models/distillation/local-enhanced-20260524-multiseed-training-handoff.tar.gz

MONOPOLY_PACKAGE_AGG_PREFIX=models/distillation/local-enhanced-20260524-multiseed \
MONOPOLY_PACKAGE_GAMEPLAY=models/distillation/local-enhanced-20260524-multiseed-seed73-mlp/gameplay_vs_hard.json \
scripts/package_distillation_artifacts.sh \
  models/distillation/local-enhanced-20260524-multiseed-seed73 \
  models/distillation/local-enhanced-20260524-multiseed-artifacts.tar.gz
```

Artifacts:

- `models/distillation/local-enhanced-20260524-multiseed-quality_gate.md`
- `models/distillation/local-enhanced-20260524-multiseed-quality_gate.json`
- `models/distillation/local-enhanced-20260524-multiseed-handoff_report.md`
- `models/distillation/local-enhanced-20260524-multiseed-handoff_report.json`
- `models/distillation/local-enhanced-20260524-multiseed-artifacts.tar.gz`
- `models/distillation/local-enhanced-20260524-multiseed-training-handoff.tar.gz`

Result:

- Quality gate verdict: `local-training-ready`.
- Report verdict: `local-ready-only`.
- DeepSeek key present: false.
- Local rows: 6372.
- Local sessions: 134.
- Local-ready runs: 3.
- Production-ready runs: 0.
- Production data ready: false, because the trace has `local_heuristic` rows only and no token usage metadata.
- Paper evidence ready: false, because the gameplay matrix is a 2-game-per-cell smoke and lacks 5-player cells.
- Gameplay matrix smoke: 9 conditions, 18 games requested, 14 natural completions, ranker win rate 0.278.
- The regenerated archives include the handoff report, gameplay matrix summary, seed summaries, representative Java-loadable MLP model, scripts, and research/training docs.

Interpretation:

This is the clean handoff state. The team can train and evaluate locally from the package, and can move to the Windows 5090 only after a DeepSeek trace exists. The report deliberately keeps the main blocker visible: no production DeepSeek-labeled dataset exists in this checkout yet.

## 2026-05-24 Strategic Local Teacher And Probe Baseline

Purpose: provide one better no-key teacher path before freezing the local handoff, without changing the meaning of the existing `local_heuristic` baseline.

Changes:

- Added `StrategicHeuristicDecisionTeacher` with source `strategic_heuristic`.
- `SimulationBatchRunner` accepts `-Dmonopoly.simulation.teacher=strategic_heuristic`.
- `TraceRelabeler` and `scripts/relabel_distillation_trace.sh` accept `MONOPOLY_RELABEL_TEACHER=strategic_heuristic`.
- The strategic teacher still selects only Java-generated legal candidates.
- It uses board context for opponent complete-set pressure, self complete sets, bank state, high-charge Just Say No, and payment/discard preservation.

Smoke command for no-key relabeling:

```bash
MONOPOLY_RELABEL_TEACHER=strategic_heuristic \
MONOPOLY_RELABEL_SELECT=true \
MONOPOLY_RELABEL_MAX_BY_KIND=PLAY_CARD:500,PAYMENT:200,JUST_SAY_NO:100,OVERFLOW_DISCARD:100 \
scripts/relabel_distillation_trace.sh \
  data/distillation/local-enhanced-20260524.jsonl \
  data/distillation/local-enhanced-20260524-strategic-probe.jsonl \
  models/distillation/local-enhanced-20260524-strategic-probe
```

Interpretation:

This is a stronger local baseline and useful fallback if the DeepSeek key is unavailable. It is not a substitute for DeepSeek production data, and quality gates should still report production readiness as false until DeepSeek rows and token usage exist.

Probe command actually run for this handoff:

```bash
MONOPOLY_RELABEL_TEACHER=strategic_heuristic \
MONOPOLY_RELABEL_SELECT=true \
MONOPOLY_RELABEL_MAX_BY_KIND=PLAY_CARD:1200,PAYMENT:500,JUST_SAY_NO:250,OVERFLOW_DISCARD:120 \
MONOPOLY_RELABEL_MIN_SELECTED_ROWS=1500 \
MONOPOLY_TRACE_MODE=overwrite \
MONOPOLY_SELECTION_TRACE_MODE=overwrite \
scripts/relabel_distillation_trace.sh \
  data/distillation/local-enhanced-20260524.jsonl \
  data/distillation/local-enhanced-20260524-strategic-probe.jsonl \
  models/distillation/local-enhanced-20260524-strategic-probe
```

Training command:

```bash
MONOPOLY_TRAIN_SOURCES=strategic_heuristic \
MONOPOLY_MIN_TRAIN_ROWS=1500 \
MONOPOLY_TRAIN_EPOCHS=4 \
MONOPOLY_TRAIN_BATCH_SIZE=256 \
MONOPOLY_TRAIN_FOREST=false \
MONOPOLY_EVAL_GAMES=3 \
MONOPOLY_EVAL_PLAYERS=3 \
MONOPOLY_EVAL_SNAPSHOTS=220 \
MONOPOLY_EVAL_OPPONENT_STRATEGY=hard \
scripts/train_distilled_rankers.sh \
  data/distillation/local-enhanced-20260524-strategic-probe.jsonl \
  models/distillation/local-enhanced-20260524-strategic-probe
```

Artifacts:

- `data/distillation/local-enhanced-20260524-strategic-probe.jsonl`
- `models/distillation/local-enhanced-20260524-strategic-probe-run_summary.md`
- `models/distillation/local-enhanced-20260524-strategic-probe-readiness.json`
- `models/distillation/local-enhanced-20260524-strategic-probe-mlp/candidate_ranker_mlp.json`

Result:

- Rows: 2070
- Sessions: 134
- Teacher source: `strategic_heuristic`
- Decision mix: `PLAY_CARD=1200`, `PAYMENT=500`, `JUST_SAY_NO=250`, `OVERFLOW_DISCARD=120`
- Player-count mix: 2-player 515, 3-player 518, 4-player 518, 5-player 519
- Status: `local-ready`
- MLP validation top-1: 0.688
- MLP validation MRR: 0.810
- First-candidate baseline: 0.409
- Random expected baseline: 0.130
- Per-kind validation top-1: `PLAY_CARD=0.620`, `PAYMENT=0.939`, `JUST_SAY_NO=0.569`, `OVERFLOW_DISCARD=0.667`
- Gameplay vs hard: 3 games requested/evaluated, 1 natural completion, ranker win rate 0.333, average ranker board rank 1.67, board lead rate 0.667

Interpretation:

The strategic probe is a real trainable no-key dataset and model, not just a code path. It gives a second local baseline with more balanced rare-decision coverage and a Java-loadable MLP. The validation score is lower than the larger `local_heuristic` dataset because this probe has fewer rows and a more diverse candidate distribution; it still beats first-candidate and random baselines and passes local readiness. It remains non-production because the labels are local and have no token usage metadata.
