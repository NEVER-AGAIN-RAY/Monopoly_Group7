#!/usr/bin/env python3
"""Build supervised decision rows from counterfactual replay reports.

The counterfactual report tells us which legal candidate led to the best
roll-forward outcome from the same stored game state. This script copies the
original decision row, replaces result.choiceId with the counterfactual winner,
and writes a normal monopoly-deal-decision-v1 JSONL suitable for distillation.
"""

from __future__ import annotations

import argparse
import collections
import json
import math
import sys
from copy import deepcopy
from pathlib import Path
from typing import Any, Dict, Iterable, List, Sequence, Tuple


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--trace", type=Path, required=True)
    parser.add_argument("--counterfactual-report", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument("--target-rows", type=int, default=0)
    parser.add_argument("--min-candidates", type=int, default=2)
    parser.add_argument("--min-reward-gap", type=float, default=0.05)
    parser.add_argument("--min-board-gap", type=float, default=100.0)
    parser.add_argument("--require-reward-gap", action="store_true")
    parser.add_argument("--require-best-beats-hard", action="store_true")
    parser.add_argument("--require-source-disagreement", action="store_true")
    parser.add_argument("--exclude-best-effects", default="")
    parser.add_argument("--exclude-original-effects", default="")
    parser.add_argument("--allow-candidate-errors", action="store_true")
    parser.add_argument("--allow-forced-candidates", action="store_true")
    parser.add_argument("--allow-missing-force-end-reason", action="store_true")
    parser.add_argument("--allow-incomplete-candidates", action="store_true")
    parser.add_argument("--trace-mode", default="fail_if_exists")
    args = parser.parse_args(argv)

    source_rows = load_trace_rows(args.trace)
    replay = json.loads(args.counterfactual_report.read_text(encoding="utf-8"))
    selected, report = select_rows(
        source_rows=source_rows,
        replay=objects(replay.get("decisions")),
        trace_path=args.trace,
        replay_path=args.counterfactual_report,
        output_path=args.output,
        target_rows=max(0, args.target_rows),
        min_candidates=max(1, args.min_candidates),
        min_reward_gap=args.min_reward_gap,
        min_board_gap=args.min_board_gap,
        require_reward_gap=args.require_reward_gap,
        require_best_beats_hard=args.require_best_beats_hard,
        require_source_disagreement=args.require_source_disagreement,
        exclude_best_effects=csv_set(args.exclude_best_effects),
        exclude_original_effects=csv_set(args.exclude_original_effects),
        allow_candidate_errors=args.allow_candidate_errors,
        allow_forced_candidates=args.allow_forced_candidates,
        allow_missing_force_end_reason=args.allow_missing_force_end_reason,
        allow_incomplete_candidates=args.allow_incomplete_candidates,
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


def load_trace_rows(path: Path) -> Dict[str, Dict[str, Any]]:
    rows: Dict[str, Dict[str, Any]] = {}
    duplicates: collections.Counter[str] = collections.Counter()
    with path.open("r", encoding="utf-8") as handle:
        for line_no, line in enumerate(handle, start=1):
            text = line.strip()
            if not text:
                continue
            try:
                row = json.loads(text)
            except json.JSONDecodeError as exc:
                raise SystemExit(f"{path}:{line_no}: invalid JSON: {exc}") from exc
            decision_id = str(obj(obj(row.get("request")).get("decisionId")) or obj(row.get("request")).get("decisionId", ""))
            if not decision_id:
                raise SystemExit(f"{path}:{line_no}: missing request.decisionId")
            if decision_id in rows:
                duplicates[decision_id] += 1
                continue
            rows[decision_id] = row
    if duplicates:
        raise SystemExit(f"{path}: duplicate decision ids: {dict(duplicates.most_common(5))}")
    return rows


def select_rows(
    source_rows: Dict[str, Dict[str, Any]],
    replay: Sequence[Dict[str, Any]],
    trace_path: Path,
    replay_path: Path,
    output_path: Path,
    target_rows: int,
    min_candidates: int,
    min_reward_gap: float,
    min_board_gap: float,
    require_reward_gap: bool,
    require_best_beats_hard: bool,
    require_source_disagreement: bool,
    exclude_best_effects: set[str],
    exclude_original_effects: set[str],
    allow_candidate_errors: bool,
    allow_forced_candidates: bool,
    allow_missing_force_end_reason: bool,
    allow_incomplete_candidates: bool,
) -> Tuple[List[Dict[str, Any]], Dict[str, Any]]:
    rejected: collections.Counter[str] = collections.Counter()
    selected_infos: List[SelectionInfo] = []
    missing_source_rows = 0
    replay_candidate_errors = 0
    replay_forced_candidates = 0
    replay_missing_force_end_reason = 0
    replay_incomplete_candidates = 0
    for decision in replay:
        decision_id = str(decision.get("decisionId", ""))
        source_row = source_rows.get(decision_id)
        if source_row is None:
            missing_source_rows += 1
            rejected["missing_source_row"] += 1
            continue
        for candidate in objects(decision.get("candidates")):
            if str(candidate.get("error", "")):
                replay_candidate_errors += 1
            if "forceEndReason" not in candidate:
                replay_missing_force_end_reason += 1
            if str(candidate.get("forceEndReason", "")).strip():
                replay_forced_candidates += 1
            if not as_bool(candidate.get("gameOver")):
                replay_incomplete_candidates += 1
        outcome, reason = select_decision(
            decision,
            min_candidates=min_candidates,
            min_reward_gap=min_reward_gap,
            min_board_gap=min_board_gap,
            require_reward_gap=require_reward_gap,
            require_best_beats_hard=require_best_beats_hard,
            require_source_disagreement=require_source_disagreement,
            exclude_best_effects=exclude_best_effects,
            exclude_original_effects=exclude_original_effects,
            allow_candidate_errors=allow_candidate_errors,
            allow_forced_candidates=allow_forced_candidates,
            allow_missing_force_end_reason=allow_missing_force_end_reason,
            allow_incomplete_candidates=allow_incomplete_candidates,
        )
        if outcome is None:
            rejected[reason] += 1
            continue
        selected_infos.append(outcome)

    selected_infos.sort(key=lambda item: ranking_key(item))
    if target_rows > 0:
        selected_infos = selected_infos[:target_rows]

    rows = [build_output_row(source_rows[item.decision_id], item, replay_path) for item in selected_infos]
    by_choice_effect = collections.Counter(item.best_effect for item in selected_infos)
    by_original_effect = collections.Counter(item.original_effect for item in selected_infos)
    by_transition = collections.Counter(f"{item.original_effect}->{item.best_effect}" for item in selected_infos)
    checks = [
        check("source_rows_found", missing_source_rows == 0, f"missing={missing_source_rows}"),
        check("selected_rows_nonzero", len(selected_infos) > 0, f"selected={len(selected_infos)}"),
    ]
    failures = [item for item in checks if item["severity"] == "error" and not item["ok"]]
    report = {
        "schema": "monopoly-deal-counterfactual-training-selection-v1",
        "ok": not failures,
        "failureCount": len(failures),
        "inputTracePath": str(trace_path),
        "counterfactualReportPath": str(replay_path),
        "outputPath": str(output_path),
        "sourceRows": len(source_rows),
        "counterfactualRows": len(replay),
        "rowsSelected": len(selected_infos),
        "candidateQuality": {
            "candidateErrors": replay_candidate_errors,
            "forcedCandidates": replay_forced_candidates,
            "missingForceEndReason": replay_missing_force_end_reason,
            "incompleteCandidates": replay_incomplete_candidates,
        },
        "filters": {
            "targetRows": target_rows,
            "minCandidates": min_candidates,
            "minRewardGap": min_reward_gap,
            "minBoardGap": min_board_gap,
            "requireRewardGap": require_reward_gap,
            "requireBestBeatsHard": require_best_beats_hard,
            "requireSourceDisagreement": require_source_disagreement,
            "excludeBestEffects": sorted(exclude_best_effects),
            "excludeOriginalEffects": sorted(exclude_original_effects),
            "allowCandidateErrors": allow_candidate_errors,
            "allowForcedCandidates": allow_forced_candidates,
            "allowMissingForceEndReason": allow_missing_force_end_reason,
            "allowIncompleteCandidates": allow_incomplete_candidates,
        },
        "rejectedReasons": dict(sorted(rejected.items())),
        "selectedBestBeatsHard": sum(1 for item in selected_infos if item.best_beats_hard),
        "selectedBestDiffersFromSource": sum(1 for item in selected_infos if item.best_id != item.source_choice_id),
        "selectedBestDiffersFromHard": sum(1 for item in selected_infos if item.best_id != item.hard_choice_id),
        "selectedNaturalWins": sum(1 for item in selected_infos if item.best_natural_win),
        "selectedRewardGap": stats(item.reward_gap for item in selected_infos),
        "selectedBoardGap": stats(item.board_gap for item in selected_infos),
        "selectedByBestEffect": dict(by_choice_effect.most_common()),
        "selectedByOriginalEffect": dict(by_original_effect.most_common()),
        "selectedByTransition": dict(by_transition.most_common(30)),
        "topExamples": [item.to_json() for item in selected_infos[:30]],
        "checks": checks,
    }
    return rows, report


def select_decision(
    decision: Dict[str, Any],
    min_candidates: int,
    min_reward_gap: float,
    min_board_gap: float,
    require_reward_gap: bool,
    require_best_beats_hard: bool,
    require_source_disagreement: bool,
    exclude_best_effects: set[str],
    exclude_original_effects: set[str],
    allow_candidate_errors: bool,
    allow_forced_candidates: bool,
    allow_missing_force_end_reason: bool,
    allow_incomplete_candidates: bool,
) -> Tuple[SelectionInfo | None, str]:
    candidates = objects(decision.get("candidates"))
    if len(candidates) < min_candidates:
        return None, "too_few_candidates"
    if not allow_candidate_errors and any(str(candidate.get("error", "")) for candidate in candidates):
        return None, "candidate_error"
    if not allow_missing_force_end_reason and any("forceEndReason" not in candidate for candidate in candidates):
        return None, "candidate_missing_force_end_reason"
    if not allow_forced_candidates and any(str(candidate.get("forceEndReason", "")).strip() for candidate in candidates):
        return None, "candidate_forced"
    if not allow_incomplete_candidates and any(not as_bool(candidate.get("gameOver")) for candidate in candidates):
        return None, "candidate_incomplete"

    ordered = sorted(candidates, key=outcome_key, reverse=True)
    best = ordered[0]
    best_key = outcome_key(best)
    tied = [candidate for candidate in ordered if outcome_key(candidate) == best_key]
    if len(tied) != 1:
        return None, "best_tied"
    second = ordered[1] if len(ordered) > 1 else {}
    reward_gap = number(best.get("reward")) - number(second.get("reward"))
    board_gap = number(best.get("boardScore")) - number(second.get("boardScore"))
    if require_reward_gap and reward_gap < min_reward_gap:
        return None, "reward_gap_too_small"
    if reward_gap < min_reward_gap and board_gap < min_board_gap:
        return None, "gap_too_small"

    best_id = str(best.get("candidateId", ""))
    source_choice_id = str(decision.get("sourceChoiceId", ""))
    hard_choice_id = str(decision.get("hardChoiceId", ""))
    if require_source_disagreement and best_id == source_choice_id:
        return None, "same_as_source"
    hard = candidate_by_id(candidates, hard_choice_id)
    best_beats_hard = bool(hard) and outcome_key(best) > outcome_key(hard)
    if require_best_beats_hard and not best_beats_hard:
        return None, "best_does_not_beat_hard"
    best_effect = candidate_effect(best)
    original_effect = candidate_effect(candidate_by_id(candidates, source_choice_id))
    if best_effect in exclude_best_effects:
        return None, "best_effect_excluded"
    if original_effect in exclude_original_effects:
        return None, "original_effect_excluded"

    return SelectionInfo(
        decision_id=str(decision.get("decisionId", "")),
        best_id=best_id,
        source_choice_id=source_choice_id,
        hard_choice_id=hard_choice_id,
        reward=number(best.get("reward")),
        board_score=number(best.get("boardScore")),
        reward_gap=reward_gap,
        board_gap=board_gap,
        best_beats_hard=best_beats_hard,
        best_natural_win=as_bool(best.get("naturalWin")),
        best_effect=best_effect,
        original_effect=original_effect,
        summary=str(best.get("summary", "")),
        candidate_count=len(candidates),
    ), ""


def build_output_row(
    source_row: Dict[str, Any],
    selected: SelectionInfo,
    replay_path: Path,
) -> Dict[str, Any]:
    row = deepcopy(source_row)
    request = obj(row.get("request"))
    result = obj(row.get("result"))
    context = obj(request.get("context"))
    result_metadata = deepcopy(obj(result.get("metadata")))

    if "sourcePolicy" not in context:
        source_policy = {
            "choiceId": result.get("choiceId", ""),
            "metadata": deepcopy(result_metadata),
        }
        context["sourcePolicy"] = source_policy

    result["choiceId"] = selected.best_id
    metadata = {
        "source": "counterfactual_replay",
        "counterfactualReportPath": str(replay_path),
        "counterfactualDecisionId": selected.decision_id,
        "sourceChoiceId": selected.source_choice_id,
        "hardChoiceId": selected.hard_choice_id,
        "bestBeatsHard": selected.best_beats_hard,
        "reward": selected.reward,
        "boardScore": selected.board_score,
        "rewardGapVsSecond": selected.reward_gap,
        "boardGapVsSecond": selected.board_gap,
        "candidateCount": selected.candidate_count,
        "labelRule": "unique_best_counterfactual_outcome",
        "sourcePolicy": result_metadata,
    }
    result["metadata"] = metadata
    row["outcome"] = {
        "schema": "monopoly-deal-outcome-v1",
        "actorPlayerId": request.get("actorPlayerId", ""),
        "gameOver": True,
        "naturalWin": selected.best_natural_win,
        "reward": selected.reward,
        "boardScore": selected.board_score,
    }
    return row


def ranking_key(item: "SelectionInfo") -> Tuple[int, float, float, str]:
    return (
        0 if item.best_beats_hard else 1,
        -item.reward_gap,
        -item.board_gap,
        item.decision_id,
    )


def outcome_key(candidate: Dict[str, Any]) -> Tuple[float, float]:
    return number(candidate.get("reward")), number(candidate.get("boardScore"))


def candidate_by_id(candidates: Sequence[Dict[str, Any]], candidate_id: str) -> Dict[str, Any]:
    for candidate in candidates:
        if str(candidate.get("candidateId", "")) == candidate_id:
            return candidate
    return {}


def candidate_effect(candidate: Dict[str, Any]) -> str:
    payload = obj(candidate.get("payload"))
    effect = str(payload.get("effectCode", "")).upper()
    if effect:
        return effect
    action_type = str(payload.get("actionType", "")).upper()
    summary = str(candidate.get("summary", ""))
    if summary.startswith("Action "):
        token = summary[len("Action "):].split(" ", 1)[0].strip(".:").upper()
        if token:
            return token
    if summary.startswith("Deploy"):
        return "DEPLOY"
    if summary.startswith("Deposit"):
        return "DEPOSIT"
    if summary.startswith("Pay "):
        return "PAYMENT"
    if summary.startswith("Discard "):
        return "OVERFLOW_DISCARD"
    if summary.startswith("Play Just Say No"):
        return "JUST_SAY_NO"
    if summary.startswith("Pass Just Say No"):
        return "JUST_SAY_NO"
    return action_type or "UNKNOWN"


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


def check(name: str, ok: bool, detail: str, severity: str = "error") -> Dict[str, Any]:
    return {"name": name, "ok": bool(ok), "severity": severity, "detail": detail}


def stats(values: Iterable[float]) -> Dict[str, Any]:
    clean = sorted(float(value) for value in values if math.isfinite(float(value)))
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


def objects(value: Any) -> List[Dict[str, Any]]:
    return [item for item in value if isinstance(item, dict)] if isinstance(value, list) else []


def obj(value: Any) -> Dict[str, Any]:
    return value if isinstance(value, dict) else {}


def number(value: Any) -> float:
    try:
        return float(value)
    except (TypeError, ValueError):
        return 0.0


def as_bool(value: Any) -> bool:
    if isinstance(value, bool):
        return value
    if isinstance(value, str):
        return value.strip().lower() in {"1", "true", "yes"}
    return bool(value)


def csv_set(raw: str) -> set[str]:
    if not raw:
        return set()
    return {item.strip().upper() for item in raw.split(",") if item.strip()}


class SelectionInfo:
    def __init__(
        self,
        *,
        decision_id: str,
        best_id: str,
        source_choice_id: str,
        hard_choice_id: str,
        reward: float,
        board_score: float,
        reward_gap: float,
        board_gap: float,
        best_beats_hard: bool,
        best_natural_win: bool,
        best_effect: str,
        original_effect: str,
        summary: str,
        candidate_count: int,
    ) -> None:
        self.decision_id = decision_id
        self.best_id = best_id
        self.source_choice_id = source_choice_id
        self.hard_choice_id = hard_choice_id
        self.reward = reward
        self.board_score = board_score
        self.reward_gap = reward_gap
        self.board_gap = board_gap
        self.best_beats_hard = best_beats_hard
        self.best_natural_win = best_natural_win
        self.best_effect = best_effect
        self.original_effect = original_effect
        self.summary = summary
        self.candidate_count = candidate_count

    def to_json(self) -> Dict[str, Any]:
        return {
            "decisionId": self.decision_id,
            "bestId": self.best_id,
            "sourceChoiceId": self.source_choice_id,
            "hardChoiceId": self.hard_choice_id,
            "bestBeatsHard": self.best_beats_hard,
            "reward": self.reward,
            "boardScore": self.board_score,
            "rewardGapVsSecond": self.reward_gap,
            "boardGapVsSecond": self.board_gap,
            "bestEffect": self.best_effect,
            "originalEffect": self.original_effect,
            "summary": self.summary,
            "candidateCount": self.candidate_count,
        }


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
