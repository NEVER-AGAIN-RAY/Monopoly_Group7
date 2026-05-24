# AI Delivery Checklist

Last updated: 2026-05-24.

## Current Status

The training and evaluation pipeline is runnable on the Mac. A paid DeepSeek relabel run plus a smaller DeepSeek direct-simulation supplement have now produced a production-ready DeepSeek-only dataset, stored separately from the local heuristic baselines.

Do not mix local heuristic data into DeepSeek production claims. The local datasets remain pipeline/baseline evidence only.

## DeepSeek Production Artifacts

Primary production trace:

- `data/distillation/deepseek-production-20260524/merged-full-plus-direct-8940.jsonl`

Source traces:

- `data/distillation/deepseek-production-20260524/full-relabel-6372.jsonl`
- `data/distillation/deepseek-production-20260524/direct-sim-5000.jsonl`

Primary production reports:

- `models/distillation/deepseek-production-20260524/merged-full-plus-direct-8940-readiness.json`
- `models/distillation/deepseek-production-20260524/merged-full-plus-direct-8940-quality_report.md`
- `models/distillation/deepseek-production-20260524/merged-full-plus-direct-8940-trace_audit.json`
- `models/distillation/deepseek-production-20260524/merged-full-plus-direct-8940-cost_estimate.json`
- `models/distillation/deepseek-production-20260524/merged-full-plus-direct-8940-run_summary.md`
- `models/distillation/deepseek-production-20260524/merged-full-plus-direct-8940-quality_gate.md`
- `models/distillation/deepseek-production-20260524/merged-full-plus-direct-8940-handoff_report.md`
- `models/distillation/deepseek-production-20260524/merged-full-plus-direct-8940-mlp/metrics.json`
- `models/distillation/deepseek-production-20260524/merged-full-plus-direct-8940-mlp/gameplay_vs_hard.json`
- `models/distillation/deepseek-production-20260524/merged-full-plus-direct-8940-mlp/candidate_ranker_mlp.json`

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

- `models/distillation/deepseek-production-20260524/full-relabel-6372-problem-ids.jsonl`

Production delivery archives:

- `models/distillation/deepseek-production-20260524/merged-full-plus-direct-8940-artifacts.tar.gz`
- `models/distillation/deepseek-production-20260524/merged-full-plus-direct-8940-training-handoff.tar.gz`

## Local Baseline Artifacts

The preferred local handoff is the enhanced local baseline. It combines the first Mac baseline with an additional quota-driven local supplement so every decision kind clears the local readiness floor.

Trace:

- `data/distillation/local-enhanced-20260524.jsonl`
- supplement trace: `data/distillation/local-supplement-20260524-overflow.jsonl`
- merge report: `models/distillation/local-enhanced-20260524-trace_merge.json`

Main local reports:

- `models/distillation/local-enhanced-20260524-multiseed-quality_gate.md`
- `models/distillation/local-enhanced-20260524-multiseed-quality_gate.json`
- `models/distillation/local-enhanced-20260524-multiseed-handoff_report.md`
- `models/distillation/local-enhanced-20260524-multiseed-handoff_report.json`
- `models/distillation/local-enhanced-20260524-multiseed-seed_summary.md`
- `models/distillation/local-enhanced-20260524-multiseed-seed_summary.json`
- per-seed prefixes: `models/distillation/local-enhanced-20260524-multiseed-seed11`, `seed42`, `seed73`
- `models/distillation/local-enhanced-20260524-run_summary.md`
- `models/distillation/local-enhanced-20260524-run_summary.json`
- `models/distillation/local-enhanced-20260524-readiness.json`
- `models/distillation/local-enhanced-20260524-trace_audit.json`
- `models/distillation/local-enhanced-20260524-mlp/gameplay_vs_hard.json`

Strategic local probe:

- Trace: `data/distillation/local-enhanced-20260524-strategic-probe.jsonl`
- Summary: `models/distillation/local-enhanced-20260524-strategic-probe-run_summary.md`
- Model: `models/distillation/local-enhanced-20260524-strategic-probe-mlp/candidate_ranker_mlp.json`
- Readiness: `models/distillation/local-enhanced-20260524-strategic-probe-readiness.json`

Local delivery archives:

- `models/distillation/local-enhanced-20260524-multiseed-artifacts.tar.gz`
- `models/distillation/local-enhanced-20260524-multiseed-training-handoff.tar.gz`

Machine-readable status:

