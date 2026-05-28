#!/usr/bin/env python3
"""Analyze counterfactual replay for rollout-gate disagreements."""

from __future__ import annotations

import argparse
import collections
import json
import math
import sys
from pathlib import Path
from typing import Any, Sequence


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
    missing_outcomes = 0
    forced_selected = 0
    forced_any = 0
    raw_better = 0
    immediate_better = 0
    same = 0
    gate_better_than_immediate = 0
    immediate_better_than_gate = 0
    gate_same_as_immediate = 0
    clean_raw_immediate_rows = 0
    clean_raw_better = 0
    clean_immediate_better = 0
    clean_same = 0
    all_rows: list[RowAnalysis] = []
    clean_rows: list[RowAnalysis] = []
    by_transition: collections.Counter[str] = collections.Counter()
    transition_cmp: dict[str, collections.Counter[str]] = collections.defaultdict(collections.Counter)
    selected_force_reasons: collections.Counter[str] = collections.Counter()

    for decision in decisions:
        decision_id = str(decision.get("decisionId", ""))
        row = rows.get(decision_id)
        if row is None:
            missing_rows += 1
            continue
        metadata = obj(obj(row.get("result")).get("metadata"))
        raw_id = str(metadata.get("rolloutRawBestId", ""))
        immediate_id = str(metadata.get("rolloutImmediateBestId", ""))
        gate_id = str(metadata.get("rolloutGateChoiceId", ""))
        if not raw_id or not immediate_id:
            missing_outcomes += 1
            continue
        outcomes = {str(item.get("candidateId", "")): item for item in objects(decision.get("candidates"))}
        raw = outcomes.get(raw_id)
        immediate = outcomes.get(immediate_id)
        gate = outcomes.get(gate_id)
        if raw is None or immediate is None:
            missing_outcomes += 1
            continue
        raw_effect = str(metadata.get("rolloutRawBestEffect", ""))
        immediate_effect = str(metadata.get("rolloutImmediateBestEffect", ""))
        transition = f"{immediate_effect}->{raw_effect}"
        cmp_raw_immediate = compare_outcomes(raw, immediate)
        cmp_gate_immediate = compare_outcomes(gate, immediate) if gate is not None else 0
        item = RowAnalysis(
            decision_id=decision_id,
            session_id=str(obj(row.get("request")).get("sessionId", "")),
            transition=transition,
            raw_id=raw_id,
            immediate_id=immediate_id,
            gate_id=gate_id,
            score_gap=number(metadata.get("rolloutOverrideScoreGap")) or 0.0,
            override_used=as_bool(metadata.get("rolloutOverrideUsed")),
            source_natural_win=as_bool(obj(row.get("outcome")).get("naturalWin")),
            raw_reward=number(raw.get("reward")) or 0.0,
            immediate_reward=number(immediate.get("reward")) or 0.0,
            raw_board=int(number(raw.get("boardScore")) or 0),
            immediate_board=int(number(immediate.get("boardScore")) or 0),
            raw_force=str(raw.get("forceEndReason", "")),
            immediate_force=str(immediate.get("forceEndReason", "")),
            gate_force=str(gate.get("forceEndReason", "")) if gate is not None else "",
            raw_summary=str(candidate_summary(decision, raw_id)),
            immediate_summary=str(candidate_summary(decision, immediate_id)),
        )
        all_rows.append(item)
        by_transition[transition] += 1
        if selected_candidates_forced(item):
            forced_selected += 1
            for reason in [item.raw_force, item.immediate_force, item.gate_force]:
                if reason:
                    selected_force_reasons[reason] += 1
        else:
            clean_raw_immediate_rows += 1
            clean_rows.append(item)
            if cmp_raw_immediate > 0:
                clean_raw_better += 1
            elif cmp_raw_immediate < 0:
                clean_immediate_better += 1
            else:
                clean_same += 1
        if any(str(candidate.get("forceEndReason", "")).strip() for candidate in objects(decision.get("candidates"))):
            forced_any += 1
        if cmp_raw_immediate > 0:
            raw_better += 1
            transition_cmp[transition]["raw_better"] += 1
        elif cmp_raw_immediate < 0:
            immediate_better += 1
            transition_cmp[transition]["immediate_better"] += 1
        else:
            same += 1
            transition_cmp[transition]["same"] += 1
        if cmp_gate_immediate > 0:
            gate_better_than_immediate += 1
        elif cmp_gate_immediate < 0:
            immediate_better_than_gate += 1
        else:
            gate_same_as_immediate += 1

    checks = [
        check("decisions_nonzero", len(decisions) > 0, f"decisions={len(decisions)}"),
        check("trace_rows_found", missing_rows == 0, f"missing={missing_rows}"),
        check("raw_immediate_outcomes_found", missing_outcomes == 0, f"missing={missing_outcomes}"),
        check(
            "clean_selected_rows_nonzero",
            clean_raw_immediate_rows > 0,
            f"clean={clean_raw_immediate_rows}",
            severity="warning",
        ),
    ]
    failures = [item for item in checks if item["severity"] == "error" and not item["ok"]]
    return {
        "schema": "monopoly-deal-rollout-gate-replay-analysis-v1",
        "ok": not failures,
        "failureCount": len(failures),
        "tracePath": str(trace_path),
        "counterfactualReportPath": str(replay_path),
        "decisions": len(decisions),
        "missingRows": missing_rows,
        "missingOutcomes": missing_outcomes,
        "forcedAnyDecisionRows": forced_any,
        "forcedSelectedRows": forced_selected,
        "cleanRawImmediateRows": clean_raw_immediate_rows,
        "cleanRawBetterThanImmediate": clean_raw_better,
        "cleanImmediateBetterThanRaw": clean_immediate_better,
        "cleanRawSameAsImmediate": clean_same,
        "rawBetterThanImmediate": raw_better,
        "immediateBetterThanRaw": immediate_better,
        "rawSameAsImmediate": same,
        "gateBetterThanImmediate": gate_better_than_immediate,
        "immediateBetterThanGate": immediate_better_than_gate,
        "gateSameAsImmediate": gate_same_as_immediate,
        "selectedForceReasons": dict(selected_force_reasons.most_common()),
        "byTransition": dict(by_transition.most_common()),
        "transitionComparison": {
            key: dict(value)
            for key, value in sorted(
                transition_cmp.items(),
                key=lambda item: (-sum(item[1].values()), item[0]),
            )
        },
        "scoreGap": stats(item.score_gap for item in all_rows),
        "cleanScoreGap": stats(item.score_gap for item in clean_rows),
        "topRawBetterExamples": [
            item.to_json()
            for item in sorted(
                (item for item in all_rows if compare_tuple(item.raw_reward, item.raw_board,
                                                            item.immediate_reward, item.immediate_board) > 0),
                key=lambda item: (-item.raw_reward, -item.raw_board, item.decision_id),
            )[:15]
        ],
        "topImmediateBetterExamples": [
            item.to_json()
            for item in sorted(
                (item for item in all_rows if compare_tuple(item.raw_reward, item.raw_board,
                                                            item.immediate_reward, item.immediate_board) < 0),
                key=lambda item: (-item.immediate_reward, -item.immediate_board, item.decision_id),
            )[:15]
        ],
        "checks": checks,
    }


