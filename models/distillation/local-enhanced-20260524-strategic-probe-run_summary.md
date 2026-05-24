# Monopoly Deal Training Run Summary

- Output prefix: `models/distillation/local-enhanced-20260524-strategic-probe`
- Status: **local-ready**
- Readiness mode: `local`
- Rows: 2070
- Sessions: 134
- Teacher sources: {'strategic_heuristic': 2070}
- Decision kinds: {'JUST_SAY_NO': 250, 'OVERFLOW_DISCARD': 120, 'PAYMENT': 500, 'PLAY_CARD': 1200}
- Player counts: {'2': 515, '3': 518, '4': 518, '5': 519}
- Rows with token usage: 0

## Readiness
- All readiness checks passed.

## Trace Audit
- Audit passed.

## Imitation Metrics
- Validation top-1: 0.688
- Validation MRR: 0.810
- First-candidate baseline: 0.409
- Random expected baseline: 0.130
- Per-kind top-1:
  - `JUST_SAY_NO`: 0.569 over 58 decisions
  - `OVERFLOW_DISCARD`: 0.667 over 21 decisions
  - `PAYMENT`: 0.939 over 99 decisions
  - `PLAY_CARD`: 0.620 over 255 decisions

## Model
- Type: `mlp`
- Device: `mps`
- Input dim: 119
- Epochs: 4
- Split: `session`
- Balance by kind: True

## Gameplay
- Opponent: `hard`
- Games requested/evaluated/natural: 3 / 3 / 1
- Natural win rate: 0.333
- Ranker win rate: 0.333
- Average ranker board rank: 1.67
- Board lead rate: 0.667
- End reasons: {'NATURAL_OR_LIMIT': 1, 'LOCAL_RANKER_EVAL_SNAPSHOT_LIMIT': 2}

## Token Usage
- No token usage metadata. This is expected for local heuristic runs and invalid for production DeepSeek readiness.

## Artifacts
- `manifest`: `models/distillation/local-enhanced-20260524-strategic-probe-dataset_manifest.json`
- `audit`: `models/distillation/local-enhanced-20260524-strategic-probe-trace_audit.json`
- `quality`: `models/distillation/local-enhanced-20260524-strategic-probe-quality_report.json`
- `readiness`: `models/distillation/local-enhanced-20260524-strategic-probe-readiness.json`
- `metrics`: `models/distillation/local-enhanced-20260524-strategic-probe-mlp/metrics.json`
- `gameplay`: `models/distillation/local-enhanced-20260524-strategic-probe-mlp/gameplay_vs_hard.json`