```bash
python3 scripts/check_ai_delivery_status.py \
  --output models/distillation/local-enhanced-20260524-multiseed-delivery_status.json

python3 scripts/check_ai_quality_gate.py --require local

python3 scripts/summarize_ai_handoff.py
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
- Single-run checkpoint: `models/distillation/local-enhanced-20260524-run_summary.md` had validation top-1 0.815 and 4-game ranker win rate 0.500; prefer the multi-seed summary for claims.

Quality gate interpretation:

- `localTrainingReady=true`: the current Mac artifacts are enough to continue local model iteration and handoff.
- `productionDataReady=true` for `models/distillation/deepseek-production-20260524/merged-full-plus-direct-8940`.
- `paperEvidenceReady=false`: the current matrix is a smoke run, not a reportable gameplay result.

Optional no-key improvement:

```bash
MONOPOLY_RELABEL_TEACHER=strategic_heuristic \
MONOPOLY_RELABEL_SELECT=true \
MONOPOLY_RELABEL_MAX_BY_KIND=PLAY_CARD:500,PAYMENT:200,JUST_SAY_NO:100,OVERFLOW_DISCARD:100 \
scripts/relabel_distillation_trace.sh \
  data/distillation/local-enhanced-20260524.jsonl \
  data/distillation/local-enhanced-20260524-strategic-probe.jsonl \
  models/distillation/local-enhanced-20260524-strategic-probe
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

- `models/distillation/local-baseline-20260524-080604-multiseed-seed_summary.md`
- 4774 rows, validation top-1 mean/std 0.810 / 0.016, gameplay ranker win rate 0.250

## Production Run Command

Run the paid probe first:

```bash
DEEPSEEK_API_KEY=... \
MONOPOLY_PRODUCTION_RUN_ID=deepseek-run1 \
MONOPOLY_PROMPT_PRICE_PER_MILLION=<dashboard-prompt-price> \
MONOPOLY_COMPLETION_PRICE_PER_MILLION=<dashboard-completion-price> \
scripts/run_deepseek_production_pipeline.sh
```

Inspect:

- `models/distillation/deepseek-run1-probe-trace_audit.json`
- `models/distillation/deepseek-run1-probe-quality_report.md`
- `models/distillation/deepseek-run1-probe-cost_estimate.json`

If the API key arrives before there is time to run new simulations, relabel part of the existing local legal trace:

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

This is a fast paid probe because it reuses stored backend-legal candidates. The selector writes `models/distillation/deepseek-relabel-probe-selection_report.json` and spreads the paid subset across decision kinds, player counts, and sessions before relabeling. It is not by itself the production target: the enhanced local trace has enough local rows for a training smoke, but the production gate still needs at least 5000 DeepSeek rows, token usage metadata, and enough rare-kind coverage after relabeling or collection.

Continue only after the probe is acceptable:

```bash
DEEPSEEK_API_KEY=... \
MONOPOLY_PRODUCTION_RUN_ID=deepseek-run1 \
MONOPOLY_PAID_CONFIRM=run-paid-overnight \
scripts/run_deepseek_production_pipeline.sh
```

## Production Completion Gate

The production goal is not complete until all of these are true:

- A merged DeepSeek trace exists.
- At least 5000 rows are present for the first production student.
- `scripts/audit_distillation_trace.py` passes with `--preferred-source deepseek --require-token-usage`.
- `scripts/check_training_readiness.py --mode production` writes `"ready": true`.
- `*-run_summary.md` says `production-ready`.
- A production readiness report is true. A single production-ready run is enough for the current delivery gate; multi-seed production summaries are still required for paper-grade evidence.
- Gameplay evaluation reports ranker win rate, board-rank metrics, natural completions, and end reasons.
- `scripts/check_ai_delivery_status.py --production-prefix <prefix>` exits with code 0.
- `scripts/check_ai_quality_gate.py --production-prefix <prefix> --require production` exits with code 0.

## Windows 5090 Plan

Use the Windows laptop for 100k+ rows, longer epoch runs, or larger model-family sweeps. After extracting a handoff archive into the repo, run:

```bash
MONOPOLY_TRAIN_SOURCES=deepseek \
MONOPOLY_MIN_TRAIN_ROWS=5000 \
MONOPOLY_TRAIN_EPOCHS=40 \
MONOPOLY_TRAIN_BATCH_SIZE=512 \
scripts/train_distilled_rankers.sh \
  data/distillation/deepseek-run1-merged.jsonl \
  models/distillation/deepseek-run1
```

Then run multi-seed reporting:

```bash
MONOPOLY_SEEDS=11,42,73 \
MONOPOLY_TRAIN_SOURCES=deepseek \
MONOPOLY_MIN_TRAIN_ROWS=5000 \
scripts/run_seed_replicates.sh \
  data/distillation/deepseek-run1-merged.jsonl \
  models/distillation/deepseek-run1
```

## Report Boundaries

Safe current claim:

- The backend can generate legal decision rows, train MLP/linear/KNN students on Mac, export Java-loadable rankers, and evaluate gameplay with ranker win-rate and board-rank metrics.
- A separate DeepSeek-only production relabel dataset exists and passes production readiness.

Unsafe current claim:

- That the gameplay result is robust enough for a paper claim.

Paper-ready evidence additionally requires:

- `scripts/check_ai_quality_gate.py --production-prefix <prefix> --require paper` exits with code 0.
- 2/3/4/5-player matrix cells exist for easy/normal/hard opponents.
- Each reportable matrix cell has at least 50 games.
