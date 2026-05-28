#!/usr/bin/env python3
"""Check whether a distillation run is ready for local or production use."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any, Dict, List, Sequence


REQUIRED_KINDS = ["PLAY_CARD", "JUST_SAY_NO", "PAYMENT", "OVERFLOW_DISCARD"]
MODEL_FILENAMES = {
    "mlp": "candidate_ranker_mlp.json",
    "linear": "candidate_ranker_linear.json",
}


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("trace", type=Path)
    parser.add_argument("output_prefix", type=Path)
    parser.add_argument("--mode", choices=["local", "production"], default="production")
    parser.add_argument("--min-rows", type=int, default=None)
    parser.add_argument("--min-rare-kind-rows", type=int, default=None)
    parser.add_argument("--min-validation-top1", type=float, default=0.55)
    parser.add_argument("--min-gameplay-games", type=int, default=1)
    parser.add_argument("--model-type", choices=["mlp", "linear"], default="mlp")
    parser.add_argument("--gameplay", type=Path, default=None)
    parser.add_argument("--require-natural-gameplay", action="store_true")
    parser.add_argument("--trace-audit", type=Path, default=None)
    parser.add_argument("--quality-report", type=Path, default=None)
    parser.add_argument("--output", type=Path, default=None)
    args = parser.parse_args(argv)

    report = build_readiness_report(
        trace=args.trace,
        output_prefix=args.output_prefix,
        mode=args.mode,
        min_rows=args.min_rows,
        min_rare_kind_rows=args.min_rare_kind_rows,
        min_validation_top1=args.min_validation_top1,
        min_gameplay_games=args.min_gameplay_games,
        model_type=args.model_type,
        gameplay_path=args.gameplay,
        require_natural_gameplay=args.require_natural_gameplay,
        trace_audit_path=args.trace_audit,
        quality_report_path=args.quality_report,
    )
    text = json.dumps(report, ensure_ascii=False, indent=2) + "\n"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(text, encoding="utf-8")
    print(text, end="")
    return 0 if report["ready"] else 1


def build_readiness_report(
    trace: Path,
    output_prefix: Path,
    mode: str,
    min_rows: int | None,
    min_rare_kind_rows: int | None,
    min_validation_top1: float,
    min_gameplay_games: int,
    model_type: str,
    gameplay_path: Path | None,
    require_natural_gameplay: bool = False,
    trace_audit_path: Path | None = None,
    quality_report_path: Path | None = None,
) -> Dict[str, Any]:
    production = mode == "production"
    preferred_source = "deepseek" if production else ""
    min_rows = min_rows if min_rows is not None else (5000 if production else 1000)
    min_rare_kind_rows = min_rare_kind_rows if min_rare_kind_rows is not None else (100 if production else 1)
    model_dir = output_prefix.parent / f"{output_prefix.name}-{model_type}"
    metrics_path = model_dir / "metrics.json"
    model_path = model_dir / MODEL_FILENAMES[model_type]
    manifest_path = output_prefix.parent / f"{output_prefix.name}-dataset_manifest.json"
    quality_path = quality_report_path or output_prefix.parent / f"{output_prefix.name}-quality_report.json"
    audit_path = trace_audit_path or output_prefix.parent / f"{output_prefix.name}-trace_audit.json"
    gameplay_path = gameplay_path or model_dir / "gameplay_vs_hard.json"

    rows = load_jsonl(trace) if trace.exists() else []
    metrics = load_json_file(metrics_path)
    manifest = load_json_file(manifest_path)
    quality = load_json_file(quality_path)
    audit = load_json_file(audit_path)
    gameplay = load_json_file(gameplay_path)
    source_counts = counts_from_rows(rows, "source")
    kind_counts = counts_from_rows(rows, "kind")
    rows_with_usage = sum(1 for row in rows if has_token_usage(row))
    validation = obj(metrics.get("validation"))

    checks: List[Dict[str, Any]] = []
    checks.append(check("trace_exists", trace.exists(), str(trace)))
    checks.append(check("trace_min_rows", len(rows) >= min_rows, f"{len(rows)} / {min_rows}"))
    checks.append(check("all_decision_kinds", all(kind_counts.get(kind, 0) > 0 for kind in REQUIRED_KINDS), str(kind_counts)))
    checks.append(check(
        "rare_kind_rows",
        all(kind_counts.get(kind, 0) >= min_rare_kind_rows for kind in REQUIRED_KINDS if kind != "PLAY_CARD"),
        f"min {min_rare_kind_rows}; counts={kind_counts}",
    ))
    if production:
        deepseek_ratio = ratio(source_counts.get("deepseek", 0), len(rows))
        checks.append(check("deepseek_source_ratio", deepseek_ratio >= 0.95, f"{deepseek_ratio:.1%} / 95.0%"))
        checks.append(check("token_usage_present", rows_with_usage == len(rows) and len(rows) > 0, f"{rows_with_usage} / {len(rows)}"))
    checks.append(check("manifest_exists", manifest_path.exists(), str(manifest_path)))
    checks.append(check("quality_report_exists", quality_path.exists(), str(quality_path)))
    if quality:
        quality_key = "readyForProductionTraining" if production else "readyForTraining"
        checks.append(check(f"quality_{quality_key}", bool(quality.get(quality_key)), str(quality.get(quality_key))))
    checks.append(check("metrics_exists", metrics_path.exists(), str(metrics_path)))
    if validation:
        top1 = number(validation.get("top1"))
        first = number(validation.get("firstCandidateTop1"))
        random_expected = number(validation.get("randomExpectedTop1"))
        checks.append(check("validation_top1_floor", top1 >= min_validation_top1, f"{top1:.3f} / {min_validation_top1:.3f}"))
        checks.append(check("validation_beats_first_candidate", top1 > first, f"{top1:.3f} vs {first:.3f}"))
        checks.append(check("validation_beats_random", top1 > random_expected, f"{top1:.3f} vs {random_expected:.3f}"))
    else:
        checks.append(check("validation_metrics_present", False, "missing validation metrics"))
    checks.append(check("model_json_exists", model_path.exists(), str(model_path)))
    checks.append(check("gameplay_exists", gameplay_path.exists(), str(gameplay_path)))
    if gameplay:
        games = int(number(gameplay.get("gamesRequested")))
        game_rows = gameplay.get("games")
        evaluated = int(number(gameplay.get("evaluatedGames")))
        if evaluated <= 0 and isinstance(game_rows, list):
            evaluated = len(game_rows)
        completed = int(number(gameplay.get("completedGames")))
        if evaluated <= 0 and completed == games:
            evaluated = games
        checks.append(check("gameplay_min_games", games >= min_gameplay_games, f"{games} / {min_gameplay_games}"))
        checks.append(check("gameplay_evaluated", evaluated == games and evaluated > 0, f"{evaluated} / {games}"))
        if require_natural_gameplay:
            checks.append(check("gameplay_natural_completed", completed == games and games > 0, f"{completed} / {games}"))
    if production:
        checks.append(check("trace_audit_exists", audit_path.exists(), str(audit_path)))
        if audit:
            checks.append(check("trace_audit_ok", bool(audit.get("ok")), str(audit.get("ok"))))

    failures = [item for item in checks if not item["ok"]]
    return {
        "schema": "monopoly-deal-training-readiness-v1",
        "mode": mode,
        "ready": not failures,
        "failureCount": len(failures),
        "trace": str(trace),
        "outputPrefix": str(output_prefix),
        "preferredSource": preferred_source or "any",
        "rows": len(rows),
        "byTeacherSource": source_counts,
        "byDecisionKind": kind_counts,
        "rowsWithTokenUsage": rows_with_usage,
        "modelType": model_type,
        "artifacts": {
            "manifest": str(manifest_path),
            "qualityReport": str(quality_path),
            "metrics": str(metrics_path),
            "modelJson": str(model_path),
            "gameplay": str(gameplay_path),
            "traceAudit": str(audit_path),
        },
        "thresholds": {
            "minRows": min_rows,
            "minRareKindRows": min_rare_kind_rows,
            "minValidationTop1": min_validation_top1,
            "minGameplayGames": min_gameplay_games,
            "requireNaturalGameplay": require_natural_gameplay,
        },
        "checks": checks,
    }


def check(name: str, ok: bool, detail: str) -> Dict[str, Any]:
    return {"name": name, "ok": bool(ok), "detail": detail}


def load_jsonl(path: Path) -> List[Dict[str, Any]]:
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
            if not isinstance(row, dict):
                raise SystemExit(f"{path}:{line_no}: row must be a JSON object")
            rows.append(row)
    return rows


def load_json_file(path: Path) -> Dict[str, Any]:
    if not path.exists():
        return {}
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        raise SystemExit(f"{path}: invalid JSON: {exc}") from exc
    return value if isinstance(value, dict) else {}


def counts_from_rows(rows: Sequence[Dict[str, Any]], field: str) -> Dict[str, int]:
    counts: Dict[str, int] = {}
    for row in rows:
        if field == "source":
            key = str(obj(obj(row.get("result")).get("metadata")).get("source", "unknown"))
        elif field == "kind":
            key = str(obj(row.get("request")).get("decisionKind", "unknown"))
        else:
            key = "unknown"
        counts[key] = counts.get(key, 0) + 1
    return dict(sorted(counts.items()))


def has_token_usage(row: Dict[str, Any]) -> bool:
    metadata = obj(obj(row.get("result")).get("metadata"))
    return any(key in metadata for key in ["promptTokensShare", "completionTokensShare", "totalTokensShare"])


def ratio(n: int, d: int) -> float:
    return 0.0 if d <= 0 else n / d


def number(value: Any) -> float:
    try:
        if value is None or value == "":
            return 0.0
        return float(value)
    except (TypeError, ValueError):
        return 0.0


def obj(value: Any) -> Dict[str, Any]:
    return value if isinstance(value, dict) else {}


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
