# Distillation Data Quality Report

- Rows: 4774
- Sessions: 60
- Actors: 5
- Preferred source: local_heuristic (100.0%)
- Training gates passed: yes
- DeepSeek production ready: no
- Label quality tier: local_baseline

## Quality Gates
- PASS `min_rows`: 4774 / 1000 rows
- PASS `all_decision_kinds`: covered
- PASS `preferred_source_95pct`: local_heuristic 100.0%
- PASS `beats_first_candidate`: top1 0.809 vs first 0.343
- PASS `beats_random`: top1 0.809 vs random 0.227

## Decision Coverage
- `JUST_SAY_NO`: 178
- `OVERFLOW_DISCARD`: 66
- `PAYMENT`: 840
- `PLAY_CARD`: 3690

## Teacher Sources
- `local_heuristic`: 4774

## Candidate Counts
- Avg: 9.41
- P50: 7
- P90: 22
- Min/Max: 1 / 32

## Game Shape
- Rounds: 1 to 13
- Player counts: {'2': 1055, '3': 1267, '4': 1223, '5': 1229}
- Actions used when deciding: {'0': 1738, '1': 1580, '2': 1390, '3': 66}

## Token Usage
- No DeepSeek usage metadata recorded.

## Metrics
- Split: session
- Kind balance weights: {'JUST_SAY_NO': 4.0, 'OVERFLOW_DISCARD': 4.0, 'PAYMENT': 2.095914, 'PLAY_CARD': 1.0}
- train: top1=0.815, mrr=0.897, first=0.343, random=0.213
- validation: top1=0.809, mrr=0.890, first=0.343, random=0.227

## Model Comparison
| Model | Validation Top1 | Validation MRR | First Baseline | Random Baseline |
|---|---:|---:|---:|---:|
| mlp | 0.809 | 0.890 | 0.343 | 0.227 |
| linear | 0.694 | 0.816 | 0.343 | 0.227 |
| knn | 0.416 | 0.591 | 0.343 | 0.227 |

## Gameplay Evaluation
| Opponent | Games | Natural Win Rate | Ranker Win Rate | Avg Snapshots | Avg Ranker Board Rank | Board Lead Rate | End Reasons |
|---|---:|---:|---:|---:|---:|---:|---|
| hard | 4 | 0.500 | 0.000 | 216.0 | 2.00 | 0.000 | {'LOCAL_RANKER_EVAL_SNAPSHOT_LIMIT': 2, 'NATURAL_OR_LIMIT': 2} |
