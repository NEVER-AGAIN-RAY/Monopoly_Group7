#!/usr/bin/env python3
"""Create deterministic cumulative JSONL subsets for label-efficiency curves."""

from __future__ import annotations

import argparse
import collections
import json
import random
import sys
from pathlib import Path
from typing import Any, Dict, List, Sequence


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("input", type=Path)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--sizes", default="1000,5000,20000,100000")
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument(
        "--stratify-by",
        default="decisionKind,playerCount",
        help="Comma-separated keys: decisionKind,playerCount,source. Empty means global shuffle.",
    )
    parser.add_argument(
        "--prefix",
        default="subset",
        help="Output filename prefix; files are <prefix>-<size>.jsonl.",
    )
    args = parser.parse_args(argv)

    rows = load_rows(args.input)
    sizes = parse_sizes(args.sizes)
    keys = [part.strip() for part in args.stratify_by.split(",") if part.strip()]
    ordered = stratified_order(rows, keys, args.seed)

    args.output_dir.mkdir(parents=True, exist_ok=True)
    manifest = {
        "schema": "monopoly-deal-scaling-subsets-v1",
        "input": str(args.input),
        "inputRows": len(rows),
        "seed": args.seed,
        "stratifyBy": keys,
        "subsets": [],
    }
    for size in sizes:
        effective_size = min(size, len(ordered))
        subset = ordered[:effective_size]
        path = args.output_dir / f"{args.prefix}-{effective_size}.jsonl"
        write_rows(path, subset)
        manifest["subsets"].append(
            {
                "requestedRows": size,
                "rows": effective_size,
                "path": str(path),
                "byDecisionKind": dict(sorted(collections.Counter(decision_kind(row) for row in subset).items())),
                "byPlayerCount": dict(sorted(collections.Counter(str(player_count(row)) for row in subset).items())),
                "byTeacherSource": dict(sorted(collections.Counter(source(row) for row in subset).items())),
            }
        )
    manifest_path = args.output_dir / f"{args.prefix}-manifest.json"
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(manifest, ensure_ascii=False, indent=2))
    return 0


def load_rows(path: Path) -> List[Dict[str, Any]]:
    rows: List[Dict[str, Any]] = []
    with path.open("r", encoding="utf-8") as handle:
        for line_no, line in enumerate(handle, start=1):
            text = line.strip()
            if not text:
                continue
            try:
                row = json.loads(text)
            except json.JSONDecodeError as exc:
                raise SystemExit(f"{path}:{line_no}: invalid JSON: {exc}") from exc
            row["_rawLine"] = text
            rows.append(row)
    return rows


def parse_sizes(raw: str) -> List[int]:
    sizes: List[int] = []
    for part in raw.split(","):
        text = part.strip()
        if not text:
            continue
        try:
            value = int(text)
        except ValueError:
            raise SystemExit(f"invalid subset size: {text}") from None
        if value <= 0:
            raise SystemExit(f"subset size must be positive: {text}")
        sizes.append(value)
    if not sizes:
        raise SystemExit("--sizes must include at least one positive integer")
    return sorted(dict.fromkeys(sizes))


def stratified_order(rows: Sequence[Dict[str, Any]], keys: Sequence[str], seed: int) -> List[Dict[str, Any]]:
    rng = random.Random(seed)
    if not keys:
        out = list(rows)
        rng.shuffle(out)
        return out
    buckets: Dict[str, List[Dict[str, Any]]] = collections.defaultdict(list)
    for row in rows:
        buckets[stratum(row, keys)].append(row)
    for bucket_rows in buckets.values():
        rng.shuffle(bucket_rows)

    active = dict(sorted(buckets.items()))
    ordered: List[Dict[str, Any]] = []
    while active:
        for key in list(active.keys()):
            bucket = active[key]
            if bucket:
                ordered.append(bucket.pop())
            if not bucket:
                del active[key]
    return ordered


def stratum(row: Dict[str, Any], keys: Sequence[str]) -> str:
    parts: List[str] = []
    for key in keys:
        if key == "decisionKind":
            parts.append(decision_kind(row))
        elif key == "playerCount":
            parts.append(str(player_count(row)))
        elif key == "source":
            parts.append(source(row))
        else:
            parts.append("unknown")
    return "|".join(parts)


def write_rows(path: Path, rows: Sequence[Dict[str, Any]]) -> None:
    with path.open("w", encoding="utf-8") as handle:
        for row in rows:
            handle.write(row.get("_rawLine") or json.dumps(strip_internal(row), ensure_ascii=False, separators=(",", ":")))
            handle.write("\n")


def strip_internal(row: Dict[str, Any]) -> Dict[str, Any]:
    return {key: value for key, value in row.items() if not key.startswith("_")}


def decision_kind(row: Dict[str, Any]) -> str:
    return str(obj(row.get("request")).get("decisionKind", "unknown"))


def player_count(row: Dict[str, Any]) -> int:
    context = obj(obj(row.get("request")).get("context"))
    meta = obj(context.get("gameMeta"))
    try:
        return int(float(meta.get("playerCount", 0)))
    except (TypeError, ValueError):
        return 0


def source(row: Dict[str, Any]) -> str:
    metadata = obj(obj(row.get("result")).get("metadata"))
    return str(metadata.get("source", "unknown"))


def obj(value: Any) -> Dict[str, Any]:
    return value if isinstance(value, dict) else {}


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
