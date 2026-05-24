# Monopoly Deal Training Run Summary

- Output prefix: `models/distillation/deepseek-production-20260524/merged-full-plus-direct-8940`
- Status: **production-ready**
- Readiness mode: `production`
- Rows: 8940
- Sessions: 214
- Teacher sources: {'deepseek': 8940}
- Decision kinds: {'JUST_SAY_NO': 501, 'OVERFLOW_DISCARD': 173, 'PAYMENT': 1747, 'PLAY_CARD': 6519}
- Player counts: {'2': 2049, '3': 2320, '4': 2273, '5': 2298}
- Rows with token usage: 8940

## Readiness
- All readiness checks passed.

## Trace Audit
- Audit passed.

## Imitation Metrics
- Validation top-1: 0.733
- Validation MRR: 0.841
- First-candidate baseline: 0.398
- Random expected baseline: 0.212
- Per-kind top-1:
  - `JUST_SAY_NO`: 0.625 over 88 decisions
  - `OVERFLOW_DISCARD`: 0.725 over 40 decisions
  - `PAYMENT`: 0.931 over 361 decisions
  - `PLAY_CARD`: 0.685 over 1295 decisions

## Model
- Type: `mlp`
- Device: `mps`
- Input dim: 119
- Epochs: 40
- Split: `session`
- Balance by kind: True

## Gameplay
- Opponent: `hard`
- Games requested/evaluated/natural: 20 / 20 / 20
- Natural win rate: 1.000
- Ranker win rate: 0.300
- Average ranker board rank: 2.10
- Board lead rate: 0.300
- End reasons: {'NATURAL_OR_LIMIT': 20}

## Token Usage
- Prompt tokens share: 10137810
- Completion tokens share: 283970
- Total tokens share: 10421780
- Avg total tokens per decision: 1165.7

## Artifacts
- `manifest`: `models/distillation/deepseek-production-20260524/merged-full-plus-direct-8940-dataset_manifest.json`
- `audit`: `models/distillation/deepseek-production-20260524/merged-full-plus-direct-8940-trace_audit.json`
- `quality`: `models/distillation/deepseek-production-20260524/merged-full-plus-direct-8940-quality_report.json`
- `readiness`: `models/distillation/deepseek-production-20260524/merged-full-plus-direct-8940-readiness.json`
- `metrics`: `models/distillation/deepseek-production-20260524/merged-full-plus-direct-8940-mlp/metrics.json`
- `gameplay`: `models/distillation/deepseek-production-20260524/merged-full-plus-direct-8940-mlp/gameplay_vs_hard.json`
