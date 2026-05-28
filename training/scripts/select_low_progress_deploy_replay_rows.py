#!/usr/bin/env python3
"""Select losing low-progress deploy overrides for counterfactual replay."""

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
    parser.add_argument("--target-rows", type=int, default=40)
    parser.add_argument("--max-per-session", type=int, default=4)
    parser.add_argument("--max-completion-score", type=int, default=75)
    parser.add_argument("--min-score-gap", type=float, default=100.0)
    parser.add_argument("--trace-mode", default="fail_if_exists")
    args = parser.parse_args(argv)

    rows = list(load_rows(args.trace))
    selected, report = select_rows(
        rows=rows,
        trace_paths=args.trace,
        output_path=args.output,
        target_rows=max(0, args.target_rows),
        max_per_session=max(0, args.max_per_session),
        max_completion_score=max(0, args.max_completion_score),
        min_score_gap=args.min_score_gap,
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
    rows: list[dict[str, Any]],
    trace_paths: Sequence[Path],
    output_path: Path,
    target_rows: int,
    max_per_session: int,
    max_completion_score: int,
    min_score_gap: float,
) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    skipped: collections.Counter[str] = collections.Counter()
    eligible: list[Selection] = []
    rows_with_memento = 0
    losing_rows = 0
    for index, row in enumerate(rows):
        if has_memento(row):
            rows_with_memento += 1
        if not as_bool(obj(row.get("outcome")).get("naturalWin")):
            losing_rows += 1
        selection, reason = inspect_row(
            row=row,
            row_index=index,
            max_completion_score=max_completion_score,
            min_score_gap=min_score_gap,
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

    selected_rows = []
    for item in selected_info:
        row = deepcopy(rows[item.row_index])
        row["selection"] = item.to_json()
        selected_rows.append(row)

    by_hard = collections.Counter(item.hard_effect for item in selected_info)
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
        "schema": "monopoly-low-progress-deploy-replay-selection-v1",
        "ok": not failures,
        "failureCount": len(failures),
        "tracePaths": [str(path) for path in trace_paths],
        "outputPath": str(output_path),
        "rowsRead": len(rows),
        "rowsWithMemento": rows_with_memento,
        "losingRows": losing_rows,
        "eligibleRows": len(eligible),
        "rowsSelected": len(selected_info),
        "selectedSessions": len(by_session),
        "filters": {
            "targetRows": target_rows,
            "maxPerSession": max_per_session,
            "maxCompletionScore": max_completion_score,
            "minScoreGap": min_score_gap,
        },
        "skippedReasons": dict(sorted(skipped.items())),
        "selectedByHardEffect": dict(by_hard.most_common()),
        "completionScore": stats(item.completion_score for item in selected_info),
        "scoreGap": stats(item.score_gap for item in selected_info),
        "topExamples": [item.to_json() for item in selected_info[:30]],
        "checks": checks,
    }
    return selected_rows, report


def inspect_row(
    row: dict[str, Any],
    row_index: int,
    max_completion_score: int,
    min_score_gap: float,
) -> tuple["Selection | None", str]:
    if not has_memento(row):
        return None, "missing_memento"
    if as_bool(obj(row.get("outcome")).get("naturalWin")):
        return None, "winning_row"

    request = obj(row.get("request"))
    result = obj(row.get("result"))
    metadata = obj(result.get("metadata"))
    candidates = objects(request.get("candidates"))
    choice_id = str(result.get("choiceId", ""))
    hard_id = str(metadata.get("hardChoiceId", ""))
    if not choice_id or not hard_id:
        return None, "missing_choice"
    if choice_id == hard_id:
        return None, "same_as_hard"
    by_id = {str(candidate.get("id", "")): candidate for candidate in candidates}
    choice = by_id.get(choice_id)
    hard = by_id.get(hard_id)
    if choice is None or hard is None:
        return None, "choice_candidate_missing"
    choice_summary = str(choice.get("summary", ""))
    if candidate_effect(choice) != "DEPLOY":
        return None, "choice_not_deploy"
    completion_score = number_after(choice_summary, "completionScore=")
    if completion_score is None:
        return None, "missing_completion_score"
    if completion_score > max_completion_score:
        return None, "high_completion_score"

    score = number(metadata.get("score"))
    hard_score = number(metadata.get("hardChoiceScore"))
    if score is None or hard_score is None:
        return None, "missing_score"
    score_gap = score - hard_score
    if score_gap < min_score_gap:
        return None, "score_gap_too_small"

    return Selection(
        row_index=row_index,
        decision_id=str(request.get("decisionId", "")),
        session_id=str(request.get("sessionId", "")),
        state_sequence=int(number(request.get("stateSequence")) or 0),
        choice_id=choice_id,
        hard_choice_id=hard_id,
        hard_effect=candidate_effect(hard),
        completion_score=completion_score,
        score_gap=score_gap,
        choice_summary=choice_summary,
        hard_summary=str(hard.get("summary", "")),
    ), ""


