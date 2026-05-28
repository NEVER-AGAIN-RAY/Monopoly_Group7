#!/usr/bin/env python3
"""Write a compact manifest for Monopoly Deal decision JSONL datasets."""

from __future__ import annotations

import argparse
import collections
import hashlib
import json
import statistics
import sys
import time
from pathlib import Path
from typing import Any, Dict, List, Sequence


def main(argv: Sequence[str]) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("jsonl", type=Path)
    parser.add_argument("--output", type=Path, default=None)
    args = parser.parse_args(argv)

    rows: List[Dict[str, Any]] = []
    sha = hashlib.sha256()
    with args.jsonl.open("rb") as handle:
        for raw in handle:
            sha.update(raw)
            line = raw.strip()
            if not line:
                continue
            rows.append(json.loads(line))

    manifest = build_manifest(args.jsonl, sha.hexdigest(), rows)
    text = json.dumps(manifest, ensure_ascii=False, indent=2) + "\n"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(text, encoding="utf-8")
    print(text, end="")
    return 0


def build_manifest(path: Path, sha256: str, rows: List[Dict[str, Any]]) -> Dict[str, Any]:
    by_kind = collections.Counter()
    by_source = collections.Counter()
    by_player_count = collections.Counter()
    by_round = collections.Counter()
    candidate_counts: List[int] = []
    sessions = set()
    actors = set()
    fallback_rows = 0
    prompt_share = 0.0
    completion_share = 0.0
    total_share = 0.0
    usage_rows = 0

    for row in rows:
        request = obj(row.get("request"))
        context = obj(request.get("context"))
        game_meta = obj(context.get("gameMeta"))
        result = obj(row.get("result"))
        metadata = obj(result.get("metadata"))
        source = str(metadata.get("source", "unknown"))
        by_source[source] += 1
        if source != "deepseek":
            fallback_rows += 1
        if any(key in metadata for key in ["promptTokensShare", "completionTokensShare", "totalTokensShare"]):
            usage_rows += 1
            prompt_share += numeric(metadata.get("promptTokensShare"))
            completion_share += numeric(metadata.get("completionTokensShare"))
            total_share += numeric(metadata.get("totalTokensShare"))
        by_kind[str(request.get("decisionKind", "unknown"))] += 1
        sessions.add(str(request.get("sessionId", "")))
        actors.add(str(request.get("actorPlayerId", "")))
        by_player_count[str(game_meta.get("playerCount", "unknown"))] += 1
        by_round[str(game_meta.get("roundNumber", "unknown"))] += 1
        candidate_counts.append(len(list_obj(request.get("candidates"))))

    return {
        "schema": "monopoly-deal-dataset-manifest-v1",
        "generatedAtEpochMs": int(time.time() * 1000),
        "path": str(path),
        "sha256": sha256,
        "rows": len(rows),
        "sessions": len(discard_blank(sessions)),
        "actors": len(discard_blank(actors)),
        "byDecisionKind": dict(sorted(by_kind.items())),
        "byTeacherSource": dict(sorted(by_source.items())),
        "deepseekRatio": ratio(by_source.get("deepseek", 0), len(rows)),
        "fallbackRatio": ratio(fallback_rows, len(rows)),
        "candidateCounts": {
            "avg": round(statistics.fmean(candidate_counts), 3) if candidate_counts else 0.0,
            "p50": percentile(candidate_counts, 50),
            "p90": percentile(candidate_counts, 90),
            "max": max(candidate_counts) if candidate_counts else 0,
        },
        "byPlayerCount": dict(sorted(by_player_count.items())),
        "byRound": dict(sorted(by_round.items(), key=lambda kv: str(kv[0]))),
        "tokenUsageEstimate": {
            "rowsWithUsage": usage_rows,
            "promptTokensShare": round(prompt_share, 3),
            "completionTokensShare": round(completion_share, 3),
            "totalTokensShare": round(total_share, 3),
            "avgTotalTokensPerDecision": round(total_share / usage_rows, 3) if usage_rows else 0.0,
        },
        "allDecisionKindsCovered": all(
            by_kind.get(k, 0) > 0
            for k in ["PLAY_CARD", "JUST_SAY_NO", "PAYMENT", "OVERFLOW_DISCARD"]
        ),
    }


def ratio(n: int, d: int) -> float:
    return 0.0 if d <= 0 else round(n / d, 6)


def percentile(values: List[int], pct: int) -> int:
    if not values:
        return 0
    ordered = sorted(values)
    index = int(round((len(ordered) - 1) * pct / 100))
    return ordered[index]


def discard_blank(values: set[str]) -> set[str]:
    return {value for value in values if value}


def obj(value: Any) -> Dict[str, Any]:
    return value if isinstance(value, dict) else {}


def list_obj(value: Any) -> List[Any]:
    return value if isinstance(value, list) else []


def numeric(value: Any) -> float:
    try:
        if value is None or value == "":
            return 0.0
        return float(value)
    except (TypeError, ValueError):
        return 0.0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
