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
- PASS `beats_first_candidate`: top1 0.830 vs first 0.337
- PASS `beats_random`: top1 0.830 vs random 0.213

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
- train: top1=0.815, mrr=0.897, first=0.345, random=0.216
- validation: top1=0.830, mrr=0.904, first=0.337, random=0.213

## Model Comparison
| Model | Validation Top1 | Validation MRR | First Baseline | Random Baseline |
|---|---:|---:|---:|---:|
| mlp | 0.830 | 0.904 | 0.337 | 0.213 |
| linear | 0.684 | 0.802 | 0.337 | 0.213 |
| knn | 0.393 | 0.578 | 0.337 | 0.213 |

## Gameplay Evaluation
| Opponent | Games | Natural Win Rate | Ranker Win Rate | Avg Snapshots | Avg Ranker Board Rank | Board Lead Rate | End Reasons |
|---|---:|---:|---:|---:|---:|---:|---|
| hard | 4 | 1.000 | 0.500 | 176.2 | 1.75 | 0.500 | {'NATURAL_OR_LIMIT': 4} |