def selected_candidates_forced(item: "RowAnalysis") -> bool:
    return bool(item.raw_force or item.immediate_force or item.gate_force)


def compare_outcomes(left: dict[str, Any] | None, right: dict[str, Any] | None) -> int:
    if left is None or right is None:
        return 0
    return compare_tuple(
        number(left.get("reward")) or 0.0,
        int(number(left.get("boardScore")) or 0),
        number(right.get("reward")) or 0.0,
        int(number(right.get("boardScore")) or 0),
    )


def compare_tuple(left_reward: float, left_board: int, right_reward: float, right_board: int) -> int:
    if left_reward > right_reward:
        return 1
    if left_reward < right_reward:
        return -1
    if left_board > right_board:
        return 1
    if left_board < right_board:
        return -1
    return 0


def candidate_summary(decision: dict[str, Any], candidate_id: str) -> str:
    for candidate in objects(decision.get("candidates")):
        if str(candidate.get("candidateId", "")) == candidate_id:
            return str(candidate.get("summary", ""))
    return ""


def check(name: str, ok: bool, detail: str, severity: str = "error") -> dict[str, Any]:
    return {"name": name, "ok": bool(ok), "severity": severity, "detail": detail}


def stats(values: Any) -> dict[str, Any]:
    clean = sorted(value for value in values if isinstance(value, (int, float)) and math.isfinite(value))
    if not clean:
        return {"min": None, "p50": None, "p90": None, "max": None, "avg": None}
    return {
        "min": clean[0],
        "p50": clean[int(round((len(clean) - 1) * 0.5))],
        "p90": clean[int(round((len(clean) - 1) * 0.9))],
        "max": clean[-1],
        "avg": sum(clean) / len(clean),
    }


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


class RowAnalysis:
    def __init__(
        self,
        *,
        decision_id: str,
        session_id: str,
        transition: str,
        raw_id: str,
        immediate_id: str,
        gate_id: str,
        score_gap: float,
        override_used: bool,
        source_natural_win: bool,
        raw_reward: float,
        immediate_reward: float,
        raw_board: int,
        immediate_board: int,
        raw_force: str,
        immediate_force: str,
        gate_force: str,
        raw_summary: str,
        immediate_summary: str,
    ) -> None:
        self.decision_id = decision_id
        self.session_id = session_id
        self.transition = transition
        self.raw_id = raw_id
        self.immediate_id = immediate_id
        self.gate_id = gate_id
        self.score_gap = score_gap
        self.override_used = override_used
        self.source_natural_win = source_natural_win
        self.raw_reward = raw_reward
        self.immediate_reward = immediate_reward
        self.raw_board = raw_board
        self.immediate_board = immediate_board
        self.raw_force = raw_force
        self.immediate_force = immediate_force
        self.gate_force = gate_force
        self.raw_summary = raw_summary
        self.immediate_summary = immediate_summary

    def to_json(self) -> dict[str, Any]:
        return {
            "decisionId": self.decision_id,
            "sessionId": self.session_id,
            "transition": self.transition,
            "rolloutRawBestId": self.raw_id,
            "rolloutImmediateBestId": self.immediate_id,
            "rolloutGateChoiceId": self.gate_id,
            "rolloutOverrideScoreGap": self.score_gap,
            "rolloutOverrideUsed": self.override_used,
            "sourceNaturalWin": self.source_natural_win,
            "rawReward": self.raw_reward,
            "immediateReward": self.immediate_reward,
            "rawBoardScore": self.raw_board,
            "immediateBoardScore": self.immediate_board,
            "rawForceEndReason": self.raw_force,
            "immediateForceEndReason": self.immediate_force,
            "gateForceEndReason": self.gate_force,
            "rawSummary": self.raw_summary,
            "immediateSummary": self.immediate_summary,
        }


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
