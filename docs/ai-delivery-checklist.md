# AI Delivery Checklist

Last updated: 2026-05-26.

## Current Status

The training and evaluation pipeline is runnable on the Mac. A paid DeepSeek relabel run plus a smaller DeepSeek direct-simulation supplement have produced a production-ready DeepSeek-only dataset, stored separately from the local heuristic baselines.

The current strongest local gameplay opponent is **`lookahead` / `search`**, not the distilled MLP student. It is a pure local Java strategy and does not call a remote LLM. The current default is the `buildingA` lookahead parameter set. Under the current natural-game evaluation policy it wins 384/600 = 64.0% against `hard`, with Wilson 95% CI 60.08%-67.74%, 600/600 natural endings, and 0 unknown outcomes (`training/data/models/evaluation/winrate/buildingA-lookahead-vs-hard-600-summary.json`). Older same-seed paired reports are retained as diagnostics, but natural multi-game win rate is the main current strength claim.

Do not mix local heuristic data into DeepSeek production claims. The local datasets remain pipeline/baseline evidence only.

## Current Strong Local AI

Use `lookahead` when the goal is a local AI that has current evidence of being stronger than `hard`.

Runtime options:

- HVM difficulty: `STRONG`, `LOOKAHEAD`, or `SEARCH`
- `CUSTOM` lineup role: `lookahead`, `search`, `local_strong`, or `strong`
- Example lineup: `human,human,lookahead,llm`
- Paired runner strategy: `-Dmonopoly.pairedBattle.treatmentLlmStrategy=lookahead`

Primary evidence:

- Summary: `training/data/models/evaluation/winrate/buildingA-lookahead-vs-hard-600-summary.json`
- Gate: `training/data/models/evaluation/winrate/buildingA-lookahead-vs-hard-600-gate.json`
- Primary metric: seat-balanced natural multi-game win rate against `hard`.
- Current default parameters: `monopoly.search.buildingActionBonus=900`, `monopoly.search.buildingRentBonusValue=320`, `monopoly.search.opponentBuildingThreatValue=260`.
- Gate command:

```bash
python3 training/scripts/check_mixed_winrate_gate.py \
  training/data/models/evaluation/winrate/buildingA-lookahead-vs-hard-600-summary.json \
  --output training/data/models/evaluation/winrate/buildingA-lookahead-vs-hard-600-gate.json
```

Claim boundary:

- Safe: `lookahead` / `search` is the current local strong AI and is clearly stronger than `hard` under the natural 600-game evidence.
- Bounded: `buildingA` has the best current natural 600-game result, but the rough comparison against winnerparsefix 600 is +2.83pp with p ~= 0.311, so do not claim statistical dominance over every older lookahead sample.
- Unsafe: the distilled MLP student is already stronger than `hard`.

Latest student checkpoint:

- Model: `backend/models/distillation/lookahead-student-v6-dagger1394-randomfirst-w4-listwise-mlp/candidate_ranker_mlp.json`
- Loss: listwise
- Offline validation top-1: 0.714
- Offline validation MRR: 0.828
- Random-first 80-pair aggregate: `training/data/models/evaluation/lookahead-student-v6-dagger1394-randomfirst-w4-listwise-randomfirst-80pairs-summary.json`
  - Rank-only paired treatment/control/tie = 10/9/61
  - Decisive paired treatment rate = 0.526; 95% CI = 0.317-0.727
  - Paired margin = +1
- Multiscenario paired gate: `training/data/models/evaluation/lookahead-student-v6-dagger1394-randomfirst-w4-listwise-multiscenario-paired-gate.json`
  - Overall rank-only paired treatment/control/tie = 33/19/108
  - Failed decisive paired CI lower-bound check: 0.4987 < 0.52
- Interpretation: v6 is the current best distilled student candidate, but it is not robust enough for the strong-AI claim. By default, `LocalRankerAiPlayStrategy` ranks only `PLAY_CARD`; `PAYMENT`, `JUST_SAY_NO`, and `OVERFLOW_DISCARD` stay on hard fallback unless `-Dmonopoly.localRanker.rankAuxiliaryDecisions=true` is explicitly set for experiments.

## DeepSeek Production Artifacts

Primary production trace:

- `training/data/distillation/deepseek-production-20260524/merged-full-plus-direct-8940.jsonl`

Source traces:

