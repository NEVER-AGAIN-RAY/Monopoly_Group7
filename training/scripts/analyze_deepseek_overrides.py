#!/usr/bin/env python3
"""Summarize where DeepSeek disagrees with the source lookahead policy."""

from __future__ import annotations

import argparse
import collections
import json
import math
from pathlib import Path
from typing import Any, Dict, Iterable, List, Sequence


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("inputs", nargs="+", type=Path)
    parser.add_argument("--output", type=Path, default=None)
    parser.add_argument("--top-examples", type=int, default=20)
    args = parser.parse_args(argv)

    rows = [row for path in args.inputs for row in load_jsonl(path)]
    report = analyze(rows, max(0, args.top_examples))
    text = json.dumps(report, ensure_ascii=False, indent=2) + "\n"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(text, encoding="utf-8")
    print(text, end="")
    return 0


def load_jsonl(path: Path) -> Iterable[Dict[str, Any]]:
    with path.open("r", encoding="utf-8") as handle:
        for line_no, line in enumerate(handle, start=1):
            raw = line.strip()
            if not raw:
                continue
            try:
                row = json.loads(raw)
            except json.JSONDecodeError as exc:
                raise SystemExit(f"{path}:{line_no}: invalid JSON: {exc}") from exc
            if isinstance(row, dict):
                yield row


def analyze(rows: Sequence[Dict[str, Any]], top_examples: int) -> Dict[str, Any]:
    total = 0
    disagree = 0
    same_as_hard = 0
    same_as_source = 0
    buckets: Dict[str, List[Dict[str, Any]]] = collections.defaultdict(list)
    transitions: Dict[str, List[Dict[str, Any]]] = collections.defaultdict(list)
    examples: List[Dict[str, Any]] = []
    for row in rows:
        request = obj(row.get("request"))
        result = obj(row.get("result"))
        context = obj(request.get("context"))
        source_policy = obj(context.get("sourcePolicy"))
        source_meta = obj(source_policy.get("metadata"))
        source_choice_id = str(source_policy.get("choiceId", ""))
        deepseek_choice_id = str(result.get("choiceId", ""))
        if not deepseek_choice_id or not source_choice_id:
            continue
        total += 1
        hard_choice_id = str(source_meta.get("hardChoiceId", ""))
        if deepseek_choice_id == source_choice_id:
            same_as_source += 1
            continue
        disagree += 1
        if hard_choice_id and deepseek_choice_id == hard_choice_id:
            same_as_hard += 1
        source_candidate = candidate_by_id(request, source_choice_id)
        chosen_candidate = candidate_by_id(request, deepseek_choice_id)
        record = disagreement_record(
            row,
            request,
            source_meta,
            source_candidate,
            chosen_candidate,
            source_choice_id,
            deepseek_choice_id,
            hard_choice_id,
        )
        buckets[record["chosenEffect"]].append(record)
        transitions[f"{record['sourceEffect']} -> {record['chosenEffect']}"].append(record)
        examples.append(record)

    examples.sort(key=example_key)
    return {
        "schema": "monopoly-deal-deepseek-override-analysis-v1",
        "rows": total,
        "sameAsSource": same_as_source,
        "disagreements": disagree,
        "disagreementRate": ratio(disagree, total),
        "disagreementSameAsHard": same_as_hard,
        "byChosenEffect": summarize_groups(buckets),
        "byTransition": summarize_groups(transitions, limit=30),
        "topRiskExamples": examples[:top_examples],
    }


