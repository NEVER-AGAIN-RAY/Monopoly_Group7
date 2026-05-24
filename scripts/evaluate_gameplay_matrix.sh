#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MODEL="${1:?Usage: scripts/evaluate_gameplay_matrix.sh path/to/candidate_ranker_mlp.json [output-dir]}"
OUT_DIR="${2:-$ROOT/models/distillation/gameplay-matrix-$(date +%Y%m%d-%H%M%S)}"

GAMES="${MONOPOLY_MATRIX_GAMES:-20}"
SNAPSHOTS="${MONOPOLY_MATRIX_SNAPSHOTS:-500}"
PLAYERS_LIST="${MONOPOLY_MATRIX_PLAYERS:-2,3,4,5}"
OPPONENTS_LIST="${MONOPOLY_MATRIX_OPPONENTS:-easy,normal,hard}"
SEATS_LIST="${MONOPOLY_MATRIX_SEATS:-1}"

cd "$ROOT"
mkdir -p "$OUT_DIR"

IFS=',' read -r -a PLAYERS_ARRAY <<< "$PLAYERS_LIST"
IFS=',' read -r -a OPPONENTS_ARRAY <<< "$OPPONENTS_LIST"
IFS=',' read -r -a SEATS_ARRAY <<< "$SEATS_LIST"

for players in "${PLAYERS_ARRAY[@]}"; do
  players="${players//[[:space:]]/}"
  [[ -z "$players" ]] && continue
  for opponent in "${OPPONENTS_ARRAY[@]}"; do
    opponent="${opponent//[[:space:]]/}"
    [[ -z "$opponent" ]] && continue
    for seat in "${SEATS_ARRAY[@]}"; do
      seat="${seat//[[:space:]]/}"
      [[ -z "$seat" ]] && continue
      if (( seat > players )); then
        continue
      fi
      out="$OUT_DIR/players${players}-${opponent}-seat${seat}.json"
      echo "[matrix] players=$players opponent=$opponent seat=$seat games=$GAMES -> $out"
      MONOPOLY_EVAL_QUIET="${MONOPOLY_EVAL_QUIET:-true}" \
      MONOPOLY_EVAL_OPPONENT_STRATEGY="$opponent" \
      MONOPOLY_EVAL_RANKER_SEAT="$seat" \
      scripts/evaluate_distilled_ranker.sh "$MODEL" "$GAMES" "$players" "$SNAPSHOTS" > "$out"
    done
  done
done

python3 scripts/summarize_gameplay_matrix.py "$OUT_DIR" \
  --output "$OUT_DIR/summary.json" \
  --markdown-output "$OUT_DIR/summary.md" >/dev/null

echo "[matrix] summary=$OUT_DIR/summary.md"