- `training/data/distillation/deepseek-production-20260524/full-relabel-6372.jsonl`
- `training/data/distillation/deepseek-production-20260524/direct-sim-5000.jsonl`

Primary production reports:

- `backend/models/distillation/deepseek-production-20260524/merged-full-plus-direct-8940-readiness.json`
- `backend/models/distillation/deepseek-production-20260524/merged-full-plus-direct-8940-quality_report.md`
- `backend/models/distillation/deepseek-production-20260524/merged-full-plus-direct-8940-trace_audit.json`
- `backend/models/distillation/deepseek-production-20260524/merged-full-plus-direct-8940-cost_estimate.json`
- `backend/models/distillation/deepseek-production-20260524/merged-full-plus-direct-8940-run_summary.md`
- `backend/models/distillation/deepseek-production-20260524/merged-full-plus-direct-8940-quality_gate.md`
- `backend/models/distillation/deepseek-production-20260524/merged-full-plus-direct-8940-handoff_report.md`
- `backend/models/distillation/deepseek-production-20260524/merged-full-plus-direct-8940-mlp/metrics.json`
- `backend/models/distillation/deepseek-production-20260524/merged-full-plus-direct-8940-mlp/gameplay_vs_hard.json`
- `backend/models/distillation/deepseek-production-20260524/merged-full-plus-direct-8940-mlp/candidate_ranker_mlp.json`

DeepSeek production result:

- Rows: 8940
- Sessions: 214
- Teacher source: `deepseek`
- Decision mix: `PLAY_CARD=6519`, `PAYMENT=1747`, `JUST_SAY_NO=501`, `OVERFLOW_DISCARD=173`
- Player-count mix: 2/3/4/5 all present
- Token usage metadata: 8940 / 8940 rows
- Status: `production-ready`
- Readiness mode: `production`
- Quality gate: `production-training-ready`
- MLP validation top-1: 0.733
- MLP validation MRR: 0.841
- First-candidate baseline: 0.398
- Random expected baseline: 0.212
- Gameplay vs hard: 20 games requested/evaluated, 20 natural completions, ranker win rate 0.300, average ranker board rank 2.10, board lead rate 0.300
- Estimated API cost for this merged trace at the recorded price inputs: 1.498805

One selected local decision did not receive a valid DeepSeek id and was isolated instead of being written as fallback data:

- `backend/models/distillation/deepseek-production-20260524/full-relabel-6372-problem-ids.jsonl`

Production delivery archives:

- `backend/models/distillation/deepseek-production-20260524/merged-full-plus-direct-8940-artifacts.tar.gz`
- `backend/models/distillation/deepseek-production-20260524/merged-full-plus-direct-8940-training-handoff.tar.gz`

## Local Baseline Artifacts

The preferred local handoff is the enhanced local baseline. It combines the first Mac baseline with an additional quota-driven local supplement so every decision kind clears the local readiness floor.

Trace:

- `training/data/distillation/local-enhanced-20260524.jsonl`
- supplement trace: `training/data/distillation/local-supplement-20260524-overflow.jsonl`
- merge report: `backend/models/distillation/local-enhanced-20260524-trace_merge.json`

Main local reports:

- `backend/models/distillation/local-enhanced-20260524-multiseed-quality_gate.md`
- `backend/models/distillation/local-enhanced-20260524-multiseed-quality_gate.json`
- `backend/models/distillation/local-enhanced-20260524-multiseed-handoff_report.md`
- `backend/models/distillation/local-enhanced-20260524-multiseed-handoff_report.json`
- `backend/models/distillation/local-enhanced-20260524-multiseed-seed_summary.md`
- `backend/models/distillation/local-enhanced-20260524-multiseed-seed_summary.json`
- per-seed prefixes: `backend/models/distillation/local-enhanced-20260524-multiseed-seed11`, `seed42`, `seed73`
- `backend/models/distillation/local-enhanced-20260524-run_summary.md`
- `backend/models/distillation/local-enhanced-20260524-run_summary.json`
- `backend/models/distillation/local-enhanced-20260524-readiness.json`
- `backend/models/distillation/local-enhanced-20260524-trace_audit.json`
- `backend/models/distillation/local-enhanced-20260524-mlp/gameplay_vs_hard.json`

Strategic local probe:

- Trace: `training/data/distillation/local-enhanced-20260524-strategic-probe.jsonl`
- Summary: `backend/models/distillation/local-enhanced-20260524-strategic-probe-run_summary.md`
- Model: `backend/models/distillation/local-enhanced-20260524-strategic-probe-mlp/candidate_ranker_mlp.json`
- Readiness: `backend/models/distillation/local-enhanced-20260524-strategic-probe-readiness.json`

