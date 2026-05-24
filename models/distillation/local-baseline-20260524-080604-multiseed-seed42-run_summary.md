# Monopoly Deal Training Run Summary

- Output prefix: `models/distillation/local-baseline-20260524-080604-multiseed-seed42`
- Status: **local-ready**
- Readiness mode: `local`
- Rows: 4774
- Sessions: 60
- Teacher sources: {'local_heuristic': 4774}
- Decision kinds: {'JUST_SAY_NO': 178, 'OVERFLOW_DISCARD': 66, 'PAYMENT': 840, 'PLAY_CARD': 3690}
- Player counts: {'2': 1055, '3': 1267, '4': 1223, '5': 1229}
- Rows with token usage: 0

## Readiness
- All readiness checks passed.

## Trace Audit
- Audit passed.

## Imitation Metrics
- Validation top-1: 0.830
- Validation MRR: 0.904
- First-candidate baseline: 0.337
- Random expected baseline: 0.213
- Per-kind top-1:
  - `JUST_SAY_NO`: 0.543 over 35 decisions
  - `OVERFLOW_DISCARD`: 0.471 over 17 decisions
  - `PAYMENT`: 0.922 over 167 decisions
  - `PLAY_CARD`: 0.831 over 720 decisions

## Model
- Type: `mlp`
- Device: `mps`
- Input dim: 119
- Epochs: 6
- Split: `session`
- Balance by kind: True

## Gameplay
- Opponent: `hard`
- Games requested/evaluated/natural: 4 / 4 / 4
- Natural win rate: 1.000
- Ranker win rate: 0.500
- Average ranker board rank: 1.75
- Board lead rate: 0.500
- End reasons: {'NATURAL_OR_LIMIT': 4}

## Token Usage
- No token usage metadata. This is expected for local heuristic runs and invalid for production DeepSeek readiness.

## Artifacts
- `manifest`: `models/distillation/local-baseline-20260524-080604-multiseed-seed42-dataset_manifest.json`
- `audit`: `models/distillation/local-baseline-20260524-080604-multiseed-seed42-trace_audit.json`
- `quality`: `models/distillation/local-baseline-20260524-080604-multiseed-seed42-quality_report.json`
- `readiness`: `models/distillation/local-baseline-20260524-080604-multiseed-seed42-readiness.json`
- `metrics`: `models/distillation/local-baseline-20260524-080604-multiseed-seed42-mlp/metrics.json`
- `gameplay`: `models/distillation/local-baseline-20260524-080604-multiseed-seed42-mlp/gameplay_vs_hard.json`
