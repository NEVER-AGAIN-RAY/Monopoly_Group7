#!/usr/bin/env python3
"""Audit lookahead outcome traces for weak losing decisions."""

from __future__ import annotations

import argparse
import collections
import json
import math
from pathlib import Path
from typing import Any, Iterable


def obj(value: Any) -> dict[str, Any]:
    return value if isinstance(value, dict) else {}


def num(value: Any) -> float | None:
    try:
        if value is None:
            return None
        out = float(value)
        return out if math.isfinite(out) else None
    except (TypeError, ValueError):
        return None


def load_rows(paths: Iterable[Path]) -> Iterable[dict[str, Any]]:
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
                yield row


def candidates_by_id(row: dict[str, Any]) -> dict[str, dict[str, Any]]:
    request = obj(row.get("request"))
    out: dict[str, dict[str, Any]] = {}
    for candidate in request.get("candidates", []):
        if isinstance(candidate, dict):
            cid = str(candidate.get("id", ""))
            if cid:
                out[cid] = candidate
    return out


def effect(candidate: dict[str, Any] | None) -> str:
    summary = str(obj(candidate).get("summary", "")).strip().upper()
    if summary.startswith("ACTION "):
        parts = summary.split()
        return parts[1].strip(".,") if len(parts) > 1 else "ACTION"
    if summary.startswith("DEPLOY"):
        return "DEPLOY"
    if "DEPOSIT" in summary or summary.startswith("BANK"):
        return "DEPOSIT"
    if summary.startswith("DISCARD"):
        return "DISCARD"
    return summary.split()[0].strip(".,") if summary.split() else "UNKNOWN"


def percentile(values: list[float], q: float) -> float | None:
    if not values:
        return None
    xs = sorted(values)
    pos = max(0.0, min(1.0, q)) * (len(xs) - 1)
    lo = math.floor(pos)
    hi = math.ceil(pos)
    if lo == hi:
        return xs[lo]
    return xs[lo] * (hi - pos) + xs[hi] * (pos - lo)


def choice_summary(row: dict[str, Any]) -> dict[str, Any]:
    result = obj(row.get("result"))
    metadata = obj(result.get("metadata"))
    request = obj(row.get("request"))
    cands = candidates_by_id(row)
    choice_id = str(result.get("choiceId", ""))
    hard_id = str(metadata.get("hardChoiceId", ""))
    choice = cands.get(choice_id)
    hard = cands.get(hard_id)
    score = num(metadata.get("score"))
    hard_score = num(metadata.get("hardChoiceScore"))
    margin = score - hard_score if score is not None and hard_score is not None else None
    return {
        "decisionId": request.get("decisionId", ""),
        "sessionId": request.get("sessionId", ""),
        "stateSequence": request.get("stateSequence", 0),
        "choiceId": choice_id,
        "hardChoiceId": hard_id,
        "choiceEffect": effect(choice),
        "hardEffect": effect(hard),
        "marginVsHard": margin,
        "choiceSummary": obj(choice).get("summary", ""),
        "hardSummary": obj(hard).get("summary", ""),
    }


