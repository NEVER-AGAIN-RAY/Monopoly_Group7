#!/usr/bin/env python3
"""Export original trace rows referenced by auxiliary risk analysis.

`analyze_auxiliary_trace.py` reports risky payment/overflow decisions by
tracePath + decisionId. This helper copies the matching raw JSONL rows into a
counterfactual replay input file.
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any, Sequence


RISK_KEYS = {
    "PAYMENT": "topRiskyPayments",
    "OVERFLOW_DISCARD": "topRiskyOverflows",
}


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--analysis", type=Path, required=True)
    parser.add_argument("--kind", choices=sorted(RISK_KEYS), required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--max-rows", type=int, default=0)
    parser.add_argument(
        "--all-kind-rows",
        action="store_true",
        help="Export all trace rows with the requested decision kind instead of only analysis risk refs.",
    )
    parser.add_argument(
        "--trace-path-contains",
        default="",
        help="Optional substring filter for analysis tracePath, e.g. seat2-30.",
    )
    parser.add_argument(
        "--natural-win",
        choices=["all", "true", "false"],
        default="all",
        help="Optional filter on the source row outcome from the analysis entry.",
    )
    parser.add_argument("--trace-mode", choices=["fail_if_exists", "overwrite"], default="fail_if_exists")
    args = parser.parse_args(argv)

    analysis = json.loads(args.analysis.read_text(encoding="utf-8"))
    if args.all_kind_rows:
        rows = load_kind_rows(
            analysis.get("tracePaths", []),
            args.kind,
            max_rows=max(0, args.max_rows),
            trace_path_contains=args.trace_path_contains,
            natural_win=args.natural_win,
        )
    else:
        refs = selected_refs(
            analysis.get(RISK_KEYS[args.kind], []),
            max_rows=max(0, args.max_rows),
            trace_path_contains=args.trace_path_contains,
            natural_win=args.natural_win,
        )
        rows = load_rows(refs)
    prepare_output(args.output, args.trace_mode)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(
        "".join(json.dumps(row, ensure_ascii=False, separators=(",", ":")) + "\n" for row in rows),
        encoding="utf-8",
    )
    report = {
        "analysisPath": str(args.analysis),
        "kind": args.kind,
        "outputPath": str(args.output),
        "rowsWritten": len(rows),
        "candidateTotal": sum(len(obj(obj(row.get("request")).get("candidates"))) for row in rows),
        "maxCandidates": max(
            [len(obj(obj(row.get("request")).get("candidates"))) for row in rows] or [0]
        ),
        "decisionIds": [str(obj(row.get("request")).get("decisionId", "")) for row in rows],
    }
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0


def selected_refs(
    items: Any,
    max_rows: int,
    trace_path_contains: str,
    natural_win: str,
) -> list[dict[str, Any]]:
    refs: list[dict[str, Any]] = []
    for item in obj(items):
        if not isinstance(item, dict):
            continue
        trace_path = str(item.get("tracePath", ""))
        if trace_path_contains and trace_path_contains not in trace_path:
            continue
        if natural_win != "all" and outcome_status(item) != natural_win:
            continue
        refs.append(item)
        if max_rows > 0 and len(refs) >= max_rows:
            break
    return refs


def load_rows(refs: list[dict[str, Any]]) -> list[dict[str, Any]]:
    wanted_by_path: dict[str, set[str]] = {}
    order: list[tuple[str, str]] = []
    for ref in refs:
        path = str(ref.get("tracePath", ""))
        decision_id = str(ref.get("decisionId", ""))
        if not path or not decision_id:
            raise SystemExit(f"analysis item missing tracePath/decisionId: {ref}")
        wanted_by_path.setdefault(path, set()).add(decision_id)
        order.append((path, decision_id))

    found: dict[tuple[str, str], dict[str, Any]] = {}
    for path_text, ids in wanted_by_path.items():
        path = Path(path_text)
        with path.open("r", encoding="utf-8") as handle:
            for line_no, line in enumerate(handle, start=1):
                raw = line.strip()
                if not raw:
                    continue
                row = json.loads(raw)
                decision_id = str(obj(row.get("request")).get("decisionId", ""))
                if decision_id in ids:
                    found[(path_text, decision_id)] = row
        missing = sorted(ids - {decision_id for (path, decision_id) in found if path == path_text})
        if missing:
            raise SystemExit(f"{path_text}: missing decision ids: {missing[:10]}")

    return [found[key] for key in order]


def load_kind_rows(
    trace_paths: Any,
    kind: str,
    max_rows: int,
    trace_path_contains: str,
    natural_win: str,
) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    for path_text in obj(trace_paths):
        path_text = str(path_text)
        if not path_text:
            continue
        if trace_path_contains and trace_path_contains not in path_text:
            continue
        with Path(path_text).open("r", encoding="utf-8") as handle:
            for line_no, line in enumerate(handle, start=1):
                raw = line.strip()
                if not raw:
                    continue
                row = json.loads(raw)
                row_kind = str(obj(row.get("request")).get("decisionKind", ""))
                if row_kind != kind:
                    continue
                if natural_win != "all" and outcome_status(row) != natural_win:
                    continue
                rows.append(row)
                if max_rows > 0 and len(rows) >= max_rows:
                    return rows
    return rows


def prepare_output(path: Path, mode: str) -> None:
    if path.exists() and mode != "overwrite":
        raise SystemExit(f"{path} already exists; use --trace-mode overwrite")


def obj(value: Any) -> Any:
    return value if isinstance(value, (dict, list)) else {}


def outcome_status(item: dict[str, Any]) -> str:
    status = str(item.get("outcomeStatus", "")).strip().lower()
    if status == "win":
        return "true"
    if status == "loss":
        return "false"
    if item.get("naturalWin") is True:
        return "true"
    if item.get("naturalWin") is False:
        return "false"
    outcome = obj(item.get("outcome"))
    if not outcome:
        return "unknown"
    if outcome.get("naturalWin") is True:
        return "true"
    if outcome.get("naturalWin") is False:
        return "false"
    return "unknown"


if __name__ == "__main__":
    raise SystemExit(main())
