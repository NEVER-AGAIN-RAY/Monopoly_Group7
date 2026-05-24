# Distillation Data Quality Report

- Rows: 6372
- Sessions: 134
- Actors: 5
- Preferred source: local_heuristic (100.0%)
- Training gates passed: yes
- DeepSeek production ready: no
- Label quality tier: local_baseline

## Quality Gates
- PASS `min_rows`: 6372 / 5000 rows
- PASS `all_decision_kinds`: covered
- PASS `preferred_source_95pct`: local_heuristic 100.0%
- PASS `beats_first_candidate`: top1 0.809 vs first 0.401
- PASS `beats_random`: top1 0.809 vs random 0.208

## Decision Coverage
- `JUST_SAY_NO`: 378
- `OVERFLOW_DISCARD`: 164
- `PAYMENT`: 1340
- `PLAY_CARD`: 4490

## Teacher Sources
- `local_heuristic`: 6372

## Candidate Counts
- Avg: 9.79
- P50: 7
- P90: 31
- Min/Max: 1 / 32

## Game Shape
- Rounds: 1 to 14
- Player counts: {'2': 1417, '3': 1670, '4': 1628, '5': 1657}
- Actions used when deciding: {'0': 2373, '1': 2066, '2': 1769, '3': 164}

## Token Usage
- No DeepSeek usage metadata recorded.

## Metrics
- Split: session
- Kind balance weights: {'JUST_SAY_NO': 3.446492, 'OVERFLOW_DISCARD': 4.0, 'PAYMENT': 1.830504, 'PLAY_CARD': 1.0}
- train: top1=0.816, mrr=0.897, first=0.380, random=0.224
- validation: top1=0.809, mrr=0.889, first=0.401, random=0.208

## Model Comparison
| Model | Validation Top1 | Validation MRR | First Baseline | Random Baseline |
|---|---:|---:|---:|---:|
| mlp | 0.809 | 0.889 | 0.401 | 0.208 |
| linear | 0.665 | 0.796 | 0.401 | 0.208 |
| knn | 0.426 | 0.600 | 0.401 | 0.208 |

## Gameplay Evaluation
| Opponent | Games | Natural Win Rate | Ranker Win Rate | Avg Snapshots | Avg Ranker Board Rank | Board Lead Rate | End Reasons |
|---|---:|---:|---:|---:|---:|---:|---|
| hard | 6 | 0.833 | 0.167 | 204.7 | 1.83 | 0.333 | {'NATURAL_OR_LIMIT': 5, 'LOCAL_RANKER_EVAL_SNAPSHOT_LIMIT': 1} |
