#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TRACE="${1:?Usage: scripts/package_training_handoff.sh data/distillation/run.jsonl models/distillation/run-prefix [archive-path]}"
OUT_PREFIX="${2:?Usage: scripts/package_training_handoff.sh data/distillation/run.jsonl models/distillation/run-prefix [archive-path]}"
ARCHIVE="${3:-$OUT_PREFIX-training-handoff.tar.gz}"
AGG_PREFIX="${MONOPOLY_PACKAGE_AGG_PREFIX:-$(printf '%s' "$OUT_PREFIX" | sed -E 's/-seed[0-9]+$//')}"
TMP_DIR="$(mktemp -d)"

cleanup() {
  rm -rf "$TMP_DIR"
}
trap cleanup EXIT

cd "$ROOT"

if [[ ! -f "$TRACE" ]]; then
  echo "[handoff] missing trace: $TRACE" >&2
  exit 2
fi

mkdir -p "$TMP_DIR/monopoly-training-handoff"

cp "$TRACE" "$TMP_DIR/monopoly-training-handoff/$(basename "$TRACE")"
for path in \
  "$OUT_PREFIX-dataset_manifest.json" \
  "$OUT_PREFIX-trace_audit.json" \
  "$OUT_PREFIX-quality_report.md" \
  "$OUT_PREFIX-quality_report.json" \
  "$OUT_PREFIX-readiness.json" \
  "$OUT_PREFIX-run_summary.md" \
  "$OUT_PREFIX-run_summary.json" \
  "$OUT_PREFIX-seed_summary.md" \
  "$OUT_PREFIX-seed_summary.json" \
  "$OUT_PREFIX-gameplay_matrix.md" \
  "$OUT_PREFIX-gameplay_matrix.json" \
  "$OUT_PREFIX-quality_gate.md" \
  "$OUT_PREFIX-quality_gate.json" \
  "$OUT_PREFIX-handoff_report.md" \
  "$OUT_PREFIX-handoff_report.json" \
  "$AGG_PREFIX-seed_summary.md" \
  "$AGG_PREFIX-seed_summary.json" \
  "$AGG_PREFIX-gameplay_matrix.md" \
  "$AGG_PREFIX-gameplay_matrix.json" \
  "$AGG_PREFIX-quality_gate.md" \
  "$AGG_PREFIX-quality_gate.json" \
  "$AGG_PREFIX-handoff_report.md" \
  "$AGG_PREFIX-handoff_report.json" \
  "data/distillation/local-enhanced-20260524-strategic-probe.jsonl" \
  "models/distillation/local-enhanced-20260524-strategic-probe-dataset_manifest.json" \
  "models/distillation/local-enhanced-20260524-strategic-probe-trace_audit.json" \
  "models/distillation/local-enhanced-20260524-strategic-probe-quality_report.md" \
  "models/distillation/local-enhanced-20260524-strategic-probe-quality_report.json" \
  "models/distillation/local-enhanced-20260524-strategic-probe-readiness.json" \
  "models/distillation/local-enhanced-20260524-strategic-probe-run_summary.md" \
  "models/distillation/local-enhanced-20260524-strategic-probe-run_summary.json" \
  "models/distillation/local-enhanced-20260524-strategic-probe-selection_report.json" \
  "models/distillation/local-enhanced-20260524-strategic-probe-relabel_summary.json" \
  "models/distillation/local-enhanced-20260524-strategic-probe-mlp/metrics.json" \
  "models/distillation/local-enhanced-20260524-strategic-probe-mlp/candidate_ranker_mlp.json" \
  "models/distillation/local-enhanced-20260524-strategic-probe-mlp/gameplay_vs_hard.json" \
  "models/distillation/local-enhanced-20260524-strategic-probe-linear/metrics.json" \
  "models/distillation/local-enhanced-20260524-strategic-probe-linear/candidate_ranker_linear.json" \
  "models/distillation/local-enhanced-20260524-strategic-probe-knn/metrics.json" \
  "$OUT_PREFIX-mlp/metrics.json" \
  "$OUT_PREFIX-mlp/candidate_ranker_mlp.json" \
  "$OUT_PREFIX-linear/metrics.json" \
  "$OUT_PREFIX-linear/candidate_ranker_linear.json" \
  "$OUT_PREFIX-knn/metrics.json" \
  "$OUT_PREFIX-forest/metrics.json"; do
  if [[ -f "$path" ]]; then
    mkdir -p "$TMP_DIR/monopoly-training-handoff/$(dirname "$path")"
    cp "$path" "$TMP_DIR/monopoly-training-handoff/$path"
  fi
