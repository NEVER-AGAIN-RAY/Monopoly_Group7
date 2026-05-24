# Monopoly Deal AI Next-Day Handoff

Generated: `2026-05-24T06:58:49+00:00`

## Verdict

- Status: **local-ready-only**
- Local ready: True
- Production ready: False
- Production blocker: No production prefix was provided; DeepSeek production run has not been completed in this checkout.
- DeepSeek key present in this shell: False
- Representative local run: `models/distillation/local-enhanced-20260524-multiseed-seed73`

## What Is Ready

- Trace: `data/distillation/local-enhanced-20260524.jsonl` (33.77 MB)
- Rows: 6372
- Sessions: 134
- Teacher sources: `{"local_heuristic":6372}`
- Decision kinds: `{"JUST_SAY_NO":378,"OVERFLOW_DISCARD":164,"PAYMENT":1340,"PLAY_CARD":4490}`
- Rows with token usage: 0
- Readiness mode: `local`, ready=True

## Training Evidence

- Runs: 3
- Local-ready runs: 3
- Production-ready runs: 0
- Validation top-1 mean/std: 0.814 / 0.005
- Validation MRR mean/std: 0.894 / 0.004
- First-candidate baseline mean/std: 0.378 / 0.018
- Random expected baseline mean/std: 0.220 / 0.009
- Ranker win rate mean/std: 0.167 / 0.000
- Average ranker board rank mean/std: 1.889 / 0.079
- Representative model: `models/distillation/local-enhanced-20260524-multiseed-seed73-mlp/candidate_ranker_mlp.json`

## Gameplay Matrix Smoke

- Runs: 9
- Games requested/completed: 18 / 14
- Ranker win rate: 0.278
- Average board rank: 1.89
- Board lead rate: 0.389
- End reasons: `{"LOCAL_RANKER_EVAL_SNAPSHOT_LIMIT":4,"NATURAL_OR_LIMIT":14}`

## Quality Gate

- Status: **local-training-ready**
- Local training ready: True
- Production data ready: False
- Paper evidence ready: False
- Failures local/production/paper: 0 / 9 / 4

## Claim Boundaries

- Safe: the Mac pipeline can generate backend-legal decision traces, train MLP/linear/KNN rankers, export Java-loadable models, and run gameplay evaluation.
- Safe: the current enhanced local trace is a local heuristic baseline with complete local coverage across decision kinds.
- Unsafe: calling the current trace DeepSeek-quality or production training data.
- Unsafe: using the current gameplay matrix as a paper-grade performance claim; it is a smoke matrix with few games.

## Next Commands

### Refresh local status report

```bash
python3 scripts/check_ai_delivery_status.py --output models/distillation/local-enhanced-20260524-multiseed-delivery_status.json
```

### Fast paid relabel probe when DeepSeek key is available

```bash
DEEPSEEK_API_KEY=... scripts/run_relabel_paid_probe.sh data/distillation/local-enhanced-20260524.jsonl data/distillation/deepseek-relabel-probe.jsonl models/distillation/deepseek-relabel-probe
```

### Full paid DeepSeek production pipeline after probe approval

```bash
DEEPSEEK_API_KEY=... MONOPOLY_PRODUCTION_RUN_ID=deepseek-run1 MONOPOLY_PAID_CONFIRM=run-paid-overnight scripts/run_deepseek_production_pipeline.sh
```

### Windows 5090 training after production trace exists

```bash
MONOPOLY_TRAIN_SOURCES=deepseek MONOPOLY_MIN_TRAIN_ROWS=5000 scripts/run_seed_replicates.sh data/distillation/deepseek-run1-merged.jsonl models/distillation/deepseek-run1
```

### Production gameplay matrix

```bash
MONOPOLY_MATRIX_GAMES=50 MONOPOLY_MATRIX_PLAYERS=2,3,4,5 MONOPOLY_MATRIX_OPPONENTS=easy,normal,hard scripts/evaluate_gameplay_matrix.sh models/distillation/deepseek-run1-seed73-mlp/candidate_ranker_mlp.json models/distillation/deepseek-run1-gameplay-matrix
```

## Paper Direction

- Frame the system as legal-action policy distillation for a complex rule-based card game.
- Keep the student constrained to Java-generated legal candidates; the model ranks actions instead of inventing protocol moves.
- Paper-grade evidence still needs DeepSeek or stronger teacher labels, multi-seed learning curves, larger gameplay matrices, and ablation baselines.

## Key Files

| Label | Exists | Size MB | Path |
|---|---:|---:|---|
| `seedSummary` | True | 0.00 | `models/distillation/local-enhanced-20260524-multiseed-seed_summary.md` |
| `gameplayMatrix` | True | 0.00 | `models/distillation/local-enhanced-20260524-multiseed-gameplay-matrix-smoke/summary.md` |
| `representativeRunSummary` | True | 0.00 | `models/distillation/local-enhanced-20260524-multiseed-seed73-run_summary.md` |
| `representativeReadiness` | True | 0.00 | `models/distillation/local-enhanced-20260524-multiseed-seed73-readiness.json` |
| `representativeTraceAudit` | True | 0.00 | `models/distillation/local-enhanced-20260524-multiseed-seed73-trace_audit.json` |
| `representativeModelJson` | True | 1.74 | `models/distillation/local-enhanced-20260524-multiseed-seed73-mlp/candidate_ranker_mlp.json` |
| `representativeGameplay` | True | 0.01 | `models/distillation/local-enhanced-20260524-multiseed-seed73-mlp/gameplay_vs_hard.json` |
| `artifactsArchive` | True | 1.63 | `models/distillation/local-enhanced-20260524-multiseed-artifacts.tar.gz` |
| `handoffArchive` | True | 3.82 | `models/distillation/local-enhanced-20260524-multiseed-training-handoff.tar.gz` |
| `deliveryStatus` | True | 0.00 | `models/distillation/local-enhanced-20260524-multiseed-delivery_status.json` |
| `qualityGateMarkdown` | True | 0.00 | `models/distillation/local-enhanced-20260524-multiseed-quality_gate.md` |
| `qualityGateJson` | True | 0.01 | `models/distillation/local-enhanced-20260524-multiseed-quality_gate.json` |
| `handoffReportMarkdown` | True | 0.01 | `models/distillation/local-enhanced-20260524-multiseed-handoff_report.md` |
| `handoffReportJson` | True | 0.01 | `models/distillation/local-enhanced-20260524-multiseed-handoff_report.json` |
| `deliveryChecklist` | True | 0.01 | `docs/ai-delivery-checklist.md` |
| `trainingLog` | True | 0.05 | `docs/ai-training-log.md` |
| `researchPlan` | True | 0.01 | `docs/ai-research-experiment-plan.md` |

## Validation Commands

- `python3 -m unittest tests.test_distill_dataset`
- `mvn -q test -Dtest=DecisionBrokerTest,SimulationWorkerTest,DeepSeekClientConfigTest,TraceRelabelerTest`
- `python3 scripts/check_ai_quality_gate.py --require local`
- `python3 scripts/check_ai_delivery_status.py --output models/distillation/local-enhanced-20260524-multiseed-delivery_status.json`
- `python3 scripts/summarize_ai_handoff.py`
