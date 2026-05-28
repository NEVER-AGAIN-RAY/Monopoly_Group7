#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT_PREFIX="${1:?Usage: training/scripts/package_distillation_artifacts.sh backend/models/distillation/run-prefix [archive-path]}"
ARCHIVE="${2:-$OUT_PREFIX-artifacts.tar.gz}"
GAMEPLAY_PATH="${MONOPOLY_PACKAGE_GAMEPLAY:-$OUT_PREFIX-mlp/gameplay_vs_${MONOPOLY_EVAL_OPPONENT_STRATEGY:-hard}.json}"
AGG_PREFIX="${MONOPOLY_PACKAGE_AGG_PREFIX:-$(printf '%s' "$OUT_PREFIX" | sed -E 's/-seed[0-9]+$//')}"

cd "$ROOT"

required=(
  "$OUT_PREFIX-dataset_manifest.json"
  "$OUT_PREFIX-quality_report.md"
  "$OUT_PREFIX-quality_report.json"
  "$OUT_PREFIX-readiness.json"
  "$OUT_PREFIX-run_summary.md"
  "$OUT_PREFIX-run_summary.json"
  "$OUT_PREFIX-mlp/metrics.json"
  "$OUT_PREFIX-mlp/candidate_ranker_mlp.json"
  "$GAMEPLAY_PATH"
  "$OUT_PREFIX-linear/metrics.json"
  "$OUT_PREFIX-linear/candidate_ranker_linear.json"
  "$OUT_PREFIX-knn/metrics.json"
)

optional=(
  "$OUT_PREFIX-trace_audit.json"
  "$OUT_PREFIX-forest/metrics.json"
  "$OUT_PREFIX-seed_summary.md"
  "$OUT_PREFIX-seed_summary.json"
  "$OUT_PREFIX-gameplay_matrix.md"
  "$OUT_PREFIX-gameplay_matrix.json"
  "$OUT_PREFIX-quality_gate.md"
  "$OUT_PREFIX-quality_gate.json"
  "$OUT_PREFIX-handoff_report.md"
  "$OUT_PREFIX-handoff_report.json"
  "$OUT_PREFIX-gameplay-matrix-smoke/summary.md"
  "$OUT_PREFIX-gameplay-matrix-smoke/summary.json"
  "$AGG_PREFIX-seed_summary.md"
  "$AGG_PREFIX-seed_summary.json"
  "$AGG_PREFIX-gameplay_matrix.md"
  "$AGG_PREFIX-gameplay_matrix.json"
  "$AGG_PREFIX-quality_gate.md"
  "$AGG_PREFIX-quality_gate.json"
  "$AGG_PREFIX-handoff_report.md"
  "$AGG_PREFIX-handoff_report.json"
  "$AGG_PREFIX-gameplay-matrix-smoke/summary.md"
  "$AGG_PREFIX-gameplay-matrix-smoke/summary.json"
  "backend/models/distillation/local-enhanced-20260524-strategic-probe-dataset_manifest.json"
  "backend/models/distillation/local-enhanced-20260524-strategic-probe-trace_audit.json"
  "backend/models/distillation/local-enhanced-20260524-strategic-probe-quality_report.md"
  "backend/models/distillation/local-enhanced-20260524-strategic-probe-quality_report.json"
  "backend/models/distillation/local-enhanced-20260524-strategic-probe-readiness.json"
  "backend/models/distillation/local-enhanced-20260524-strategic-probe-run_summary.md"
  "backend/models/distillation/local-enhanced-20260524-strategic-probe-run_summary.json"
  "backend/models/distillation/local-enhanced-20260524-strategic-probe-mlp/metrics.json"
  "backend/models/distillation/local-enhanced-20260524-strategic-probe-mlp/candidate_ranker_mlp.json"
  "backend/models/distillation/local-enhanced-20260524-strategic-probe-mlp/gameplay_vs_hard.json"
)

missing=0
for path in "${required[@]}"; do
  if [[ ! -f "$path" ]]; then
    echo "[package] missing $path" >&2
    missing=1
  fi
done
if [[ "$missing" -ne 0 ]]; then
  exit 2
fi

for path in "${optional[@]}"; do
  if [[ -f "$path" ]]; then
    required+=("$path")
  fi
done

for matrix_json in "$OUT_PREFIX-gameplay-matrix-smoke"/*.json "$AGG_PREFIX-gameplay-matrix-smoke"/*.json; do
  [[ -f "$matrix_json" ]] || continue
  required+=("$matrix_json")
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
      required+=("$path")
    fi
  done
  for path in "$seed_prefix-mlp"/gameplay_vs_*.json; do
    if [[ -f "$path" ]]; then
      required+=("$path")
    fi
  done
done

deduped=()
seen=" "
for path in "${required[@]}"; do
  if [[ "$seen" != *" $path "* ]]; then
    deduped+=("$path")
    seen="$seen$path "
  fi
done
required=("${deduped[@]}")

mkdir -p "$(dirname "$ARCHIVE")"
tar -czf "$ARCHIVE" "${required[@]}"
echo "[package] wrote $ARCHIVE"