done

for path in "$OUT_PREFIX-mlp"/gameplay_vs_*.json; do
  if [[ -f "$path" ]]; then
    mkdir -p "$TMP_DIR/monopoly-training-handoff/$(dirname "$path")"
    cp "$path" "$TMP_DIR/monopoly-training-handoff/$path"
  fi
done

for path in "$OUT_PREFIX-gameplay-matrix-smoke"/*.json "$OUT_PREFIX-gameplay-matrix-smoke"/*.md \
            "$AGG_PREFIX-gameplay-matrix-smoke"/*.json "$AGG_PREFIX-gameplay-matrix-smoke"/*.md; do
  if [[ -f "$path" ]]; then
    mkdir -p "$TMP_DIR/monopoly-training-handoff/$(dirname "$path")"
    cp "$path" "$TMP_DIR/monopoly-training-handoff/$path"
  fi
done

for seed_summary in "$AGG_PREFIX"-seed[0-9]*-run_summary.json; do
  [[ -f "$seed_summary" ]] || continue
  seed_prefix="${seed_summary%-run_summary.json}"
  for path in \
    "$seed_prefix-dataset_manifest.json" \
    "$seed_prefix-trace_audit.json" \
    "$seed_prefix-quality_report.md" \
    "$seed_prefix-quality_report.json" \
    "$seed_prefix-readiness.json" \
    "$seed_prefix-run_summary.md" \
    "$seed_prefix-run_summary.json" \
    "$seed_prefix-mlp/metrics.json" \
    "$seed_prefix-mlp/candidate_ranker_mlp.json" \
    "$seed_prefix-linear/metrics.json" \
    "$seed_prefix-linear/candidate_ranker_linear.json" \
    "$seed_prefix-knn/metrics.json" \
    "$seed_prefix-forest/metrics.json"; do
    if [[ -f "$path" ]]; then
      mkdir -p "$TMP_DIR/monopoly-training-handoff/$(dirname "$path")"
      cp "$path" "$TMP_DIR/monopoly-training-handoff/$path"
    fi
  done
  for path in "$seed_prefix-mlp"/gameplay_vs_*.json; do
    if [[ -f "$path" ]]; then
      mkdir -p "$TMP_DIR/monopoly-training-handoff/$(dirname "$path")"
      cp "$path" "$TMP_DIR/monopoly-training-handoff/$path"
    fi
  done
done

mkdir -p "$TMP_DIR/monopoly-training-handoff/scripts" "$TMP_DIR/monopoly-training-handoff/docs"
cp scripts/distill_dataset.py \
   scripts/dataset_manifest.py \
   scripts/report_distillation_quality.py \
   scripts/audit_distillation_trace.py \
   scripts/select_relabel_trace.py \
   scripts/merge_distillation_traces.py \
   scripts/check_training_readiness.py \
   scripts/check_ai_delivery_status.py \
   scripts/check_ai_quality_gate.py \
   scripts/summarize_ai_handoff.py \
   scripts/summarize_training_run.py \
   scripts/evaluate_distilled_ranker.sh \
   scripts/evaluate_local_ranker.sh \
   scripts/evaluate_gameplay_matrix.sh \
   scripts/summarize_gameplay_matrix.py \
   scripts/make_scaling_subsets.py \
   scripts/run_scaling_curve.sh \
   scripts/summarize_seed_runs.py \
   scripts/run_seed_replicates.sh \
   scripts/run_deepseek_production_pipeline.sh \
   scripts/run_relabel_paid_probe.sh \
   scripts/relabel_distillation_trace.sh \
   scripts/train_distilled_rankers.sh \
   "$TMP_DIR/monopoly-training-handoff/scripts/"
cp docs/ai-distillation-plan.md \
   docs/ai-cost-and-experiment-roadmap.md \
   docs/ai-paper-outline.md \
   docs/ai-research-experiment-plan.md \
   docs/ai-training-log.md \
   docs/ai-delivery-checklist.md \
   "$TMP_DIR/monopoly-training-handoff/docs/"

cat > "$TMP_DIR/monopoly-training-handoff/README_WINDOWS_5090.md" <<EOF
# Monopoly Deal Training Handoff

This archive contains one trace plus the current training scripts and reports.

## Recommended Windows 5090 setup

Use Python 3.11+ and install PyTorch with CUDA from the official PyTorch selector.
Then run from the repository root after copying this archive contents into the repo:

\`\`\`bash
MONOPOLY_TRAIN_SOURCES=deepseek \\
MONOPOLY_MIN_TRAIN_ROWS=5000 \\
MONOPOLY_TRAIN_EPOCHS=40 \\
MONOPOLY_TRAIN_BATCH_SIZE=512 \\
scripts/train_distilled_rankers.sh "$TRACE" "$OUT_PREFIX"
\`\`\`

After training, run the gameplay matrix:

\`\`\`bash
MONOPOLY_MATRIX_GAMES=50 \\
MONOPOLY_MATRIX_PLAYERS=2,3,4,5 \\
MONOPOLY_MATRIX_OPPONENTS=easy,normal,hard \\
scripts/evaluate_gameplay_matrix.sh \\
  "$OUT_PREFIX-mlp/candidate_ranker_mlp.json" \\
  "$OUT_PREFIX-gameplay-matrix"
\`\`\`

If the trace is local heuristic only, set:

\`\`\`bash
MONOPOLY_TRAIN_SOURCES=local_heuristic
\`\`\`

Before treating the handoff as production-ready, inspect:

\`\`\`bash
cat "$OUT_PREFIX-run_summary.md"
cat "$OUT_PREFIX-trace_audit.json"
cat "$OUT_PREFIX-readiness.json"
\`\`\`

For DeepSeek-quality production data, \`readiness.json\` must have \`"mode": "production"\` and \`"ready": true\`. A local heuristic run can prove the pipeline works, but it is not a production training dataset.

If this archive contains a local trace and a DeepSeek key becomes available, you can relabel stored legal candidates before running a new simulation:

\`\`\`bash
DEEPSEEK_API_KEY=... \\
scripts/run_relabel_paid_probe.sh \\
  "$TRACE" \\
  data/distillation/deepseek-relabel-probe.jsonl \\
  models/distillation/deepseek-relabel-probe
\`\`\`

The lower-level command is:

\`\`\`bash
DEEPSEEK_API_KEY=... \\
MONOPOLY_RELABEL_SELECT=true \\
MONOPOLY_RELABEL_MAX_BY_KIND=PLAY_CARD:250,PAYMENT:120,JUST_SAY_NO:80,OVERFLOW_DISCARD:50 \\
scripts/relabel_distillation_trace.sh \\
  "$TRACE" \\
  data/distillation/deepseek-relabel-probe.jsonl \\
  models/distillation/deepseek-relabel-probe
\`\`\`

This is a paid probe shortcut, not a production-ready shortcut; the relabeled trace still has to pass production readiness.

For multi-seed reporting, run \`MONOPOLY_SEEDS=11,42,73 scripts/run_seed_replicates.sh "$TRACE" "$AGG_PREFIX"\` and inspect \`$AGG_PREFIX-seed_summary.md\`. If the archive already contains \`*-seed_summary.md\` or \`*-gameplay_matrix.md\`, read those first for the aggregate result.

## Notes

- Java runtime evaluation still requires the Java/Maven project.
- The student model never emits arbitrary actions; it ranks Java-generated legal candidates.
- Read \`docs/ai-delivery-checklist.md\`, \`docs/ai-distillation-plan.md\`, and \`docs/ai-cost-and-experiment-roadmap.md\` before scaling.
- Run \`python3 scripts/check_ai_quality_gate.py --require production --production-prefix <prefix>\` before calling a dataset production-ready.
EOF

mkdir -p "$(dirname "$ARCHIVE")"
tar -C "$TMP_DIR" -czf "$ARCHIVE" monopoly-training-handoff
echo "[handoff] wrote $ARCHIVE"
