# AI Cost and Experiment Roadmap

## Current Constraint

No DeepSeek key is configured in the current shell. The code can generate local heuristic data and train students now; DeepSeek-quality data starts when `DEEPSEEK_API_KEY` or `MONOPOLY_DEEPSEEK_API_KEY` is set.

## Practical Run Levels

| Level | Labels | Purpose | Machine | Expected Output |
|---|---:|---|---|---|
| Smoke | 50-500 | Verify pipeline and schema | Mac M5 Pro | JSONL, MLP/linear artifacts, quality report |
| First paid | 5k-20k | First meaningful DeepSeek student | Mac M5 Pro | Usable local ranker candidate, per-kind metrics |
| Paper pilot | 100k | Scaling curve and ablations | Mac or 5090 | Stable imitation metrics, gameplay comparisons |
| Large study | 500k-1M | Publishable-scale evidence | 5090 preferred | Multiple seeds, model-family comparison |

The Mac M5 Pro is enough for the current 119-feature MLP and linear ranker. Use the 5090 laptop when training larger neural models, GBDT sweeps, or 100k+ row ablations.

## Cost Planning

The exact cost depends on DeepSeek account pricing and tokenization, so record actual API usage after the first paid run. Engineering estimate for this project:

- One batched decision case is compact but still includes public game state, private hand, candidates, and summaries.
- Batch requests amortize shared system/rule/strategy text across cases.
- Start with 5k labels, inspect prompt/completion tokens, then scale.
- DeepSeek usage metadata is written into each trace row when the API response includes token usage. `dataset_manifest.json` and `quality_report.md` aggregate `promptTokensShare`, `completionTokensShare`, and `avgTotalTokensPerDecision` across rows.

Recommended budget checkpoints:

- Low-risk trial: enough balance for 5k labels. Goal is to verify source ratio, parse fallback rate, and first DeepSeek model quality.
- Useful student: 20k-50k labels. Goal is to beat hard heuristic in some fixed-seat evaluations.
- Paper pilot: 100k labels. Goal is a clear scaling curve plus ablations.
- Publishable push: 500k+ labels if the 100k curve is still improving.

Do not spend heavily until the quality report shows at least 95% `deepseek` rows and low fallback rates.

After a paid trace exists, build the scaling curve from subsets of that same trace rather than making new DeepSeek calls for every curve point:

```bash
python3 scripts/make_scaling_subsets.py \
  data/distillation/deepseek-run1.jsonl \
  --output-dir models/distillation/deepseek-run1-subsets \
  --sizes 1000,5000,20000,100000 \
  --prefix deepseek-run1
```

The subset files are cumulative and stratified, so `5000` contains the `1000` examples plus more, while preserving rare decision kinds as much as the source trace allows.

After the first paid probe, estimate cost with:

```text
estimated_cost =
  prompt_tokens_share / 1_000_000 * prompt_price_per_million
  + completion_tokens_share / 1_000_000 * completion_price_per_million
```

Use the prices shown in the DeepSeek account dashboard at run time. Do not hard-code old prices into the repo; provider pricing can change.

The helper command is:

```bash
scripts/estimate_deepseek_cost.py \
  models/distillation/deepseek-run1-dataset_manifest.json \
  --prompt-price-per-million <dashboard-prompt-price> \
  --completion-price-per-million <dashboard-completion-price> \
  --target-labels 100000
```

Use `scripts/preflight_paid_collection.sh data/distillation/deepseek-run1.jsonl` before spending tokens. It fails fast when the key is absent or the trace path would accidentally reuse an old file.

Run a small paid probe before overnight collection:

```bash
DEEPSEEK_API_KEY=... \
MONOPOLY_PROMPT_PRICE_PER_MILLION=<dashboard-prompt-price> \
MONOPOLY_COMPLETION_PRICE_PER_MILLION=<dashboard-completion-price> \
scripts/run_paid_probe.sh \
  data/distillation/deepseek-probe-run1.jsonl \
  models/distillation/deepseek-probe-run1
```

