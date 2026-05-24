#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MODEL="${1:?Usage: scripts/evaluate_local_ranker.sh path/to/candidate_ranker_linear.json|candidate_ranker_mlp.json [players] [snapshots]}"
PLAYERS="${2:-2}"
SNAPSHOTS="${3:-120}"

cd "$ROOT"

mvn -q compile exec:java \
  -Dexec.mainClass=com.monopoly.tools.AiBattleExperimentRunner \
  -Dmonopoly.aiBattle.strategy=local_ranker \
  -Dmonopoly.localRanker.modelPath="$MODEL" \
  -Dmonopoly.aiBattle.players="$PLAYERS" \
  -Dmonopoly.aiBattle.maxSnapshots="$SNAPSHOTS" \
  -Dmonopoly.ai.decisionDelayMs=0 \
  -Dmonopoly.aiBattle.log.enabled=false
