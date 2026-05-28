#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
TRACE="${1:?Usage: training/scripts/run_scaling_curve.sh training/data/distillation/run.jsonl backend/models/distillation/scaling-run}"
OUT_PREFIX="${2:?Usage: training/scripts/run_scaling_curve.sh training/data/distillation/run.jsonl backend/models/distillation/scaling-run}"

SIZES="${MONOPOLY_SCALING_SIZES:-1000,5000,20000,100000}"
SEED="${MONOPOLY_SCALING_SEED:-42}"
SUBSET_DIR="${OUT_PREFIX}-subsets"

cd "$ROOT"

python3 training/scripts/make_scaling_subsets.py "$TRACE" \
  --output-dir "$SUBSET_DIR" \
  --sizes "$SIZES" \
  --seed "$SEED" \
  --prefix "$(basename "$OUT_PREFIX")"

python3 - <<'PY' "$SUBSET_DIR/$(basename "$OUT_PREFIX")-manifest.json" "$OUT_PREFIX"
import json
import os
import subprocess
import sys
from pathlib import Path

manifest = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
out_prefix = sys.argv[2]
for subset in manifest["subsets"]:
    rows = int(subset["rows"])
    subset_path = subset["path"]
    model_prefix = f"{out_prefix}-{rows}"
    env = os.environ.copy()
    env["MONOPOLY_MIN_TRAIN_ROWS"] = str(rows)
    cmd = ["training/scripts/train_distilled_rankers.sh", subset_path, model_prefix]
    print("[scaling] train rows=", rows, "trace=", subset_path, "out=", model_prefix, flush=True)
    subprocess.run(cmd, check=True, env=env)
PY

echo "[scaling] subsets=$SUBSET_DIR"
