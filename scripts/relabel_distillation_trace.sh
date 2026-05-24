#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
INPUT="${1:?Usage: scripts/relabel_distillation_trace.sh input.jsonl output.jsonl [output-prefix]}"
OUTPUT="${2:?Usage: scripts/relabel_distillation_trace.sh input.jsonl output.jsonl [output-prefix]}"
STAMP="$(date +%Y%m%d-%H%M%S)"
OUT_PREFIX="${3:-$ROOT/models/distillation/relabel-$STAMP}"
TEACHER="${MONOPOLY_RELABEL_TEACHER:-deepseek}"
TRACE_MODE="${MONOPOLY_TRACE_MODE:-fail_if_exists}"
MAX_ROWS="${MONOPOLY_RELABEL_MAX_ROWS:-0}"
MAX_BY_KIND="${MONOPOLY_RELABEL_MAX_BY_KIND:-}"
BATCH_SIZE="${MONOPOLY_RELABEL_BATCH_SIZE:-32}"
INCLUDE_SOURCES="${MONOPOLY_RELABEL_INCLUDE_SOURCES:-}"
SELECT="${MONOPOLY_RELABEL_SELECT:-false}"
SELECTED_INPUT="$INPUT"
SELECTED_TRACE="$OUT_PREFIX-selected.jsonl"
SELECTION_REPORT="$OUT_PREFIX-selection_report.json"
VALIDATE_SOURCE="${MONOPOLY_RELABEL_VALIDATE_SOURCE:-}"
RELABEL_LOG="$OUT_PREFIX-relabel.log"
RELABEL_SUMMARY="$OUT_PREFIX-relabel_summary.json"

cd "$ROOT"

if [[ ! -f "$INPUT" ]]; then
  echo "[relabel] missing input trace: $INPUT" >&2
  exit 2
fi

if [[ -e "$OUTPUT" && "$TRACE_MODE" != "append" && "$TRACE_MODE" != "overwrite" && "$TRACE_MODE" != "replace" && "$TRACE_MODE" != "truncate" ]]; then
  cat >&2 <<EOF
Relabel output already exists: $OUTPUT
Set MONOPOLY_TRACE_MODE=append to resume into it, or MONOPOLY_TRACE_MODE=overwrite to replace it.
EOF
  exit 2
fi

if [[ "$TEACHER" == "deepseek" && -z "${DEEPSEEK_API_KEY:-}" && -z "${MONOPOLY_DEEPSEEK_API_KEY:-}" ]]; then
  cat >&2 <<'EOF'
DeepSeek API key is not configured.
Set DEEPSEEK_API_KEY, or set MONOPOLY_DEEPSEEK_API_KEY for this script.
For a no-cost smoke check, run with MONOPOLY_RELABEL_TEACHER=heuristic.
EOF
  exit 2
fi

if [[ -z "$VALIDATE_SOURCE" ]]; then
  case "$TEACHER" in
    deepseek)
      VALIDATE_SOURCE="deepseek"
      ;;
    first|first_choice)
      VALIDATE_SOURCE="first_choice"
      ;;
    strategic|strategic_heuristic)
      VALIDATE_SOURCE="strategic_heuristic"
      ;;
    *)
      VALIDATE_SOURCE="local_heuristic"
      ;;
  esac
fi

API_KEY_ARGS=()
if [[ -n "${MONOPOLY_DEEPSEEK_API_KEY:-}" ]]; then
  API_KEY_ARGS=(-Dmonopoly.deepseek.apiKey="$MONOPOLY_DEEPSEEK_API_KEY")
fi

mkdir -p "$(dirname "$OUTPUT")" "$(dirname "$OUT_PREFIX")"

if [[ "$SELECT" == "true" || "$SELECT" == "1" || "$SELECT" == "yes" ]]; then
  python3 scripts/select_relabel_trace.py "$INPUT" \
    --output "$SELECTED_TRACE" \
    --report "$SELECTION_REPORT" \
    --max-by-kind "$MAX_BY_KIND" \
    --target-rows "$MAX_ROWS" \
    --include-sources "$INCLUDE_SOURCES" \
    --require-kinds "${MONOPOLY_RELABEL_REQUIRE_KINDS:-PLAY_CARD,PAYMENT,JUST_SAY_NO,OVERFLOW_DISCARD}" \
    --min-rows "${MONOPOLY_RELABEL_MIN_SELECTED_ROWS:-1}" \
    --seed "${MONOPOLY_RELABEL_SELECTION_SEED:-20260524}" \
    --trace-mode "${MONOPOLY_SELECTION_TRACE_MODE:-fail_if_exists}" >/dev/null
  SELECTED_INPUT="$SELECTED_TRACE"
