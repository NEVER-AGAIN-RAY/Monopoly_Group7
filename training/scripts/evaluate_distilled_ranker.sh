#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
MODEL="${1:?Usage: training/scripts/evaluate_distilled_ranker.sh path/to/candidate_ranker_linear.json|candidate_ranker_mlp.json [games] [players] [snapshots-per-game]}"
GAMES="${2:-20}"
PLAYERS="${3:-3}"
SNAPSHOTS="${4:-240}"
OPPONENT="${MONOPOLY_EVAL_OPPONENT_STRATEGY:-ranker}"
RANKER_SEAT="${MONOPOLY_EVAL_RANKER_SEAT:-1}"

cd "$ROOT"

if [[ "${MONOPOLY_EVAL_QUIET:-false}" == "true" ]]; then
  exec 3>&1
  mvn -q compile exec:java \
    -Dexec.mainClass=com.monopoly.tools.LocalRankerEvaluationRunner \
    -Dmonopoly.localRanker.modelPath="$MODEL" \
    -Dmonopoly.localRankerEval.games="$GAMES" \
    -Dmonopoly.localRankerEval.players="$PLAYERS" \
    -Dmonopoly.localRankerEval.maxSnapshots="$SNAPSHOTS" \
    -Dmonopoly.localRankerEval.opponentStrategy="$OPPONENT" \
    -Dmonopoly.localRankerEval.rankerSeat="$RANKER_SEAT" \
    -Dmonopoly.autosave=false \
    -Dmonopoly.ai.decisionDelayMs=0 \
    -Dmonopoly.aiBattle.log.enabled=false \
    2>/dev/null | python3 -c 'import sys
text = sys.stdin.read()
start = text.rfind("\n{")
if start >= 0:
    start += 1
else:
    start = text.find("{")
if start < 0:
    sys.exit(1)
sys.stdout.write(text[start:])
' >&3
  exec 3>&-
  exit 0
fi

mvn -q compile exec:java \
  -Dexec.mainClass=com.monopoly.tools.LocalRankerEvaluationRunner \
  -Dmonopoly.localRanker.modelPath="$MODEL" \
  -Dmonopoly.localRankerEval.games="$GAMES" \
  -Dmonopoly.localRankerEval.players="$PLAYERS" \
  -Dmonopoly.localRankerEval.maxSnapshots="$SNAPSHOTS" \
  -Dmonopoly.localRankerEval.opponentStrategy="$OPPONENT" \
  -Dmonopoly.localRankerEval.rankerSeat="$RANKER_SEAT" \
  -Dmonopoly.autosave=false \
  -Dmonopoly.ai.decisionDelayMs=0 \
  -Dmonopoly.aiBattle.log.enabled=false
