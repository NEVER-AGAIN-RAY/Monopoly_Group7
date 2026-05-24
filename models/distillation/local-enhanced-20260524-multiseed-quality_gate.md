# Monopoly Deal AI Quality Gate

Generated: `2026-05-24T06:58:49+00:00`

## Verdict

- Status: **local-training-ready**
- Local training ready: True
- Production data ready: False
- Paper evidence ready: False

## Trace Summary

- Rows: 6372
- Sessions: 134
- Teacher sources: `{"local_heuristic":6372}`
- Decision kinds: `{"JUST_SAY_NO":378,"OVERFLOW_DISCARD":164,"PAYMENT":1340,"PLAY_CARD":4490}`
- Player counts: `{"2":1417,"3":1670,"4":1628,"5":1657}`
- Rows with token usage: 0
- First-choice ratio: 0.383

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
| `trace_exists` | True | data/distillation/local-enhanced-20260524.jsonl |
| `min_local_rows` | True | 6372 / 5000 |
| `all_decision_kinds` | True | {"JUST_SAY_NO":378,"OVERFLOW_DISCARD":164,"PAYMENT":1340,"PLAY_CARD":4490} |
| `readiness_local_ready` | True | True |
| `three_seed_summary` | True | 3 / 3 |
| `all_seed_runs_ready` | True | 3 / 3 |
| `validation_top1_floor` | True | 0.814 / 0.550 |
| `validation_beats_first_candidate` | True | 0.814 vs 0.378 |
| `model_json_exists` | True | models/distillation/local-enhanced-20260524-multiseed-seed73-mlp/candidate_ranker_mlp.json |
| `gameplay_json_exists` | True | models/distillation/local-enhanced-20260524-multiseed-seed73-mlp/gameplay_vs_hard.json |

## productionData

- Ready: False
- Failures: 9

| Check | OK | Detail |
|---|---:|---|
| `production_prefix_provided` | False |  |
| `production_readiness_ready` | False | None |
| `production_seed_summary_present` | False | False |
| `production_ready_seed_runs` | False | 0 / 1 |
| `min_deepseek_rows` | False | 0 / 5000 |
| `deepseek_source_ratio` | False | {} |
| `token_usage_present` | False | 0 / 0 |
| `rare_kind_rows` | False | min 100; counts={} |
| `current_trace_is_not_production` | False | current sources={"local_heuristic":6372}; use DeepSeek relabel or collection first |

## paperEvidence

- Ready: False
- Failures: 4

| Check | OK | Detail |
|---|---:|---|
| `production_data_gate_ready` | False | False |
| `three_or_more_seed_runs` | True | 3 / 3 |
| `all_seed_runs_production_ready` | False | 0 / 3 |
| `gameplay_matrix_present` | True | True |
| `reportable_cells_present` | False | 5-easy,5-hard,5-normal |
| `games_per_cell_floor` | False | 2-easy:2/50,2-hard:2/50,2-normal:2/50,3-easy:2/50,3-hard:2/50,3-normal:2/50,4-easy:2/50,4-hard:2/50 |
| `natural_completion_reported` | True | 14 completed |

## Next Actions

- Run a DeepSeek paid probe or relabel pass, then collect/merge at least 5000 DeepSeek rows with token usage.
- Train production multi-seed students with MONOPOLY_TRAIN_SOURCES=deepseek and rerun readiness in production mode.
- After production readiness, run a 2/3/4/5-player by easy/normal/hard gameplay matrix with at least 50 games per cell.
- Add scaling curves and ablations before making paper-strength claims.
