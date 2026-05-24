# Distillation Data Quality Report

- Rows: 2070
- Sessions: 134
- Actors: 5
- Preferred source: strategic_heuristic (100.0%)
- Training gates passed: yes
- DeepSeek production ready: no
- Label quality tier: local_baseline

## Quality Gates
- PASS `min_rows`: 2070 / 1500 rows
- PASS `all_decision_kinds`: covered
- PASS `preferred_source_95pct`: strategic_heuristic 100.0%
- PASS `beats_first_candidate`: top1 0.688 vs first 0.409
- PASS `beats_random`: top1 0.688 vs random 0.130

## Decision Coverage
- `JUST_SAY_NO`: 250
- `OVERFLOW_DISCARD`: 120
- `PAYMENT`: 500
- `PLAY_CARD`: 1200

## Teacher Sources
- `strategic_heuristic`: 2070

## Candidate Counts
- Avg: 16.49
- P50: 14
- P90: 32
- Min/Max: 1 / 32

## Game Shape
- Rounds: 1 to 14
- Player counts: {'2': 515, '3': 518, '4': 518, '5': 519}
- Actions used when deciding: {'0': 1001, '1': 605, '2': 344, '3': 120}

## Token Usage
- No DeepSeek usage metadata recorded.

## Metrics
- Split: session
- Kind balance weights: {'JUST_SAY_NO': 2.19089, 'OVERFLOW_DISCARD': 3.162278, 'PAYMENT': 1.549193, 'PLAY_CARD': 1.0}
- train: top1=0.737, mrr=0.846, first=0.415, random=0.122
- validation: top1=0.688, mrr=0.810, first=0.409, random=0.130

## Model Comparison
| Model | Validation Top1 | Validation MRR | First Baseline | Random Baseline |
|---|---:|---:|---:|---:|
| mlp | 0.688 | 0.810 | 0.409 | 0.130 |
| linear | 0.559 | 0.707 | 0.409 | 0.130 |
| knn | 0.372 | 0.562 | 0.409 | 0.130 |

## Gameplay Evaluation
| Opponent | Games | Natural Win Rate | Ranker Win Rate | Avg Snapshots | Avg Ranker Board Rank | Board Lead Rate | End Reasons |
|---|---:|---:|---:|---:|---:|---:|---|
| hard | 3 | 0.333 | 0.333 | 214.3 | 1.67 | 0.667 | {'NATURAL_OR_LIMIT': 1, 'LOCAL_RANKER_EVAL_SNAPSHOT_LIMIT': 2} |
