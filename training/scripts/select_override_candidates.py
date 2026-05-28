#!/usr/bin/env python3
"""Select high-signal DeepSeek disagreements for override and counterfactual work."""

from __future__ import annotations

import argparse
import collections
import hashlib
import json
import math
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Dict, Iterable, List, Sequence


DEFAULT_INCLUDE_EFFECTS = (
    "STEAL_PROPERTY",
    "FORCED_DEAL",
    "DEAL_BREAKER",
    "RENT",
    "RENT_DUAL",
    "DEBT_COLLECTOR",
    "BIRTHDAY",
    "HOUSE",
    "HOTEL",
)


@dataclass(frozen=True)
class RowInfo:
    line_no: int
    raw: str
    row: Dict[str, Any]
    decision_id: str
    session_id: str
    choice_id: str
    source_choice_id: str
    hard_choice_id: str
    chosen_effect: str
    source_effect: str
    chosen_summary: str
    source_summary: str
    lookahead_gap_vs_source: float | None
    same_as_hard: bool
    candidate_count: int
    natural_win: bool
    reward: float | None

    @property
    def transition(self) -> str:
        return f"{self.source_effect} -> {self.chosen_effect}"


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("jsonl", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument("--target-rows", type=int, default=120)
    parser.add_argument("--max-by-chosen-effect", default="")
    parser.add_argument("--include-chosen-effects", default=",".join(DEFAULT_INCLUDE_EFFECTS))
    parser.add_argument("--exclude-source-effects", default="PASS_GO")
    parser.add_argument("--min-gap-vs-source", type=float, default=-150.0)
    parser.add_argument("--max-gap-vs-source", type=float, default=None)
    parser.add_argument("--include-same-as-source", action="store_true")
    parser.add_argument("--min-rows", type=int, default=1)
    parser.add_argument("--top-examples", type=int, default=30)
    parser.add_argument("--seed", default="20260525")
    parser.add_argument("--trace-mode", default="fail_if_exists")
    args = parser.parse_args(argv)

    rows = list(load_rows(args.jsonl))
    include_effects = csv_set(args.include_chosen_effects)
    exclude_source_effects = csv_set(args.exclude_source_effects)
    quotas = parse_quotas(args.max_by_chosen_effect)
    selected, report = select_rows(
        rows,
        input_path=args.jsonl,
        output_path=args.output,
        quotas=quotas,
        include_effects=include_effects,
        exclude_source_effects=exclude_source_effects,
        min_gap_vs_source=args.min_gap_vs_source,
        max_gap_vs_source=args.max_gap_vs_source,
        include_same_as_source=args.include_same_as_source,
        target_rows=max(0, args.target_rows),
        min_rows=max(0, args.min_rows),
        top_examples=max(0, args.top_examples),
        seed=args.seed,
    )

    prepare_output(args.output, args.trace_mode)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text("".join(row.raw + "\n" for row in selected), encoding="utf-8")
    args.report.parent.mkdir(parents=True, exist_ok=True)
    text = json.dumps(report, ensure_ascii=False, indent=2) + "\n"
    args.report.write_text(text, encoding="utf-8")
    print(text, end="")
    return 0 if report["ok"] else 1


def load_rows(path: Path) -> Iterable[RowInfo]:
    with path.open("r", encoding="utf-8") as handle:
        for line_no, line in enumerate(handle, start=1):
            raw = line.strip()
            if not raw:
                continue
            try:
                row = json.loads(raw)
            except json.JSONDecodeError as exc:
                raise SystemExit(f"{path}:{line_no}: invalid JSON: {exc}") from exc
            info = row_info(row, raw, line_no)
            if info is not None:
                yield info


def row_info(row: Dict[str, Any], raw: str, line_no: int) -> RowInfo | None:
    request = obj(row.get("request"))
    result = obj(row.get("result"))
    context = obj(request.get("context"))
    source_policy = obj(context.get("sourcePolicy"))
    source_meta = obj(source_policy.get("metadata"))
    choice_id = str(result.get("choiceId", ""))
    source_choice_id = str(source_policy.get("choiceId", ""))
    if not choice_id:
        return None
    source_candidate = candidate_by_id(request, source_choice_id)
    chosen_candidate = candidate_by_id(request, choice_id)
    hard_choice_id = str(source_meta.get("hardChoiceId", ""))
    scores = obj(source_meta.get("candidateScores"))
    outcome = obj(row.get("outcome"))
    chosen_score = maybe_float(scores.get(choice_id))
    source_score = maybe_float(scores.get(source_choice_id))
    reward = maybe_float(outcome.get("reward"))
    return RowInfo(
        line_no=line_no,
        raw=raw,
        row=row,
        decision_id=str(request.get("decisionId", "")),
        session_id=str(request.get("sessionId", "")),
        choice_id=choice_id,
        source_choice_id=source_choice_id,
        hard_choice_id=hard_choice_id,
        chosen_effect=candidate_effect(chosen_candidate),
        source_effect=candidate_effect(source_candidate),
        chosen_summary=str(chosen_candidate.get("summary", "")),
        source_summary=str(source_candidate.get("summary", "")),
        lookahead_gap_vs_source=delta(chosen_score, source_score),
        same_as_hard=bool(hard_choice_id and choice_id == hard_choice_id),
        candidate_count=len(list_obj(request.get("candidates"))),
        natural_win=bool(outcome.get("naturalWin")),
        reward=reward,
    )


def select_rows(
    rows: Sequence[RowInfo],
    input_path: Path,
    output_path: Path,
    quotas: Dict[str, int],
    include_effects: set[str],
    exclude_source_effects: set[str],
    min_gap_vs_source: float | None,
    max_gap_vs_source: float | None,
    include_same_as_source: bool,
    target_rows: int,
    min_rows: int,
    top_examples: int,
    seed: str,
) -> tuple[List[RowInfo], Dict[str, Any]]:
    rejected: collections.Counter[str] = collections.Counter()
    eligible: List[RowInfo] = []
    for row in rows:
        reason = rejection_reason(
            row,
            include_effects,
            exclude_source_effects,
            min_gap_vs_source,
            max_gap_vs_source,
            include_same_as_source,
        )
        if reason is None:
            eligible.append(row)
        else:
            rejected[reason] += 1

    if quotas:
        selected: List[RowInfo] = []
        for effect, limit in quotas.items():
            effect_rows = [row for row in eligible if row.chosen_effect == effect]
            selected.extend(select_diverse(effect_rows, limit, f"{seed}:{effect}"))
    else:
        limit = target_rows if target_rows > 0 else len(eligible)
        selected = select_diverse(eligible, limit, seed)

    if target_rows > 0 and len(selected) > target_rows:
        selected = select_diverse(selected, target_rows, f"{seed}:target")
    selected = sorted(selected, key=lambda row: row.line_no)

    selected_by_effect = counter_json(row.chosen_effect for row in selected)
    quota_deficits = {
        effect: max(0, limit - selected_by_effect.get(effect, 0))
        for effect, limit in quotas.items()
    }
    checks = [
        check("min_rows", len(selected) >= min_rows, f"{len(selected)} / {min_rows}"),
    ]
    if quotas:
        checks.append(check(
            "quota_rows_available",
            all(value == 0 for value in quota_deficits.values()),
            str(quota_deficits),
            severity="warn",
        ))
    failures = [item for item in checks if item["severity"] == "error" and not item["ok"]]
    warnings = [item for item in checks if item["severity"] == "warn" and not item["ok"]]
    report = {
        "schema": "monopoly-deal-deepseek-override-candidate-selection-v1",
        "ok": not failures,
        "warningCount": len(warnings),
        "failureCount": len(failures),
        "inputPath": str(input_path),
        "outputPath": str(output_path),
        "rowsRead": len(rows),
        "rowsEligible": len(eligible),
        "rowsSelected": len(selected),
        "filters": {
            "includeChosenEffects": sorted(include_effects),
            "excludeSourceEffects": sorted(exclude_source_effects),
            "minGapVsSource": min_gap_vs_source,
            "maxGapVsSource": max_gap_vs_source,
            "includeSameAsSource": include_same_as_source,
        },
        "requestedMaxByChosenEffect": quotas,
        "quotaDeficits": quota_deficits,
        "rejectedReasons": dict(sorted(rejected.items())),
        "availableByChosenEffect": counter_json(row.chosen_effect for row in eligible),
        "selectedByChosenEffect": selected_by_effect,
        "selectedBySourceEffect": counter_json(row.source_effect for row in selected),
        "selectedByTransition": dict(collections.Counter(row.transition for row in selected).most_common(30)),
        "selectedSameAsHard": sum(1 for row in selected if row.same_as_hard),
        "selectedSessions": len({row.session_id for row in selected if row.session_id}),
        "selectedNaturalWins": sum(1 for row in selected if row.natural_win),
        "selectedGapVsSource": float_stats([
            row.lookahead_gap_vs_source
            for row in selected
            if row.lookahead_gap_vs_source is not None
        ]),
        "selectedReward": float_stats([
            row.reward for row in selected if row.reward is not None
        ]),
        "topSelectedExamples": [example_json(row) for row in selected[:top_examples]],
        "checks": checks,
    }
    return selected, report


def rejection_reason(
    row: RowInfo,
    include_effects: set[str],
    exclude_source_effects: set[str],
    min_gap_vs_source: float | None,
    max_gap_vs_source: float | None,
    include_same_as_source: bool,
) -> str | None:
    if not row.source_choice_id:
        return "missing_source_policy"
    if not include_same_as_source and row.choice_id == row.source_choice_id:
        return "same_as_source"
    if include_effects and row.chosen_effect not in include_effects:
        return "chosen_effect_excluded"
    if exclude_source_effects and row.source_effect in exclude_source_effects:
        return "source_effect_excluded"
    if row.lookahead_gap_vs_source is None:
        return "missing_gap_vs_source"
    if min_gap_vs_source is not None and row.lookahead_gap_vs_source < min_gap_vs_source:
        return "gap_below_min"
    if max_gap_vs_source is not None and row.lookahead_gap_vs_source > max_gap_vs_source:
        return "gap_above_max"
    return None


def select_diverse(rows: Sequence[RowInfo], limit: int, seed: str) -> List[RowInfo]:
    remaining = sorted(rows, key=lambda row: base_key(row, seed))
    selected: List[RowInfo] = []
    by_session: collections.Counter[str] = collections.Counter()
    by_transition: collections.Counter[str] = collections.Counter()
    while remaining and len(selected) < limit:
        best_index = min(
            range(len(remaining)),
            key=lambda index: diversity_key(
                remaining[index],
                by_session,
                by_transition,
                seed,
            ),
        )
        row = remaining.pop(best_index)
        selected.append(row)
        by_session[row.session_id] += 1
        by_transition[row.transition] += 1
    return selected


def diversity_key(
    row: RowInfo,
    by_session: collections.Counter[str],
    by_transition: collections.Counter[str],
    seed: str,
) -> tuple[int, int, float, int, int]:
    gap_penalty = 0.0
    if row.lookahead_gap_vs_source is not None:
        gap_penalty = max(0.0, -row.lookahead_gap_vs_source)
    return (
        by_transition[row.transition],
        by_session[row.session_id],
        gap_penalty,
        -row.candidate_count,
        stable_int(seed, row.decision_id or str(row.line_no)),
    )


def base_key(row: RowInfo, seed: str) -> tuple[float, int, int]:
    gap_penalty = 0.0
    if row.lookahead_gap_vs_source is not None:
        gap_penalty = max(0.0, -row.lookahead_gap_vs_source)
    return (
        gap_penalty,
        -row.candidate_count,
        stable_int(seed, row.decision_id or str(row.line_no)),
    )


def example_json(row: RowInfo) -> Dict[str, Any]:
    return {
        "decisionId": row.decision_id,
        "sessionId": row.session_id,
        "choiceId": row.choice_id,
        "sourceChoiceId": row.source_choice_id,
        "hardChoiceId": row.hard_choice_id,
        "sameAsHard": row.same_as_hard,
        "chosenEffect": row.chosen_effect,
        "sourceEffect": row.source_effect,
        "chosenSummary": row.chosen_summary,
        "sourceSummary": row.source_summary,
        "lookaheadGapVsSource": row.lookahead_gap_vs_source,
        "reward": row.reward,
        "naturalWin": row.natural_win,
    }


def candidate_by_id(request: Dict[str, Any], candidate_id: str) -> Dict[str, Any]:
    for candidate in list_obj(request.get("candidates")):
        if isinstance(candidate, dict) and str(candidate.get("id", "")) == candidate_id:
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
    return action_type or "UNKNOWN"


def prepare_output(path: Path, mode: str) -> None:
    normalized = mode.strip().lower()
    if normalized in {"overwrite", "replace", "truncate"}:
        path.unlink(missing_ok=True)
        return
    if normalized in {"fail", "fail_if_exists", "create_new", ""}:
        if path.exists():
            raise SystemExit(f"selection output already exists: {path}")
        return
    if normalized == "append":
        raise SystemExit("select_override_candidates.py does not support append mode")
    raise SystemExit(f"unsupported trace mode: {mode}")


def parse_quotas(raw: str) -> Dict[str, int]:
    out: Dict[str, int] = {}
    if not raw:
        return out
    for part in raw.split(","):
        text = part.strip()
        if not text:
            continue
        pieces = text.replace("=", ":", 1).split(":", 1)
        if len(pieces) != 2:
            continue
        try:
            value = int(pieces[1].strip())
        except ValueError:
            continue
        if value > 0:
            out[pieces[0].strip().upper()] = value
    return out


def csv_set(raw: str) -> set[str]:
    if not raw:
        return set()
    return {item.strip().upper() for item in raw.split(",") if item.strip()}


def float_stats(values: Sequence[float]) -> Dict[str, Any]:
    clean = sorted(float(v) for v in values if math.isfinite(float(v)))
    if not clean:
        return {"min": None, "p50": None, "p90": None, "max": None, "avg": None}
    return {
        "min": round(clean[0], 3),
        "p50": round(percentile(clean, 50), 3),
        "p90": round(percentile(clean, 90), 3),
        "max": round(clean[-1], 3),
        "avg": round(sum(clean) / len(clean), 3),
    }


def percentile(ordered: Sequence[float], pct: int) -> float:
    index = int(round((len(ordered) - 1) * pct / 100))
    return ordered[index]


def counter_json(values: Sequence[str] | Any) -> Dict[str, int]:
    return dict(sorted(collections.Counter(values).items()))


def check(name: str, ok: bool, detail: str, severity: str = "error") -> Dict[str, Any]:
    return {"name": name, "ok": bool(ok), "severity": severity, "detail": detail}


def stable_int(seed: str, value: str) -> int:
    digest = hashlib.sha256(f"{seed}:{value}".encode("utf-8")).hexdigest()
    return int(digest[:16], 16)


def delta(a: float | None, b: float | None) -> float | None:
    return None if a is None or b is None else a - b


def maybe_float(value: Any) -> float | None:
    try:
        if value is None:
            return None
        out = float(value)
        return out if math.isfinite(out) else None
    except (TypeError, ValueError):
        return None


def obj(value: Any) -> Dict[str, Any]:
    return value if isinstance(value, dict) else {}


def list_obj(value: Any) -> List[Any]:
    return value if isinstance(value, list) else []


if __name__ == "__main__":
    raise SystemExit(main())
