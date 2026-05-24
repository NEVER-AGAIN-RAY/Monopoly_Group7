# Distillation Data Quality Report

- Rows: 2569
- Sessions: 80
- Actors: 5
- Preferred source: deepseek (100.0%)
- Training gates passed: yes
- DeepSeek production ready: yes
- Label quality tier: deepseek_teacher

## Quality Gates
- PASS `min_rows`: 2569 / 1000 rows
- PASS `all_decision_kinds`: covered
- PASS `preferred_source_95pct`: deepseek 100.0%

## Decision Coverage
- `JUST_SAY_NO`: 123
- `OVERFLOW_DISCARD`: 9
- `PAYMENT`: 407
- `PLAY_CARD`: 2030

## Teacher Sources
- `deepseek`: 2569

## Candidate Counts
- Avg: 8.10
- P50: 7
- P90: 15
- Min/Max: 1 / 32

## Game Shape
- Rounds: 1 to 7
- Player counts: {'2': 632, '3': 650, '4': 646, '5': 641}
- Actions used when deciding: {'0': 908, '1': 864, '2': 788, '3': 9}

## Token Usage
- Rows with usage: 2569
- Prompt token share: 2509162
- Completion token share: 80643
- Total token share: 2589805
- Avg total tokens per decision: 1008.1

## Metrics
- No metrics file supplied.
