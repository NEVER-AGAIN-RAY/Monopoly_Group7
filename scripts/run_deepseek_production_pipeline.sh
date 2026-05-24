#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
STAMP="$(date +%Y%m%d-%H%M%S)"
RUN_ID="${MONOPOLY_PRODUCTION_RUN_ID:-deepseek-production-$STAMP}"

TRACE_DIR="${MONOPOLY_PRODUCTION_TRACE_DIR:-$ROOT/data/distillation}"
MODEL_DIR="${MONOPOLY_PRODUCTION_MODEL_DIR:-$ROOT/models/distillation}"
PROBE_TRACE="${MONOPOLY_PROBE_TRACE:-$TRACE_DIR/$RUN_ID-probe.jsonl}"
PROBE_PREFIX="${MONOPOLY_PROBE_PREFIX:-$MODEL_DIR/$RUN_ID-probe}"
MAIN_TRACE="${MONOPOLY_MAIN_TRACE:-$TRACE_DIR/$RUN_ID-main.jsonl}"
MERGED_TRACE="${MONOPOLY_MERGED_TRACE:-$TRACE_DIR/$RUN_ID-merged.jsonl}"
OUT_PREFIX="${MONOPOLY_OUT_PREFIX:-$MODEL_DIR/$RUN_ID}"
ARTIFACT_ARCHIVE="${MONOPOLY_ARTIFACT_ARCHIVE:-$OUT_PREFIX-artifacts.tar.gz}"
HANDOFF_ARCHIVE="${MONOPOLY_HANDOFF_ARCHIVE:-$OUT_PREFIX-training-handoff.tar.gz}"

CONFIRM="${MONOPOLY_PAID_CONFIRM:-probe-only}"
MIN_ROWS="${MONOPOLY_PRODUCTION_MIN_ROWS:-5000}"
MIN_RARE="${MONOPOLY_PRODUCTION_MIN_RARE_KIND_ROWS:-100}"
OPPONENT="${MONOPOLY_EVAL_OPPONENT_STRATEGY:-hard}"

cd "$ROOT"
mkdir -p "$TRACE_DIR" "$MODEL_DIR"

echo "[production] runId=$RUN_ID"
echo "[production] probeTrace=$PROBE_TRACE"
echo "[production] mainTrace=$MAIN_TRACE"
echo "[production] mergedTrace=$MERGED_TRACE"
echo "[production] outputPrefix=$OUT_PREFIX"

if [[ -z "${DEEPSEEK_API_KEY:-}" && -z "${MONOPOLY_DEEPSEEK_API_KEY:-}" ]]; then
  echo "[production] missing DeepSeek key: set DEEPSEEK_API_KEY or MONOPOLY_DEEPSEEK_API_KEY" >&2
  exit 2
fi

if [[ -f "$PROBE_TRACE" && -f "$PROBE_PREFIX-trace_audit.json" && "${MONOPOLY_RECOLLECT_PROBE:-false}" != "true" ]]; then
  echo "[production] reuse existing probe artifacts"
else
  if [[ -f "$PROBE_TRACE" && "${MONOPOLY_RECOLLECT_PROBE:-false}" != "true" ]]; then
    echo "[production] probe trace exists but audit is missing: $PROBE_TRACE" >&2
    echo "[production] set MONOPOLY_RECOLLECT_PROBE=true and MONOPOLY_TRACE_MODE=overwrite to replace it" >&2
    exit 2
  fi
  TRACE_MODE_FOR_PROBE="${MONOPOLY_TRACE_MODE:-fail_if_exists}"
  if [[ "${MONOPOLY_RECOLLECT_PROBE:-false}" == "true" ]]; then
    TRACE_MODE_FOR_PROBE="${MONOPOLY_TRACE_MODE:-overwrite}"
  fi
  MONOPOLY_TRACE_MODE="$TRACE_MODE_FOR_PROBE" \
  scripts/run_paid_probe.sh "$PROBE_TRACE" "$PROBE_PREFIX"
fi

if [[ "$CONFIRM" != "run-paid-overnight" ]]; then
  cat <<EOF
[production] probe complete; paid overnight collection has not been started.
[production] Inspect:
  $PROBE_PREFIX-trace_audit.json
  $PROBE_PREFIX-quality_report.md
  $PROBE_PREFIX-cost_estimate.json

[production] To continue with the paid production collection, rerun with the same run id:
  MONOPOLY_PRODUCTION_RUN_ID=$RUN_ID \\
  MONOPOLY_PAID_CONFIRM=run-paid-overnight \\
  scripts/run_deepseek_production_pipeline.sh