def disagreement_record(
    row: Dict[str, Any],
    request: Dict[str, Any],
    source_meta: Dict[str, Any],
    source_candidate: Dict[str, Any],
    chosen_candidate: Dict[str, Any],
    source_choice_id: str,
    deepseek_choice_id: str,
    hard_choice_id: str,
) -> Dict[str, Any]:
    scores = obj(source_meta.get("candidateScores"))
    outcome = obj(row.get("outcome"))
    chosen_score = maybe_float(scores.get(deepseek_choice_id))
    source_score = maybe_float(scores.get(source_choice_id))
    hard_score = maybe_float(scores.get(hard_choice_id))
    return {
        "decisionId": str(request.get("decisionId", "")),
        "sessionId": str(request.get("sessionId", "")),
        "choiceId": deepseek_choice_id,
        "sourceChoiceId": source_choice_id,
        "hardChoiceId": hard_choice_id,
        "chosenEffect": candidate_effect(chosen_candidate),
        "sourceEffect": candidate_effect(source_candidate),
        "chosenSummary": str(chosen_candidate.get("summary", "")),
        "sourceSummary": str(source_candidate.get("summary", "")),
        "lookaheadGapVsSource": delta(chosen_score, source_score),
        "lookaheadGapVsHard": delta(chosen_score, hard_score),
        "reward": maybe_float(outcome.get("reward")),
        "naturalWin": bool(outcome.get("naturalWin")),
        "boardRank": outcome.get("boardRank"),
        "completeSets": outcome.get("completeSets"),
    }


def summarize_groups(
    groups: Dict[str, List[Dict[str, Any]]],
    limit: int | None = None,
) -> List[Dict[str, Any]]:
    rows = []
    for key, values in groups.items():
        rewards = [v["reward"] for v in values if isinstance(v.get("reward"), (int, float))]
        gaps = [v["lookaheadGapVsSource"] for v in values if isinstance(v.get("lookaheadGapVsSource"), (int, float))]
        rows.append({
            "key": key,
            "count": len(values),
            "naturalWins": sum(1 for v in values if v.get("naturalWin")),
            "avgReward": avg(rewards),
            "avgLookaheadGapVsSource": avg(gaps),
            "negativeGapCount": sum(1 for gap in gaps if gap < 0),
        })
    rows.sort(key=lambda r: (-r["count"], str(r["key"])))
    return rows if limit is None else rows[:limit]


def candidate_by_id(request: Dict[str, Any], candidate_id: str) -> Dict[str, Any]:
    for candidate in list_obj(request.get("candidates")):
        if isinstance(candidate, dict) and str(candidate.get("id", "")) == candidate_id:
            return candidate
    return {}


def candidate_effect(candidate: Dict[str, Any]) -> str:
    payload = obj(candidate.get("payload"))
    action_type = str(payload.get("actionType", "")).upper()
    effect = str(payload.get("effectCode", "")).upper()
    if effect:
        return effect
    summary = str(candidate.get("summary", ""))
    if summary.startswith("Action "):
        token = summary[len("Action "):].split(" ", 1)[0].strip(".:").upper()
        if token:
            return token
    return action_type or "UNKNOWN"


def example_key(row: Dict[str, Any]) -> tuple[float, float, str]:
    reward = row.get("reward")
    gap = row.get("lookaheadGapVsSource")
    return (
        float(reward) if isinstance(reward, (int, float)) else 0.0,
        float(gap) if isinstance(gap, (int, float)) else 0.0,
        str(row.get("decisionId", "")),
    )


def ratio(num: int, den: int) -> float:
    return 0.0 if den <= 0 else num / den


def avg(values: Sequence[float]) -> float | None:
    clean = [float(v) for v in values if math.isfinite(float(v))]
    return None if not clean else sum(clean) / len(clean)


def delta(a: float | None, b: float | None) -> float | None:
    return None if a is None or b is None else a - b


def maybe_float(value: Any) -> float | None:
    try:
        if value is None:
            return None
        out = float(value)
        return out if math.isfinite(out) else None
    except (TypeError, ValueError):
        return None


def obj(value: Any) -> Dict[str, Any]:
    return value if isinstance(value, dict) else {}


def list_obj(value: Any) -> List[Any]:
    return value if isinstance(value, list) else []


if __name__ == "__main__":
    raise SystemExit(main())
