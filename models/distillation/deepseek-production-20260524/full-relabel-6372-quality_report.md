# Distillation Data Quality Report

- Rows: 6371
- Sessions: 134
- Actors: 5
- Preferred source: deepseek (100.0%)
- Training gates passed: yes
- DeepSeek production ready: yes
- Label quality tier: deepseek_teacher

## Quality Gates
- PASS `min_rows`: 6371 / 5000 rows
- PASS `all_decision_kinds`: covered
- PASS `preferred_source_95pct`: deepseek 100.0%
- PASS `beats_first_candidate`: top1 0.722 vs first 0.387
- PASS `beats_random`: top1 0.722 vs random 0.223

## Decision Coverage
- `JUST_SAY_NO`: 378
- `OVERFLOW_DISCARD`: 164
- `PAYMENT`: 1340
- `PLAY_CARD`: 4489

## Teacher Sources
- `deepseek`: 6371

## Candidate Counts
- Avg: 9.79
- P50: 7
- P90: 31
- Min/Max: 1 / 32

## Game Shape
- Rounds: 1 to 14
- Player counts: {'2': 1417, '3': 1670, '4': 1627, '5': 1657}
- Actions used when deciding: {'0': 2373, '1': 2066, '2': 1768, '3': 164}

## Token Usage
- Rows with usage: 6371
- Prompt token share: 7628648
- Completion token share: 203327
- Total token share: 7831975
- Avg total tokens per decision: 1229.3

## Metrics
- Split: session
- Kind balance weights: {'JUST_SAY_NO': 3.446108, 'OVERFLOW_DISCARD': 4.0, 'PAYMENT': 1.830301, 'PLAY_CARD': 1.0}
- train: top1=0.768, mrr=0.865, first=0.422, random=0.221
- validation: top1=0.722, mrr=0.830, first=0.387, random=0.223

## Model Comparison
| Model | Validation Top1 | Validation MRR | First Baseline | Random Baseline |
|---|---:|---:|---:|---:|
| mlp | 0.722 | 0.830 | 0.387 | 0.223 |
| linear | 0.613 | 0.763 | 0.387 | 0.223 |
| knn | 0.356 | 0.553 | 0.387 | 0.223 |
| forest | 0.588 | 0.744 | 0.387 | 0.223 |

## Gameplay Evaluation
| Opponent | Games | Natural Win Rate | Ranker Win Rate | Avg Snapshots | Avg Ranker Board Rank | Board Lead Rate | End Reasons |
|---|---:|---:|---:|---:|---:|---:|---|
| hard | 20 | 1.000 | 0.300 | 177.1 | 2.05 | 0.300 | {'NATURAL_OR_LIMIT': 20} |