fi

mvn -q compile exec:java \
  -Dexec.mainClass=com.monopoly.tools.TraceRelabeler \
  ${API_KEY_ARGS[@]+"${API_KEY_ARGS[@]}"} \
  -Dmonopoly.relabel.inputPath="$SELECTED_INPUT" \
  -Dmonopoly.relabel.outputPath="$OUTPUT" \
  -Dmonopoly.relabel.teacher="$TEACHER" \
  -Dmonopoly.relabel.traceMode="$TRACE_MODE" \
  -Dmonopoly.relabel.maxRows="$([[ "$SELECTED_INPUT" == "$INPUT" ]] && printf '%s' "$MAX_ROWS" || printf '0')" \
  -Dmonopoly.relabel.maxByKind="$([[ "$SELECTED_INPUT" == "$INPUT" ]] && printf '%s' "$MAX_BY_KIND" || printf '')" \
  -Dmonopoly.relabel.batchSize="$BATCH_SIZE" \
  -Dmonopoly.relabel.includeSources="$([[ "$SELECTED_INPUT" == "$INPUT" ]] && printf '%s' "$INCLUDE_SOURCES" || printf '')" \
  -Dmonopoly.deepseek.maxTokens="${MONOPOLY_DEEPSEEK_MAX_TOKENS:-4096}" \
  | tee "$RELABEL_LOG"

python3 - "$RELABEL_LOG" "$RELABEL_SUMMARY" <<'PY'
import json
import sys
from pathlib import Path

log_path = Path(sys.argv[1])
summary_path = Path(sys.argv[2])
summary = None
for line in reversed(log_path.read_text(encoding="utf-8").splitlines()):
    text = line.strip()
    if not text.startswith("{"):
        continue
    try:
        candidate = json.loads(text)
    except json.JSONDecodeError:
        continue
    if isinstance(candidate, dict) and "rowsRelabeled" in candidate:
        summary = candidate
        break
if summary is None:
    raise SystemExit(f"no relabel summary JSON found in {log_path}")
summary_path.write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
PY

python3 scripts/dataset_manifest.py "$OUTPUT" \
  --output "$OUT_PREFIX-dataset_manifest.json" >/dev/null

if [[ "$TEACHER" == "deepseek" ]]; then
  python3 scripts/audit_distillation_trace.py "$OUTPUT" \
    --preferred-source deepseek \
    --min-rows "${MONOPOLY_MIN_DEEPSEEK_ROWS:-1}" \
    --min-rare-kind-rows "${MONOPOLY_AUDIT_MIN_RARE_KIND_ROWS:-1}" \
    --max-first-choice-ratio "${MONOPOLY_AUDIT_MAX_FIRST_CHOICE_RATIO:-0.85}" \
    --require-token-usage \
    --output "$OUT_PREFIX-trace_audit.json"

  python3 scripts/report_distillation_quality.py "$OUTPUT" \
    --source deepseek \
    --min-rows "${MONOPOLY_MIN_DEEPSEEK_ROWS:-1}" \
    --output "$OUT_PREFIX-quality_report.md" \
    --json-output "$OUT_PREFIX-quality_report.json"
else
  python3 scripts/distill_dataset.py "$OUTPUT" \
    --mode validate \
    --include-sources "$VALIDATE_SOURCE" \
    --min-rows "${MONOPOLY_MIN_LOCAL_ROWS:-1}" \
    --output-dir "$OUT_PREFIX-validate"
fi

echo "[relabel] done"
echo "[relabel] input=$INPUT"
if [[ "$SELECTED_INPUT" != "$INPUT" ]]; then
  echo "[relabel] selectedInput=$SELECTED_INPUT"
  echo "[relabel] selectionReport=$SELECTION_REPORT"
fi
echo "[relabel] output=$OUTPUT"
echo "[relabel] summary=$RELABEL_SUMMARY"
echo "[relabel] manifest=$OUT_PREFIX-dataset_manifest.json"
if [[ -f "$OUT_PREFIX-trace_audit.json" ]]; then
  echo "[relabel] audit=$OUT_PREFIX-trace_audit.json"
fi
if [[ -f "$OUT_PREFIX-quality_report.md" ]]; then
  echo "[relabel] report=$OUT_PREFIX-quality_report.md"
fi
