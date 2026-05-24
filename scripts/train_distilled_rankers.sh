#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TRACE="${1:?Usage: scripts/train_distilled_rankers.sh data/distillation/deepseek-run.jsonl [output-prefix]}"
OUT_PREFIX="${2:-$ROOT/models/distillation/$(basename "$TRACE" .jsonl)}"

MIN_ROWS="${MONOPOLY_MIN_TRAIN_ROWS:-5000}"
EPOCHS="${MONOPOLY_TRAIN_EPOCHS:-30}"
BATCH_SIZE="${MONOPOLY_TRAIN_BATCH_SIZE:-256}"
LR="${MONOPOLY_TRAIN_LR:-0.001}"
SEED="${MONOPOLY_TRAIN_SEED:-42}"
SOURCES="${MONOPOLY_TRAIN_SOURCES:-deepseek}"
BATTLE_PLAYERS="${MONOPOLY_EVAL_PLAYERS:-3}"
BATTLE_SNAPSHOTS="${MONOPOLY_EVAL_SNAPSHOTS:-500}"
SPLIT_BY="${MONOPOLY_TRAIN_SPLIT_BY:-session}"
VALIDATION_PLAYER_COUNTS="${MONOPOLY_VALIDATION_PLAYER_COUNTS:-4,5}"
FOREST_ARGS=()
if [[ "${MONOPOLY_TRAIN_FOREST:-true}" == "true" ]]; then
  FOREST_ARGS=(--metrics "$OUT_PREFIX-forest/metrics.json")
fi
BALANCE_ARGS=()
if [[ "${MONOPOLY_TRAIN_BALANCE_BY_KIND:-true}" == "true" ]]; then
  BALANCE_ARGS=(--balance-by-kind --kind-balance-max "${MONOPOLY_TRAIN_KIND_BALANCE_MAX:-4.0}")
fi

cd "$ROOT"

python3 scripts/distill_dataset.py "$TRACE" \
  --mode validate \
  --include-sources "$SOURCES" \
  --min-rows "$MIN_ROWS" \
  --output-dir "$OUT_PREFIX-validate"

python3 scripts/dataset_manifest.py "$TRACE" \
  --output "$OUT_PREFIX-dataset_manifest.json" >/dev/null

python3 scripts/distill_dataset.py "$TRACE" \
  --output-dir "$OUT_PREFIX-mlp" \
  --include-sources "$SOURCES" \
  --min-rows "$MIN_ROWS" \
  --epochs "$EPOCHS" \
  --batch-size "$BATCH_SIZE" \
  --lr "$LR" \
  --seed "$SEED" \
  --split-by "$SPLIT_BY" \
  --validation-player-counts "$VALIDATION_PLAYER_COUNTS" \
  ${BALANCE_ARGS[@]+"${BALANCE_ARGS[@]}"}

python3 scripts/distill_dataset.py "$TRACE" \
  --output-dir "$OUT_PREFIX-linear" \
  --include-sources "$SOURCES" \
  --min-rows "$MIN_ROWS" \
  --epochs "$EPOCHS" \
  --batch-size "$BATCH_SIZE" \
  --lr "$LR" \
  --seed "$SEED" \
  --model-type linear \
  --split-by "$SPLIT_BY" \
  --validation-player-counts "$VALIDATION_PLAYER_COUNTS" \
  ${BALANCE_ARGS[@]+"${BALANCE_ARGS[@]}"}

python3 scripts/distill_dataset.py "$TRACE" \
  --output-dir "$OUT_PREFIX-knn" \
  --include-sources "$SOURCES" \
  --min-rows "$MIN_ROWS" \
  --model-type knn \
  --seed "$SEED" \
  --split-by "$SPLIT_BY" \
  --validation-player-counts "$VALIDATION_PLAYER_COUNTS" \
  --knn-k "${MONOPOLY_KNN_K:-5}"

if [[ "${MONOPOLY_TRAIN_FOREST:-true}" == "true" ]]; then
  python3 scripts/distill_dataset.py "$TRACE" \
    --output-dir "$OUT_PREFIX-forest" \
    --include-sources "$SOURCES" \
    --min-rows "$MIN_ROWS" \
    --model-type forest \
    --seed "$SEED" \
    --split-by "$SPLIT_BY" \
    --validation-player-counts "$VALIDATION_PLAYER_COUNTS" \
    --forest-trees "${MONOPOLY_FOREST_TREES:-16}" \
    --forest-features-per-tree "${MONOPOLY_FOREST_FEATURES_PER_TREE:-32}" \
    ${BALANCE_ARGS[@]+"${BALANCE_ARGS[@]}"}
