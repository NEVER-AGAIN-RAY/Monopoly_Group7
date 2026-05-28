#!/usr/bin/env python3
"""Summarize lookahead auxiliary-decision traces.

This is a lightweight triage step before expensive counterfactual replay. It
counts Just Say No, payment, and overflow decisions, then uses the embedded
memento to score payment and overflow candidates with local board features.
"""

from __future__ import annotations

import argparse
import collections
import json
from pathlib import Path
from typing import Any, Iterable, Sequence


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

ACTION_BANK_VALUES = {
    "RENT": 1,
    "RENT_DUAL": 1,
    "DOUBLE_RENT": 1,
    "PASS_GO": 1,
    "BIRTHDAY": 2,
    "STEAL_PROPERTY": 3,
    "FORCED_DEAL": 3,
    "DEBT_COLLECTOR": 3,
    "HOUSE": 3,
    "HOTEL": 4,
    "RENT_WAIVER": 4,
    "DEAL_BREAKER": 5,
}

PAYMENT_COMPLETE_SET_BREAK_PENALTY = 4.0
PAYMENT_NEAR_SET_BREAK_PENALTY = 1.5


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("trace", nargs="+", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--top", type=int, default=30)
    args = parser.parse_args(argv)

    rows = list(load_rows(args.trace))
    report = analyze(rows, args.trace, max(0, args.top))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    text = json.dumps(report, ensure_ascii=False, indent=2) + "\n"
    args.output.write_text(text, encoding="utf-8")
    print(text, end="")
    return 0


def load_rows(paths: Iterable[Path]) -> Iterable[dict[str, Any]]:
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
                row["_tracePath"] = str(path)
                row["_lineNo"] = line_no
                yield row


def analyze(rows: list[dict[str, Any]], paths: Sequence[Path], top: int) -> dict[str, Any]:
    by_kind: collections.Counter[str] = collections.Counter()
    with_memento: collections.Counter[str] = collections.Counter()
    natural_wins: collections.Counter[str] = collections.Counter()
    losses: collections.Counter[str] = collections.Counter()
    outcome_missing: collections.Counter[str] = collections.Counter()
    candidate_counts: dict[str, list[int]] = collections.defaultdict(list)
    risky_payments: list[dict[str, Any]] = []
    risky_overflows: list[dict[str, Any]] = []
    jsn_rows: list[dict[str, Any]] = []
    payment_comparison: collections.Counter[str] = collections.Counter()
    overflow_comparison: collections.Counter[str] = collections.Counter()
    jsn_counts: collections.Counter[str] = collections.Counter()

    for row in rows:
        req = obj(row.get("request"))
        kind = str(req.get("decisionKind", ""))
        if not kind:
            continue
        by_kind[kind] += 1
        candidates = objs(req.get("candidates"))
        candidate_counts[kind].append(len(candidates))
        if has_memento(row):
            with_memento[kind] += 1
        status = outcome_status(row)
        if status == "win":
            natural_wins[kind] += 1
        elif status == "loss":
            losses[kind] += 1
        else:
            outcome_missing[kind] += 1
        if kind == "PAYMENT":
            item, counters = payment_risk(row)
            payment_comparison.update(counters)
            if item is not None:
                risky_payments.append(item)
        elif kind == "OVERFLOW_DISCARD":
            item, counters = overflow_risk(row)
            overflow_comparison.update(counters)
            if item is not None:
                risky_overflows.append(item)
        elif kind == "JUST_SAY_NO":
            item = jsn_summary(row)
            if item is not None:
                jsn_rows.append(item)
                jsn_counts["rows"] += 1
                if item["candidateCount"] <= 1:
                    jsn_counts["oneCandidateRows"] += 1
                else:
                    jsn_counts["actionableRows"] += 1
                    if item["choiceId"] == "PASS":
                        jsn_counts["actionablePassRows"] += 1
                    elif item["choiceId"] == "PLAY_JSN":
                        jsn_counts["actionablePlayRows"] += 1

    risky_payments.sort(key=lambda item: item["riskScore"], reverse=True)
    risky_overflows.sort(key=lambda item: item["riskScore"], reverse=True)
    jsn_rows.sort(key=lambda item: (
        item["candidateCount"],
        item.get("amountDueM", 0),
        outcome_sort_value(item),
    ), reverse=True)

    return {
        "schema": "monopoly-auxiliary-trace-analysis-v2",
        "tracePaths": [str(path) for path in paths],
        "rowsRead": len(rows),
        "byKind": dict(by_kind),
        "withMemento": dict(with_memento),
        "naturalWins": dict(natural_wins),
        "losses": dict(losses),
        "outcomeMissing": dict(outcome_missing),
        "candidateCounts": {
            kind: stats(values) for kind, values in sorted(candidate_counts.items())
        },
        "paymentComparison": dict(payment_comparison),
        "overflowComparison": dict(overflow_comparison),
        "justSayNoComparison": dict(jsn_counts),
        "riskyPaymentCount": len(risky_payments),
        "riskyOverflowCount": len(risky_overflows),
        "jsnDecisionCount": len(jsn_rows),
        "topRiskyPayments": risky_payments[:top],
        "topRiskyOverflows": risky_overflows[:top],
        "topJustSayNo": jsn_rows[:top],
    }


def payment_risk(row: dict[str, Any]) -> tuple[dict[str, Any] | None, collections.Counter[str]]:
    counters: collections.Counter[str] = collections.Counter()
    req = obj(row.get("request"))
    result = obj(row.get("result"))
    choice_id = str(result.get("choiceId", ""))
    fallback_id = str(obj(result.get("metadata")).get("fallbackChoiceId", ""))
    candidates = objs(req.get("candidates"))
    chosen = candidate_by_id(candidates, choice_id)
    fallback = candidate_by_id(candidates, fallback_id)
    if fallback is None:
        fallback = chosen
        fallback_id = choice_id
    if chosen is None or fallback is None:
        counters["missingChoiceRows"] += 1
        return None, counters

    actor_id = str(req.get("actorPlayerId", ""))
    memento = memento_json(row)
    player = find_player(memento, actor_id)
    if player is None:
        counters["missingMementoPlayerRows"] += 1
        return None, counters

    amount_due = number(obj(req.get("context")).get("amountDueM"))
    scored = [score_payment_candidate(candidate, player, amount_due) for candidate in candidates]
    scored = [item for item in scored if item is not None]
    if not scored:
        counters["unscoredRows"] += 1
        return None, counters
    best = min(scored, key=lambda item: (item["score"], item["amountPaidM"], item["cardCount"], item["key"]))
    chosen_score = next((item for item in scored if item["id"] == choice_id), None)
    fallback_score = next((item for item in scored if item["id"] == fallback_id), None)
    if chosen_score is None or fallback_score is None:
        counters["missingScoredChoiceRows"] += 1
        return None, counters

    counters["scoredRows"] += 1
    if fallback_id != best["id"]:
        counters["fallbackDiffersFromStaticBestRows"] += 1
        status = outcome_status(row)
        if status == "loss":
            counters["losingFallbackDiffersFromStaticBestRows"] += 1
        elif status == "unknown":
            counters["outcomeMissingFallbackDiffersFromStaticBestRows"] += 1
    if choice_id != fallback_id:
        counters["chosenDiffersFromFallbackRows"] += 1
    if choice_id != best["id"]:
        counters["chosenDiffersFromStaticBestRows"] += 1

    score_gap = max(0.0, fallback_score["score"] - best["score"])
    property_gap = fallback_score["propertyCards"] - best["propertyCards"]
    breaks_gap = fallback_score["completeSetBreaks"] - best["completeSetBreaks"]
    if score_gap <= 0 and property_gap <= 0 and breaks_gap <= 0:
        return None, counters

    risk = score_gap * 10 + property_gap * 4 + breaks_gap * 20
    return common(row) | {
        "choiceId": choice_id,
        "fallbackChoiceId": fallback_id,
        "staticBestChoiceId": best["id"],
        "choice": chosen_score,
        "fallback": fallback_score,
        "staticBest": best,
        "amountDueM": amount_due,
        "scoreGapFallbackMinusBest": score_gap,
        "riskScore": risk,
        "candidateCount": len(candidates),
    }, counters


def overflow_risk(row: dict[str, Any]) -> tuple[dict[str, Any] | None, collections.Counter[str]]:
    counters: collections.Counter[str] = collections.Counter()
    req = obj(row.get("request"))
    result = obj(row.get("result"))
    choice_id = str(result.get("choiceId", ""))
    fallback_id = str(obj(result.get("metadata")).get("fallbackChoiceId", ""))
    candidates = objs(req.get("candidates"))
    chosen = candidate_by_id(candidates, choice_id)
    fallback = candidate_by_id(candidates, fallback_id)
    if fallback is None:
        fallback = chosen
        fallback_id = choice_id
    if chosen is None or fallback is None:
        counters["missingChoiceRows"] += 1
        return None, counters

    actor_id = str(req.get("actorPlayerId", ""))
    memento = memento_json(row)
    player = find_player(memento, actor_id)
    if player is None:
        counters["missingMementoPlayerRows"] += 1
        return None, counters

    scored = [score_overflow_candidate(candidate, player) for candidate in candidates]
    scored = [item for item in scored if item is not None]
    if not scored:
        counters["unscoredRows"] += 1
        return None, counters
    best = min(scored, key=lambda item: (item["discardRetentionScore"], item["cardCount"], item["key"]))
    chosen_score = next((item for item in scored if item["id"] == choice_id), None)
    fallback_score = next((item for item in scored if item["id"] == fallback_id), None)
    if chosen_score is None or fallback_score is None:
        counters["missingScoredChoiceRows"] += 1
        return None, counters

    counters["scoredRows"] += 1
    if fallback_id != best["id"]:
        counters["fallbackDiffersFromStaticBestRows"] += 1
        status = outcome_status(row)
        if status == "loss":
            counters["losingFallbackDiffersFromStaticBestRows"] += 1
        elif status == "unknown":
            counters["outcomeMissingFallbackDiffersFromStaticBestRows"] += 1
    if choice_id != fallback_id:
        counters["chosenDiffersFromFallbackRows"] += 1
    if choice_id != best["id"]:
        counters["chosenDiffersFromStaticBestRows"] += 1

    score_gap = max(0.0, fallback_score["discardRetentionScore"] - best["discardRetentionScore"])
    high_gap = fallback_score["highValueCards"] - best["highValueCards"]
    if score_gap <= 0 and high_gap <= 0:
        return None, counters

    risk = score_gap + high_gap * 200
    return common(row) | {
        "choiceId": choice_id,
        "fallbackChoiceId": fallback_id,
        "staticBestChoiceId": best["id"],
        "choice": chosen_score,
        "fallback": fallback_score,
        "staticBest": best,
        "scoreGapFallbackMinusBest": score_gap,
        "riskScore": risk,
        "candidateCount": len(candidates),
    }, counters


def jsn_summary(row: dict[str, Any]) -> dict[str, Any] | None:
    req = obj(row.get("request"))
    result = obj(row.get("result"))
    candidates = objs(req.get("candidates"))
    memento = memento_json(row)
    stack = objs(memento.get("effectStack"))
    entry = stack[0] if stack else {}
    color_or_effect = str(entry.get("colorKey", ""))
    return common(row) | {
        "choiceId": str(result.get("choiceId", "")),
        "fallbackChoiceId": str(obj(result.get("metadata")).get("fallbackChoiceId", "")),
        "candidateCount": len(candidates),
        "counterRole": bool(obj(req.get("context")).get("counterRole")),
        "fallbackPlayJustSayNo": bool(obj(req.get("context")).get("fallbackPlayJustSayNo")),
        "effectKind": str(entry.get("kind", "")),
        "amountDueM": number(entry.get("amountDue")),
        "effectActorPlayerId": str(entry.get("actorPlayerId", "")),
        "effectTenantPlayerId": str(entry.get("tenantPlayerId", "")),
        "colorKey": color_or_effect if color_or_effect in REQUIRED_BY_COLOR else "",
        "effectLabel": color_or_effect,
    }


def common(row: dict[str, Any]) -> dict[str, Any]:
    req = obj(row.get("request"))
    outcome = obj(row.get("outcome"))
    status = outcome_status(row)
    return {
        "tracePath": row.get("_tracePath", ""),
        "lineNo": row.get("_lineNo", 0),
        "decisionId": str(req.get("decisionId", "")),
        "sessionId": str(req.get("sessionId", "")),
        "stateSequence": req.get("stateSequence", 0),
        "outcomeStatus": status,
        "naturalWin": True if status == "win" else False if status == "loss" else None,
        "reward": outcome.get("reward", 0),
        "completeSets": outcome.get("completeSets", 0),
        "boardRank": outcome.get("boardRank", 0),
        "hasMemento": has_memento(row),
    }


def outcome_status(row: dict[str, Any]) -> str:
    outcome = obj(row.get("outcome"))
    if not outcome:
        return "unknown"
    if bool(outcome.get("naturalWin")):
        return "win"
    return "loss"


def outcome_sort_value(item: dict[str, Any]) -> int:
    status = str(item.get("outcomeStatus", "unknown"))
    if status == "loss":
        return 2
    if status == "unknown":
        return 1
    return 0


def has_memento(row: dict[str, Any]) -> bool:
    ctx = obj(obj(row.get("request")).get("context"))
    return bool(str(obj(ctx.get("counterfactual")).get("mementoJson", "")).strip())


def memento_json(row: dict[str, Any]) -> dict[str, Any]:
    ctx = obj(obj(row.get("request")).get("context"))
    raw = str(obj(ctx.get("counterfactual")).get("mementoJson", "")).strip()
    if not raw:
        return {}
    try:
        parsed = json.loads(raw)
    except json.JSONDecodeError:
        return {}
    return obj(parsed)


def find_player(memento: dict[str, Any], player_id: str) -> dict[str, Any] | None:
    for player in objs(memento.get("sessionPlayers")):
        if str(player.get("playerId", "")) == player_id:
            return player
    return None


def candidate_by_id(candidates: list[dict[str, Any]], choice_id: str) -> dict[str, Any] | None:
    for candidate in candidates:
        if str(candidate.get("id", "")) == choice_id:
            return candidate
    return None


def score_payment_candidate(
        candidate: dict[str, Any],
        player: dict[str, Any],
        amount_due: float) -> dict[str, Any] | None:
    payload = obj(candidate.get("payload"))
    ids = string_list(payload.get("cardIds"))
    card_index = player_card_index(player)
    cards = [card_index.get(card_id) for card_id in ids]
    if any(card is None for card in cards):
        return None
    present_cards = [card for card in cards if card is not None]
    amount_paid = number(payload.get("amountPaidM"))
    if amount_paid <= 0:
        amount_paid = sum(payment_value(card) for card in present_cards)
    details = [payment_card_damage(card, player) for card in present_cards]
    board_damage = sum(detail["damage"] for detail in details)
    property_cards = sum(1 for card in present_cards if is_property(card))
    bank_cards = sum(1 for card in present_cards if card_zone(player, card.get("id")) == "bankCards")
    return {
        "id": str(candidate.get("id", "")),
        "summary": str(candidate.get("summary", "")),
        "cardIds": ids,
        "key": "|".join(sorted(ids)),
        "amountPaidM": amount_paid,
        "overpayM": max(0.0, amount_paid - amount_due),
        "score": amount_paid + board_damage,
        "boardDamage": board_damage,
        "cardCount": len(ids),
        "bankCards": bank_cards,
        "propertyCards": property_cards,
        "completeSetBreaks": sum(1 for detail in details if detail["breaksCompleteSet"]),
        "nearSetBreaks": sum(1 for detail in details if detail["breaksNearSet"]),
        "wildCards": sum(1 for card in present_cards if is_wild(card)),
        "cards": details,
    }


def payment_card_damage(card: dict[str, Any], player: dict[str, Any]) -> dict[str, Any]:
    card_id = str(card.get("id", ""))
    value = payment_value(card)
    if not is_property(card):
        return {
            "cardId": card_id,
            "zone": card_zone(player, card_id),
            "className": str(card.get("className", "")),
            "valueM": value,
            "damage": 0.0,
            "color": "",
            "effectiveCount": 0,
            "requiredCount": 0,
            "breaksCompleteSet": False,
            "breaksNearSet": False,
            "wild": False,
        }

    color = property_color(card)
    if not color:
        return {
            "cardId": card_id,
            "zone": card_zone(player, card_id),
            "className": str(card.get("className", "")),
            "valueM": value,
            "damage": value * 0.1,
            "color": "",
            "effectiveCount": 0,
            "requiredCount": 0,
            "breaksCompleteSet": False,
            "breaksNearSet": False,
            "wild": is_wild(card),
        }

    need = max(1, REQUIRED_BY_COLOR.get(color, 3))
    effective = effective_count_for_color(objs(player.get("propertyCards")), color)
    damage = value * 0.1
    breaks_complete = effective >= need
    breaks_near = effective == need - 1
    if breaks_complete:
        damage += PAYMENT_COMPLETE_SET_BREAK_PENALTY
    elif breaks_near:
        damage += PAYMENT_NEAR_SET_BREAK_PENALTY
    if is_wild(card):
        damage += 0.5
    return {
        "cardId": card_id,
        "zone": card_zone(player, card_id),
        "className": str(card.get("className", "")),
        "valueM": value,
        "damage": damage,
        "color": color,
        "effectiveCount": effective,
        "requiredCount": need,
        "breaksCompleteSet": breaks_complete,
        "breaksNearSet": breaks_near,
        "wild": is_wild(card),
    }


def score_overflow_candidate(candidate: dict[str, Any], player: dict[str, Any]) -> dict[str, Any] | None:
    payload = obj(candidate.get("payload"))
    ids = string_list(payload.get("cardIds"))
    card_index = player_card_index(player)
    cards = [card_index.get(card_id) for card_id in ids]
    if any(card is None for card in cards):
        return None
    present_cards = [card for card in cards if card is not None]
    details = [overflow_card_score(card, player) for card in present_cards]
    return {
        "id": str(candidate.get("id", "")),
        "summary": str(candidate.get("summary", "")),
        "cardIds": ids,
        "key": "|".join(sorted(ids)),
        "discardRetentionScore": sum(detail["retentionScore"] for detail in details),
        "cardCount": len(ids),
        "highValueCards": sum(1 for detail in details if detail["retentionScore"] >= 900),
        "propertyCards": sum(1 for card in present_cards if is_property(card)),
        "actionCards": sum(1 for card in present_cards if is_action(card)),
        "moneyCards": sum(1 for card in present_cards if is_money(card)),
        "cards": details,
    }


def overflow_card_score(card: dict[str, Any], player: dict[str, Any]) -> dict[str, Any]:
    value = payment_value(card)
    if is_wild(card):
        wild_kind = str(card.get("wildKind", "")).upper()
        score = 1050.0 if wild_kind == "ANY_COLOR" else 920.0 + value * 12.0
    elif is_property(card):
        score = property_overflow_retention_score(card, player)
    elif is_action(card):
        score = action_overflow_retention_score(card)
    else:
        score = value * 10.0
    return {
        "cardId": str(card.get("id", "")),
        "zone": card_zone(player, card.get("id")),
        "className": str(card.get("className", "")),
        "name": str(card.get("name", "")),
        "effectCode": normalized(card.get("effectCode")),
        "color": property_color(card),
        "valueM": value,
        "retentionScore": score,
        "wild": is_wild(card),
    }


def property_overflow_retention_score(card: dict[str, Any], player: dict[str, Any]) -> float:
    color = normalized(card.get("colorGroup"))
    value = payment_value(card)
    if not color:
        return 520.0 + value * 20.0
    need = max(1, REQUIRED_BY_COLOR.get(color, 3))
    effective = effective_count_for_color(objs(player.get("propertyCards")), color)
    after = effective + 1
    if after >= need:
        return 1120.0 + value * 18.0
    if after == need - 1:
        return 820.0 + value * 18.0
    if after == 1:
        return 390.0 + value * 18.0
    return 560.0 + value * 18.0


def action_overflow_retention_score(card: dict[str, Any]) -> float:
    effect = normalized(card.get("effectCode"))
    value = payment_value(card)
    base = {
        "DEAL_BREAKER": 1080.0,
        "RENT_WAIVER": 980.0,
        "STEAL_PROPERTY": 930.0,
        "FORCED_DEAL": 930.0,
        "PASS_GO": 760.0,
        "HOUSE": 735.0,
        "HOTEL": 735.0,
        "DEBT_COLLECTOR": 650.0,
        "BIRTHDAY": 650.0,
        "DOUBLE_RENT": 610.0,
        "RENT": 610.0,
        "RENT_DUAL": 610.0,
    }.get(effect, 520.0)
    return base + value * 10.0


def player_card_index(player: dict[str, Any]) -> dict[str, dict[str, Any]]:
    out: dict[str, dict[str, Any]] = {}
    for zone in ("handCards", "bankCards", "propertyCards", "actionZoneCards"):
        for card in objs(player.get(zone)):
            card_id = str(card.get("id", ""))
            if card_id:
                out[card_id] = card
    return out


def card_zone(player: dict[str, Any], card_id: Any) -> str:
    target = str(card_id or "")
    if not target:
        return ""
    for zone in ("handCards", "bankCards", "propertyCards", "actionZoneCards"):
        for card in objs(player.get(zone)):
            if str(card.get("id", "")) == target:
                return zone
    return ""


def payment_value(card: dict[str, Any]) -> int:
    class_name = str(card.get("className", ""))
    if class_name == "MoneyCard":
        return int(number(card.get("valueM")))
    if class_name == "ActionCard":
        return ACTION_BANK_VALUES.get(normalized(card.get("effectCode")), 3)
    if class_name == "PropertyWildCard":
        wild_kind = str(card.get("wildKind", "")).upper()
        if wild_kind == "ANY_COLOR":
            return 0
        pair = normalized_pair(card.get("wildPrintedPair"))
        if pair_equals(pair, "LIGHT_BLUE", "BROWN"):
            return 1
        if pair_equals(pair, "PINK", "ORANGE") or pair_equals(pair, "RAILROAD", "UTILITY"):
            return 2
        if pair_equals(pair, "RED", "YELLOW"):
            return 3
        if (pair_equals(pair, "LIGHT_BLUE", "RAILROAD")
                or pair_equals(pair, "DARK_BLUE", "GREEN")
                or pair_equals(pair, "GREEN", "RAILROAD")):
            return 4
        return max([single_color_payment_value(color) for color in pair] or [0])
    if class_name == "PropertyCard":
        return single_color_payment_value(card.get("colorGroup"))
    return 0


def single_color_payment_value(color: Any) -> int:
    key = normalized(color)
    if key in {"BROWN", "DARK_BLUE", "UTILITY"}:
        return 2
    if key in {"LIGHT_BLUE", "PINK", "ORANGE", "RED", "YELLOW", "GREEN"}:
        return 3
    if key == "RAILROAD":
        return 4
    return 0


def property_color(card: dict[str, Any]) -> str:
    if is_wild(card):
        return normalized(card.get("assignedColorKey"))
    return normalized(card.get("colorGroup"))


def effective_count_for_color(cards: list[dict[str, Any]], color: str) -> int:
    key = normalized(color)
    if not key:
        return 0
    total = 0
    for card in cards:
        if is_wild(card):
            if normalized(card.get("assignedColorKey")) == key:
                total += 1
        elif normalized(card.get("colorGroup")) == key:
            total += 1
    return total


def is_property(card: dict[str, Any]) -> bool:
    return str(card.get("className", "")) in {"PropertyCard", "PropertyWildCard"}


def is_wild(card: dict[str, Any]) -> bool:
    return str(card.get("className", "")) == "PropertyWildCard"


def is_action(card: dict[str, Any]) -> bool:
    return str(card.get("className", "")) == "ActionCard"


def is_money(card: dict[str, Any]) -> bool:
    return str(card.get("className", "")) == "MoneyCard"


def normalized(value: Any) -> str:
    return str(value or "").strip().upper()


def normalized_pair(value: Any) -> list[str]:
    raw = str(value or "")
    return [part.strip().upper() for part in raw.split("|") if part.strip()]


def pair_equals(pair: list[str], left: str, right: str) -> bool:
    return len(pair) == 2 and set(pair) == {left, right}


def string_list(value: Any) -> list[str]:
    if not isinstance(value, list):
        return []
    return [str(item) for item in value if str(item)]


def stats(values: list[int]) -> dict[str, Any]:
    if not values:
        return {"count": 0}
    ordered = sorted(values)
    return {
        "count": len(values),
        "min": ordered[0],
        "median": ordered[len(ordered) // 2],
        "max": ordered[-1],
        "mean": sum(values) / len(values),
    }


def obj(value: Any) -> dict[str, Any]:
    return value if isinstance(value, dict) else {}


def objs(value: Any) -> list[dict[str, Any]]:
    return [item for item in value if isinstance(item, dict)] if isinstance(value, list) else []


def number(value: Any) -> float:
    try:
        return float(value)
    except (TypeError, ValueError):
        return 0.0


if __name__ == "__main__":
    raise SystemExit(main())