Local delivery archives:

- `backend/models/distillation/local-enhanced-20260524-multiseed-artifacts.tar.gz`
- `backend/models/distillation/local-enhanced-20260524-multiseed-training-handoff.tar.gz`

Machine-readable status:

```bash
python3 training/scripts/check_ai_delivery_status.py \
  --output backend/models/distillation/local-enhanced-20260524-multiseed-delivery_status.json

python3 training/scripts/check_ai_quality_gate.py --require local

python3 training/scripts/summarize_ai_handoff.py
```

Local enhanced result:

- Rows: 6372
- Sessions: 134
- Teacher source: `local_heuristic`
- Decision mix: `PLAY_CARD=4490`, `PAYMENT=1340`, `JUST_SAY_NO=378`, `OVERFLOW_DISCARD=164`
- Player-count mix: 2/3/4/5 all present
- Status: `local-ready`
- Quality gate: `local-training-ready`
- Production-ready runs: 0
- 3-seed MLP validation top-1 mean/std: 0.814 / 0.005
- 3-seed MLP validation MRR mean/std: 0.894 / 0.004
- First-candidate baseline mean: 0.378
- Random expected baseline mean: 0.220
- Gameplay vs hard: 18 games requested/evaluated across three seeds, 16 natural completions, ranker win rate mean 0.167, average ranker board rank mean 1.89, board lead rate mean 0.222
- Single-run checkpoint: `backend/models/distillation/local-enhanced-20260524-run_summary.md` had validation top-1 0.815 and 4-game ranker win rate 0.500; prefer the multi-seed summary for claims.

Quality gate interpretation:

- `localTrainingReady=true`: the current Mac artifacts are enough to continue local model iteration and handoff.
- `productionDataReady=true` for `backend/models/distillation/deepseek-production-20260524/merged-full-plus-direct-8940`.
- `paperEvidenceReady=false`: the current matrix is a smoke run, not a reportable gameplay result.

Optional no-key improvement:

```bash
MONOPOLY_RELABEL_TEACHER=strategic_heuristic \
MONOPOLY_RELABEL_SELECT=true \
MONOPOLY_RELABEL_MAX_BY_KIND=PLAY_CARD:500,PAYMENT:200,JUST_SAY_NO:100,OVERFLOW_DISCARD:100 \
training/scripts/relabel_distillation_trace.sh \
  training/data/distillation/local-enhanced-20260524.jsonl \
  training/data/distillation/local-enhanced-20260524-strategic-probe.jsonl \
  backend/models/distillation/local-enhanced-20260524-strategic-probe
```

This produces `strategic_heuristic` labels for a stronger local baseline. It is still not production data and must not be mixed into DeepSeek production claims.

Strategic probe result:

- Rows: 2070
- Sessions: 134
- Teacher source: `strategic_heuristic`
- Decision mix: `PLAY_CARD=1200`, `PAYMENT=500`, `JUST_SAY_NO=250`, `OVERFLOW_DISCARD=120`
- Player-count mix: 2/3/4/5 all present
- Status: `local-ready`
- MLP validation top-1: 0.688
- MLP validation MRR: 0.810
- First-candidate baseline: 0.409
- Random expected baseline: 0.130
- Gameplay vs hard: 3 games requested/evaluated, 1 natural completion, ranker win rate 0.333, average ranker board rank 1.67, board lead rate 0.667
- Production-ready: no, because every row is `strategic_heuristic` and no token usage metadata is present.

Older multi-seed local baseline, kept for seed variance reference:

- `backend/models/distillation/local-baseline-20260524-080604-multiseed-seed_summary.md`
- 4774 rows, validation top-1 mean/std 0.810 / 0.016, gameplay ranker win rate 0.250

## Production Run Command

Run the paid probe first:

```bash
DEEPSEEK_API_KEY=... \
MONOPOLY_PRODUCTION_RUN_ID=deepseek-run1 \
MONOPOLY_PROMPT_PRICE_PER_MILLION=<dashboard-prompt-price> \
MONOPOLY_COMPLETION_PRICE_PER_MILLION=<dashboard-completion-price> \
training/scripts/run_deepseek_production_pipeline.sh
```

Inspect:

