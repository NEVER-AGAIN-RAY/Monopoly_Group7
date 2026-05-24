# Distillation Data Quality Report

- Rows: 2453
- Sessions: 48
- Actors: 5
- Preferred source: local_heuristic (100.0%)
- Training gates passed: yes
- DeepSeek production ready: no
- Label quality tier: local_baseline

## Quality Gates
- PASS `min_rows`: 2453 / 2000 rows
- PASS `all_decision_kinds`: covered
- PASS `preferred_source_95pct`: local_heuristic 100.0%
- PASS `beats_first_candidate`: top1 0.764 vs first 0.299
- PASS `beats_random`: top1 0.764 vs random 0.212

## Decision Coverage
- `JUST_SAY_NO`: 93
- `OVERFLOW_DISCARD`: 35
- `PAYMENT`: 409
- `PLAY_CARD`: 1916

## Teacher Sources
- `local_heuristic`: 2453

## Candidate Counts
- Avg: 8.75
- P50: 7
- P90: 17
- Min/Max: 1 / 32

## Game Shape
- Rounds: 1 to 9
- Player counts: {'2': 621, '3': 606, '4': 619, '5': 607}
- Actions used when deciding: {'0': 908, '1': 797, '2': 713, '3': 35}

## Metrics
- train: top1=0.803, mrr=0.886, first=0.332, random=0.213
- validation: top1=0.764, mrr=0.865, first=0.299, random=0.212
