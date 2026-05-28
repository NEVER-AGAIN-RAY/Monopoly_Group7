#!/usr/bin/env python3
"""Summarize same-seed dual-seat mixed-battle confirmations."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any


def wilson_interval(successes: int, total: int, z: float = 1.96) -> tuple[float, float]:
    if total <= 0:
        return (0.0, 0.0)
    p = successes / total
    denominator = 1.0 + z * z / total
    center = (p + z * z / (2.0 * total)) / denominator
    half = z * math.sqrt((p * (1.0 - p) + z * z / (4.0 * total)) / total) / denominator
    return (center - half, center + half)


def pct(value: float) -> str:
    return f"{value * 100.0:.2f}%"


def as_int(value: Any) -> int:
    if isinstance(value, bool):
        return 1 if value else 0
    if isinstance(value, (int, float)):
        return int(value)
    if isinstance(value, str):
        try:
            return int(float(value))
        except ValueError:
            return 0
    return 0


def normalized_team(value: Any) -> str:
    raw = str(value or "").strip().lower()
    if "lookahead" in raw or "search" in raw or "strong" in raw:
        return "lookahead"
    if "hard" in raw:
        return "hard"
    if "normal" in raw:
        return "normal"
    if "easy" in raw:
        return "easy"
    if "deepseek" in raw:
        return "deepseek"
    if "openai" in raw or "gpt" in raw:
        return "openai"
    return raw or "unknown"


def natural_winner_team(game: dict[str, Any]) -> str | None:
    if not game.get("gameOver") or game.get("forceEndReason") not in (None, ""):
        return None
    team = normalized_team(game.get("naturalWinnerTeam"))
    if team != "unknown":
        return team
    summary = str(game.get("lastActionSummary", "") or "").strip().lower()
    for player in game.get("playersByBoardRank", []):
        if not isinstance(player, dict):
            continue
        display_name = str(player.get("displayName", "") or "").strip().lower()
        player_id = str(player.get("playerId", "") or "").strip().lower()
        if (display_name and summary.startswith(f"{display_name} wins")) \
                or (player_id and summary.startswith(f"{player_id} wins")):
            return normalized_team(player.get("team") or player.get("displayName"))
    winners = [
        player
        for player in game.get("playersByBoardRank", [])
        if isinstance(player, dict) and as_int(player.get("completeSets")) >= 3
    ]
    if len(winners) == 1:
        return normalized_team(winners[0].get("team") or winners[0].get("displayName"))
    return None


def config_digest(report: dict[str, Any]) -> str:
    config = report.get("effectiveLookaheadConfig")
    if not isinstance(config, dict):
        config = {}
    raw = json.dumps(config, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(raw.encode("utf-8")).hexdigest()


def load_reports(paths: list[Path]) -> list[dict[str, Any]]:
    reports: list[dict[str, Any]] = []
    for path in paths:
        report = json.loads(path.read_text(encoding="utf-8"))
        report["_path"] = str(path)
        reports.append(report)
    return reports


def summarize(reports: list[dict[str, Any]], team: str, expected_seats_per_seed: int) -> dict[str, Any]:
    target = normalized_team(team)
    seed_rows: dict[int, list[dict[str, Any]]] = defaultdict(list)
    report_rows: list[dict[str, Any]] = []
    config_digests: dict[str, str] = {}
    config_key_counts: dict[str, int] = {}
    total_games = 0
    natural_games = 0
    forced_games = 0
    unknown_games = 0
    wins = 0
    initial_team_counts: Counter[str] = Counter()
    initial_team_winner_counts: Counter[str] = Counter()
    lineup_winner_counts: Counter[str] = Counter()

    for report in reports:
        path = str(report.get("_path", ""))
        lineup = str(report.get("lineup", ""))
        digest = config_digest(report)
        config_digests[path] = digest
        cfg = report.get("effectiveLookaheadConfig")
        config_key_counts[path] = len(cfg) if isinstance(cfg, dict) else 0
        report_games = 0
        report_natural = 0
        report_wins = 0
        report_forced = 0
        report_unknown = 0
        for game in report.get("games", []):
            if not isinstance(game, dict):
                continue
            report_games += 1
            total_games += 1
            seed = game.get("deckSeed")
            if isinstance(seed, str) and seed.strip().lstrip("-").isdigit():
                seed = int(seed)
            if isinstance(seed, int):
                seed_rows[seed].append({"report": path, "lineup": lineup, "game": game})
            winner = natural_winner_team(game)
            if winner is None:
                if game.get("forceEndReason"):
                    forced_games += 1
                    report_forced += 1
                else:
                    unknown_games += 1
                    report_unknown += 1
            else:
                natural_games += 1
                report_natural += 1
                if winner == target:
                    wins += 1
                    report_wins += 1
            initial_team = normalized_team(game.get("initialPlayerTeam"))
            initial_team_counts[initial_team] += 1
            initial_team_winner_counts[f"{initial_team}->{winner or 'none'}"] += 1
            lineup_winner_counts[f"{lineup}->{winner or 'none'}"] += 1
        report_rows.append({
            "path": path,
            "lineup": lineup,
            "games": report_games,
            "naturalGames": report_natural,
            "forcedGames": report_forced,
            "unknownGames": report_unknown,
            "teamNaturalWins": report_wins,
            "teamNaturalWinRate": report_wins / report_natural if report_natural else 0.0,
            "configKeyCount": config_key_counts[path],
            "configDigest": digest,
        })

    paired_win_counts: Counter[int] = Counter()
    seed_row_counts: Counter[int] = Counter()
    missing_or_extra: list[dict[str, Any]] = []
    for seed, rows in sorted(seed_rows.items()):
        seed_row_counts[len(rows)] += 1
        seed_wins = 0
        for row in rows:
            winner = natural_winner_team(row["game"])
            if winner == target:
                seed_wins += 1
        paired_win_counts[seed_wins] += 1
        if len(rows) != expected_seats_per_seed:
            missing_or_extra.append({
                "deckSeed": seed,
                "rows": len(rows),
                "reports": [row["report"] for row in rows],
            })

    ci = wilson_interval(wins, natural_games)
    unique_config_digests = sorted(set(config_digests.values()))
    checks = [
        {
            "name": "all_reports_have_effective_config",
            "passed": all(count > 0 for count in config_key_counts.values()),
            "detail": str(dict(config_key_counts)),
        },
        {
            "name": "effective_config_identical",
            "passed": len(unique_config_digests) == 1,
            "detail": f"{len(unique_config_digests)} unique config digests",
        },
        {
            "name": "expected_rows_per_seed",
            "passed": not missing_or_extra,
            "detail": f"expected {expected_seats_per_seed}; distribution {dict(sorted(seed_row_counts.items()))}",
        },
        {
            "name": "all_games_natural",
            "passed": total_games > 0 and natural_games == total_games,
            "detail": f"{natural_games}/{total_games} natural, forced={forced_games}, unknown={unknown_games}",
        },
        {
            "name": "team_above_hard_baseline",
            "passed": natural_games > 0 and wins / natural_games > 0.5,
            "detail": f"{pct(wins / natural_games if natural_games else 0.0)} > 50.00%",
        },
        {
            "name": "ci_low_above_hard_baseline",
            "passed": ci[0] > 0.5,
            "detail": f"{pct(ci[0])} > 50.00%",
        },
    ]

    return {
        "schema": "monopoly-paired-seat-summary-v1",
        "team": target,
        "expectedSeatsPerSeed": expected_seats_per_seed,
        "reports": report_rows,
        "totals": {
            "reports": len(reports),
            "games": total_games,
            "uniqueDeckSeeds": len(seed_rows),
            "naturalGames": natural_games,
            "forcedGames": forced_games,
            "unknownGames": unknown_games,
            "teamNaturalWins": wins,
            "teamNaturalWinRate": wins / natural_games if natural_games else 0.0,
            "teamNaturalWinRate95ci": list(ci),
            "pairedSeedWinDistribution": {
                str(k): v for k, v in sorted(paired_win_counts.items())
            },
            "seedRowCountDistribution": {
                str(k): v for k, v in sorted(seed_row_counts.items())
            },
            "initialTeamCounts": dict(sorted(initial_team_counts.items())),
            "initialTeamWinnerCounts": dict(sorted(initial_team_winner_counts.items())),
            "lineupWinnerCounts": dict(sorted(lineup_winner_counts.items())),
            "uniqueConfigDigests": unique_config_digests,
            "missingOrExtraSeedRows": missing_or_extra[:50],
        },
        "checks": checks,
        "passed": all(check["passed"] for check in checks),
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("reports", nargs="+", type=Path)
    parser.add_argument("--team", default="lookahead")
    parser.add_argument("--expected-seats-per-seed", type=int, default=2)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()

    result = summarize(load_reports(args.reports), args.team, args.expected_seats_per_seed)
    text = json.dumps(result, ensure_ascii=False, indent=2)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(text + "\n", encoding="utf-8")
    print(text)
    return 0 if result["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
