#!/usr/bin/env python3
"""Select trace rows worth counterfactual replay.

The output is still a normal decision JSONL trace, but limited to losing
decisions where the lookahead policy disagreed with the hard policy in a way
that is tactical, high-margin, or a same-effect target difference.
"""

from __future__ import annotations

import argparse
import collections
import json
import math
import sys
from copy import deepcopy
from pathlib import Path
from typing import Any, Iterable, Sequence


TACTICAL_EFFECTS = {
    "DEAL_BREAKER",
    "FORCED_DEAL",
    "STEAL_PROPERTY",
}


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("trace", nargs="+", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument("--target-rows", type=int, default=0)
    parser.add_argument("--max-per-session", type=int, default=4)
    parser.add_argument("--min-candidates", type=int, default=2)
    parser.add_argument("--min-score-gap", type=float, default=500.0)
    parser.add_argument("--include-wins", action="store_true")
    parser.add_argument("--trace-mode", default="fail_if_exists")
    args = parser.parse_args(argv)

    rows = list(load_rows(args.trace))
    selected, report = select_rows(
        rows=rows,
        trace_paths=args.trace,
        output_path=args.output,
        target_rows=max(0, args.target_rows),
        max_per_session=max(0, args.max_per_session),
        min_candidates=max(1, args.min_candidates),
        min_score_gap=args.min_score_gap,
        include_wins=args.include_wins,
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
    min_candidates: int,
    min_score_gap: float,
    include_wins: bool,
) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    skipped: collections.Counter[str] = collections.Counter()
    eligible: list[Selection] = []
    rows_with_memento = 0
    losing_rows = 0
    for index, row in enumerate(rows):
        request = obj(row.get("request"))
        outcome = obj(row.get("outcome"))
        if has_memento(row):
            rows_with_memento += 1
        if not as_bool(outcome.get("naturalWin")):
            losing_rows += 1
        selection, reason = inspect_row(
            row=row,
            row_index=index,
            min_candidates=min_candidates,
            min_score_gap=min_score_gap,
            include_wins=include_wins,
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

    by_choice = collections.Counter(item.choice_effect for item in selected_info)
    by_hard = collections.Counter(item.hard_effect for item in selected_info)
    by_transition = collections.Counter(f"{item.hard_effect}->{item.choice_effect}" for item in selected_info)
    by_reason = collections.Counter(reason for item in selected_info for reason in item.reasons)
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
        "schema": "monopoly-deal-lookahead-replay-row-selection-v1",
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
            "minCandidates": min_candidates,
            "minScoreGap": min_score_gap,
            "includeWins": include_wins,
            "tacticalEffects": sorted(TACTICAL_EFFECTS),
        },
        "skippedReasons": dict(sorted(skipped.items())),
        "selectedByChoiceEffect": dict(by_choice.most_common()),
        "selectedByHardEffect": dict(by_hard.most_common()),
        "selectedByTransition": dict(by_transition.most_common(30)),
        "selectedByReason": dict(by_reason.most_common()),
        "scoreGapAbs": stats(abs(item.score_gap) for item in selected_info),
        "topExamples": [item.to_json() for item in selected_info[:30]],
        "checks": checks,
    }
    return selected_rows, report


