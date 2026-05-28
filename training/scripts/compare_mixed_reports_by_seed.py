#!/usr/bin/env python3
"""Compare mixed-battle reports game-by-game by deck seed.

This is a diagnostic companion to summary-level win-rate comparison. It is
useful for matched screens where a variant reuses the same seed bases as a
baseline and we want to know how many individual games actually flipped.
"""

from __future__ import annotations

import argparse
import collections
import json
import math
from pathlib import Path
from typing import Any, Iterable, Sequence


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--variant", nargs="+", type=Path, required=True)
    parser.add_argument("--baseline", nargs="+", type=Path, required=True)
    parser.add_argument("--team", default="lookahead")
    parser.add_argument("--allow-partial", action="store_true")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args(argv)

    variant = load_games(args.variant, args.team)
    baseline = load_games(args.baseline, args.team)
    common_keys = sorted(set(variant) & set(baseline))
    missing_variant = sorted(set(baseline) - set(variant))
    missing_baseline = sorted(set(variant) - set(baseline))

    changed: list[dict[str, Any]] = []
    by_lineup: dict[str, collections.Counter[str]] = collections.defaultdict(collections.Counter)
    variant_wins = 0
    baseline_wins = 0
    for key in common_keys:
        v = variant[key]
        b = baseline[key]
        v_win = bool(v["teamWon"])
        b_win = bool(b["teamWon"])
        variant_wins += int(v_win)
        baseline_wins += int(b_win)
        transition = f"{outcome_name(b_win)}->{outcome_name(v_win)}"
        by_lineup[str(v["lineup"])][transition] += 1
        if v_win != b_win:
            changed.append({
                "deckSeed": key,
                "lineup": v["lineup"],
                "baselineWon": b_win,
                "variantWon": v_win,
                "transition": transition,
                "baselineSummary": b["lastActionSummary"],
                "variantSummary": v["lastActionSummary"],
            })

    out = {
        "schema": "monopoly-mixed-report-seed-comparison-v1",
        "team": args.team,
        "variantReports": [str(path) for path in args.variant],
        "baselineReports": [str(path) for path in args.baseline],
        "commonGames": len(common_keys),
        "missingVariant": len(missing_variant),
        "missingBaseline": len(missing_baseline),
        "variantWins": variant_wins,
        "baselineWins": baseline_wins,
        "netWins": variant_wins - baseline_wins,
        "changedGames": len(changed),
        "pairedStats": paired_stats(by_lineup),
        "byLineup": {lineup: dict(counter) for lineup, counter in sorted(by_lineup.items())},
        "changedExamples": changed[:50],
        "checks": [
            check("common_games_nonzero", len(common_keys) > 0, f"common={len(common_keys)}"),
            check("no_missing_variant", args.allow_partial or not missing_variant, f"missing={len(missing_variant)}"),
            check("no_missing_baseline", args.allow_partial or not missing_baseline, f"missing={len(missing_baseline)}"),
        ],
    }
    text = json.dumps(out, ensure_ascii=False, indent=2)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(text + "\n", encoding="utf-8")
    print(text)
    return 0 if all(item["passed"] for item in out["checks"]) else 1


def paired_stats(by_lineup: dict[str, collections.Counter[str]]) -> dict[str, Any]:
    loss_to_win = sum(counter.get("L->W", 0) for counter in by_lineup.values())
    win_to_loss = sum(counter.get("W->L", 0) for counter in by_lineup.values())
    changed = loss_to_win + win_to_loss
    if changed == 0:
        return {
            "lossToWin": loss_to_win,
            "winToLoss": win_to_loss,
            "netFlips": 0,
            "changedGames": 0,
            "signExactTwoSidedP": 1.0,
            "mcnemarContinuityPApprox": 1.0,
        }
    larger = max(loss_to_win, win_to_loss)
    tail = sum(math.comb(changed, i) for i in range(larger, changed + 1))
    sign_p = min(1.0, tail * 2.0 / (2 ** changed))
    chi_square = (max(0, abs(loss_to_win - win_to_loss) - 1) ** 2) / changed
    mcnemar_p = math.erfc(math.sqrt(chi_square / 2.0))
    return {
        "lossToWin": loss_to_win,
        "winToLoss": win_to_loss,
        "netFlips": loss_to_win - win_to_loss,
        "changedGames": changed,
        "signExactTwoSidedP": sign_p,
        "mcnemarContinuityPApprox": mcnemar_p,
    }


def load_games(paths: Iterable[Path], team: str) -> dict[int, dict[str, Any]]:
    rows: dict[int, dict[str, Any]] = {}
    duplicates: list[int] = []
    for path in paths:
        report = json.loads(path.read_text(encoding="utf-8"))
        lineup = str(report.get("lineup", ""))
        for game in objects(report.get("games")):
            deck_seed = int(number(game.get("deckSeed")))
            if deck_seed == 0:
                continue
            if deck_seed in rows:
                duplicates.append(deck_seed)
                continue
            rows[deck_seed] = {
                "path": str(path),
                "lineup": lineup,
                "teamWon": natural_winner_team(game) == team.strip().lower(),
                "winnerTeam": natural_winner_team(game),
                "lastActionSummary": str(game.get("lastActionSummary", "")),
            }
    if duplicates:
        raise SystemExit(f"duplicate deck seeds: {duplicates[:10]}")
    return rows


def natural_winner_team(game: dict[str, Any]) -> str:
    if not game.get("gameOver") or game.get("forceEndReason") not in (None, ""):
        return "unknown"
    explicit = str(game.get("naturalWinnerTeam", "") or "").strip().lower()
    if explicit:
        return explicit
    winners = [
        normalized_player_team(player)
        for player in objects(game.get("playersByBoardRank"))
        if int(number(player.get("completeSets"))) >= 3
    ]
    return winners[0] if len(winners) == 1 else "unknown"


def normalized_player_team(player: dict[str, Any]) -> str:
    raw_team = str(player.get("team", "") or "").strip().lower()
    text = " ".join([
        raw_team,
        str(player.get("displayName", "") or "").strip().lower(),
        str(player.get("playerId", "") or "").strip().lower(),
    ])
    if "lookahead" in text or "search" in text:
        return "lookahead"
    if "hard" in text:
        return "hard"
    return raw_team or "unknown"


def outcome_name(won: bool) -> str:
    return "W" if won else "L"


def check(name: str, passed: bool, detail: str) -> dict[str, Any]:
    return {"name": name, "passed": bool(passed), "detail": detail}


def objects(value: Any) -> list[dict[str, Any]]:
    return [item for item in value if isinstance(item, dict)] if isinstance(value, list) else []


def number(value: Any) -> float:
    try:
        return float(value)
    except (TypeError, ValueError):
        return 0.0


if __name__ == "__main__":
    raise SystemExit(main())
