#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
TRACE="${1:-$ROOT/training/data/distillation/deepseek-preflight.jsonl}"
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
  training/scripts/distill_dataset.py \
  training/scripts/dataset_manifest.py \
  training/scripts/report_distillation_quality.py \
  training/scripts/estimate_deepseek_cost.py \
  training/scripts/select_relabel_trace.py \
  training/scripts/merge_distillation_traces.py \
  training/scripts/check_training_readiness.py \
  training/scripts/check_ai_delivery_status.py \
  training/scripts/check_ai_quality_gate.py \
  training/scripts/summarize_ai_handoff.py \
  training/scripts/summarize_training_run.py \
  training/scripts/summarize_seed_runs.py \
  training/scripts/audit_distillation_trace.py

bash -n \
  training/scripts/relabel_distillation_trace.sh \
  training/scripts/collect_deepseek_distillation.sh \
  training/scripts/overnight_distillation_run.sh \
  training/scripts/train_distilled_rankers.sh \
  training/scripts/package_distillation_artifacts.sh \
  training/scripts/package_training_handoff.sh \
  training/scripts/run_deepseek_production_pipeline.sh \
  training/scripts/run_seed_replicates.sh \
  training/scripts/run_relabel_paid_probe.sh \
  training/scripts/run_paid_probe.sh

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
training/scripts/overnight_distillation_run.sh "$TRACE" backend/models/distillation/deepseek-run
EOF
