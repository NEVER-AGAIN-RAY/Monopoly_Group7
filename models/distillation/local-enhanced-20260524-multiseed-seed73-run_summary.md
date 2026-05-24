# Monopoly Deal Training Run Summary

- Output prefix: `models/distillation/local-enhanced-20260524-multiseed-seed73`
- Status: **local-ready**
- Readiness mode: `local`
- Rows: 6372
- Sessions: 134
- Teacher sources: {'local_heuristic': 6372}
- Decision kinds: {'JUST_SAY_NO': 378, 'OVERFLOW_DISCARD': 164, 'PAYMENT': 1340, 'PLAY_CARD': 4490}
- Player counts: {'2': 1417, '3': 1670, '4': 1628, '5': 1657}
- Rows with token usage: 0

## Readiness
- All readiness checks passed.

## Trace Audit
- Audit passed.

## Imitation Metrics
- Validation top-1: 0.820
- Validation MRR: 0.898
- First-candidate baseline: 0.374
- Random expected baseline: 0.229
- Per-kind top-1:
  - `JUST_SAY_NO`: 0.613 over 75 decisions
  - `OVERFLOW_DISCARD`: 0.818 over 22 decisions
  - `PAYMENT`: 0.919 over 235 decisions
  - `PLAY_CARD`: 0.812 over 1004 decisions

## Model
- Type: `mlp`
- Device: `mps`
- Input dim: 119
- Epochs: 6
- Split: `session`
- Balance by kind: True

## Gameplay
- Opponent: `hard`
- Games requested/evaluated/natural: 6 / 6 / 6
- Natural win rate: 1.000
- Ranker win rate: 0.167
- Average ranker board rank: 1.83
- Board lead rate: 0.167
- End reasons: {'NATURAL_OR_LIMIT': 6}

## Token Usage
- No token usage metadata. This is expected for local heuristic runs and invalid for production DeepSeek readiness.

## Artifacts
- `manifest`: `models/distillation/local-enhanced-20260524-multiseed-seed73-dataset_manifest.json`
- `audit`: `models/distillation/local-enhanced-20260524-multiseed-seed73-trace_audit.json`
- `quality`: `models/distillation/local-enhanced-20260524-multiseed-seed73-quality_report.json`
- `readiness`: `models/distillation/local-enhanced-20260524-multiseed-seed73-readiness.json`
- `metrics`: `models/distillation/local-enhanced-20260524-multiseed-seed73-mlp/metrics.json`
- `gameplay`: `models/distillation/local-enhanced-20260524-multiseed-seed73-mlp/gameplay_vs_hard.json`
