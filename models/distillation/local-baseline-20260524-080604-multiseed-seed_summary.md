# Monopoly Deal Multi-Seed Training Summary

- Runs: 3
- Ready runs: 3
- Production-ready runs: 0

## Aggregate Metrics
| Metric | Mean | Std | Min | Max |
|---|---:|---:|---:|---:|
| `validationTop1` | 0.810 | 0.016 | 0.791 | 0.830 |
| `validationMrr` | 0.892 | 0.009 | 0.881 | 0.904 |
| `firstCandidateTop1` | 0.332 | 0.011 | 0.317 | 0.343 |
| `randomExpectedTop1` | 0.218 | 0.006 | 0.213 | 0.227 |
| `averageRankerBoardRank` | 1.833 | 0.118 | 1.750 | 2.000 |
| `rankerBoardLeadRate` | 0.333 | 0.236 | 0.000 | 0.500 |
| `rankerWinRate` | 0.250 | 0.204 | 0.000 | 0.500 |
| `naturalWinRate` | 0.750 | 0.204 | 0.500 | 1.000 |

## Runs
| Output | Ready | Production | Rows | Top1 | MRR | Board Rank | Lead Rate | Win Rate | Evaluated/Natural |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| `models/distillation/local-baseline-20260524-080604-multiseed-seed11` | True | False | 4774 | 0.809 | 0.890 | 2.00 | 0.000 | 0.000 | 4/2 |
| `models/distillation/local-baseline-20260524-080604-multiseed-seed42` | True | False | 4774 | 0.830 | 0.904 | 1.75 | 0.500 | 0.500 | 4/4 |
| `models/distillation/local-baseline-20260524-080604-multiseed-seed73` | True | False | 4774 | 0.791 | 0.881 | 1.75 | 0.500 | 0.250 | 4/3 |
