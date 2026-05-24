#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TRACE="${1:?Usage: scripts/run_seed_replicates.sh data/distillation/run.jsonl models/distillation/run-seeds}"
OUT_PREFIX="${2:?Usage: scripts/run_seed_replicates.sh data/distillation/run.jsonl models/distillation/run-seeds}"

SEEDS="${MONOPOLY_SEEDS:-11,42,73}"
SUMMARY_FILES=()

cd "$ROOT"

IFS=',' read -r -a SEED_ARRAY <<< "$SEEDS"
for seed in "${SEED_ARRAY[@]}"; do
  seed="${seed//[[:space:]]/}"
  [[ -z "$seed" ]] && continue
  seed_prefix="$OUT_PREFIX-seed$seed"
  echo "[seed] train seed=$seed out=$seed_prefix"
  MONOPOLY_TRAIN_SEED="$seed" \
  scripts/train_distilled_rankers.sh "$TRACE" "$seed_prefix"
  SUMMARY_FILES+=("$seed_prefix-run_summary.json")
done

python3 scripts/summarize_seed_runs.py \
  ${SUMMARY_FILES[@]+"${SUMMARY_FILES[@]}"} \
  --markdown-output "$OUT_PREFIX-seed_summary.md" \
  --json-output "$OUT_PREFIX-seed_summary.json"

echo "[seed] summary=$OUT_PREFIX-seed_summary.md"
