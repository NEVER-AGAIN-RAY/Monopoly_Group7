#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
STAMP="$(date +%Y%m%d-%H%M%S)"
MODE="${MONOPOLY_OVERNIGHT_MODE:-auto}"
TRACE="${1:-$ROOT/data/distillation/overnight-$STAMP.jsonl}"
OUT_PREFIX="${2:-$ROOT/models/distillation/overnight-$STAMP}"
TRACE_MODE="${MONOPOLY_TRACE_MODE:-fail_if_exists}"

cd "$ROOT"
mkdir -p "$(dirname "$TRACE")" "$(dirname "$OUT_PREFIX")"

if [[ -e "$TRACE" && "$TRACE_MODE" != "append" && "$TRACE_MODE" != "overwrite" && "$TRACE_MODE" != "replace" && "$TRACE_MODE" != "truncate" ]]; then
  cat >&2 <<EOF
Decision trace already exists: $TRACE
Set MONOPOLY_TRACE_MODE=append to resume into it, or MONOPOLY_TRACE_MODE=overwrite to replace it.
EOF
  exit 2
fi

has_deepseek_key() {
  [[ -n "${DEEPSEEK_API_KEY:-}" || -n "${MONOPOLY_DEEPSEEK_API_KEY:-}" ]]
}

run_deepseek_collection() {
  echo "[overnight] mode=deepseek trace=$TRACE"
  MONOPOLY_SIM_GAMES="${MONOPOLY_SIM_GAMES:-300}" \
  MONOPOLY_SIM_PARALLEL="${MONOPOLY_SIM_PARALLEL:-8}" \
  MONOPOLY_SIM_PLAYER_COUNTS="${MONOPOLY_SIM_PLAYER_COUNTS:-2,3,4,5}" \
  MONOPOLY_SIM_MAX_SNAPSHOTS="${MONOPOLY_SIM_MAX_SNAPSHOTS:-260}" \
  MONOPOLY_SIM_BATCH_SIZE="${MONOPOLY_SIM_BATCH_SIZE:-32}" \
  MONOPOLY_SIM_BATCH_WAIT_MS="${MONOPOLY_SIM_BATCH_WAIT_MS:-160}" \
  MONOPOLY_SIM_RUNTIME_SECONDS="${MONOPOLY_SIM_RUNTIME_SECONDS:-90}" \
  MONOPOLY_MIN_DEEPSEEK_ROWS="${MONOPOLY_MIN_DEEPSEEK_ROWS:-1000}" \
  scripts/collect_deepseek_distillation.sh "$TRACE"
}

run_local_collection() {
  echo "[overnight] mode=local_heuristic trace=$TRACE"
  mvn -q compile exec:java \
    -Dexec.mainClass=com.monopoly.tools.SimulationBatchRunner \
    -Dmonopoly.deepseek.enabled=false \
    -Dmonopoly.ai.decisionDelayMs=0 \
    -Dmonopoly.simulation.teacher=heuristic \
    -Dmonopoly.simulation.games="${MONOPOLY_SIM_GAMES:-120}" \
    -Dmonopoly.simulation.parallel="${MONOPOLY_SIM_PARALLEL:-8}" \
    -Dmonopoly.simulation.players="${MONOPOLY_SIM_PLAYERS:-3}" \
    -Dmonopoly.simulation.playerCounts="${MONOPOLY_SIM_PLAYER_COUNTS:-2,3,4,5}" \
    -Dmonopoly.simulation.maxSnapshots="${MONOPOLY_SIM_MAX_SNAPSHOTS:-180}" \
    -Dmonopoly.simulation.batchSize="${MONOPOLY_SIM_BATCH_SIZE:-32}" \
    -Dmonopoly.simulation.batchWaitMs="${MONOPOLY_SIM_BATCH_WAIT_MS:-50}" \
    -Dmonopoly.simulation.runtimeSeconds="${MONOPOLY_SIM_RUNTIME_SECONDS:-30}" \
    -Dmonopoly.simulation.maxByKind="${MONOPOLY_SIM_MAX_BY_KIND:-}" \
    -Dmonopoly.simulation.traceMode="$TRACE_MODE" \
    -Dmonopoly.simulation.tracePath="$TRACE"
}

case "$MODE" in
  auto)
    if has_deepseek_key; then
      SOURCE="deepseek"
      run_deepseek_collection
    else
      SOURCE="local_heuristic"
      run_local_collection
    fi
    ;;
  deepseek)
    SOURCE="deepseek"
    run_deepseek_collection
    ;;
  local|local_heuristic)
    SOURCE="local_heuristic"
    run_local_collection
    ;;
  *)
    echo "Unsupported MONOPOLY_OVERNIGHT_MODE=$MODE" >&2
    exit 2
    ;;
esac

MIN_ROWS_DEFAULT=5000
if [[ "$SOURCE" == "local_heuristic" ]]; then
  MIN_ROWS_DEFAULT=1000
fi

MONOPOLY_TRAIN_SOURCES="$SOURCE" \
MONOPOLY_MIN_TRAIN_ROWS="${MONOPOLY_MIN_TRAIN_ROWS:-$MIN_ROWS_DEFAULT}" \
MONOPOLY_TRAIN_EPOCHS="${MONOPOLY_TRAIN_EPOCHS:-20}" \
MONOPOLY_EVAL_GAMES="${MONOPOLY_EVAL_GAMES:-10}" \
MONOPOLY_EVAL_PLAYERS="${MONOPOLY_EVAL_PLAYERS:-3}" \
MONOPOLY_EVAL_SNAPSHOTS="${MONOPOLY_EVAL_SNAPSHOTS:-240}" \
MONOPOLY_EVAL_OPPONENT_STRATEGY="${MONOPOLY_EVAL_OPPONENT_STRATEGY:-hard}" \
scripts/train_distilled_rankers.sh "$TRACE" "$OUT_PREFIX"

python3 scripts/dataset_manifest.py "$TRACE" \
  --output "$OUT_PREFIX-dataset_manifest.json" >/dev/null

echo "[overnight] done"
echo "[overnight] trace=$TRACE"
echo "[overnight] manifest=$OUT_PREFIX-dataset_manifest.json"
echo "[overnight] audit=$OUT_PREFIX-trace_audit.json"
echo "[overnight] readiness=$OUT_PREFIX-readiness.json"
echo "[overnight] report=$OUT_PREFIX-quality_report.md"
echo "[overnight] mlp=$OUT_PREFIX-mlp/candidate_ranker_mlp.json"
echo "[overnight] linear=$OUT_PREFIX-linear/candidate_ranker_linear.json"
