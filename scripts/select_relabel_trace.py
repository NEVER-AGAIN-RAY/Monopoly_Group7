#!/usr/bin/env python3
"""Select a diverse legal-state subset before paid relabeling."""

from __future__ import annotations

import argparse
import collections
import hashlib
import json
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Dict, List, Sequence


DEFAULT_KINDS = ["PLAY_CARD", "PAYMENT", "JUST_SAY_NO", "OVERFLOW_DISCARD"]


@dataclass(frozen=True)
class RowInfo:
    line_no: int
    raw: str
    row: Dict[str, Any]
    decision_id: str
    kind: str
    source: str
    session_id: str
    actor_id: str
    player_count: str
    candidate_count: int


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("jsonl", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--report", type=Path, default=None)
    parser.add_argument("--max-by-kind", default="")
    parser.add_argument("--target-rows", type=int, default=0)
    parser.add_argument("--include-sources", default="")
    parser.add_argument("--require-kinds", default="")
    parser.add_argument("--min-rows", type=int, default=1)
    parser.add_argument("--seed", default="20260524")
    parser.add_argument("--trace-mode", default="fail_if_exists")
    args = parser.parse_args(argv)

    rows, duplicate_skipped = load_rows(args.jsonl)
    quotas = parse_quotas(args.max_by_kind)
    include_sources = csv_set(args.include_sources)
    require_kinds = csv_list(args.require_kinds)
    selected, report = select_rows(
        rows,
        input_path=args.jsonl,
        output_path=args.output,
        quotas=quotas,
        target_rows=max(0, args.target_rows),
        include_sources=include_sources,
        require_kinds=require_kinds,
        min_rows=max(0, args.min_rows),
        seed=args.seed,
        duplicate_rows_skipped=duplicate_skipped,
    )
    prepare_output(args.output, args.trace_mode)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text("".join(row.raw + "\n" for row in selected), encoding="utf-8")
    text = json.dumps(report, ensure_ascii=False, indent=2) + "\n"
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(text, encoding="utf-8")
    print(text, end="")
    return 0 if report["ok"] else 1


def load_rows(path: Path) -> tuple[List[RowInfo], int]:
    rows: List[RowInfo] = []
    seen: set[str] = set()
    duplicate_skipped = 0
    with path.open("r", encoding="utf-8") as handle:
        for line_no, line in enumerate(handle, start=1):
            raw = line.strip()
            if not raw:
                continue
            try:
                row = json.loads(raw)
            except json.JSONDecodeError as exc:
                raise SystemExit(f"{path}:{line_no}: invalid JSON: {exc}") from exc
            info = row_info(row, raw, line_no)
            if info.decision_id and info.decision_id in seen:
                duplicate_skipped += 1
                continue
            if info.decision_id:
                seen.add(info.decision_id)
            rows.append(info)
    return rows, duplicate_skipped


def select_rows(
    rows: Sequence[RowInfo],
    input_path: Path,
    output_path: Path,
    quotas: Dict[str, int],
    target_rows: int,
    include_sources: set[str],
    require_kinds: Sequence[str],
    min_rows: int,
    seed: str,
    duplicate_rows_skipped: int = 0,
) -> tuple[List[RowInfo], Dict[str, Any]]:
    eligible = [
        row
        for row in rows
        if not include_sources or row.source in include_sources
    ]
    if quotas:
        selected: List[RowInfo] = []
        for kind, limit in quotas.items():
            kind_rows = [row for row in eligible if row.kind == kind]
            selected.extend(select_diverse(kind_rows, limit, f"{seed}:{kind}", include_kind=False))
        selected = sorted(selected, key=lambda row: row.line_no)
    else:
        limit = target_rows if target_rows > 0 else len(eligible)
        selected = select_diverse(eligible, limit, seed, include_kind=True)
        selected = sorted(selected, key=lambda row: row.line_no)

    if target_rows > 0 and len(selected) > target_rows:
        selected = select_diverse(selected, target_rows, f"{seed}:target", include_kind=True)
        selected = sorted(selected, key=lambda row: row.line_no)

    report = build_report(
        rows=rows,
        eligible=eligible,
        selected=selected,
        input_path=input_path,
        output_path=output_path,
        quotas=quotas,
        include_sources=include_sources,
        require_kinds=require_kinds,
        min_rows=min_rows,
        duplicate_rows_skipped=duplicate_rows_skipped,
    )
    return selected, report


def select_diverse(
    rows: Sequence[RowInfo],
    limit: int,
    seed: str,
    include_kind: bool,
) -> List[RowInfo]:
    remaining = list(rows)
    selected: List[RowInfo] = []
    by_kind: collections.Counter[str] = collections.Counter()
    by_player_count: collections.Counter[str] = collections.Counter()
    by_session: collections.Counter[str] = collections.Counter()
    while remaining and len(selected) < limit:
        best_index = min(
            range(len(remaining)),
            key=lambda index: diversity_key(
                remaining[index],
                seed,
                by_kind,
                by_player_count,
                by_session,
                include_kind=include_kind,
            ),
        )
        row = remaining.pop(best_index)
        selected.append(row)
        by_kind[row.kind] += 1
        by_player_count[row.player_count] += 1
        by_session[row.session_id] += 1
    return selected


def diversity_key(
    row: RowInfo,
    seed: str,
    by_kind: collections.Counter[str],
    by_player_count: collections.Counter[str],
    by_session: collections.Counter[str],
    include_kind: bool,
) -> tuple[int, int, int, int, int]:
    kind_count = by_kind[row.kind] if include_kind else 0
    return (
        kind_count,
        by_player_count[row.player_count],
        by_session[row.session_id],
        -row.candidate_count,
        stable_int(seed, row.decision_id or str(row.line_no)),
    )


def build_report(
    rows: Sequence[RowInfo],
    eligible: Sequence[RowInfo],
    selected: Sequence[RowInfo],
    input_path: Path,
    output_path: Path,
    quotas: Dict[str, int],
    include_sources: set[str],
    require_kinds: Sequence[str],
    min_rows: int,
    duplicate_rows_skipped: int,
) -> Dict[str, Any]:
    selected_by_kind = counter_json(row.kind for row in selected)
    available_by_kind = counter_json(row.kind for row in eligible)
    deficits = {
        kind: max(0, limit - selected_by_kind.get(kind, 0))
        for kind, limit in quotas.items()
    }
    checks = [
        check("min_rows", len(selected) >= min_rows, f"{len(selected)} / {min_rows}"),
    ]
    if require_kinds:
        checks.append(check(
            "required_kinds",
            all(selected_by_kind.get(kind, 0) > 0 for kind in require_kinds),
            str({kind: selected_by_kind.get(kind, 0) for kind in require_kinds}),
        ))
    if quotas:
        checks.append(check(
            "quota_rows_available",
            all(value == 0 for value in deficits.values()),
            str(deficits),
            severity="warn",
        ))
    failures = [item for item in checks if item["severity"] == "error" and not item["ok"]]
    warnings = [item for item in checks if item["severity"] == "warn" and not item["ok"]]
    return {
        "schema": "monopoly-deal-relabel-selection-v1",
        "ok": not failures,
        "warningCount": len(warnings),
        "failureCount": len(failures),
        "inputPath": str(input_path),
        "outputPath": str(output_path),
        "rowsRead": len(rows) + duplicate_rows_skipped,
        "duplicateRowsSkipped": duplicate_rows_skipped,
        "rowsEligible": len(eligible),
        "rowsSelected": len(selected),
        "includeSources": sorted(include_sources),
        "requestedMaxByKind": quotas,
        "quotaDeficits": deficits,
        "availableByDecisionKind": available_by_kind,
        "selectedByDecisionKind": selected_by_kind,
        "selectedByPlayerCount": counter_json(row.player_count for row in selected),
        "selectedByTeacherSource": counter_json(row.source for row in selected),
        "selectedSessions": len({row.session_id for row in selected if row.session_id}),
        "selectedActors": len({row.actor_id for row in selected if row.actor_id}),
        "candidateCounts": candidate_stats([row.candidate_count for row in selected]),
        "checks": checks,
    }


def row_info(row: Dict[str, Any], raw: str, line_no: int) -> RowInfo:
    request = obj(row.get("request"))
    context = obj(request.get("context"))
    game_meta = obj(context.get("gameMeta"))
    result = obj(row.get("result"))
    metadata = obj(result.get("metadata"))
    return RowInfo(
        line_no=line_no,
        raw=raw,
        row=row,
        decision_id=str(request.get("decisionId", "")),
        kind=str(request.get("decisionKind", "unknown")),
        source=str(metadata.get("source", "unknown")),
        session_id=str(request.get("sessionId", "")),
        actor_id=str(request.get("actorPlayerId", "")),
        player_count=str(game_meta.get("playerCount", "unknown")),
        candidate_count=len(list_obj(request.get("candidates"))),
    )


def parse_quotas(raw: str) -> Dict[str, int]:
    out: Dict[str, int] = {}
    if not raw:
        return out
    for part in raw.split(","):
        text = part.strip()
        if not text:
            continue
        pieces = text.replace("=", ":", 1).split(":", 1)
        if len(pieces) != 2:
            continue
        try:
            value = int(pieces[1].strip())
        except ValueError:
            continue
        if value > 0:
            out[pieces[0].strip().upper()] = value
    return out


def prepare_output(path: Path, mode: str) -> None:
    normalized = mode.strip().lower()
    if normalized in {"overwrite", "replace", "truncate"}:
        path.unlink(missing_ok=True)
        return
    if normalized in {"fail", "fail_if_exists", "create_new", ""}:
        if path.exists():
            raise SystemExit(f"selection output already exists: {path}")
        return
    if normalized == "append":
        raise SystemExit("select_relabel_trace.py does not support append mode")
    raise SystemExit(f"unsupported trace mode: {mode}")


def candidate_stats(values: List[int]) -> Dict[str, Any]:
    if not values:
        return {"min": 0, "p50": 0, "p90": 0, "max": 0, "avg": 0.0}
    return {
        "min": min(values),
        "p50": percentile(values, 50),
        "p90": percentile(values, 90),
        "max": max(values),
        "avg": round(sum(values) / len(values), 3),
    }


def percentile(values: List[int], pct: int) -> int:
    ordered = sorted(values)
    index = int(round((len(ordered) - 1) * pct / 100))
    return ordered[index]


def stable_int(seed: str, value: str) -> int:
    digest = hashlib.sha256(f"{seed}:{value}".encode("utf-8")).hexdigest()
    return int(digest[:16], 16)


def counter_json(values: Sequence[str] | Any) -> Dict[str, int]:
    return dict(sorted(collections.Counter(values).items()))


def check(name: str, ok: bool, detail: str, severity: str = "error") -> Dict[str, Any]:
    return {"name": name, "ok": bool(ok), "severity": severity, "detail": detail}


def csv_set(raw: str) -> set[str]:
    return {item for item in csv_list(raw)}


def csv_list(raw: str) -> List[str]:
    if not raw:
        return []
    return [item.strip() for item in raw.split(",") if item.strip()]


def obj(value: Any) -> Dict[str, Any]:
    return value if isinstance(value, dict) else {}


def list_obj(value: Any) -> List[Any]:
    return value if isinstance(value, list) else []


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
