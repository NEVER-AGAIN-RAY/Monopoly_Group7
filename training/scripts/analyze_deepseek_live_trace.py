#!/usr/bin/env python3
"""Summarize live DeepSeek decision traces against the localScore prior."""

from __future__ import annotations

import argparse
import collections
import json
from pathlib import Path


def decision_kind(summary: str) -> str:
    text = (summary or "").upper()
    if "DEPOSIT" in text or "BANK" in text:
        return "DEPOSIT"
    if "DEPLOY" in text:
        return "DEPLOY"
    if "PASS_GO" in text:
        return "PASS_GO"
    if "DEAL_BREAKER" in text:
        return "DEAL_BREAKER"
    if "FORCED_DEAL" in text:
        return "FORCED_DEAL"
    if "STEAL_PROPERTY" in text:
        return "STEAL_PROPERTY"
    if "RENT" in text:
        return "RENT"
    if "DEBT_COLLECTOR" in text:
        return "DEBT_COLLECTOR"
    if "BIRTHDAY" in text:
        return "BIRTHDAY"
    return "OTHER"


def load_rows(paths: list[Path]) -> list[dict]:
    rows = []
    for path in paths:
        for line_number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
            if not line.strip():
                continue
            row = json.loads(line)
            row["_sourcePath"] = str(path)
            row["_sourceLine"] = line_number
            rows.append(row)
    return rows


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("traces", nargs="+", type=Path)
    parser.add_argument("--output", type=Path, default=None)
    parser.add_argument("--large-gap", type=float, default=500.0)
    parser.add_argument("--examples", type=int, default=20)
    args = parser.parse_args()

    rows = load_rows(args.traces)
    chosen_kinds = collections.Counter()
    local_best_kinds = collections.Counter()
    disagreements = []
    large_gaps = []
    local_fallbacks = 0
    total_gap = 0.0

    for row in rows:
        request = row.get("request", {})
        result = row.get("result", {})
        metadata = result.get("metadata", {})
        choice_id = result.get("choiceId", "")
        local_best_id = metadata.get("localBestChoiceId", "")
        scores = metadata.get("localCandidateScores", {})
        candidates = {
            candidate.get("id", ""): candidate
            for candidate in request.get("candidates", [])
        }
        chosen_summary = candidates.get(choice_id, {}).get("summary", "")
        local_summary = candidates.get(local_best_id, {}).get("summary", "")
        chosen_kind = decision_kind(chosen_summary)
        local_kind = decision_kind(local_summary)
        chosen_kinds[chosen_kind] += 1
        local_best_kinds[local_kind] += 1
        if metadata.get("localFallbackUsed"):
            local_fallbacks += 1

        chosen_score = float(scores.get(choice_id, 0.0))
        local_score = float(scores.get(local_best_id, chosen_score))
        gap = local_score - chosen_score
        total_gap += gap
        if choice_id != local_best_id:
            item = {
                "sourcePath": row.get("_sourcePath", ""),
                "sourceLine": row.get("_sourceLine", 0),
                "stateSequence": request.get("stateSequence", 0),
                "choiceId": choice_id,
                "choiceKind": chosen_kind,
                "choiceScore": chosen_score,
                "choiceSummary": chosen_summary,
                "localBestChoiceId": local_best_id,
                "localBestKind": local_kind,
                "localBestScore": local_score,
                "localBestSummary": local_summary,
                "localScoreGap": gap,
                "requestSource": metadata.get("requestSource", ""),
            }
            disagreements.append(item)
            if gap >= args.large_gap:
                large_gaps.append(item)

    out = {
        "traces": [str(path) for path in args.traces],
        "decisions": len(rows),
        "disagreementsWithLocalBest": len(disagreements),
        "largeLocalScoreGaps": len(large_gaps),
        "largeGapThreshold": args.large_gap,
        "localFallbacks": local_fallbacks,
        "averageLocalScoreGap": total_gap / len(rows) if rows else 0.0,
        "chosenKinds": dict(sorted(chosen_kinds.items())),
        "localBestKinds": dict(sorted(local_best_kinds.items())),
        "largeGapExamples": large_gaps[: args.examples],
        "disagreementExamples": disagreements[: args.examples],
    }
    text = json.dumps(out, ensure_ascii=False, indent=2)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(text + "\n", encoding="utf-8")
    print(text)


if __name__ == "__main__":
    main()
