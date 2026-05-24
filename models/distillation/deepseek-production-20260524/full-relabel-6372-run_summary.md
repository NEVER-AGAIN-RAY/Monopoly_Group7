# Monopoly Deal Training Run Summary

- Output prefix: `models/distillation/deepseek-production-20260524/full-relabel-6372`
- Status: **production-ready**
- Readiness mode: `production`
- Rows: 6371
- Sessions: 134
- Teacher sources: {'deepseek': 6371}
- Decision kinds: {'JUST_SAY_NO': 378, 'OVERFLOW_DISCARD': 164, 'PAYMENT': 1340, 'PLAY_CARD': 4489}
- Player counts: {'2': 1417, '3': 1670, '4': 1627, '5': 1657}
- Rows with token usage: 6371

## Readiness
- All readiness checks passed.

## Trace Audit
- Audit passed.

## Imitation Metrics
- Validation top-1: 0.722
- Validation MRR: 0.830
- First-candidate baseline: 0.387
- Random expected baseline: 0.223
- Per-kind top-1:
  - `JUST_SAY_NO`: 0.586 over 87 decisions
  - `OVERFLOW_DISCARD`: 0.667 over 24 decisions
  - `PAYMENT`: 0.947 over 262 decisions
  - `PLAY_CARD`: 0.676 over 990 decisions

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
- Average ranker board rank: 2.05
- Board lead rate: 0.300
- End reasons: {'NATURAL_OR_LIMIT': 20}

## Token Usage
- Prompt tokens share: 7628648
- Completion tokens share: 203327
- Total tokens share: 7831975
- Avg total tokens per decision: 1229.3

## Artifacts
- `manifest`: `models/distillation/deepseek-production-20260524/full-relabel-6372-dataset_manifest.json`
- `audit`: `models/distillation/deepseek-production-20260524/full-relabel-6372-trace_audit.json`
- `quality`: `models/distillation/deepseek-production-20260524/full-relabel-6372-quality_report.json`
- `readiness`: `models/distillation/deepseek-production-20260524/full-relabel-6372-readiness.json`
- `metrics`: `models/distillation/deepseek-production-20260524/full-relabel-6372-mlp/metrics.json`
- `gameplay`: `models/distillation/deepseek-production-20260524/full-relabel-6372-mlp/gameplay_vs_hard.json`
