#!/usr/bin/env python3
"""Select rollout-gate disagreement rows for counterfactual replay.

The input is a normal outcome decision trace from SearchLookaheadAiPlayStrategy
with mementos enabled. The output keeps the original JSONL rows, limited to
states where the rollout raw best and one-ply immediate best disagree.
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


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("trace", nargs="+", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument("--target-rows", type=int, default=0)
    parser.add_argument("--max-per-session", type=int, default=4)
    parser.add_argument("--min-score-gap", type=float, default=0.0)
    parser.add_argument("--only-overrides", action="store_true")
    parser.add_argument("--only-losses", action="store_true")
    parser.add_argument("--trace-mode", default="fail_if_exists")
    args = parser.parse_args(argv)

    rows = list(load_rows(args.trace))
    selected, report = select_rows(
        rows=rows,
        trace_paths=args.trace,
        output_path=args.output,
        target_rows=max(0, args.target_rows),
        max_per_session=max(0, args.max_per_session),
        min_score_gap=args.min_score_gap,
        only_overrides=args.only_overrides,
        only_losses=args.only_losses,
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
    *,
    rows: list[dict[str, Any]],
    trace_paths: Sequence[Path],
    output_path: Path,
    target_rows: int,
    max_per_session: int,
    min_score_gap: float,
    only_overrides: bool,
    only_losses: bool,
) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    skipped: collections.Counter[str] = collections.Counter()
    eligible: list[Selection] = []
    rows_with_memento = 0
    rows_with_rollout_metadata = 0
    override_rows = 0
    losing_rows = 0
    for index, row in enumerate(rows):
        if has_memento(row):
            rows_with_memento += 1
        metadata = obj(obj(row.get("result")).get("metadata"))
        if "rolloutRawBestId" in metadata:
            rows_with_rollout_metadata += 1
        if as_bool(metadata.get("rolloutOverrideUsed")):
            override_rows += 1
        if not as_bool(obj(row.get("outcome")).get("naturalWin")):
            losing_rows += 1
        selection, reason = inspect_row(
            row=row,
            row_index=index,
            min_score_gap=min_score_gap,
            only_overrides=only_overrides,
            only_losses=only_losses,
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

    checks = [
        check("input_rows_nonzero", len(rows) > 0, f"rows={len(rows)}"),
        check("rollout_metadata_present", rows_with_rollout_metadata > 0, f"rows={rows_with_rollout_metadata}"),
        check("selected_rows_nonzero", len(selected_info) > 0, f"selected={len(selected_info)}"),
        check(
            "selected_rows_have_memento",
            all(has_memento(rows[item.row_index]) for item in selected_info),
            f"selected={len(selected_info)}",
        ),
    ]
    failures = [item for item in checks if item["severity"] == "error" and not item["ok"]]
    report = {
        "schema": "monopoly-deal-rollout-gate-replay-row-selection-v1",
        "ok": not failures,
        "failureCount": len(failures),
        "tracePaths": [str(path) for path in trace_paths],
        "outputPath": str(output_path),
        "rowsRead": len(rows),
        "rowsWithMemento": rows_with_memento,
        "rowsWithRolloutMetadata": rows_with_rollout_metadata,
        "rolloutOverrideRows": override_rows,
        "losingRows": losing_rows,
        "eligibleRows": len(eligible),
        "rowsSelected": len(selected_info),
        "selectedSessions": len(by_session),
        "filters": {
            "targetRows": target_rows,
            "maxPerSession": max_per_session,
            "minScoreGap": min_score_gap,
            "onlyOverrides": only_overrides,
            "onlyLosses": only_losses,
        },
        "skippedReasons": dict(sorted(skipped.items())),
        "selectedByRawEffect": dict(collections.Counter(item.raw_effect for item in selected_info).most_common()),
        "selectedByImmediateEffect": dict(
            collections.Counter(item.immediate_effect for item in selected_info).most_common()
        ),
        "selectedByTransition": dict(
            collections.Counter(
                f"{item.immediate_effect}->{item.raw_effect}" for item in selected_info
            ).most_common(30)
        ),
        "scoreGap": stats(item.score_gap for item in selected_info),
        "topExamples": [item.to_json() for item in selected_info[:30]],
        "checks": checks,
    }
    return selected_rows, report


def inspect_row(
    *,
    row: dict[str, Any],
    row_index: int,
    min_score_gap: float,
    only_overrides: bool,
    only_losses: bool,
) -> tuple["Selection | None", str]:
    request = obj(row.get("request"))
    result = obj(row.get("result"))
    metadata = obj(result.get("metadata"))
    outcome = obj(row.get("outcome"))
    candidates = objects(request.get("candidates"))
    if not has_memento(row):
        return None, "missing_memento"
    if only_losses and as_bool(outcome.get("naturalWin")):
        return None, "winning_row"
    raw_id = str(metadata.get("rolloutRawBestId", ""))
    immediate_id = str(metadata.get("rolloutImmediateBestId", ""))
    gate_id = str(metadata.get("rolloutGateChoiceId", ""))
    if not raw_id or not immediate_id:
        return None, "missing_rollout_ids"
    if raw_id == immediate_id:
        return None, "same_candidate"
    if only_overrides and not as_bool(metadata.get("rolloutOverrideUsed")):
        return None, "not_override_used"
    score_gap = number(metadata.get("rolloutOverrideScoreGap"))
    if score_gap is None:
        return None, "missing_score_gap"
    if abs(score_gap) < min_score_gap:
        return None, "low_score_gap"
    by_id = {str(candidate.get("id", "")): candidate for candidate in candidates}
    if raw_id not in by_id or immediate_id not in by_id:
        return None, "candidate_missing"
    raw = by_id[raw_id]
    immediate = by_id[immediate_id]
    return Selection(
        row_index=row_index,
        decision_id=str(request.get("decisionId", "")),
        session_id=str(request.get("sessionId", "")),
        state_sequence=int(number(request.get("stateSequence")) or 0),
        raw_id=raw_id,
        immediate_id=immediate_id,
        gate_id=gate_id,
        raw_effect=str(metadata.get("rolloutRawBestEffect", "")) or candidate_effect(raw),
        immediate_effect=str(metadata.get("rolloutImmediateBestEffect", "")) or candidate_effect(immediate),
        gate_effect=str(metadata.get("rolloutGateChoiceEffect", "")),
        score_gap=score_gap,
        override_used=as_bool(metadata.get("rolloutOverrideUsed")),
        natural_win=as_bool(outcome.get("naturalWin")),
        candidate_count=len(candidates),
        raw_summary=str(raw.get("summary", "")),
        immediate_summary=str(immediate.get("summary", "")),
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
    summary = str(candidate.get("summary", ""))
    if summary.startswith("Action "):
        token = summary[len("Action "):].split(" ", 1)[0].strip(".:").upper()
        if token:
            return token
    if summary.startswith("Deploy"):
        return "DEPLOY"
    if summary.startswith("Deposit action card "):
        token = summary[len("Deposit action card "):].split(" ", 1)[0].strip(".:").upper()
        return token or "DEPOSIT"
    if summary.startswith("Deposit"):
        return "DEPOSIT"
    if summary.startswith("Discard"):
        return "DISCARD"
    return str(payload.get("actionType", "")).upper() or "UNKNOWN"


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
        raw_id: str,
        immediate_id: str,
        gate_id: str,
        raw_effect: str,
        immediate_effect: str,
        gate_effect: str,
        score_gap: float,
        override_used: bool,
        natural_win: bool,
        candidate_count: int,
        raw_summary: str,
        immediate_summary: str,
    ) -> None:
        self.row_index = row_index
        self.decision_id = decision_id
        self.session_id = session_id
        self.state_sequence = state_sequence
        self.raw_id = raw_id
        self.immediate_id = immediate_id
        self.gate_id = gate_id
        self.raw_effect = raw_effect
        self.immediate_effect = immediate_effect
        self.gate_effect = gate_effect
        self.score_gap = score_gap
        self.override_used = override_used
        self.natural_win = natural_win
        self.candidate_count = candidate_count
        self.raw_summary = raw_summary
        self.immediate_summary = immediate_summary

    def ranking_key(self) -> tuple[int, int, float, int, str]:
        loss_rank = 0 if not self.natural_win else 1
        override_rank = 0 if self.override_used else 1
        return (loss_rank, override_rank, -abs(self.score_gap), self.state_sequence, self.decision_id)

    def to_json(self) -> dict[str, Any]:
        return {
            "decisionId": self.decision_id,
            "sessionId": self.session_id,
            "stateSequence": self.state_sequence,
            "rolloutRawBestId": self.raw_id,
            "rolloutImmediateBestId": self.immediate_id,
            "rolloutGateChoiceId": self.gate_id,
            "rolloutRawBestEffect": self.raw_effect,
            "rolloutImmediateBestEffect": self.immediate_effect,
            "rolloutGateChoiceEffect": self.gate_effect,
            "rolloutOverrideScoreGap": self.score_gap,
            "rolloutOverrideUsed": self.override_used,
            "naturalWin": self.natural_win,
            "candidateCount": self.candidate_count,
            "rawSummary": self.raw_summary,
            "immediateSummary": self.immediate_summary,
        }


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