def inspect_row(
    row: dict[str, Any],
    row_index: int,
    min_candidates: int,
    min_score_gap: float,
    include_wins: bool,
) -> tuple["Selection | None", str]:
    request = obj(row.get("request"))
    result = obj(row.get("result"))
    metadata = obj(result.get("metadata"))
    outcome = obj(row.get("outcome"))
    candidates = objects(request.get("candidates"))
    if len(candidates) < min_candidates:
        return None, "too_few_candidates"
    if not has_memento(row):
        return None, "missing_memento"
    if not include_wins and as_bool(outcome.get("naturalWin")):
        return None, "winning_row"

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

    choice_effect = candidate_effect(choice)
    hard_effect = candidate_effect(hard)
    score = number(metadata.get("score"))
    hard_score = number(metadata.get("hardChoiceScore"))
    if score is None or hard_score is None:
        score_gap = number(metadata.get("hardMargin"))
        score_gap = 0.0 if score_gap is None else score_gap
    else:
        score_gap = score - hard_score

    reasons: list[str] = []
    if choice_effect == hard_effect and choice_effect in TACTICAL_EFFECTS:
        reasons.append("same_effect_tactical_target_diff")
    if choice_effect in TACTICAL_EFFECTS or hard_effect in TACTICAL_EFFECTS:
        reasons.append("tactical_transition")
    if abs(score_gap) >= min_score_gap:
        reasons.append("score_gap")
    if not reasons:
        return None, "not_tactical_or_high_gap"

    return Selection(
        row_index=row_index,
        decision_id=str(request.get("decisionId", "")),
        session_id=str(request.get("sessionId", "")),
        state_sequence=int(number(request.get("stateSequence")) or 0),
        choice_id=choice_id,
        hard_choice_id=hard_id,
        choice_effect=choice_effect,
        hard_effect=hard_effect,
        score_gap=score_gap,
        candidate_count=len(candidates),
        reasons=tuple(reasons),
        choice_summary=str(choice.get("summary", "")),
        hard_summary=str(hard.get("summary", "")),
    ), ""


def has_memento(row: dict[str, Any]) -> bool:
    request = obj(row.get("request"))
    context = obj(request.get("context"))
    counterfactual = obj(context.get("counterfactual"))
    return bool(str(counterfactual.get("mementoJson", "")).strip())


def candidate_effect(candidate: dict[str, Any]) -> str:
    payload = obj(candidate.get("payload"))
    effect = str(payload.get("effectCode", "")).upper()
    if effect:
        return effect
    action_type = str(payload.get("actionType", "")).upper()
    summary = str(candidate.get("summary", ""))
    if summary.startswith("Action "):
        token = summary[len("Action "):].split(" ", 1)[0].strip(".:").upper()
        if token:
            return token
    if summary.startswith("Deploy"):
        return "DEPLOY"
    if summary.startswith("Deposit"):
        return "DEPOSIT"
    if summary.startswith("Discard"):
        return "DISCARD"
    return action_type or "UNKNOWN"


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
        choice_id: str,
        hard_choice_id: str,
        choice_effect: str,
        hard_effect: str,
        score_gap: float,
        candidate_count: int,
        reasons: tuple[str, ...],
        choice_summary: str,
        hard_summary: str,
    ) -> None:
        self.row_index = row_index
        self.decision_id = decision_id
        self.session_id = session_id
        self.state_sequence = state_sequence
        self.choice_id = choice_id
        self.hard_choice_id = hard_choice_id
        self.choice_effect = choice_effect
        self.hard_effect = hard_effect
        self.score_gap = score_gap
        self.candidate_count = candidate_count
        self.reasons = reasons
        self.choice_summary = choice_summary
        self.hard_summary = hard_summary

    def ranking_key(self) -> tuple[int, int, float, int, str]:
        tactical_same = 0 if "same_effect_tactical_target_diff" in self.reasons else 1
        tactical_transition = 0 if "tactical_transition" in self.reasons else 1
        return (
            tactical_same,
            tactical_transition,
            -abs(self.score_gap),
            self.state_sequence,
            self.decision_id,
        )

    def to_json(self) -> dict[str, Any]:
        return {
            "decisionId": self.decision_id,
            "sessionId": self.session_id,
            "stateSequence": self.state_sequence,
            "choiceId": self.choice_id,
            "hardChoiceId": self.hard_choice_id,
            "choiceEffect": self.choice_effect,
            "hardEffect": self.hard_effect,
            "scoreGapVsHard": self.score_gap,
            "candidateCount": self.candidate_count,
            "reasons": list(self.reasons),
            "choiceSummary": self.choice_summary,
            "hardSummary": self.hard_summary,
        }


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
