#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
STAMP="$(date +%Y%m%d-%H%M%S)"
INPUT="${1:-$ROOT/data/distillation/local-baseline-20260524-080604.jsonl}"
TRACE="${2:-$ROOT/data/distillation/deepseek-relabel-probe-$STAMP.jsonl}"
OUT_PREFIX="${3:-$ROOT/models/distillation/deepseek-relabel-probe-$STAMP}"
PROMPT_PRICE="${MONOPOLY_PROMPT_PRICE_PER_MILLION:-0}"
COMPLETION_PRICE="${MONOPOLY_COMPLETION_PRICE_PER_MILLION:-0}"
TRACE_MODE="${MONOPOLY_TRACE_MODE:-fail_if_exists}"

cd "$ROOT"

echo "[relabel-probe] input=$INPUT"
echo "[relabel-probe] trace=$TRACE"
echo "[relabel-probe] outPrefix=$OUT_PREFIX"

if [[ ! -f "$INPUT" ]]; then
  echo "[relabel-probe] missing input trace: $INPUT" >&2
  exit 2
fi

scripts/preflight_paid_collection.sh "$TRACE"

MONOPOLY_TRACE_MODE="$TRACE_MODE" \
MONOPOLY_RELABEL_TEACHER=deepseek \
MONOPOLY_RELABEL_SELECT="${MONOPOLY_RELABEL_SELECT:-true}" \
MONOPOLY_RELABEL_MAX_BY_KIND="${MONOPOLY_RELABEL_MAX_BY_KIND:-PLAY_CARD:250,PAYMENT:120,JUST_SAY_NO:80,OVERFLOW_DISCARD:50}" \
MONOPOLY_RELABEL_MIN_SELECTED_ROWS="${MONOPOLY_RELABEL_MIN_SELECTED_ROWS:-20}" \
MONOPOLY_RELABEL_BATCH_SIZE="${MONOPOLY_RELABEL_BATCH_SIZE:-16}" \
MONOPOLY_MIN_DEEPSEEK_ROWS="${MONOPOLY_MIN_DEEPSEEK_ROWS:-20}" \
MONOPOLY_AUDIT_MIN_RARE_KIND_ROWS="${MONOPOLY_AUDIT_MIN_RARE_KIND_ROWS:-1}" \
scripts/relabel_distillation_trace.sh "$INPUT" "$TRACE" "$OUT_PREFIX"

if [[ "$PROMPT_PRICE" != "0" || "$COMPLETION_PRICE" != "0" ]]; then
  scripts/estimate_deepseek_cost.py \
    "$OUT_PREFIX-dataset_manifest.json" \
    --prompt-price-per-million "$PROMPT_PRICE" \
    --completion-price-per-million "$COMPLETION_PRICE" \
    --target-labels "${MONOPOLY_COST_TARGET_LABELS:-100000}" \
    --output "$OUT_PREFIX-cost_estimate.json"
else
  echo "[relabel-probe] skip cost estimate: set MONOPOLY_PROMPT_PRICE_PER_MILLION and MONOPOLY_COMPLETION_PRICE_PER_MILLION"
fi

echo "[relabel-probe] done"
echo "[relabel-probe] trace=$TRACE"
echo "[relabel-probe] selection=$OUT_PREFIX-selection_report.json"
echo "[relabel-probe] summary=$OUT_PREFIX-relabel_summary.json"
echo "[relabel-probe] manifest=$OUT_PREFIX-dataset_manifest.json"
echo "[relabel-probe] audit=$OUT_PREFIX-trace_audit.json"
echo "[relabel-probe] report=$OUT_PREFIX-quality_report.md"
if [[ -f "$OUT_PREFIX-cost_estimate.json" ]]; then
  echo "[relabel-probe] cost=$OUT_PREFIX-cost_estimate.json"
fi
