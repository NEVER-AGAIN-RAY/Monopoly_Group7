# Distillation Data Quality Report

- Rows: 8940
- Sessions: 214
- Actors: 5
- Preferred source: deepseek (100.0%)
- Training gates passed: yes
- DeepSeek production ready: yes
- Label quality tier: deepseek_teacher

## Quality Gates
- PASS `min_rows`: 8940 / 5000 rows
- PASS `all_decision_kinds`: covered
- PASS `preferred_source_95pct`: deepseek 100.0%
- PASS `beats_first_candidate`: top1 0.733 vs first 0.398
- PASS `beats_random`: top1 0.733 vs random 0.212

## Decision Coverage
- `JUST_SAY_NO`: 501
- `OVERFLOW_DISCARD`: 173
- `PAYMENT`: 1747
- `PLAY_CARD`: 6519

## Teacher Sources
- `deepseek`: 8940

## Candidate Counts
- Avg: 9.31
- P50: 7
- P90: 22
- Min/Max: 1 / 32

## Game Shape
- Rounds: 1 to 14
- Player counts: {'2': 2049, '3': 2320, '4': 2273, '5': 2298}
- Actions used when deciding: {'0': 3281, '1': 2930, '2': 2556, '3': 173}

## Token Usage
- Rows with usage: 8940
- Prompt token share: 10137810
- Completion token share: 283970
- Total token share: 10421780
- Avg total tokens per decision: 1165.7

## Metrics
- Split: session
- Kind balance weights: {'JUST_SAY_NO': 3.607212, 'OVERFLOW_DISCARD': 4.0, 'PAYMENT': 1.931719, 'PLAY_CARD': 1.0}
- train: top1=0.751, mrr=0.853, first=0.394, random=0.218
- validation: top1=0.733, mrr=0.841, first=0.398, random=0.212

## Model Comparison
| Model | Validation Top1 | Validation MRR | First Baseline | Random Baseline |
|---|---:|---:|---:|---:|
| mlp | 0.733 | 0.841 | 0.398 | 0.212 |
| linear | 0.663 | 0.794 | 0.398 | 0.212 |
| knn | 0.382 | 0.574 | 0.398 | 0.212 |
| forest | 0.568 | 0.728 | 0.398 | 0.212 |

## Gameplay Evaluation
| Opponent | Games | Natural Win Rate | Ranker Win Rate | Avg Snapshots | Avg Ranker Board Rank | Board Lead Rate | End Reasons |
|---|---:|---:|---:|---:|---:|---:|---|
| hard | 20 | 1.000 | 0.300 | 191.2 | 2.10 | 0.300 | {'NATURAL_OR_LIMIT': 20} |