- `backend/models/distillation/deepseek-run1-probe-trace_audit.json`
- `backend/models/distillation/deepseek-run1-probe-quality_report.md`
- `backend/models/distillation/deepseek-run1-probe-cost_estimate.json`

If the API key arrives before there is time to run new simulations, relabel part of the existing local legal trace:

```bash
DEEPSEEK_API_KEY=... \
training/scripts/run_relabel_paid_probe.sh \
  training/data/distillation/local-enhanced-20260524.jsonl \
  training/data/distillation/deepseek-relabel-probe.jsonl \
  backend/models/distillation/deepseek-relabel-probe
```

Lower-level equivalent:

```bash
DEEPSEEK_API_KEY=... \
MONOPOLY_RELABEL_SELECT=true \
MONOPOLY_RELABEL_MAX_BY_KIND=PLAY_CARD:250,PAYMENT:120,JUST_SAY_NO:80,OVERFLOW_DISCARD:50 \
training/scripts/relabel_distillation_trace.sh \
  training/data/distillation/local-enhanced-20260524.jsonl \
  training/data/distillation/deepseek-relabel-probe.jsonl \
  backend/models/distillation/deepseek-relabel-probe
```

This is a fast paid probe because it reuses stored backend-legal candidates. The selector writes `backend/models/distillation/deepseek-relabel-probe-selection_report.json` and spreads the paid subset across decision kinds, player counts, and sessions before relabeling. It is not by itself the production target: the enhanced local trace has enough local rows for a training smoke, but the production gate still needs at least 5000 DeepSeek rows, token usage metadata, and enough rare-kind coverage after relabeling or collection.

Continue only after the probe is acceptable:

```bash
DEEPSEEK_API_KEY=... \
MONOPOLY_PRODUCTION_RUN_ID=deepseek-run1 \
MONOPOLY_PAID_CONFIRM=run-paid-overnight \
training/scripts/run_deepseek_production_pipeline.sh
```

## Production Completion Gate

The production goal is not complete until all of these are true:

- A merged DeepSeek trace exists.
- At least 5000 rows are present for the first production student.
- `training/scripts/audit_distillation_trace.py` passes with `--preferred-source deepseek --require-token-usage`.
- `training/scripts/check_training_readiness.py --mode production` writes `"ready": true`.
- `*-run_summary.md` says `production-ready`.
- A production readiness report is true. A single production-ready run is enough for the current delivery gate; multi-seed production summaries are still required for paper-grade evidence.
- Gameplay evaluation reports ranker win rate, board-rank metrics, natural completions, and end reasons.
- `training/scripts/check_ai_delivery_status.py --production-prefix <prefix>` exits with code 0.
- `training/scripts/check_ai_quality_gate.py --production-prefix <prefix> --require production` exits with code 0.

## Windows 5090 Plan

Use the Windows laptop for 100k+ rows, longer epoch runs, or larger model-family sweeps. After extracting a handoff archive into the repo, run:

```bash
MONOPOLY_TRAIN_SOURCES=deepseek \
MONOPOLY_MIN_TRAIN_ROWS=5000 \
MONOPOLY_TRAIN_EPOCHS=40 \
MONOPOLY_TRAIN_BATCH_SIZE=512 \
training/scripts/train_distilled_rankers.sh \
  training/data/distillation/deepseek-run1-merged.jsonl \
  backend/models/distillation/deepseek-run1
```

Then run multi-seed reporting:

```bash
MONOPOLY_SEEDS=11,42,73 \
MONOPOLY_TRAIN_SOURCES=deepseek \
MONOPOLY_MIN_TRAIN_ROWS=5000 \
training/scripts/run_seed_replicates.sh \
  training/data/distillation/deepseek-run1-merged.jsonl \
  backend/models/distillation/deepseek-run1
```

## Report Boundaries

Safe current claim:

- The backend can generate legal decision rows, train MLP/linear/KNN students on Mac, export Java-loadable rankers, and evaluate gameplay with ranker win-rate and board-rank metrics.
- A separate DeepSeek-only production relabel dataset exists and passes production readiness.

Unsafe current claim:

- That the gameplay result is robust enough for a paper claim.

Paper-ready evidence additionally requires:

- `training/scripts/check_ai_quality_gate.py --production-prefix <prefix> --require paper` exits with code 0.
- 2/3/4/5-player matrix cells exist for easy/normal/hard opponents.
- Each reportable matrix cell has at least 50 games.