fi

scripts/evaluate_local_ranker.sh \
  "$OUT_PREFIX-mlp/candidate_ranker_mlp.json" \
  "$BATTLE_PLAYERS" \
  "$BATTLE_SNAPSHOTS"

scripts/evaluate_local_ranker.sh \
  "$OUT_PREFIX-linear/candidate_ranker_linear.json" \
  "$BATTLE_PLAYERS" \
  "$BATTLE_SNAPSHOTS"

MONOPOLY_EVAL_QUIET="${MONOPOLY_EVAL_QUIET:-true}" \
MONOPOLY_EVAL_OPPONENT_STRATEGY="${MONOPOLY_EVAL_OPPONENT_STRATEGY:-hard}" \
scripts/evaluate_distilled_ranker.sh \
  "$OUT_PREFIX-mlp/candidate_ranker_mlp.json" \
  "${MONOPOLY_EVAL_GAMES:-20}" \
  "$BATTLE_PLAYERS" \
  "$BATTLE_SNAPSHOTS" \
  > "$OUT_PREFIX-mlp/gameplay_vs_${MONOPOLY_EVAL_OPPONENT_STRATEGY:-hard}.json"

GAMEPLAY_REPORT="$OUT_PREFIX-mlp/gameplay_vs_${MONOPOLY_EVAL_OPPONENT_STRATEGY:-hard}.json"

python3 scripts/report_distillation_quality.py "$TRACE" \
  --metrics "$OUT_PREFIX-mlp/metrics.json" \
  --metrics "$OUT_PREFIX-linear/metrics.json" \
  --metrics "$OUT_PREFIX-knn/metrics.json" \
  ${FOREST_ARGS[@]+"${FOREST_ARGS[@]}"} \
  --gameplay "$GAMEPLAY_REPORT" \
  --source "$SOURCES" \
  --min-rows "$MIN_ROWS" \
  --output "$OUT_PREFIX-quality_report.md" \
  --json-output "$OUT_PREFIX-quality_report.json"

READINESS_MODE="production"
READINESS_RARE_DEFAULT=100
if [[ "$SOURCES" != "deepseek" ]]; then
  READINESS_MODE="local"
  READINESS_RARE_DEFAULT=1
fi
READINESS_MIN_RARE="${MONOPOLY_READINESS_MIN_RARE_KIND_ROWS:-$READINESS_RARE_DEFAULT}"
AUDIT_ARGS=(
  --preferred-source "$SOURCES"
  --min-rows "$MIN_ROWS"
  --min-rare-kind-rows "$READINESS_MIN_RARE"
  --max-first-choice-ratio "${MONOPOLY_AUDIT_MAX_FIRST_CHOICE_RATIO:-0.80}"
  --output "$OUT_PREFIX-trace_audit.json"
)
if [[ "$SOURCES" == "deepseek" ]]; then
  AUDIT_ARGS+=(--require-token-usage)
fi
python3 scripts/audit_distillation_trace.py "$TRACE" "${AUDIT_ARGS[@]}"

READINESS_NATURAL_ARGS=()
if [[ "${MONOPOLY_READINESS_REQUIRE_NATURAL_GAMEPLAY:-false}" == "true" ]]; then
  READINESS_NATURAL_ARGS=(--require-natural-gameplay)
fi

python3 scripts/check_training_readiness.py "$TRACE" "$OUT_PREFIX" \
  --mode "$READINESS_MODE" \
  --min-rows "$MIN_ROWS" \
  --min-rare-kind-rows "$READINESS_MIN_RARE" \
  --min-validation-top1 "${MONOPOLY_READINESS_MIN_TOP1:-0.55}" \
  --min-gameplay-games "${MONOPOLY_EVAL_GAMES:-20}" \
  --gameplay "$GAMEPLAY_REPORT" \
  ${READINESS_NATURAL_ARGS[@]+"${READINESS_NATURAL_ARGS[@]}"} \
  --output "$OUT_PREFIX-readiness.json"

python3 scripts/summarize_training_run.py "$OUT_PREFIX" \
  --gameplay "$GAMEPLAY_REPORT" \
  --markdown-output "$OUT_PREFIX-run_summary.md" \
  --json-output "$OUT_PREFIX-run_summary.json"
