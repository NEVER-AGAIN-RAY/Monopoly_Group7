#!/usr/bin/env python3
"""Estimate DeepSeek labeling cost from Monopoly Deal trace or manifest usage."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any, Dict, Sequence


def main(argv: Sequence[str]) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("inputs", nargs="+", type=Path)
    parser.add_argument("--prompt-price-per-million", type=float, required=True)
    parser.add_argument("--completion-price-per-million", type=float, required=True)
    parser.add_argument("--target-labels", type=int, default=0)
    parser.add_argument("--output", type=Path, default=None)
    args = parser.parse_args(argv)

    usage = {"rowsWithUsage": 0, "promptTokensShare": 0.0, "completionTokensShare": 0.0, "totalTokensShare": 0.0}
    rows = 0
    for path in args.inputs:
        partial, count = usage_from_path(path)
        rows += count
        for key in usage:
            usage[key] += partial.get(key, 0.0)

    prompt_cost = usage["promptTokensShare"] / 1_000_000 * args.prompt_price_per_million
    completion_cost = usage["completionTokensShare"] / 1_000_000 * args.completion_price_per_million
    total_cost = prompt_cost + completion_cost
    per_label = total_cost / usage["rowsWithUsage"] if usage["rowsWithUsage"] else 0.0
    projected = per_label * args.target_labels if args.target_labels else 0.0
    result = {
        "inputs": [str(path) for path in args.inputs],
        "rows": rows,
        "rowsWithUsage": int(usage["rowsWithUsage"]),
        "promptTokensShare": round(usage["promptTokensShare"], 3),
        "completionTokensShare": round(usage["completionTokensShare"], 3),
        "totalTokensShare": round(usage["totalTokensShare"], 3),
        "avgTotalTokensPerDecision": round(
            usage["totalTokensShare"] / usage["rowsWithUsage"], 3
        ) if usage["rowsWithUsage"] else 0.0,
        "promptPricePerMillion": args.prompt_price_per_million,
        "completionPricePerMillion": args.completion_price_per_million,
        "estimatedPromptCost": round(prompt_cost, 6),
        "estimatedCompletionCost": round(completion_cost, 6),
        "estimatedTotalCost": round(total_cost, 6),
        "estimatedCostPerLabel": round(per_label, 8),
        "targetLabels": args.target_labels,
        "projectedTargetCost": round(projected, 6),
    }
    text = json.dumps(result, ensure_ascii=False, indent=2) + "\n"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(text, encoding="utf-8")
    print(text, end="")
    return 0


def usage_from_path(path: Path) -> tuple[Dict[str, float], int]:
    if path.suffix == ".jsonl":
        return usage_from_jsonl(path)
    payload = json.loads(path.read_text(encoding="utf-8"))
    if "tokenUsageEstimate" in payload:
        usage = obj(payload.get("tokenUsageEstimate"))
    elif "tokenUsage" in payload:
        usage = obj(payload.get("tokenUsage"))
    else:
        usage = obj(payload.get("tokenUsageEstimate"))
    return {
        "rowsWithUsage": number(usage.get("rowsWithUsage")),
        "promptTokensShare": number(usage.get("promptTokensShare")),
        "completionTokensShare": number(usage.get("completionTokensShare")),
        "totalTokensShare": number(usage.get("totalTokensShare")),
    }, int(number(payload.get("rows")))


def usage_from_jsonl(path: Path) -> tuple[Dict[str, float], int]:
    usage = {"rowsWithUsage": 0.0, "promptTokensShare": 0.0, "completionTokensShare": 0.0, "totalTokensShare": 0.0}
    rows = 0
    with path.open("r", encoding="utf-8") as handle:
        for line_no, line in enumerate(handle, start=1):
            text = line.strip()
            if not text:
                continue
            rows += 1
            try:
                row = json.loads(text)
            except json.JSONDecodeError as exc:
                raise SystemExit(f"{path}:{line_no}: invalid JSON: {exc}") from exc
            metadata = obj(obj(row.get("result")).get("metadata"))
            if not any(key in metadata for key in ["promptTokensShare", "completionTokensShare", "totalTokensShare"]):
                continue
            usage["rowsWithUsage"] += 1
            usage["promptTokensShare"] += number(metadata.get("promptTokensShare"))
            usage["completionTokensShare"] += number(metadata.get("completionTokensShare"))
            usage["totalTokensShare"] += number(metadata.get("totalTokensShare"))
    return usage, rows


def obj(value: Any) -> Dict[str, Any]:
    return value if isinstance(value, dict) else {}


def number(value: Any) -> float:
    try:
        if value is None or value == "":
            return 0.0
        return float(value)
    except (TypeError, ValueError):
        return 0.0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
