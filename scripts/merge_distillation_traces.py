#!/usr/bin/env python3
"""Merge Monopoly Deal distillation JSONL traces safely.

The trainer intentionally rejects duplicate decision ids. This script is the
pre-training step for combining multiple paid probes, overnight runs, machines,
or accounts into one canonical trace while preserving the original JSON rows.
"""

from __future__ import annotations

import argparse
import collections
import hashlib
import json
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Dict, Iterable, Sequence


SCHEMA = "monopoly-deal-trace-merge-v1"


@dataclass(frozen=True)
class TraceRecord:
    path: Path
    line_no: int
    raw: str
    row: Dict[str, Any]
    canonical: str
    decision_id: str
    source: str
    decision_kind: str
    session_id: str
    player_count: int


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("inputs", nargs="+", type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--report", type=Path, default=None)
    parser.add_argument(
        "--include-sources",
        default="",
        help="Comma-separated teacher sources to keep, for example deepseek. Empty keeps all sources.",
    )
    parser.add_argument(
        "--on-conflict",
        choices=["fail", "keep-first", "keep-last"],
        default="fail",
        help="How to handle the same decisionId with different JSON content.",
    )
    args = parser.parse_args(argv)

    include_sources = parse_csv_set(args.include_sources)
    result = merge_traces(
        args.inputs,
        include_sources=include_sources,
        on_conflict=args.on_conflict,
    )
    if result["conflicts"] and args.on_conflict == "fail":
        print(render_conflict_error(result), file=sys.stderr)
        write_report(args.report, result)
        return 2
    write_output(args.output, result["records"])
    write_report(args.report, result)
    print(json.dumps(report_without_records(result), ensure_ascii=False, indent=2))
    return 0


def merge_traces(
    inputs: Sequence[Path],
    include_sources: set[str] | None,
    on_conflict: str,
) -> Dict[str, Any]:
    by_decision: Dict[str, TraceRecord] = {}
    order: List[str] = []
    duplicates: List[Dict[str, Any]] = []
    conflicts: List[Dict[str, Any]] = []
    filtered = collections.Counter()
    rows_read = 0
    blank_lines = 0
    input_stats: Dict[str, Dict[str, Any]] = {}

    for path in inputs:
        stats = input_stats.setdefault(str(path), empty_input_stats())
        for record in read_records(path):
            if record is None:
                blank_lines += 1
                stats["blankLines"] += 1
                continue
            rows_read += 1
            stats["rowsRead"] += 1
            stats["byTeacherSource"][record.source] += 1
            stats["byDecisionKind"][record.decision_kind] += 1
            if include_sources is not None and record.source not in include_sources:
                filtered[record.source] += 1
                stats["rowsFilteredBySource"] += 1
                continue
            previous = by_decision.get(record.decision_id)
            if previous is None:
                by_decision[record.decision_id] = record
                order.append(record.decision_id)
                stats["rowsAccepted"] += 1
                continue
            if previous.canonical == record.canonical:
                duplicates.append(duplicate_item(previous, record, identical=True))
                stats["duplicateRowsSkipped"] += 1
                continue
            conflict = conflict_item(previous, record)
            conflicts.append(conflict)
            stats["conflictingRows"] += 1
            if on_conflict == "keep-last":
                by_decision[record.decision_id] = record
            elif on_conflict == "keep-first":
                continue

    records = [by_decision[decision_id] for decision_id in order if decision_id in by_decision]
    by_source = collections.Counter(record.source for record in records)
    by_kind = collections.Counter(record.decision_kind for record in records)
    by_player_count = collections.Counter(str(record.player_count) for record in records)
    by_session = collections.Counter(record.session_id for record in records if record.session_id)
    for stats in input_stats.values():
        stats["byTeacherSource"] = dict(sorted(stats["byTeacherSource"].items()))
        stats["byDecisionKind"] = dict(sorted(stats["byDecisionKind"].items()))

    output_sha = sha256_lines(record.raw for record in records)
    return {
        "schema": SCHEMA,
        "ok": not conflicts or on_conflict != "fail",
        "inputs": [str(path) for path in inputs],
        "includeSources": sorted(include_sources) if include_sources is not None else [],
        "onConflict": on_conflict,
        "rowsRead": rows_read,
        "blankLines": blank_lines,
        "rowsWritten": len(records),
        "rowsFilteredBySource": dict(sorted(filtered.items())),
        "duplicateRowsSkipped": len(duplicates),
        "conflictCount": len(conflicts),
        "conflicts": conflicts[:50],
        "duplicateSamples": duplicates[:20],
        "byInput": input_stats,
        "byTeacherSource": dict(sorted(by_source.items())),
        "byDecisionKind": dict(sorted(by_kind.items())),
        "byPlayerCount": dict(sorted(by_player_count.items())),
        "sessionCount": len(by_session),
        "sha256": output_sha,
        "records": records,
    }


def read_records(path: Path) -> Iterable[TraceRecord | None]:
    with path.open("r", encoding="utf-8") as handle:
        for line_no, line in enumerate(handle, start=1):
            raw = line.rstrip("\n")
            text = raw.strip()
            if not text:
                yield None
                continue
            try:
                row = json.loads(text)
            except json.JSONDecodeError as exc:
                raise SystemExit(f"{path}:{line_no}: invalid JSON: {exc}") from exc
            if not isinstance(row, dict):
                raise SystemExit(f"{path}:{line_no}: row must be a JSON object")
            request = obj(row.get("request"))
            decision_id = str(request.get("decisionId", ""))
            if not decision_id:
                raise SystemExit(f"{path}:{line_no}: request.decisionId is required")
            yield TraceRecord(
                path=path,
                line_no=line_no,
                raw=text,
                row=row,
                canonical=canonical_json(row),
                decision_id=decision_id,
                source=teacher_source(row),
                decision_kind=str(request.get("decisionKind", "unknown")),
                session_id=str(request.get("sessionId", "")),
                player_count=player_count(row),
            )


def write_output(path: Path, records: Sequence[TraceRecord]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as handle:
        for record in records:
            handle.write(record.raw)
            handle.write("\n")


def write_report(path: Path | None, result: Dict[str, Any]) -> None:
    if path is None:
        return
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(report_without_records(result), ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )


def render_conflict_error(result: Dict[str, Any]) -> str:
    lines = [
        f"[merge] found {result['conflictCount']} conflicting duplicate decisionId rows.",
        "[merge] Default behavior is to stop before training on contradictory labels.",
    ]
    for item in result["conflicts"][:5]:
        lines.append(
            "[merge] "
            f"{item['decisionId']} first={item['first']['path']}:{item['first']['line']} "
            f"new={item['new']['path']}:{item['new']['line']}"
        )
    return "\n".join(lines)


def report_without_records(result: Dict[str, Any]) -> Dict[str, Any]:
    return {key: value for key, value in result.items() if key != "records"}


def empty_input_stats() -> Dict[str, Any]:
    return {
        "rowsRead": 0,
        "blankLines": 0,
        "rowsAccepted": 0,
        "rowsFilteredBySource": 0,
        "duplicateRowsSkipped": 0,
        "conflictingRows": 0,
        "byTeacherSource": collections.Counter(),
        "byDecisionKind": collections.Counter(),
    }


def duplicate_item(first: TraceRecord, new: TraceRecord, identical: bool) -> Dict[str, Any]:
    return {
        "decisionId": first.decision_id,
        "identical": identical,
        "first": location_item(first),
        "new": location_item(new),
    }


def conflict_item(first: TraceRecord, new: TraceRecord) -> Dict[str, Any]:
    return {
        "decisionId": first.decision_id,
        "first": location_item(first),
        "new": location_item(new),
        "firstChoiceId": str(obj(first.row.get("result")).get("choiceId", "")),
        "newChoiceId": str(obj(new.row.get("result")).get("choiceId", "")),
        "firstSha256": sha256_text(first.canonical),
        "newSha256": sha256_text(new.canonical),
    }


def location_item(record: TraceRecord) -> Dict[str, Any]:
    return {
        "path": str(record.path),
        "line": record.line_no,
        "source": record.source,
        "decisionKind": record.decision_kind,
        "sessionId": record.session_id,
    }


def canonical_json(row: Dict[str, Any]) -> str:
    return json.dumps(row, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def sha256_lines(lines: Sequence[str]) -> str:
    digest = hashlib.sha256()
    for line in lines:
        digest.update(line.encode("utf-8"))
        digest.update(b"\n")
    return digest.hexdigest()


def sha256_text(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def parse_csv_set(value: str) -> set[str] | None:
    items = {part.strip() for part in value.split(",") if part.strip()}
    return items or None


def teacher_source(row: Dict[str, Any]) -> str:
    return str(obj(obj(row.get("result")).get("metadata")).get("source", "unknown"))


def player_count(row: Dict[str, Any]) -> int:
    meta = obj(obj(obj(row.get("request")).get("context")).get("gameMeta"))
    try:
        return int(float(meta.get("playerCount", 0)))
    except (TypeError, ValueError):
        return 0


def obj(value: Any) -> Dict[str, Any]:
    return value if isinstance(value, dict) else {}


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