def sorted_session_rows(rows: list[dict[str, Any]]) -> list[dict[str, Any]]:
    return sorted(rows, key=lambda r: obj(r.get("request")).get("stateSequence", 0))


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("trace", nargs="+", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--top", type=int, default=30)
    parser.add_argument("--high-margin", type=float, default=500.0)
    parser.add_argument("--late-window", type=int, default=8)
    args = parser.parse_args()

    rows = list(load_rows(args.trace))
    total = len(rows)
    rows_by_session: dict[str, list[dict[str, Any]]] = collections.defaultdict(list)
    losing_rows = []
    winning_rows = []
    by_choice = collections.Counter()
    losing_by_choice = collections.Counter()
    hard_override_losing = collections.Counter()
    high_margin_losing_overrides = collections.Counter()
    late_losing_choice_effects = collections.Counter()
    late_losing_hard_transitions = collections.Counter()
    session_outcomes = collections.Counter()
    losing_sessions_by_sets = collections.Counter()
    winning_sessions_by_sets = collections.Counter()
    margins: list[float] = []
    losing_margins: list[float] = []
    rows_per_session: list[float] = []
    examples: list[dict[str, Any]] = []
    high_margin_examples: list[dict[str, Any]] = []
    losing_session_examples: list[dict[str, Any]] = []

    for row in rows:
        request = obj(row.get("request"))
        session_id = str(request.get("sessionId", ""))
        rows_by_session[session_id].append(row)
        result = obj(row.get("result"))
        metadata = obj(result.get("metadata"))
        outcome = obj(row.get("outcome"))
        cands = candidates_by_id(row)
        choice_id = str(result.get("choiceId", ""))
        hard_id = str(metadata.get("hardChoiceId", ""))
        choice = cands.get(choice_id)
        hard = cands.get(hard_id)
        choice_effect = effect(choice)
        hard_effect = effect(hard)
        natural_win = bool(outcome.get("naturalWin"))
        reward = num(outcome.get("reward"))
        score = num(metadata.get("score"))
        hard_score = num(metadata.get("hardChoiceScore"))
        margin = score - hard_score if score is not None and hard_score is not None else None

        by_choice[choice_effect] += 1
        if margin is not None:
            margins.append(margin)
        if natural_win:
            winning_rows.append(row)
            continue
        losing_rows.append(row)
        losing_by_choice[choice_effect] += 1
        if choice_id and hard_id and choice_id != hard_id:
            hard_override_losing[f"{hard_effect}->{choice_effect}"] += 1
            if margin is not None and margin >= args.high_margin:
                high_margin_losing_overrides[f"{hard_effect}->{choice_effect}"] += 1
                if len(high_margin_examples) < args.top:
                    example = choice_summary(row)
                    example.update({
                        "reward": reward,
                        "completeSets": outcome.get("completeSets", 0),
                        "boardRank": outcome.get("boardRank", 0),
                    })
                    high_margin_examples.append(example)
        if margin is not None:
            losing_margins.append(margin)
        if len(examples) < args.top:
            examples.append({
                "decisionId": request.get("decisionId", ""),
                "sessionId": request.get("sessionId", ""),
                "stateSequence": request.get("stateSequence", 0),
                "choiceId": choice_id,
                "hardChoiceId": hard_id,
                "choiceEffect": choice_effect,
                "hardEffect": hard_effect,
                "marginVsHard": margin,
                "reward": reward,
                "completeSets": outcome.get("completeSets", 0),
                "boardRank": outcome.get("boardRank", 0),
                "choiceSummary": obj(choice).get("summary", ""),
                "hardSummary": obj(hard).get("summary", ""),
            })

    for session_id, session_rows in sorted(rows_by_session.items()):
        ordered = sorted_session_rows(session_rows)
        rows_per_session.append(float(len(ordered)))
        final_outcome = obj(ordered[-1].get("outcome")) if ordered else {}
        natural_win = bool(final_outcome.get("naturalWin"))
        complete_sets = str(final_outcome.get("completeSets", 0))
        if natural_win:
            session_outcomes["win"] += 1
            winning_sessions_by_sets[complete_sets] += 1
            continue
        session_outcomes["loss"] += 1
        losing_sessions_by_sets[complete_sets] += 1
        late_rows = ordered[-max(0, args.late_window):] if args.late_window > 0 else []
        for row in late_rows:
            summary = choice_summary(row)
            late_losing_choice_effects[summary["choiceEffect"]] += 1
            if summary["choiceId"] and summary["hardChoiceId"] and summary["choiceId"] != summary["hardChoiceId"]:
                late_losing_hard_transitions[
                    f"{summary['hardEffect']}->{summary['choiceEffect']}"
                ] += 1
        if len(losing_session_examples) < args.top:
            high_margin = [
                choice_summary(row)
                for row in ordered
                if (choice_summary(row)["marginVsHard"] is not None
                    and choice_summary(row)["marginVsHard"] >= args.high_margin
                    and choice_summary(row)["choiceId"] != choice_summary(row)["hardChoiceId"])
            ]
            losing_session_examples.append({
                "sessionId": session_id,
                "decisions": len(ordered),
                "completeSets": final_outcome.get("completeSets", 0),
                "boardRank": final_outcome.get("boardRank", 0),
                "reward": final_outcome.get("reward"),
                "lateChoices": [choice_summary(row) for row in late_rows],
                "highMarginOverrides": high_margin[:5],
            })

    report = {
        "schema": "monopoly-lookahead-outcome-trace-audit-v1",
        "traces": [str(path) for path in args.trace],
        "rows": total,
        "sessions": len(rows_by_session),
        "sessionOutcomes": dict(session_outcomes),
        "sessionWinRate": (
            session_outcomes["win"] / len(rows_by_session)
            if rows_by_session else 0.0
        ),
        "rowsPerSession": {
            "p10": percentile(rows_per_session, 0.10),
            "median": percentile(rows_per_session, 0.50),
            "p90": percentile(rows_per_session, 0.90),
        },
        "winningSessionsByFinalCompleteSets": dict(winning_sessions_by_sets),
        "losingSessionsByFinalCompleteSets": dict(losing_sessions_by_sets),
        "winningRows": len(winning_rows),
        "losingRows": len(losing_rows),
        "naturalWinRateByDecision": len(winning_rows) / total if total else 0.0,
        "choiceEffects": by_choice.most_common(args.top),
        "losingChoiceEffects": losing_by_choice.most_common(args.top),
        "losingHardOverrideTransitions": hard_override_losing.most_common(args.top),
        "highMarginThreshold": args.high_margin,
        "highMarginLosingOverrideTransitions": high_margin_losing_overrides.most_common(args.top),
        "lateWindow": args.late_window,
        "lateLosingChoiceEffects": late_losing_choice_effects.most_common(args.top),
        "lateLosingHardOverrideTransitions": late_losing_hard_transitions.most_common(args.top),
        "marginVsHard": {
            "p10": percentile(margins, 0.10),
            "median": percentile(margins, 0.50),
            "p90": percentile(margins, 0.90),
        },
        "losingMarginVsHard": {
            "p10": percentile(losing_margins, 0.10),
            "median": percentile(losing_margins, 0.50),
            "p90": percentile(losing_margins, 0.90),
        },
        "losingExamples": examples,
        "highMarginLosingExamples": high_margin_examples,
        "losingSessionExamples": losing_session_examples,
    }
    text = json.dumps(report, ensure_ascii=False, indent=2)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(text + "\n", encoding="utf-8")
    print(text)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
