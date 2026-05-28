#!/usr/bin/env python3
"""Compare an override candidate trace against a later relabel pass."""

from __future__ import annotations

import argparse
import collections
import json
from pathlib import Path
from typing import Any, Dict, Sequence


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--original", type=Path, required=True)
    parser.add_argument("--relabeled", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--top-examples", type=int, default=30)
    args = parser.parse_args(argv)

    report = compare(args.original, args.relabeled, max(0, args.top_examples))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    text = json.dumps(report, ensure_ascii=False, indent=2) + "\n"
    args.output.write_text(text, encoding="utf-8")
    print(text, end="")
    return 0 if report["ok"] else 1


def compare(original_path: Path, relabeled_path: Path, top_examples: int) -> Dict[str, Any]:
    originals = load_originals(original_path)
    rows = 0
    missing_original = 0
    choice_buckets: collections.Counter[str] = collections.Counter()
    new_effects: collections.Counter[str] = collections.Counter()
    old_to_new_effects: collections.Counter[tuple[str, str]] = collections.Counter()
    source_to_new_effects: collections.Counter[tuple[str, str]] = collections.Counter()
    examples = []
    usage = collections.Counter()
    token_totals = collections.Counter()

    for row in read_jsonl(relabeled_path):
        rows += 1
        request = obj(row.get("request"))
        result = obj(row.get("result"))
        decision_id = str(request.get("decisionId", ""))
        original = originals.get(decision_id)
        if original is None:
            missing_original += 1
            continue
        new_choice = str(result.get("choiceId", ""))
        bucket = choice_bucket(new_choice, original)
        new_effect = candidate_effect(original["candidates"].get(new_choice, {}))
        choice_buckets[bucket] += 1
        new_effects[new_effect] += 1
        old_to_new_effects[(original["oldEffect"], new_effect)] += 1
        source_to_new_effects[(original["sourceEffect"], new_effect)] += 1
        metadata = obj(result.get("metadata"))
        usage[str(metadata.get("source", "unknown"))] += 1
        usage_obj = obj(metadata.get("usage"))
        for key in ["prompt_tokens", "completion_tokens", "total_tokens"]:
            value = maybe_float(usage_obj.get(key))
            if value is not None:
                token_totals[key] += value
        if len(examples) < top_examples:
            examples.append(example_json(decision_id, new_choice, new_effect, bucket, original))

    matched = rows - missing_original
    return {
        "schema": "monopoly-deal-override-relabel-comparison-v1",
        "ok": missing_original == 0 and matched > 0,
        "originalPath": str(original_path),
        "relabeledPath": str(relabeled_path),
        "rows": rows,
        "matchedRows": matched,
        "missingOriginal": missing_original,
        "choiceBuckets": dict(sorted(choice_buckets.items())),
        "choiceRates": {
            key: round(value / matched, 4) if matched else 0.0
            for key, value in sorted(choice_buckets.items())
        },
        "newChosenEffect": dict(new_effects.most_common()),
        "topOldDeepSeekToNewEffect": transition_rows(old_to_new_effects),
        "topSourceLookaheadToNewEffect": transition_rows(source_to_new_effects),
        "byTeacherSource": dict(sorted(usage.items())),
        "tokenTotals": {key: int(value) for key, value in sorted(token_totals.items())},
        "topExamples": examples,
    }


def load_originals(path: Path) -> Dict[str, Dict[str, Any]]:
    out: Dict[str, Dict[str, Any]] = {}
    for row in read_jsonl(path):
        request = obj(row.get("request"))
        result = obj(row.get("result"))
        context = obj(request.get("context"))
        source_policy = obj(context.get("sourcePolicy"))
        source_meta = obj(source_policy.get("metadata"))
        candidates = {
            str(candidate.get("id", "")): candidate
            for candidate in list_obj(request.get("candidates"))
            if isinstance(candidate, dict)
        }
        old_choice = str(result.get("choiceId", ""))
        source_choice = str(source_policy.get("choiceId", ""))
        hard_choice = str(source_meta.get("hardChoiceId", ""))
        decision_id = str(request.get("decisionId", ""))
        out[decision_id] = {
            "old": old_choice,
            "source": source_choice,
            "hard": hard_choice,
            "oldEffect": candidate_effect(candidates.get(old_choice, {})),
            "sourceEffect": candidate_effect(candidates.get(source_choice, {})),
            "hardEffect": candidate_effect(candidates.get(hard_choice, {})),
            "oldSummary": str(candidates.get(old_choice, {}).get("summary", "")),
            "sourceSummary": str(candidates.get(source_choice, {}).get("summary", "")),
            "hardSummary": str(candidates.get(hard_choice, {}).get("summary", "")),
            "candidates": candidates,
        }
    return out


def choice_bucket(new_choice: str, original: Dict[str, Any]) -> str:
    if new_choice == original["old"]:
        return "same_as_old_deepseek"
    if new_choice == original["source"]:
        return "back_to_source_lookahead"
    if original["hard"] and new_choice == original["hard"]:
        return "same_as_hard"
    return "third_choice"


def candidate_effect(candidate: Dict[str, Any]) -> str:
    payload = obj(candidate.get("payload"))
    effect = str(payload.get("effectCode", "")).upper()
    if effect:
        return effect
    summary = str(candidate.get("summary", ""))
    if summary.startswith("Action "):
        token = summary[len("Action "):].split(" ", 1)[0].strip(".:").upper()
        if token:
            return token
    if summary.startswith("Deploy"):
        return "DEPLOY"
    if summary.startswith("Deposit"):
        return "DEPOSIT"
    return str(payload.get("actionType", "UNKNOWN")).upper()


def transition_rows(counter: collections.Counter[tuple[str, str]]) -> list[Dict[str, Any]]:
    return [
        {"from": start, "to": end, "count": count}
        for (start, end), count in counter.most_common(30)
    ]


def example_json(
    decision_id: str,
    new_choice: str,
    new_effect: str,
    bucket: str,
    original: Dict[str, Any],
) -> Dict[str, Any]:
    candidate = original["candidates"].get(new_choice, {})
    return {
        "decisionId": decision_id,
        "bucket": bucket,
        "newChoiceId": new_choice,
        "newEffect": new_effect,
        "newSummary": str(candidate.get("summary", "")),
        "oldDeepSeekChoiceId": original["old"],
        "oldDeepSeekEffect": original["oldEffect"],
        "oldDeepSeekSummary": original["oldSummary"],
        "sourceLookaheadChoiceId": original["source"],
        "sourceLookaheadEffect": original["sourceEffect"],
        "sourceLookaheadSummary": original["sourceSummary"],
        "hardChoiceId": original["hard"],
        "hardEffect": original["hardEffect"],
        "hardSummary": original["hardSummary"],
    }


def read_jsonl(path: Path):
    with path.open("r", encoding="utf-8") as handle:
        for line_no, line in enumerate(handle, start=1):
            raw = line.strip()
            if not raw:
                continue
            try:
                yield json.loads(raw)
            except json.JSONDecodeError as exc:
                raise SystemExit(f"{path}:{line_no}: invalid JSON: {exc}") from exc


def maybe_float(value: Any) -> float | None:
    try:
        if value is None:
            return None
        return float(value)
    except (TypeError, ValueError):
        return None


def obj(value: Any) -> Dict[str, Any]:
    return value if isinstance(value, dict) else {}


def list_obj(value: Any) -> list[Any]:
    return value if isinstance(value, list) else []


if __name__ == "__main__":
    raise SystemExit(main())