Scale only if the probe report shows mostly `deepseek` rows, no severe fallback issue, and acceptable projected cost.
The probe also writes `<output-prefix>-trace_audit.json`; do not scale if it fails `preferred_source_ratio`, `valid_choice_ids`, `duplicate_decision_ids`, `first_choice_bias`, or `token_usage_present`.

When a local trace already has useful legal-state coverage, use offline relabeling to estimate quality and token cost without rerunning simulation:

```bash
DEEPSEEK_API_KEY=... \
scripts/run_relabel_paid_probe.sh \
  data/distillation/local-enhanced-20260524.jsonl \
  data/distillation/deepseek-relabel-probe.jsonl \
  models/distillation/deepseek-relabel-probe
```

Lower-level equivalent:

```bash
DEEPSEEK_API_KEY=... \
MONOPOLY_RELABEL_SELECT=true \
MONOPOLY_RELABEL_MAX_BY_KIND=PLAY_CARD:250,PAYMENT:120,JUST_SAY_NO:80,OVERFLOW_DISCARD:50 \
scripts/relabel_distillation_trace.sh \
  data/distillation/local-enhanced-20260524.jsonl \
  data/distillation/deepseek-relabel-probe.jsonl \
  models/distillation/deepseek-relabel-probe
```

This spends tokens only on labeling existing candidates. The enhanced local trace is the preferred relabel source because it has 6372 backend-legal decisions and clears the local rare-kind floor. The selector keeps the probe representative enough to inspect rare response decisions and writes a selection report before paid relabeling. It is good for a fast probe, but production readiness still depends on the relabeled trace passing the same DeepSeek source, token-usage, row-count, and rare-kind gates.

The guarded production wrapper performs the same two-stage flow with an explicit paid-confirmation stop:

```bash
DEEPSEEK_API_KEY=... \
MONOPOLY_PRODUCTION_RUN_ID=deepseek-run1 \
MONOPOLY_PROMPT_PRICE_PER_MILLION=<dashboard-prompt-price> \
MONOPOLY_COMPLETION_PRICE_PER_MILLION=<dashboard-completion-price> \
scripts/run_deepseek_production_pipeline.sh

DEEPSEEK_API_KEY=... \
MONOPOLY_PRODUCTION_RUN_ID=deepseek-run1 \
MONOPOLY_PAID_CONFIRM=run-paid-overnight \
scripts/run_deepseek_production_pipeline.sh
```

The first command runs only the paid probe and then exits. The second command reuses the probe, runs the larger collection, merges traces, audits the merged trace, trains the students, checks production readiness, and packages artifacts.

After training, use readiness as the handoff decision:

```bash
python3 scripts/check_training_readiness.py \
  data/distillation/deepseek-run1.jsonl \
  models/distillation/deepseek-run1 \
  --mode production \
  --output models/distillation/deepseek-run1-readiness.json
```

Do not call a run production-ready unless this report has `"ready": true`. A local heuristic run may pass `--mode local`, but that only proves the pipeline and does not count as high-quality DeepSeek training data.

If several probe or overnight traces were collected separately, merge them before cost reporting, scaling subsets, and training:

```bash
python3 scripts/merge_distillation_traces.py \
  data/distillation/deepseek-probe-run*.jsonl \
  --include-sources deepseek \
  --output data/distillation/deepseek-merged.jsonl \
  --report models/distillation/deepseek-merged-trace_merge.json
```

Keep the default conflict policy. A conflicting duplicate `decisionId` means two labels claim to answer the same backend decision, so the merged training set should stop until that source trace is inspected.

## Overnight Command

