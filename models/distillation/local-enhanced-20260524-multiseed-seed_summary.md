# Monopoly Deal Multi-Seed Training Summary

- Runs: 3
- Ready runs: 3
- Production-ready runs: 0

## Aggregate Metrics
| Metric | Mean | Std | Min | Max |
|---|---:|---:|---:|---:|
| `validationTop1` | 0.814 | 0.005 | 0.809 | 0.820 |
| `validationMrr` | 0.894 | 0.004 | 0.889 | 0.898 |
| `firstCandidateTop1` | 0.378 | 0.018 | 0.357 | 0.401 |
| `randomExpectedTop1` | 0.220 | 0.009 | 0.208 | 0.229 |
| `averageRankerBoardRank` | 1.889 | 0.079 | 1.833 | 2.000 |
| `rankerBoardLeadRate` | 0.222 | 0.079 | 0.167 | 0.333 |
| `rankerWinRate` | 0.167 | 0.000 | 0.167 | 0.167 |
| `naturalWinRate` | 0.889 | 0.079 | 0.833 | 1.000 |

## Runs
| Output | Ready | Production | Rows | Top1 | MRR | Board Rank | Lead Rate | Win Rate | Evaluated/Natural |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| `models/distillation/local-enhanced-20260524-multiseed-seed11` | True | False | 6372 | 0.809 | 0.889 | 1.83 | 0.333 | 0.167 | 6/5 |
| `models/distillation/local-enhanced-20260524-multiseed-seed42` | True | False | 6372 | 0.815 | 0.896 | 2.00 | 0.167 | 0.167 | 6/5 |
| `models/distillation/local-enhanced-20260524-multiseed-seed73` | True | False | 6372 | 0.820 | 0.898 | 1.83 | 0.167 | 0.167 | 6/6 |
