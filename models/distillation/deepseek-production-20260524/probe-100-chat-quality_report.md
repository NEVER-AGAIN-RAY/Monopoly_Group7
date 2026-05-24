# Distillation Data Quality Report

- Rows: 100
- Sessions: 72
- Actors: 5
- Preferred source: deepseek (100.0%)
- Training gates passed: yes
- DeepSeek production ready: yes
- Label quality tier: deepseek_teacher

## Quality Gates
- PASS `min_rows`: 100 / 80 rows
- PASS `all_decision_kinds`: covered
- PASS `preferred_source_95pct`: deepseek 100.0%

## Decision Coverage
- `JUST_SAY_NO`: 20
- `OVERFLOW_DISCARD`: 15
- `PAYMENT`: 25
- `PLAY_CARD`: 40

## Teacher Sources
- `deepseek`: 100

## Candidate Counts
- Avg: 24.44
- P50: 32
- P90: 32
- Min/Max: 2 / 32

## Game Shape
- Rounds: 1 to 13
- Player counts: {'2': 25, '3': 25, '4': 25, '5': 25}
- Actions used when deciding: {'0': 59, '1': 13, '2': 13, '3': 15}

## Token Usage
- Rows with usage: 100
- Prompt token share: 200363
- Completion token share: 3227
- Total token share: 203590
- Avg total tokens per decision: 2035.9

## Metrics
- No metrics file supplied.
