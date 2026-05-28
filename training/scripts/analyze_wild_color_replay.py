#!/usr/bin/env python3
"""Analyze counterfactual replay results for wild deployment color choices."""

from __future__ import annotations

import argparse
import collections
import json
import math
from pathlib import Path
from typing import Any, Sequence


REQUIRED_BY_COLOR = {
    "BROWN": 2,
    "LIGHT_BLUE": 3,
    "PINK": 3,
    "ORANGE": 3,
    "RED": 3,
    "YELLOW": 3,
    "GREEN": 3,
    "DARK_BLUE": 2,
    "RAILROAD": 4,
    "UTILITY": 2,
}


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--trace", type=Path, required=True)
    parser.add_argument("--counterfactual-report", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args(argv)

    rows = load_trace_rows(args.trace)
    replay = json.loads(args.counterfactual_report.read_text(encoding="utf-8"))
    report = analyze(rows, objects(replay.get("decisions")), args.trace, args.counterfactual_report)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    text = json.dumps(report, ensure_ascii=False, indent=2) + "\n"
    args.output.write_text(text, encoding="utf-8")
    print(text, end="")
    return 0 if report["ok"] else 1


def load_trace_rows(path: Path) -> dict[str, dict[str, Any]]:
    rows: dict[str, dict[str, Any]] = {}
    with path.open("r", encoding="utf-8") as handle:
        for line_no, line in enumerate(handle, start=1):
            text = line.strip()
            if not text:
                continue
            try:
                row = json.loads(text)
            except json.JSONDecodeError as exc:
                raise SystemExit(f"{path}:{line_no}: invalid JSON: {exc}") from exc
            decision_id = str(obj(row.get("request")).get("decisionId", ""))
            if not decision_id:
                raise SystemExit(f"{path}:{line_no}: missing request.decisionId")
            if decision_id in rows:
                raise SystemExit(f"{path}:{line_no}: duplicate decisionId {decision_id}")
            rows[decision_id] = row
    return rows


def analyze(
    rows: dict[str, dict[str, Any]],
    decisions: list[dict[str, Any]],
    trace_path: Path,
    replay_path: Path,
) -> dict[str, Any]:
    missing_rows = 0
    wild_decisions = 0
    informative_wild_decisions = 0
    clean_wild_decisions = 0
    forced_wild_decisions = 0
    candidate_errors = 0
    incomplete_candidates = 0
    source_best = 0
    hard_best = 0
    best_wild = 0
    source_better_than_hard = 0
    hard_better_than_source = 0
    source_same_as_hard = 0
    source_wild_better_than_hard_wild = 0
    hard_wild_better_than_source_wild = 0
    source_hard_wild_same = 0
    best_feature_counts: collections.Counter[str] = collections.Counter()
    best_color_counts: collections.Counter[str] = collections.Counter()
    source_color_counts: collections.Counter[str] = collections.Counter()
    hard_color_counts: collections.Counter[str] = collections.Counter()
    heuristic_top1: collections.Counter[str] = collections.Counter()
    rows_out: list[dict[str, Any]] = []

    for decision in decisions:
        decision_id = str(decision.get("decisionId", ""))
        row = rows.get(decision_id)
        if row is None:
            missing_rows += 1
            continue
        wild_candidates = [
            candidate for candidate in objects(decision.get("candidates"))
            if wild_info(candidate)
        ]
        if not wild_candidates:
            continue
        wild_decisions += 1
        forced = any(str(candidate.get("forceEndReason", "")).strip() for candidate in wild_candidates)
        if forced:
            forced_wild_decisions += 1
        else:
            clean_wild_decisions += 1
        candidate_errors += sum(1 for candidate in wild_candidates if str(candidate.get("error", "")).strip())
        incomplete_candidates += sum(1 for candidate in wild_candidates if not as_bool(candidate.get("gameOver")))

        best = best_outcomes(wild_candidates)
        if len(best) > 0:
            best_wild += 1
        if is_informative(wild_candidates):
            informative_wild_decisions += 1
        candidate_by_id = {str(candidate.get("candidateId", "")): candidate for candidate in wild_candidates}
        source = candidate_by_id.get(str(decision.get("sourceChoiceId", "")))
        hard = candidate_by_id.get(str(decision.get("hardChoiceId", "")))
        if source is not None and any(same_outcome(source, item) for item in best):
            source_best += 1
        if hard is not None and any(same_outcome(hard, item) for item in best):
            hard_best += 1
        if source is not None and hard is not None:
            cmp_source_hard = compare_outcomes(source, hard)
            if cmp_source_hard > 0:
                source_better_than_hard += 1
            elif cmp_source_hard < 0:
                hard_better_than_source += 1
            else:
                source_same_as_hard += 1
            if not forced:
                if cmp_source_hard > 0:
                    source_wild_better_than_hard_wild += 1
                elif cmp_source_hard < 0:
                    hard_wild_better_than_source_wild += 1
                else:
                    source_hard_wild_same += 1

        context_sets = self_sets(row)
        enriched = [enrich_candidate(candidate, context_sets) for candidate in wild_candidates]
        enriched_by_id = {item["candidateId"]: item for item in enriched}
        best_ids = {str(item.get("candidateId", "")) for item in best}
        best_enriched = [item for item in enriched if item["candidateId"] in best_ids]
        for item in best_enriched:
            best_color_counts[item["color"]] += 1
            best_feature_counts.update(feature_tags(item))
        for item in enriched:
            if item["candidateId"] == str(decision.get("sourceChoiceId", "")):
                source_color_counts[item["color"]] += 1
            if item["candidateId"] == str(decision.get("hardChoiceId", "")):
                hard_color_counts[item["color"]] += 1

        for name, key in HEURISTICS.items():
            top = sorted(enriched, key=key, reverse=True)
            if top and top[0]["candidateId"] in best_ids:
                heuristic_top1[name] += 1

        rows_out.append({
            "decisionId": decision_id,
            "sessionId": str(obj(row.get("request")).get("sessionId", "")),
            "naturalWin": as_bool(obj(row.get("outcome")).get("naturalWin")),
            "forcedWild": forced,
            "informativeWild": is_informative(wild_candidates),
            "sourceChoiceId": str(decision.get("sourceChoiceId", "")),
            "hardChoiceId": str(decision.get("hardChoiceId", "")),
            "bestWildCandidateIds": sorted(best_ids),
            "sourceWild": selected_summary(enriched_by_id.get(str(decision.get("sourceChoiceId", "")))),
            "hardWild": selected_summary(enriched_by_id.get(str(decision.get("hardChoiceId", "")))),
            "bestWild": [selected_summary(item) for item in best_enriched],
            "wildCandidates": enriched,
        })

    checks = [
        check("decisions_nonzero", len(decisions) > 0, f"decisions={len(decisions)}"),
        check("trace_rows_found", missing_rows == 0, f"missing={missing_rows}"),
        check("wild_decisions_nonzero", wild_decisions > 0, f"wild={wild_decisions}"),
        check("clean_wild_decisions_nonzero", clean_wild_decisions > 0, f"clean={clean_wild_decisions}", "warning"),
    ]
    failures = [item for item in checks if item["severity"] == "error" and not item["ok"]]
    return {
        "schema": "monopoly-deal-wild-color-replay-analysis-v1",
        "ok": not failures,
        "failureCount": len(failures),
        "tracePath": str(trace_path),
        "counterfactualReportPath": str(replay_path),
        "decisions": len(decisions),
        "missingRows": missing_rows,
        "wildDecisions": wild_decisions,
        "informativeWildDecisions": informative_wild_decisions,
        "cleanWildDecisions": clean_wild_decisions,
        "forcedWildDecisions": forced_wild_decisions,
        "candidateErrors": candidate_errors,
        "incompleteCandidates": incomplete_candidates,
        "sourceWildBestCount": source_best,
        "hardWildBestCount": hard_best,
        "anyBestWildCount": best_wild,
        "sourceWildBetterThanHardWild": source_better_than_hard,
        "hardWildBetterThanSourceWild": hard_better_than_source,
        "sourceWildSameAsHardWild": source_same_as_hard,
        "cleanSourceWildBetterThanHardWild": source_wild_better_than_hard_wild,
        "cleanHardWildBetterThanSourceWild": hard_wild_better_than_source_wild,
        "cleanSourceWildSameAsHardWild": source_hard_wild_same,
        "bestFeatureCounts": dict(best_feature_counts.most_common()),
        "bestColorCounts": dict(best_color_counts.most_common()),
        "sourceColorCounts": dict(source_color_counts.most_common()),
        "hardColorCounts": dict(hard_color_counts.most_common()),
        "heuristicTop1Matches": dict(heuristic_top1.most_common()),
        "topInformativeExamples": [
            item for item in rows_out
            if item["informativeWild"] and not item["forcedWild"]
        ][:20],
        "topForcedExamples": [
            item for item in rows_out
            if item["forcedWild"]
        ][:10],
        "checks": checks,
    }


def self_sets(row: dict[str, Any]) -> dict[str, tuple[int, int]]:
    out: dict[str, tuple[int, int]] = {}
    self_obj = obj(obj(obj(row.get("request")).get("context")).get("self"))
    for item in objects(self_obj.get("sets")):
        color = str(item.get("color", "")).upper()
        if not color:
            continue
        out[color] = (
            int(number(item.get("count")) or 0),
            int(number(item.get("need")) or REQUIRED_BY_COLOR.get(color, 3)),
        )
    return out


def enrich_candidate(candidate: dict[str, Any], sets: dict[str, tuple[int, int]]) -> dict[str, Any]:
    info = wild_info(candidate) or {"card_id": "", "color": ""}
    color = info["color"]
    count, need = sets.get(color, (0, REQUIRED_BY_COLOR.get(color, 3)))
    after = count + 1
    out = {
        "candidateId": str(candidate.get("candidateId", "")),
        "cardId": info["card_id"],
        "color": color,
        "count": count,
        "need": need,
        "after": after,
        "complete": after >= need,
        "overfullBefore": count >= need,
        "nearAfter": after == need - 1,
        "progress": count / max(1, need),
        "afterProgress": after / max(1, need),
        "reward": number(candidate.get("reward")) or 0.0,
        "boardScore": int(number(candidate.get("boardScore")) or 0),
        "naturalWin": as_bool(candidate.get("naturalWin")),
        "gameOver": as_bool(candidate.get("gameOver")),
        "forceEndReason": str(candidate.get("forceEndReason", "")),
        "error": str(candidate.get("error", "")),
        "summary": str(candidate.get("summary", "")),
    }
    return out


def feature_tags(item: dict[str, Any]) -> list[str]:
    tags: list[str] = []
    if item["complete"]:
        tags.append("complete_after")
    if item["overfullBefore"]:
        tags.append("overfull_before")
    if item["nearAfter"]:
        tags.append("near_after")
    if item["need"] <= 2:
        tags.append("short_set")
    if item["count"] == 0:
        tags.append("fresh_color")
    return tags


def best_outcomes(candidates: list[dict[str, Any]]) -> list[dict[str, Any]]:
    if not candidates:
        return []
    best = max(candidates, key=lambda item: (number(item.get("reward")) or 0.0,
                                            int(number(item.get("boardScore")) or 0)))
    return [item for item in candidates if same_outcome(item, best)]


def is_informative(candidates: list[dict[str, Any]]) -> bool:
    if len(candidates) < 2:
        return False
    first = candidates[0]
    return any(not same_outcome(first, item) for item in candidates[1:])


def same_outcome(left: dict[str, Any], right: dict[str, Any]) -> bool:
    return (
        (number(left.get("reward")) or 0.0) == (number(right.get("reward")) or 0.0)
        and int(number(left.get("boardScore")) or 0) == int(number(right.get("boardScore")) or 0)
    )


def compare_outcomes(left: dict[str, Any], right: dict[str, Any]) -> int:
    left_key = (number(left.get("reward")) or 0.0, int(number(left.get("boardScore")) or 0))
    right_key = (number(right.get("reward")) or 0.0, int(number(right.get("boardScore")) or 0))
    return (left_key > right_key) - (left_key < right_key)


def selected_summary(candidate: dict[str, Any] | None) -> dict[str, Any]:
    if candidate is None:
        return {}
    if "color" in candidate:
        return {
            "candidateId": candidate["candidateId"],
            "cardId": candidate["cardId"],
            "color": candidate["color"],
            "count": candidate["count"],
            "need": candidate["need"],
            "complete": candidate["complete"],
            "overfullBefore": candidate["overfullBefore"],
            "reward": candidate["reward"],
            "boardScore": candidate["boardScore"],
        }
    enriched = enrich_candidate(candidate, {})
    return selected_summary(enriched)


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


def check(name: str, ok: bool, detail: str, severity: str = "error") -> dict[str, Any]:
    return {"name": name, "ok": bool(ok), "severity": severity, "detail": detail}


HEURISTICS = {
    "current_progress": lambda item: (item["progress"], item["count"], -item["need"]),
    "after_progress": lambda item: (item["afterProgress"], item["after"], -item["need"]),
    "complete_near_short": lambda item: (
        int(item["complete"]),
        int(item["nearAfter"]),
        -item["need"],
        item["count"],
    ),
    "short_complete_near": lambda item: (
        -item["need"],
        int(item["complete"]),
        int(item["nearAfter"]),
        item["count"],
    ),
    "avoid_overfull_then_complete": lambda item: (
        -int(item["overfullBefore"]),
        int(item["complete"]),
        int(item["nearAfter"]),
        item["afterProgress"],
    ),
}


if __name__ == "__main__":
    raise SystemExit(main())
