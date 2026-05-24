# Monopoly Deal Training Run Summary

- Output prefix: `models/distillation/local-baseline-20260524-080604-multiseed-seed11`
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
- Validation top-1: 0.809
- Validation MRR: 0.890
- First-candidate baseline: 0.343
- Random expected baseline: 0.227
- Per-kind top-1:
  - `JUST_SAY_NO`: 0.667 over 30 decisions
  - `OVERFLOW_DISCARD`: 0.714 over 7 decisions
  - `PAYMENT`: 0.900 over 150 decisions
  - `PLAY_CARD`: 0.797 over 708 decisions

## Model
- Type: `mlp`
- Device: `mps`
- Input dim: 119
- Epochs: 6
- Split: `session`
- Balance by kind: True

## Gameplay
- Opponent: `hard`
- Games requested/evaluated/natural: 4 / 4 / 2
- Natural win rate: 0.500
- Ranker win rate: 0.000
- Average ranker board rank: 2.00
- Board lead rate: 0.000
- End reasons: {'LOCAL_RANKER_EVAL_SNAPSHOT_LIMIT': 2, 'NATURAL_OR_LIMIT': 2}

## Token Usage
- No token usage metadata. This is expected for local heuristic runs and invalid for production DeepSeek readiness.

## Artifacts
- `manifest`: `models/distillation/local-baseline-20260524-080604-multiseed-seed11-dataset_manifest.json`
- `audit`: `models/distillation/local-baseline-20260524-080604-multiseed-seed11-trace_audit.json`
- `quality`: `models/distillation/local-baseline-20260524-080604-multiseed-seed11-quality_report.json`
- `readiness`: `models/distillation/local-baseline-20260524-080604-multiseed-seed11-readiness.json`
- `metrics`: `models/distillation/local-baseline-20260524-080604-multiseed-seed11-mlp/metrics.json`
- `gameplay`: `models/distillation/local-baseline-20260524-080604-multiseed-seed11-mlp/gameplay_vs_hard.json`
