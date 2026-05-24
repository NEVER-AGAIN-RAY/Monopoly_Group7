#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TRACE="${1:-$ROOT/data/distillation/deepseek-preflight.jsonl}"
MODE="${MONOPOLY_OVERNIGHT_MODE:-deepseek}"

cd "$ROOT"

echo "[preflight] repo=$ROOT"
echo "[preflight] trace=$TRACE"
echo "[preflight] mode=$MODE"

if [[ "$MODE" != "deepseek" && "$MODE" != "auto" ]]; then
  echo "[preflight] warning: MONOPOLY_OVERNIGHT_MODE=$MODE will not force paid DeepSeek collection" >&2
fi

if [[ -z "${DEEPSEEK_API_KEY:-}" && -z "${MONOPOLY_DEEPSEEK_API_KEY:-}" ]]; then
  echo "[preflight] missing DeepSeek key: set DEEPSEEK_API_KEY or MONOPOLY_DEEPSEEK_API_KEY" >&2
  exit 2
fi

if [[ -e "$TRACE" && "${MONOPOLY_TRACE_MODE:-fail_if_exists}" != "append" && "${MONOPOLY_TRACE_MODE:-fail_if_exists}" != "overwrite" && "${MONOPOLY_TRACE_MODE:-fail_if_exists}" != "replace" && "${MONOPOLY_TRACE_MODE:-fail_if_exists}" != "truncate" ]]; then
  echo "[preflight] trace already exists and MONOPOLY_TRACE_MODE does not allow reuse: $TRACE" >&2
  exit 2
fi

python3 -m py_compile \
  scripts/distill_dataset.py \
  scripts/dataset_manifest.py \
  scripts/report_distillation_quality.py \
  scripts/estimate_deepseek_cost.py \
  scripts/select_relabel_trace.py \
  scripts/merge_distillation_traces.py \
  scripts/check_training_readiness.py \
  scripts/check_ai_delivery_status.py \
  scripts/check_ai_quality_gate.py \
  scripts/summarize_ai_handoff.py \
  scripts/summarize_training_run.py \
  scripts/summarize_seed_runs.py \
  scripts/audit_distillation_trace.py

bash -n \
  scripts/relabel_distillation_trace.sh \
  scripts/collect_deepseek_distillation.sh \
  scripts/overnight_distillation_run.sh \
  scripts/train_distilled_rankers.sh \
  scripts/package_distillation_artifacts.sh \
  scripts/package_training_handoff.sh \
  scripts/run_deepseek_production_pipeline.sh \
  scripts/run_seed_replicates.sh \
  scripts/run_relabel_paid_probe.sh \
  scripts/run_paid_probe.sh

mvn -q test -Dtest=DecisionBrokerTest,SimulationWorkerTest,DeepSeekClientConfigTest,TraceRelabelerTest

echo "[preflight] ok"
echo "[preflight] recommended command:"
cat <<EOF
DEEPSEEK_API_KEY=... \\
MONOPOLY_OVERNIGHT_MODE=deepseek \\
MONOPOLY_SIM_GAMES=\${MONOPOLY_SIM_GAMES:-300} \\
MONOPOLY_SIM_PARALLEL=\${MONOPOLY_SIM_PARALLEL:-8} \\
MONOPOLY_SIM_PLAYER_COUNTS=\${MONOPOLY_SIM_PLAYER_COUNTS:-2,3,4,5} \\
MONOPOLY_SIM_BATCH_SIZE=\${MONOPOLY_SIM_BATCH_SIZE:-32} \\
MONOPOLY_SIM_MAX_BY_KIND=\${MONOPOLY_SIM_MAX_BY_KIND:-PLAY_CARD:20000,PAYMENT:5000,JUST_SAY_NO:5000,OVERFLOW_DISCARD:5000} \\
scripts/overnight_distillation_run.sh "$TRACE" models/distillation/deepseek-run
EOF