```bash
DEEPSEEK_API_KEY=... \
MONOPOLY_SIM_GAMES=300 \
MONOPOLY_SIM_PARALLEL=8 \
MONOPOLY_SIM_PLAYER_COUNTS=2,3,4,5 \
MONOPOLY_SIM_BATCH_SIZE=32 \
MONOPOLY_MIN_TRAIN_ROWS=5000 \
MONOPOLY_TRAIN_EPOCHS=20 \
MONOPOLY_EVAL_GAMES=50 \
MONOPOLY_EVAL_OPPONENT_STRATEGY=hard \
scripts/overnight_distillation_run.sh \
  data/distillation/deepseek-overnight.jsonl \
  models/distillation/deepseek-overnight
```

If no key is set, this command falls back to local heuristic data. That is useful for dry-runs only.

The overnight script refuses to write into an existing trace path by default. Keep that behavior for paid runs so manifests and quotas describe one clean collection run. Use `MONOPOLY_TRACE_MODE=append` only for a deliberate resume, and record that choice in the training log.

## Evaluation Matrix

For each trained model:

| Evaluation | Settings |
|---|---|
| Imitation | top-1, MRR, first-candidate baseline, random baseline |
| Coverage | rows by decision kind, candidate counts, player counts, rounds |
| Runtime smoke | all ranker players, 3 players, 240+ snapshots |
| Fixed-seat gameplay | ranker seat 1 vs Easy/Normal/Hard, 2/3/4 players |
| Traditional baseline | KNN nearest-neighbor metrics against the same train/validation split |
| Ablation | only `PLAY_CARD`, `PLAY_CARD+PAYMENT`, all four kinds |
| Generalization | train on 2/3 players, test on 4/5 players |

Minimum reportable gameplay result: 50 games per cell. Better: 200 games per cell with at least three random seeds.

Current local pilot matrix:

- Model: `models/distillation/local-enhanced-20260524-multiseed-seed73-mlp/candidate_ranker_mlp.json`
- Output: `models/distillation/local-enhanced-20260524-multiseed-gameplay-matrix-smoke/summary.md`
- Conditions: 2/3/4 players x easy/normal/hard, ranker seat 1, 2 games per cell
- Games requested: 18
- Natural completions: 14
- Ranker win rate: 0.278
- Average ranker board rank: 1.89
- Board lead rate: 0.389

This is a smoke/pilot matrix only. It proves the evaluation harness and gives an early warning that the local-teacher student struggles in 3/4-player games. For a reportable production run, use at least:

```bash
MONOPOLY_MATRIX_GAMES=50 \
MONOPOLY_MATRIX_SNAPSHOTS=500 \
MONOPOLY_MATRIX_PLAYERS=2,3,4,5 \
MONOPOLY_MATRIX_OPPONENTS=easy,normal,hard \
MONOPOLY_MATRIX_SEATS=1 \
scripts/evaluate_gameplay_matrix.sh \
  models/distillation/deepseek-run1-mlp/candidate_ranker_mlp.json \
  models/distillation/deepseek-run1-gameplay-matrix
```

## Research Framing

The paper angle is not "LLM plays a card game." It is:

> Legal-action distillation for a complex rule-based imperfect-information card game.

Core claim to test:

1. A symbolic engine guarantees legal states and legal actions.
2. A strong LLM labels only legal candidate choices.
3. A small local model ranks legal candidates and never emits invalid actions.
4. Batch labeling plus prompt caching makes data collection affordable.
5. Offline imitation metrics predict some, but not all, gameplay strength.

## Related Work Anchors

- DouZero: <https://arxiv.org/abs/2106.06135>
- Policy Distillation: <https://arxiv.org/abs/1511.06295>
- DeepStack: <https://arxiv.org/abs/1701.01724>
- Pluribus: <https://www.science.org/doi/10.1126/science.aay2400>
- Deep CFR: <https://arxiv.org/abs/1811.00164>
- ReBeL: <https://arxiv.org/abs/2007.13544>
- DeepNash: <https://www.nature.com/articles/s41586-022-05421-1>

These are comparison points, not direct templates. Monopoly Deal has hidden information, stochastic draws, multi-player negotiation-like attacks, and a large rule-coded action surface.
