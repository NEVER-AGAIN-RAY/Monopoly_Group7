# Monopoly Deal Training Run Summary

- Output prefix: `models/distillation/local-enhanced-20260524-multiseed-seed11`
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
- Validation top-1: 0.809
- Validation MRR: 0.889
- First-candidate baseline: 0.401
- Random expected baseline: 0.208
- Per-kind top-1:
  - `JUST_SAY_NO`: 0.645 over 62 decisions
  - `OVERFLOW_DISCARD`: 0.788 over 33 decisions
  - `PAYMENT`: 0.904 over 250 decisions
  - `PLAY_CARD`: 0.790 over 689 decisions

## Model
- Type: `mlp`
- Device: `mps`
- Input dim: 119
- Epochs: 6
- Split: `session`
- Balance by kind: True

## Gameplay
- Opponent: `hard`
- Games requested/evaluated/natural: 6 / 6 / 5
- Natural win rate: 0.833
- Ranker win rate: 0.167
- Average ranker board rank: 1.83
- Board lead rate: 0.333
- End reasons: {'NATURAL_OR_LIMIT': 5, 'LOCAL_RANKER_EVAL_SNAPSHOT_LIMIT': 1}

## Token Usage
- No token usage metadata. This is expected for local heuristic runs and invalid for production DeepSeek readiness.

## Artifacts
- `manifest`: `models/distillation/local-enhanced-20260524-multiseed-seed11-dataset_manifest.json`
- `audit`: `models/distillation/local-enhanced-20260524-multiseed-seed11-trace_audit.json`
- `quality`: `models/distillation/local-enhanced-20260524-multiseed-seed11-quality_report.json`
- `readiness`: `models/distillation/local-enhanced-20260524-multiseed-seed11-readiness.json`
- `metrics`: `models/distillation/local-enhanced-20260524-multiseed-seed11-mlp/metrics.json`
- `gameplay`: `models/distillation/local-enhanced-20260524-multiseed-seed11-mlp/gameplay_vs_hard.json`
