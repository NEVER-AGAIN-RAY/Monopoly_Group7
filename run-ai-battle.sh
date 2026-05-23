#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"

mvn -q -DskipTests compile
mvn -q dependency:build-classpath -Dmdep.outputFile=target/classpath.txt

MAX_SNAPSHOTS="${MONOPOLY_AI_BATTLE_MAX_SNAPSHOTS:-120}"
MAX_TOKENS="${MONOPOLY_DEEPSEEK_MAX_TOKENS:-512}"
MAX_CANDIDATES="${MONOPOLY_DEEPSEEK_MAX_CANDIDATES:-28}"
PREFER_FALLBACK_JSON="${MONOPOLY_DEEPSEEK_PREFER_FALLBACK_JSON:-true}"

exec java \
  -Dmonopoly.aiBattle.maxSnapshots="${MAX_SNAPSHOTS}" \
  -Dmonopoly.deepseek.maxTokens="${MAX_TOKENS}" \
  -Dmonopoly.deepseek.maxCandidates="${MAX_CANDIDATES}" \
  -Dmonopoly.deepseek.preferFallbackForStrictJson="${PREFER_FALLBACK_JSON}" \
  "$@" \
  -cp "target/classes:$(cat target/classpath.txt)" \
  com.monopoly.tools.AiBattleExperimentRunner
