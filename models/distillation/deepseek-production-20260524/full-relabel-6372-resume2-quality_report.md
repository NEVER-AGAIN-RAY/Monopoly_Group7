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
- No metrics file supplied.
