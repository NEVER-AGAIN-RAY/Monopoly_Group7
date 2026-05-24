#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TRACE="${1:-$ROOT/data/distillation/smoke.jsonl}"
OUT_DIR="${2:-$ROOT/models/distillation/smoke}"

mkdir -p "$(dirname "$TRACE")" "$OUT_DIR"
rm -f "$TRACE"

cd "$ROOT"

mvn -q test -Dtest=DecisionBrokerTest,SimulationWorkerTest,PingProtocolTest

mvn -q compile exec:java \
  -Dexec.mainClass=com.monopoly.tools.SimulationBatchRunner \
  -Dmonopoly.deepseek.enabled=false \
  -Dmonopoly.ai.decisionDelayMs=0 \
  -Dmonopoly.simulation.teacher=heuristic \
  -Dmonopoly.simulation.games=4 \
  -Dmonopoly.simulation.parallel=2 \
  -Dmonopoly.simulation.players=3 \
  -Dmonopoly.simulation.maxSnapshots=40 \
  -Dmonopoly.simulation.batchSize=8 \
  -Dmonopoly.simulation.batchWaitMs=25 \
  -Dmonopoly.simulation.runtimeSeconds=5 \
  -Dmonopoly.simulation.tracePath="$TRACE"

python3 scripts/distill_dataset.py "$TRACE" --output-dir "$OUT_DIR" --epochs 8
python3 scripts/distill_dataset.py "$TRACE" --output-dir "$OUT_DIR-linear" --epochs 8 --model-type linear
scripts/evaluate_local_ranker.sh "$OUT_DIR-linear/candidate_ranker_linear.json" 2 40
