#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
STAMP="$(date +%Y%m%d-%H%M%S)"
TRACE="${1:-$ROOT/training/data/distillation/deepseek-probe-$STAMP.jsonl}"
OUT_PREFIX="${2:-$ROOT/backend/models/distillation/deepseek-probe-$STAMP}"
PROMPT_PRICE="${MONOPOLY_PROMPT_PRICE_PER_MILLION:-0}"
COMPLETION_PRICE="${MONOPOLY_COMPLETION_PRICE_PER_MILLION:-0}"

cd "$ROOT"

training/scripts/preflight_paid_collection.sh "$TRACE"

MONOPOLY_TRACE_MODE="${MONOPOLY_TRACE_MODE:-fail_if_exists}" \
MONOPOLY_SIM_GAMES="${MONOPOLY_SIM_GAMES:-8}" \
MONOPOLY_SIM_PARALLEL="${MONOPOLY_SIM_PARALLEL:-2}" \
MONOPOLY_SIM_PLAYER_COUNTS="${MONOPOLY_SIM_PLAYER_COUNTS:-2,3}" \
MONOPOLY_SIM_MAX_SNAPSHOTS="${MONOPOLY_SIM_MAX_SNAPSHOTS:-80}" \
MONOPOLY_SIM_BATCH_SIZE="${MONOPOLY_SIM_BATCH_SIZE:-8}" \
MONOPOLY_SIM_BATCH_WAIT_MS="${MONOPOLY_SIM_BATCH_WAIT_MS:-80}" \
MONOPOLY_SIM_RUNTIME_SECONDS="${MONOPOLY_SIM_RUNTIME_SECONDS:-45}" \
MONOPOLY_SIM_MAX_BY_KIND="${MONOPOLY_SIM_MAX_BY_KIND:-PLAY_CARD:120,PAYMENT:40,JUST_SAY_NO:30,OVERFLOW_DISCARD:30}" \
MONOPOLY_MIN_DEEPSEEK_ROWS="${MONOPOLY_MIN_DEEPSEEK_ROWS:-20}" \
training/scripts/collect_deepseek_distillation.sh "$TRACE"

python3 training/scripts/dataset_manifest.py "$TRACE" \
  --output "$OUT_PREFIX-dataset_manifest.json" >/dev/null

python3 training/scripts/audit_distillation_trace.py "$TRACE" \
  --preferred-source deepseek \
  --min-rows "${MONOPOLY_MIN_DEEPSEEK_ROWS:-20}" \
  --min-rare-kind-rows "${MONOPOLY_AUDIT_MIN_RARE_KIND_ROWS:-1}" \
  --max-first-choice-ratio "${MONOPOLY_AUDIT_MAX_FIRST_CHOICE_RATIO:-0.85}" \
  --require-token-usage \
  --output "$OUT_PREFIX-trace_audit.json"

python3 training/scripts/report_distillation_quality.py "$TRACE" \
  --source deepseek \
  --min-rows "${MONOPOLY_MIN_DEEPSEEK_ROWS:-20}" \
  --output "$OUT_PREFIX-quality_report.md" \
  --json-output "$OUT_PREFIX-quality_report.json"

if [[ "$PROMPT_PRICE" != "0" || "$COMPLETION_PRICE" != "0" ]]; then
  training/scripts/estimate_deepseek_cost.py \
    "$OUT_PREFIX-dataset_manifest.json" \
    --prompt-price-per-million "$PROMPT_PRICE" \
    --completion-price-per-million "$COMPLETION_PRICE" \
    --target-labels "${MONOPOLY_COST_TARGET_LABELS:-100000}" \
    --output "$OUT_PREFIX-cost_estimate.json"
else
  echo "[probe] skip cost estimate: set MONOPOLY_PROMPT_PRICE_PER_MILLION and MONOPOLY_COMPLETION_PRICE_PER_MILLION"
fi

echo "[probe] done"
echo "[probe] trace=$TRACE"
echo "[probe] manifest=$OUT_PREFIX-dataset_manifest.json"
echo "[probe] audit=$OUT_PREFIX-trace_audit.json"
echo "[probe] report=$OUT_PREFIX-quality_report.md"
if [[ -f "$OUT_PREFIX-cost_estimate.json" ]]; then
  echo "[probe] cost=$OUT_PREFIX-cost_estimate.json"
fi
