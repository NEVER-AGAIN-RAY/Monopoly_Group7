#!/usr/bin/env python3
"""Select multi-color wild deployment decisions for counterfactual replay."""

from __future__ import annotations

import argparse
import collections
import json
import math
from copy import deepcopy
from pathlib import Path
from typing import Any, Iterable, Sequence


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("trace", nargs="+", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument("--target-rows", type=int, default=0)
    parser.add_argument("--max-per-session", type=int, default=4)
    parser.add_argument("--only-losses", action="store_true")
    parser.add_argument("--require-reference-disagreement", action="store_true")
    parser.add_argument("--trace-mode", default="fail_if_exists")
    args = parser.parse_args(argv)

    rows = list(load_rows(args.trace))
    selected, report = select_rows(
        rows=rows,
        trace_paths=args.trace,
        output_path=args.output,
        target_rows=max(0, args.target_rows),
        max_per_session=max(0, args.max_per_session),
        only_losses=args.only_losses,
        require_reference_disagreement=args.require_reference_disagreement,
    )

    prepare_output(args.output, args.trace_mode)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(
        "".join(json.dumps(row, ensure_ascii=False, separators=(",", ":")) + "\n" for row in selected),
        encoding="utf-8",
    )
    args.report.parent.mkdir(parents=True, exist_ok=True)
    text = json.dumps(report, ensure_ascii=False, indent=2) + "\n"
    args.report.write_text(text, encoding="utf-8")
    print(text, end="")
    return 0 if report["ok"] else 1


def load_rows(paths: Iterable[Path]) -> Iterable[dict[str, Any]]:
    seen: set[str] = set()
    for path in paths:
        with path.open("r", encoding="utf-8") as handle:
            for line_no, line in enumerate(handle, start=1):
                raw = line.strip()
                if not raw:
                    continue
                try:
                    row = json.loads(raw)
                except json.JSONDecodeError as exc:
                    raise SystemExit(f"{path}:{line_no}: invalid JSON: {exc}") from exc
                decision_id = str(obj(row.get("request")).get("decisionId", ""))
                if not decision_id:
                    raise SystemExit(f"{path}:{line_no}: missing request.decisionId")
                if decision_id in seen:
                    raise SystemExit(f"{path}:{line_no}: duplicate decisionId {decision_id}")
                seen.add(decision_id)
                yield row


def select_rows(
    *,
    rows: list[dict[str, Any]],
    trace_paths: Sequence[Path],
    output_path: Path,
    target_rows: int,
    max_per_session: int,
    only_losses: bool,
    require_reference_disagreement: bool,
) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    skipped: collections.Counter[str] = collections.Counter()
    eligible: list[Selection] = []
    rows_with_memento = 0
    multi_wild_rows = 0
    reference_disagreement_rows = 0
    losing_rows = 0
    for index, row in enumerate(rows):
        if has_memento(row):
            rows_with_memento += 1
        if not as_bool(obj(row.get("outcome")).get("naturalWin")):
            losing_rows += 1
        groups = wild_groups(row)
        if groups:
            multi_wild_rows += 1
        if has_reference_disagreement(row, groups):
            reference_disagreement_rows += 1
        selection, reason = inspect_row(
            row=row,
            row_index=index,
            only_losses=only_losses,
            require_reference_disagreement=require_reference_disagreement,
        )
        if selection is None:
            skipped[reason] += 1
            continue
        eligible.append(selection)

    eligible.sort(key=lambda item: item.ranking_key())
    selected_info: list[Selection] = []
    by_session: collections.Counter[str] = collections.Counter()
    for item in eligible:
        if target_rows > 0 and len(selected_info) >= target_rows:
            break
        if max_per_session > 0 and by_session[item.session_id] >= max_per_session:
            skipped["max_per_session"] += 1
            continue
        selected_info.append(item)
        by_session[item.session_id] += 1

    selected_rows: list[dict[str, Any]] = []
    for item in selected_info:
        row = deepcopy(rows[item.row_index])
        row["selection"] = item.to_json()
        selected_rows.append(row)

    checks = [
        check("input_rows_nonzero", len(rows) > 0, f"rows={len(rows)}"),
        check("selected_rows_nonzero", len(selected_info) > 0, f"selected={len(selected_info)}"),
        check(
            "selected_rows_have_memento",
            all(has_memento(rows[item.row_index]) for item in selected_info),
            f"selected={len(selected_info)}",
        ),
    ]
    failures = [item for item in checks if item["severity"] == "error" and not item["ok"]]
    report = {
        "schema": "monopoly-deal-wild-color-replay-row-selection-v1",
        "ok": not failures,
        "failureCount": len(failures),
        "tracePaths": [str(path) for path in trace_paths],
        "outputPath": str(output_path),
        "rowsRead": len(rows),
        "rowsWithMemento": rows_with_memento,
        "multiWildRows": multi_wild_rows,
        "referenceDisagreementRows": reference_disagreement_rows,
        "losingRows": losing_rows,
        "eligibleRows": len(eligible),
        "rowsSelected": len(selected_info),
        "selectedSessions": len(by_session),
        "filters": {
            "targetRows": target_rows,
            "maxPerSession": max_per_session,
            "onlyLosses": only_losses,
            "requireReferenceDisagreement": require_reference_disagreement,
        },
        "skippedReasons": dict(sorted(skipped.items())),
        "selectedBySourceKind": dict(collections.Counter(item.source_kind for item in selected_info).most_common()),
        "selectedBySourceWildColor": dict(
            collections.Counter(item.source_color for item in selected_info if item.source_color).most_common()
        ),
        "selectedByHardWildColor": dict(
            collections.Counter(item.hard_color for item in selected_info if item.hard_color).most_common()
        ),
        "wildCandidateCount": stats(item.wild_candidate_count for item in selected_info),
        "topExamples": [item.to_json() for item in selected_info[:30]],
        "checks": checks,
    }
    return selected_rows, report


def inspect_row(
    *,
    row: dict[str, Any],
    row_index: int,
    only_losses: bool,
    require_reference_disagreement: bool,
) -> tuple["Selection | None", str]:
    request = obj(row.get("request"))
    if str(request.get("decisionKind", "")).upper() != "PLAY_CARD":
        return None, "not_play_card"
    if not has_memento(row):
        return None, "missing_memento"
    outcome = obj(row.get("outcome"))
    if only_losses and as_bool(outcome.get("naturalWin")):
        return None, "winning_row"
    groups = wild_groups(row)
    if not groups:
        return None, "no_multi_wild_group"
    if require_reference_disagreement and not has_reference_disagreement(row, groups):
        return None, "no_reference_disagreement"

    result = obj(row.get("result"))
    metadata = obj(result.get("metadata"))
    candidates = objects(request.get("candidates"))
    by_id = {str(candidate.get("id", "")): candidate for candidate in candidates}
    source = by_id.get(str(result.get("choiceId", "")))
    hard = by_id.get(str(metadata.get("hardChoiceId", "")))
    source_wild = wild_info(source)
    hard_wild = wild_info(hard)
    source_kind = "other"
    if source_wild and source_wild["card_id"] in groups:
        source_kind = "wild"
    elif source is not None:
        source_kind = candidate_effect(source)

    return Selection(
        row_index=row_index,
        decision_id=str(request.get("decisionId", "")),
        session_id=str(request.get("sessionId", "")),
        state_sequence=int(number(request.get("stateSequence")) or 0),
        natural_win=as_bool(outcome.get("naturalWin")),
        source_choice_id=str(result.get("choiceId", "")),
        hard_choice_id=str(metadata.get("hardChoiceId", "")),
        source_kind=source_kind,
        source_color=source_wild["color"] if source_wild else "",
        hard_color=hard_wild["color"] if hard_wild else "",
        wild_candidate_count=sum(len(value) for value in groups.values()),
        wild_group_count=len(groups),
        reference_disagreement=has_reference_disagreement(row, groups),
        source_summary=str(source.get("summary", "")) if source is not None else "",
        hard_summary=str(hard.get("summary", "")) if hard is not None else "",
        wild_groups={
            card_id: [
                {
                    "id": str(candidate.get("id", "")),
                    "color": wild_info(candidate)["color"],
                    "summary": str(candidate.get("summary", "")),
                }
                for candidate in values
            ]
            for card_id, values in groups.items()
        },
    ), ""


def wild_groups(row: dict[str, Any]) -> dict[str, list[dict[str, Any]]]:
    request = obj(row.get("request"))
    groups: dict[str, list[dict[str, Any]]] = collections.defaultdict(list)
    for candidate in objects(request.get("candidates")):
        info = wild_info(candidate)
        if info:
            groups[info["card_id"]].append(candidate)
    return {key: value for key, value in groups.items() if key and len(value) >= 2}


def wild_info(candidate: dict[str, Any] | None) -> dict[str, str] | None:
    if candidate is None:
        return None
    summary = str(candidate.get("summary", ""))
    payload = obj(candidate.get("payload"))
    if not summary.startswith("Deploy wild property as "):
        return None
    if str(payload.get("actionType", "")).upper() != "DEPLOY":
        return None
    card_id = str(payload.get("cardId", ""))
    color = str(payload.get("targetColorKey", "")).upper()
    if not card_id or not color:
        return None
    return {"card_id": card_id, "color": color}


def has_reference_disagreement(row: dict[str, Any], groups: dict[str, list[dict[str, Any]]]) -> bool:
    if not groups:
        return False
    result = obj(row.get("result"))
    metadata = obj(result.get("metadata"))
    candidates = objects(obj(row.get("request")).get("candidates"))
    by_id = {str(candidate.get("id", "")): candidate for candidate in candidates}
    ref_ids = [
        str(result.get("choiceId", "")),
        str(metadata.get("hardChoiceId", "")),
        str(metadata.get("modelBestId", "")),
        str(metadata.get("rolloutRawBestId", "")),
        str(metadata.get("rolloutImmediateBestId", "")),
        str(metadata.get("rolloutGateChoiceId", "")),
    ]
    seen: set[tuple[str, str]] = set()
    for ref_id in ref_ids:
        info = wild_info(by_id.get(ref_id))
        if info and info["card_id"] in groups:
            seen.add((info["card_id"], info["color"]))
    return len(seen) >= 2


def candidate_effect(candidate: dict[str, Any]) -> str:
    summary = str(candidate.get("summary", ""))
    if summary.startswith("Action "):
        return summary[len("Action "):].split(" ", 1)[0].strip(".:").upper() or "ACTION"
    if summary.startswith("Deploy"):
        return "DEPLOY"
    if summary.startswith("Deposit action card "):
        return summary[len("Deposit action card "):].split(" ", 1)[0].strip(".:").upper() or "DEPOSIT"
    if summary.startswith("Deposit"):
        return "DEPOSIT"
    if summary.startswith("Discard"):
        return "DISCARD"
    return str(obj(candidate.get("payload")).get("actionType", "")).upper() or "UNKNOWN"


def has_memento(row: dict[str, Any]) -> bool:
    request = obj(row.get("request"))
    context = obj(request.get("context"))
    counterfactual = obj(context.get("counterfactual"))
    return bool(str(counterfactual.get("mementoJson", "")).strip())


def prepare_output(path: Path, mode: str) -> None:
    normalized = mode.strip().lower()
    if normalized in {"overwrite", "replace", "truncate"}:
        path.unlink(missing_ok=True)
        return
    if normalized in {"fail", "fail_if_exists", "create_new", ""}:
        if path.exists():
            raise SystemExit(f"output already exists: {path}")
        return
    raise SystemExit(f"unsupported trace mode: {mode}")


def check(name: str, ok: bool, detail: str, severity: str = "error") -> dict[str, Any]:
    return {"name": name, "ok": bool(ok), "severity": severity, "detail": detail}


def stats(values: Iterable[float]) -> dict[str, Any]:
    clean = sorted(value for value in values if math.isfinite(value))
    if not clean:
        return {"min": None, "p50": None, "p90": None, "max": None, "avg": None}
    return {
        "min": clean[0],
        "p50": percentile(clean, 50),
        "p90": percentile(clean, 90),
        "max": clean[-1],
        "avg": sum(clean) / len(clean),
    }


def percentile(values: Sequence[float], pct: int) -> float:
    if not values:
        return 0.0
    index = int(round((len(values) - 1) * pct / 100))
    return float(values[index])


def objects(value: Any) -> list[dict[str, Any]]:
    return [item for item in value if isinstance(item, dict)] if isinstance(value, list) else []


def obj(value: Any) -> dict[str, Any]:
    return value if isinstance(value, dict) else {}


def number(value: Any) -> float | None:
    try:
        out = float(value)
        return out if math.isfinite(out) else None
    except (TypeError, ValueError):
        return None


def as_bool(value: Any) -> bool:
    if isinstance(value, bool):
        return value
    if isinstance(value, str):
        return value.strip().lower() in {"1", "true", "yes"}
    return bool(value)


class Selection:
    def __init__(
        self,
        *,
        row_index: int,
        decision_id: str,
        session_id: str,
        state_sequence: int,
        natural_win: bool,
        source_choice_id: str,
        hard_choice_id: str,
        source_kind: str,
        source_color: str,
        hard_color: str,
        wild_candidate_count: int,
        wild_group_count: int,
        reference_disagreement: bool,
        source_summary: str,
        hard_summary: str,
        wild_groups: dict[str, list[dict[str, str]]],
    ) -> None:
        self.row_index = row_index
        self.decision_id = decision_id
        self.session_id = session_id
        self.state_sequence = state_sequence
        self.natural_win = natural_win
        self.source_choice_id = source_choice_id
        self.hard_choice_id = hard_choice_id
        self.source_kind = source_kind
        self.source_color = source_color
        self.hard_color = hard_color
        self.wild_candidate_count = wild_candidate_count
        self.wild_group_count = wild_group_count
        self.reference_disagreement = reference_disagreement
        self.source_summary = source_summary
        self.hard_summary = hard_summary
        self.wild_groups = wild_groups

    def ranking_key(self) -> tuple[int, int, int, str]:
        return (
            0 if self.reference_disagreement else 1,
            0 if not self.natural_win else 1,
            -self.wild_candidate_count,
            self.decision_id,
        )

    def to_json(self) -> dict[str, Any]:
        return {
            "decisionId": self.decision_id,
            "sessionId": self.session_id,
            "stateSequence": self.state_sequence,
            "naturalWin": self.natural_win,
            "sourceChoiceId": self.source_choice_id,
            "hardChoiceId": self.hard_choice_id,
            "sourceKind": self.source_kind,
            "sourceColor": self.source_color,
            "hardColor": self.hard_color,
            "wildCandidateCount": self.wild_candidate_count,
            "wildGroupCount": self.wild_group_count,
            "referenceDisagreement": self.reference_disagreement,
            "sourceSummary": self.source_summary,
            "hardSummary": self.hard_summary,
            "wildGroups": self.wild_groups,
        }


if __name__ == "__main__":
    raise SystemExit(main())