def has_memento(row: dict[str, Any]) -> bool:
    context = obj(obj(row.get("request")).get("context"))
    counterfactual = obj(context.get("counterfactual"))
    return bool(str(counterfactual.get("mementoJson", "")).strip())


def candidate_effect(candidate: dict[str, Any]) -> str:
    payload = obj(candidate.get("payload"))
    action_type = str(payload.get("actionType", "")).strip().upper()
    summary = str(candidate.get("summary", "")).strip().upper()
    if action_type == "DEPLOY" or summary.startswith("DEPLOY"):
        return "DEPLOY"
    if action_type == "DEPOSIT" or "DEPOSIT" in summary:
        return "DEPOSIT"
    if summary.startswith("ACTION "):
        parts = summary.split()
        return parts[1].strip(".,") if len(parts) > 1 else "ACTION"
    return action_type or (summary.split()[0].strip(".,") if summary.split() else "UNKNOWN")


def number_after(summary: str, marker: str) -> int | None:
    start = summary.find(marker)
    if start < 0:
        return None
    start += len(marker)
    end = start
    while end < len(summary) and summary[end].isdigit():
        end += 1
    if end == start:
        return None
    return int(summary[start:end])


def number(value: Any) -> float | None:
    try:
        if value is None:
            return None
        out = float(value)
        return out if math.isfinite(out) else None
    except (TypeError, ValueError):
        return None


def as_bool(value: Any) -> bool:
    return bool(value) if isinstance(value, bool) else str(value).lower() == "true"


def obj(value: Any) -> dict[str, Any]:
    return value if isinstance(value, dict) else {}


def objects(value: Any) -> list[dict[str, Any]]:
    if not isinstance(value, list):
        return []
    return [item for item in value if isinstance(item, dict)]


def stats(values: Iterable[float]) -> dict[str, Any]:
    xs = sorted(float(value) for value in values)
    if not xs:
        return {"count": 0}
    return {
        "count": len(xs),
        "min": xs[0],
        "median": percentile(xs, 0.5),
        "mean": sum(xs) / len(xs),
        "max": xs[-1],
    }


def percentile(xs: list[float], q: float) -> float:
    if len(xs) == 1:
        return xs[0]
    pos = max(0.0, min(1.0, q)) * (len(xs) - 1)
    lo = math.floor(pos)
    hi = math.ceil(pos)
    if lo == hi:
        return xs[lo]
    return xs[lo] * (hi - pos) + xs[hi] * (pos - lo)


def check(name: str, ok: bool, detail: str, severity: str = "error") -> dict[str, Any]:
    return {"name": name, "ok": ok, "detail": detail, "severity": severity}


def prepare_output(path: Path, mode: str) -> None:
    if mode == "overwrite":
        path.unlink(missing_ok=True)
        return
    if mode == "fail_if_exists":
        if path.exists():
            raise SystemExit(f"output already exists: {path}")
        return
    raise SystemExit(f"unsupported trace mode: {mode}")


class Selection:
    def __init__(
        self,
        row_index: int,
        decision_id: str,
        session_id: str,
        state_sequence: int,
        choice_id: str,
        hard_choice_id: str,
        hard_effect: str,
        completion_score: int,
        score_gap: float,
        choice_summary: str,
        hard_summary: str,
    ) -> None:
        self.row_index = row_index
        self.decision_id = decision_id
        self.session_id = session_id
        self.state_sequence = state_sequence
        self.choice_id = choice_id
        self.hard_choice_id = hard_choice_id
        self.hard_effect = hard_effect
        self.completion_score = completion_score
        self.score_gap = score_gap
        self.choice_summary = choice_summary
        self.hard_summary = hard_summary

    def ranking_key(self) -> tuple[float, int, str, int]:
        return (-self.score_gap, self.completion_score, self.session_id, self.state_sequence)

    def to_json(self) -> dict[str, Any]:
        return {
            "decisionId": self.decision_id,
            "sessionId": self.session_id,
            "stateSequence": self.state_sequence,
            "choiceId": self.choice_id,
            "hardChoiceId": self.hard_choice_id,
            "choiceEffect": "DEPLOY",
            "hardEffect": self.hard_effect,
            "completionScore": self.completion_score,
            "scoreGap": self.score_gap,
            "choiceSummary": self.choice_summary,
            "hardSummary": self.hard_summary,
        }


if __name__ == "__main__":
    raise SystemExit(main())
