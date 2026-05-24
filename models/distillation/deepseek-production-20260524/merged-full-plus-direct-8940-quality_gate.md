# Monopoly Deal AI Quality Gate

Generated: `2026-05-24T10:01:01+00:00`

## Verdict

- Status: **production-training-ready**
- Local training ready: True
- Production data ready: True
- Paper evidence ready: False

## Trace Summary

- Rows: 8940
- Sessions: 214
- Teacher sources: `{"deepseek":8940}`
- Decision kinds: `{"JUST_SAY_NO":501,"OVERFLOW_DISCARD":173,"PAYMENT":1747,"PLAY_CARD":6519}`
- Player counts: `{"2":2049,"3":2320,"4":2273,"5":2298}`
- Rows with token usage: 8940
- First-choice ratio: 0.394

## Training Metrics

- Runs: 3
- Ready runs: 3
- Production-ready runs: 0
- Validation top-1 mean/std: 0.814 / 0.005
- Validation MRR mean/std: 0.894 / 0.004
- Ranker win rate mean/std: 0.167 / 0.000

## localTraining

- Ready: True
- Failures: 0

| Check | OK | Detail |
|---|---:|---|
| `trace_exists` | True | data/distillation/deepseek-production-20260524/merged-full-plus-direct-8940.jsonl |
| `min_local_rows` | True | 8940 / 5000 |
| `all_decision_kinds` | True | {"JUST_SAY_NO":501,"OVERFLOW_DISCARD":173,"PAYMENT":1747,"PLAY_CARD":6519} |
| `readiness_local_ready` | True | True |
| `three_seed_summary` | True | 3 / 3 |
| `all_seed_runs_ready` | True | 3 / 3 |
| `validation_top1_floor` | True | 0.814 / 0.550 |
| `validation_beats_first_candidate` | True | 0.814 vs 0.378 |
| `model_json_exists` | True | models/distillation/local-enhanced-20260524-multiseed-seed73-mlp/candidate_ranker_mlp.json |
| `gameplay_json_exists` | True | models/distillation/local-enhanced-20260524-multiseed-seed73-mlp/gameplay_vs_hard.json |

## productionData

- Ready: True
- Failures: 0

| Check | OK | Detail |
|---|---:|---|
| `production_prefix_provided` | True | models/distillation/deepseek-production-20260524/merged-full-plus-direct-8940 |
| `production_readiness_ready` | True | True |
| `production_seed_or_single_ready_run` | True | single production readiness accepted |
| `production_ready_seed_runs` | True | 1 / 1 |
| `min_deepseek_rows` | True | 8940 / 5000 |
| `deepseek_source_ratio` | True | {"deepseek":8940} |
| `token_usage_present` | True | 8940 / 8940 |
| `rare_kind_rows` | True | min 100; counts={"JUST_SAY_NO":501,"OVERFLOW_DISCARD":173,"PAYMENT":1747,"PLAY_CARD":6519} |

## paperEvidence

- Ready: False
- Failures: 3

| Check | OK | Detail |
|---|---:|---|
| `production_data_gate_ready` | True | True |
| `three_or_more_seed_runs` | True | 3 / 3 |
| `all_seed_runs_production_ready` | False | 0 / 3 |
| `gameplay_matrix_present` | True | True |
| `reportable_cells_present` | False | 5-easy,5-hard,5-normal |
| `games_per_cell_floor` | False | 2-easy:2/50,2-hard:2/50,2-normal:2/50,3-easy:2/50,3-hard:2/50,3-normal:2/50,4-easy:2/50,4-hard:2/50 |
| `natural_completion_reported` | True | 14 completed |

## Next Actions

- After production readiness, run a 2/3/4/5-player by easy/normal/hard gameplay matrix with at least 50 games per cell.
- Add scaling curves and ablations before making paper-strength claims.