EOF
  exit 0
fi

if [[ -f "$MAIN_TRACE" && "${MONOPOLY_RECOLLECT_MAIN:-false}" != "true" ]]; then
  echo "[production] reuse existing main trace"
else
  TRACE_MODE_FOR_MAIN="${MONOPOLY_TRACE_MODE:-fail_if_exists}"
  if [[ "${MONOPOLY_RECOLLECT_MAIN:-false}" == "true" ]]; then
    TRACE_MODE_FOR_MAIN="${MONOPOLY_TRACE_MODE:-overwrite}"
  fi
  MONOPOLY_TRACE_MODE="$TRACE_MODE_FOR_MAIN" \
  MONOPOLY_SIM_GAMES="${MONOPOLY_SIM_GAMES:-300}" \
  MONOPOLY_SIM_PARALLEL="${MONOPOLY_SIM_PARALLEL:-8}" \
  MONOPOLY_SIM_PLAYER_COUNTS="${MONOPOLY_SIM_PLAYER_COUNTS:-2,3,4,5}" \
  MONOPOLY_SIM_MAX_SNAPSHOTS="${MONOPOLY_SIM_MAX_SNAPSHOTS:-260}" \
  MONOPOLY_SIM_BATCH_SIZE="${MONOPOLY_SIM_BATCH_SIZE:-32}" \
  MONOPOLY_SIM_BATCH_WAIT_MS="${MONOPOLY_SIM_BATCH_WAIT_MS:-160}" \
  MONOPOLY_SIM_RUNTIME_SECONDS="${MONOPOLY_SIM_RUNTIME_SECONDS:-90}" \
  MONOPOLY_SIM_MAX_BY_KIND="${MONOPOLY_SIM_MAX_BY_KIND:-PLAY_CARD:20000,PAYMENT:5000,JUST_SAY_NO:5000,OVERFLOW_DISCARD:5000}" \
  MONOPOLY_MIN_DEEPSEEK_ROWS="$MIN_ROWS" \
  scripts/collect_deepseek_distillation.sh "$MAIN_TRACE"
fi

python3 scripts/merge_distillation_traces.py \
  "$PROBE_TRACE" \
  "$MAIN_TRACE" \
  --include-sources deepseek \
  --output "$MERGED_TRACE" \
  --report "$OUT_PREFIX-trace_merge.json"

python3 scripts/audit_distillation_trace.py "$MERGED_TRACE" \
  --preferred-source deepseek \
  --require-token-usage \
  --min-rows "$MIN_ROWS" \
  --min-rare-kind-rows "$MIN_RARE" \
  --max-first-choice-ratio "${MONOPOLY_AUDIT_MAX_FIRST_CHOICE_RATIO:-0.80}" \
  --output "$OUT_PREFIX-trace_audit.json"

MONOPOLY_TRAIN_SOURCES=deepseek \
MONOPOLY_MIN_TRAIN_ROWS="$MIN_ROWS" \
MONOPOLY_READINESS_MIN_RARE_KIND_ROWS="$MIN_RARE" \
MONOPOLY_TRAIN_EPOCHS="${MONOPOLY_TRAIN_EPOCHS:-40}" \
MONOPOLY_TRAIN_BATCH_SIZE="${MONOPOLY_TRAIN_BATCH_SIZE:-512}" \
MONOPOLY_EVAL_GAMES="${MONOPOLY_EVAL_GAMES:-20}" \
MONOPOLY_EVAL_PLAYERS="${MONOPOLY_EVAL_PLAYERS:-3}" \
MONOPOLY_EVAL_SNAPSHOTS="${MONOPOLY_EVAL_SNAPSHOTS:-500}" \
MONOPOLY_EVAL_OPPONENT_STRATEGY="$OPPONENT" \
scripts/train_distilled_rankers.sh "$MERGED_TRACE" "$OUT_PREFIX"

scripts/package_distillation_artifacts.sh "$OUT_PREFIX" "$ARTIFACT_ARCHIVE"
scripts/package_training_handoff.sh "$MERGED_TRACE" "$OUT_PREFIX" "$HANDOFF_ARCHIVE"

echo "[production] done"
echo "[production] mergedTrace=$MERGED_TRACE"
echo "[production] readiness=$OUT_PREFIX-readiness.json"
echo "[production] report=$OUT_PREFIX-quality_report.md"
echo "[production] artifactArchive=$ARTIFACT_ARCHIVE"
echo "[production] handoffArchive=$HANDOFF_ARCHIVE"
