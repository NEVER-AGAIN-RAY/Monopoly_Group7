#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
STAMP="$(date +%Y%m%d-%H%M%S)"
TRACE="${1:-$ROOT/data/distillation/deepseek-$STAMP.jsonl}"
TRACE_MODE="${MONOPOLY_TRACE_MODE:-fail_if_exists}"

mkdir -p "$(dirname "$TRACE")"

cd "$ROOT"

if [[ -e "$TRACE" && "$TRACE_MODE" != "append" && "$TRACE_MODE" != "overwrite" && "$TRACE_MODE" != "replace" && "$TRACE_MODE" != "truncate" ]]; then
  cat >&2 <<EOF
Decision trace already exists: $TRACE
Set MONOPOLY_TRACE_MODE=append to resume into it, or MONOPOLY_TRACE_MODE=overwrite to replace it.
EOF
  exit 2
fi

if [[ -z "${DEEPSEEK_API_KEY:-}" && -z "${MONOPOLY_DEEPSEEK_API_KEY:-}" ]]; then
  cat >&2 <<'EOF'
DeepSeek API key is not configured.
Set DEEPSEEK_API_KEY, or set MONOPOLY_DEEPSEEK_API_KEY for this script.
EOF
  exit 2
fi

API_KEY_ARGS=()
if [[ -n "${MONOPOLY_DEEPSEEK_API_KEY:-}" ]]; then
  API_KEY_ARGS=(-Dmonopoly.deepseek.apiKey="$MONOPOLY_DEEPSEEK_API_KEY")
fi

mvn -q compile exec:java \
  -Dexec.mainClass=com.monopoly.tools.SimulationBatchRunner \
  ${API_KEY_ARGS[@]+"${API_KEY_ARGS[@]}"} \
  -Dmonopoly.simulation.teacher=deepseek \
  -Dmonopoly.simulation.games="${MONOPOLY_SIM_GAMES:-200}" \
  -Dmonopoly.simulation.parallel="${MONOPOLY_SIM_PARALLEL:-8}" \
  -Dmonopoly.simulation.players="${MONOPOLY_SIM_PLAYERS:-3}" \
  -Dmonopoly.simulation.playerCounts="${MONOPOLY_SIM_PLAYER_COUNTS:-2,3,4,5}" \
  -Dmonopoly.simulation.maxSnapshots="${MONOPOLY_SIM_MAX_SNAPSHOTS:-240}" \
  -Dmonopoly.simulation.batchSize="${MONOPOLY_SIM_BATCH_SIZE:-24}" \
  -Dmonopoly.simulation.batchWaitMs="${MONOPOLY_SIM_BATCH_WAIT_MS:-120}" \
  -Dmonopoly.simulation.runtimeSeconds="${MONOPOLY_SIM_RUNTIME_SECONDS:-60}" \
  -Dmonopoly.simulation.maxByKind="${MONOPOLY_SIM_MAX_BY_KIND:-}" \
  -Dmonopoly.simulation.traceMode="$TRACE_MODE" \
  -Dmonopoly.deepseek.maxTokens="${MONOPOLY_DEEPSEEK_MAX_TOKENS:-4096}" \
  -Dmonopoly.simulation.tracePath="$TRACE"

python3 scripts/distill_dataset.py "$TRACE" \
  --mode validate \
  --include-sources deepseek \
  --min-rows "${MONOPOLY_MIN_DEEPSEEK_ROWS:-1}" \
  --output-dir "$ROOT/models/distillation/validate-$STAMP"
