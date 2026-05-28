# AI Training Log

This log records reproducible distillation runs and their evidence.

## 2026-05-27 Payment DP Fix and Final Strong Bot Wrap-up

Purpose: audit the newly promoted `boardAwarePayment` + `boardAwareOverflowDiscard`
default before stopping this optimization pass and naming the best local
opponent for play.

Findings:

- The promoted default was rechecked against the old `buildingA` baseline on
  600 matched random-first seeds:
  `training/data/models/evaluation/winrate/paymentOverflowA-rfp-lookahead-vs-hard-600-summary.json`
  = 402/600 = 67.0%, 600/600 natural, 0 forced/unknown.
  The matched comparison against `buildingA` was net +18.
- A targeted W->L audit reproduced all 16 negative flips:
  `training/data/models/evaluation/winrate/w2l-new-vs-old-trace-seed-comparison.json`
  shows old `buildingA` 16/16 wins vs promoted default 0/16 wins on those
  exact seed+lineup cases.
- Auxiliary outcome traces showed old `buildingA` had no payment/overflow
  fallback divergence on these 16 games, while the promoted default had 45
  payment divergences and 4 overflow divergences. This correctly localized the
  risk to the new auxiliary decisions.
- The payment audit found a real implementation issue: board-aware payment
  selection used a truncated combinational enumeration (`MAX_PAYMENT_CANDIDATES`)
  for the actual choice, so many payable cards could cause it to miss exact
  low-value bank payments and choose larger payments first. This was fixed by
  switching the actual payment selection to dynamic programming by paid amount
  while keeping the same score objective (`amountPaid + boardDamage`).
- The DP fix recovered 2 of the 16 audited W->L cases:
  `training/data/models/evaluation/winrate/w2l-fixedpayment-vs-newdefault-trace-seed-comparison.json`
  = 2 L->W, 0 W->L. It did not fully eliminate the auxiliary-rule downside.
- Full 600-game recheck after the DP fix:
  `training/data/models/evaluation/winrate/fixedPaymentDp-rfp-lookahead-vs-hard-600-summary.json`
  = 402/600 = 67.0%, 600/600 natural, 0 forced/unknown.
  Against pre-DP `paymentOverflowA`, the matched comparison
  `training/data/models/evaluation/winrate/fixedPaymentDp-rfp-vs-paymentOverflowA-rfp-600-seed-comparison.json`
  was net +0 with only 4 changed games (2 L->W, 2 W->L).
  Against `buildingA`,
  `training/data/models/evaluation/winrate/fixedPaymentDp-rfp-vs-buildingA-rfp-600-seed-comparison.json`
  remained net +18, with 33 L->W vs 15 W->L, exact sign-test p ~= 0.0133.

Implementation:

- `SearchLookaheadAiPlayStrategy` now uses DP for board-aware payment choice,
  avoiding candidate-truncation bias in real gameplay decisions.
- `MixedAiBattleExperimentRunner` now supports explicit seed lists through
  `monopoly.mixedBattle.seedList`, rejects seed lists when `seeded=false`, and
  supports `monopoly.mixedBattle.progressEvery` for long-running win-rate runs.
- `training/scripts/compare_mixed_reports_by_seed.py` emits paired flip statistics, so
  promotion evidence can use matched seed flips rather than only aggregate
  natural win rates.

Final local bot recommendation:

- Best playable local opponent after this pass:
  `SearchLookaheadAiPlayStrategy` with default settings.
- In plain terms, this is the old `buildingA` play-decision policy plus:
  - `monopoly.search.boardAwarePayment=true`
  - `monopoly.search.boardAwareOverflowDiscard=true`
  - DP-fixed board-aware payment selection.
- Use the existing difficulty aliases `STRONG` / `LOOKAHEAD` / `SEARCH` for
  user-facing play. For direct experiments, use lineup token `lookahead`.

Why other candidates were not selected:

- Distilled local ranker / MLP students improved offline metrics but repeatedly
  failed paired gameplay gates or overfit trace distributions.
- Remaining-turn rollout and rollout override gates produced unstable positive
  and negative flips; clean counterfactual labels were too sparse to trust.
- Static target/wild/rent/deposit/payment parameter nudges mostly had zero
  matched-seed impact or regressed the 600-game baseline.
- Payment/overflow together were the only tested change with a stable,
  statistically favorable 600-game matched lift over `buildingA`.

Self-checks:

- A 300-game explicit seed-list run was stopped after becoming too slow because
  it was waiting on per-game latch completion without progress output. The run
  produced no report and is not used as evidence.
- The final 600-game DP recheck used six bounded report blocks with
  `progressEvery` and `timeoutSeconds=60`; all 600 games ended naturally.

Final close-loss audit:

- Remaining losses after the DP fix were audited instead of starting a new
  broad parameter sweep. The 600-game loss analysis
  `training/data/models/evaluation/winrate/fixedPaymentDp-rfp-lookahead-vs-hard-600-loss-analysis.json`
  showed the champion still had 198 losses: 47 close losses where the final
  target had 2 complete sets and 151 blowout losses. Compared with
  pre-payment/overflow `buildingA`, this confirms the auxiliary promotion
  reduced many 0/1-set failures but did not expose a simple final-step rule.
- Twenty reproducible close-loss seeds were replayed with mementos:
  `training/data/models/traces/fixedPaymentDp-close-loss-memento-seat1-10.jsonl` and
  `training/data/models/traces/fixedPaymentDp-close-loss-memento-seat2-10.jsonl`.
  Both stress reports remained 0/20 for the champion and 20/20 natural hard
  wins, so the sample is a valid pressure set rather than a parsing artifact.
- From those 897 memento rows, 60 high-gap PLAY_CARD decisions were selected
  in `training/data/distillation/fixedPaymentDp-close-loss-memento20-selected-60.jsonl`
  and replayed with hard roll-forward:
  `training/data/models/evaluation/counterfactual/fixedPaymentDp-close-loss-memento20-selected60-replay-hard340.json`
  and `...hard700.json`. The 700-snapshot replay was identical to 340:
  sourceBetter 15, hardBetter 10, same 35; source natural wins 7 vs hard 8.
  This does not justify a hard fallback rule.
- Strict clean counterfactual labels from the same replay were sparse and
  scattered: 10 selected rows, mostly `DEPOSIT`/`DEPLOY`/`FORCED_DEAL`.
  The most tempting old knob, `monopoly.search.passGoDepositBonus=1600`,
  had already failed a 200-game screen, and a direct 20-game close-loss stress
  rerun still saved 0 games:
  `training/data/models/evaluation/winrate/mixed-passGoDeposit1600-close-loss-seat1-10x340.json`
  and `...seat2-10x340.json` both remained 10/10 hard wins.
- Conclusion: the remaining close losses do not support a low-risk default
  change. The final recommendation stays the default
  `SearchLookaheadAiPlayStrategy` with board-aware payment/overflow and the DP
  payment fix.

Independent confirmation block:

- To check that the 600-game result was not just a lucky fixed seed block, two
  fresh random-first confirmation blocks were run with new seed ranges:
  `training/data/models/evaluation/winrate/finalconfirm23-rfp-lookahead-vs-hard-200-summary.json`
  = 116/200 = 58.0%, 200/200 natural, 0 forced, 0 unknown. The 200-game gate
  `training/data/models/evaluation/winrate/finalconfirm23-rfp-lookahead-vs-hard-200-gate.json`
  passed with Wilson CI low ~= 51.07%.
- The fresh confirmation result is materially lower than the earlier 600-game
  fixedPaymentDp block (58.0% vs 67.0%; rough two-proportion p ~= 0.021), so it
  should be treated as a variance warning rather than ignored. Self-checks found
  no seed overlap, no duplicate deck seeds, balanced lineups (100/100), and
  200/200 `NATURAL_WIN` with no missing winner fields.
- Combining the original 600-game evidence with the fresh 200-game confirmation
  gives
  `training/data/models/evaluation/winrate/fixedPaymentDp-plus-finalconfirm23-rfp-lookahead-vs-hard-800-summary.json`
  = 518/800 = 64.75%, 800/800 natural, 0 forced, 0 unknown, 95% CI
  ~= 61.38%-67.98%. The corresponding gate
  `training/data/models/evaluation/winrate/fixedPaymentDp-plus-finalconfirm23-rfp-lookahead-vs-hard-800-gate.json`
  passed.
- Fresh 200-game loss analysis
  `training/data/models/evaluation/winrate/finalconfirm23-rfp-lookahead-vs-hard-200-loss-analysis.json`
  shows 84 losses, with 25 close losses and 59 blowout losses; losses by target
  complete sets were `{0:36, 1:23, 2:25}`. This matches the earlier pattern that
  residual failures are mixed between early blowouts and final-step misses.

Tooling self-check:

- `MixedAiBattleExperimentRunner` now writes
  `effectiveLookaheadConfig` in every new report. This records the actual
  `SearchLookaheadAiPlayStrategy` static defaults, not only explicitly supplied
  `-Dmonopoly.search.*` properties. This was added because old reports showed
  missing `monopoly.search.*` keys when defaults were used, making it harder to
  rule out configuration drift during win-rate swings.
- Smoke report
  `training/data/models/evaluation/winrate/mixed-effective-config-smoke-1x340.json` confirms
  the new field includes the promoted defaults: `buildingActionBonus=900`,
  `buildingRentBonusValue=320`, `opponentBuildingThreatValue=260`,
  `boardAwarePayment=true`, and `boardAwareOverflowDiscard=true`.

Config-aware paired-seat confirmation:

- A new config-aware confirmation block was run after the
  `effectiveLookaheadConfig` report fix:
  `training/data/models/evaluation/winrate/finalconfirm4-rfp-lookahead-vs-hard-100-summary.json`
  = 71/100 = 71.0%, 100/100 natural, 0 forced, 0 unknown. Both raw reports
  carry 62 effective lookahead config keys and identical promoted defaults.
- Self-audit flagged the raw 50-game seat split as suspiciously uneven:
  `lookahead,hard` was 43/50, while `hard,lookahead` was 28/50. The same 100
  deck seeds were therefore replayed with reversed lineups instead of accepting
  the raw 71% as a standalone claim.
- The paired-seat replay produced four 50-game blocks: 43/50, 22/50, 28/50,
  and 34/50. The resulting dual-seat summary
  `training/data/models/evaluation/winrate/finalconfirm4-paired-rfp-lookahead-vs-hard-200-summary.json`
  = 127/200 = 63.5%, 200/200 natural, 0 forced, 0 unknown, Wilson CI
  ~= 56.63%-69.86%. The gate
  `training/data/models/evaluation/winrate/finalconfirm4-paired-rfp-lookahead-vs-hard-200-gate.json`
  passed all checks.
- Loss analysis for this paired block
  `training/data/models/evaluation/winrate/finalconfirm4-paired-rfp-lookahead-vs-hard-200-loss-analysis.json`
  shows 73 losses: 21 close losses and 52 blowout losses; losses by target
  complete sets were `{0:25, 1:27, 2:21}`.
- The paired replay found 100 unique deck seeds used twice by design: on those
  seeds, lookahead won both seats 29 times, split 69 times, and lost both seats
  only 2 times. This supports the champion while also showing that short
  single-seat blocks are too seat/first-player sensitive for final claims.
- `MixedAiBattleExperimentRunner` now records `initialPlayer`,
  `initialPlayerIndex`, `initialPlayerRole`, and `initialPlayerTeam` per game.
  A smoke report
  `training/data/models/evaluation/winrate/mixed-initial-player-smoke-2x340.json` confirms
  these fields and the 62-key effective config snapshot are written. Future
  confirmation runs should prefer explicit same-seed dual-seat blocks and
  inspect initial-player balance before treating a raw win-rate swing as a
  policy improvement.
- Combined evidence including the original 600 games, the fresh 200-game
  confirmation, and the new paired-seat 200-game confirmation is
  `training/data/models/evaluation/winrate/fixedPaymentDp-plus-finalconfirm234-paired-rfp-lookahead-vs-hard-1000-summary.json`
  = 645/1000 = 64.5%, 1000/1000 natural, 0 forced, 0 unknown, 95% CI
  ~= 61.48%-67.41%. This 1000-game rollup includes the intentional dual-seat
  duplicate deck seeds from `finalconfirm4`, so use it as aggregate robustness
  evidence, not as 1000 independent deck seeds.

Additional paired-seat confirmation:

- A further independent 50-seed dual-seat block was run with the same explicit
  seed-list protocol and no overlap with prior mixed reports:
  `training/data/models/evaluation/winrate/finalconfirm5-paired-rfp-lookahead-vs-hard-100-summary.json`
  = 59/100 = 59.0%, 100/100 natural, 0 forced, 0 unknown. The two seat orders
  were 28/50 and 31/50, and both raw reports carried 62 identical effective
  lookahead config keys.
- This 100-game block failed the strict CI-low gate by a narrow margin:
  `training/data/models/evaluation/winrate/finalconfirm5-paired-rfp-lookahead-vs-hard-100-gate.json`
  had Wilson CI ~= 49.20%-68.13%. It is therefore supporting evidence, not an
  independent proof block.
- Its paired seed split was still favorable: among 50 deck seeds, lookahead won
  both seats 11 times, split 37 times, and lost both seats 2 times. Initial
  player fields were present and did not indicate a missing-recording bug.
- Combining `finalconfirm4` and `finalconfirm5` paired-seat confirmations gives
  `training/data/models/evaluation/winrate/finalconfirm45-paired-rfp-lookahead-vs-hard-300-summary.json`
  = 186/300 = 62.0%, 300/300 natural, 0 forced, 0 unknown, 95% CI
  ~= 56.39%-67.31%. The paired-only gate
  `training/data/models/evaluation/winrate/finalconfirm45-paired-rfp-lookahead-vs-hard-300-gate.json`
  passed all checks.
- The newest paired block loss analysis
  `training/data/models/evaluation/winrate/finalconfirm5-paired-rfp-lookahead-vs-hard-100-loss-analysis.json`
  shows 41 losses: 8 close losses and 33 blowout losses; losses by target
  complete sets were `{0:20, 1:13, 2:8}`. This remains consistent with the
  earlier residual-failure pattern.
- Combined aggregate robustness evidence is now
  `training/data/models/evaluation/winrate/fixedPaymentDp-plus-finalconfirm2345-paired-rfp-lookahead-vs-hard-1100-summary.json`
  = 704/1100 = 64.0%, 1100/1100 natural, 0 forced, 0 unknown, 95% CI
  ~= 61.12%-66.78%; the matching 1100-game gate passed. This rollup contains
  950 unique deck seeds: 800 used once and 150 intentionally used twice for
  paired-seat replay. Treat it as mixed aggregate robustness evidence, while
  using the 300-game paired-only summary as the cleaner seat-balanced
  confirmation.
- A third independent paired-seat block, `finalconfirm6`, used 50 new deck
  seeds with no overlap against prior mixed reports:
  `training/data/models/evaluation/winrate/finalconfirm6-paired-rfp-lookahead-vs-hard-100-summary.json`
  = 68/100 = 68.0%, 100/100 natural, 0 forced, 0 unknown, Wilson CI
  ~= 58.34%-76.33%. Both the standard gate
  `training/data/models/evaluation/winrate/finalconfirm6-paired-rfp-lookahead-vs-hard-100-gate.json`
  and the paired-seat audit
  `training/data/models/evaluation/winrate/finalconfirm6-paired-seat-audit.json` passed.
- `finalconfirm6` paired audit shows the protocol was clean: 50 unique deck
  seeds, exactly 2 rows per seed, balanced initial teams (`hard=50`,
  `lookahead=50`), identical 62-key effective config digest, and no forced or
  unknown endings. On those 50 seeds, lookahead split 32 seeds and won both
  seats 18 seeds; it lost both seats 0 times.
- `finalconfirm6` loss analysis
  `training/data/models/evaluation/winrate/finalconfirm6-paired-rfp-lookahead-vs-hard-100-loss-analysis.json`
  shows 32 losses: 6 close losses and 26 blowout losses; losses by target
  complete sets were `{0:14, 1:12, 2:6}`.
- Pair-only evidence across `finalconfirm4+5+6` is now
  `training/data/models/evaluation/winrate/finalconfirm456-paired-rfp-lookahead-vs-hard-400-summary.json`
  = 254/400 = 63.5%, 400/400 natural, 0 forced, 0 unknown, 95% CI
  ~= 58.67%-68.07%; `training/data/models/evaluation/winrate/finalconfirm456-paired-seat-audit.json`
  also passes with 200 deck seeds, exactly 2 rows per seed, 58 double-seat wins,
  138 splits, and only 4 double-seat losses.
- The latest aggregate rollup is
  `training/data/models/evaluation/winrate/fixedPaymentDp-plus-finalconfirm23456-paired-rfp-lookahead-vs-hard-1200-summary.json`
  = 772/1200 = 64.33%, 1200/1200 natural, 0 forced, 0 unknown, 95% CI
  ~= 61.58%-66.99%; its gate passed. This rollup contains 1000 unique deck
  seeds: 800 used once and 200 intentionally used twice for paired-seat replay.

Paired-seat audit tooling:

- `training/scripts/summarize_paired_seat_reports.py` now turns the paired-seat protocol
  into a reusable JSON audit. It checks that every deck seed has the expected
  number of seat rows, effective lookahead configs are present and identical,
  all games ended naturally, and the team win-rate Wilson CI clears the hard
  baseline.
- The newest 100-game block audit
  `training/data/models/evaluation/winrate/finalconfirm5-paired-seat-audit.json` correctly
  fails only on the CI-low check (49.20% > 50.00% is false), matching the
  normal gate result and preventing weak short blocks from being overclaimed.
- The 300-game paired-only audit
  `training/data/models/evaluation/winrate/finalconfirm45-paired-seat-audit.json` passes:
  150 deck seeds each have exactly two rows, all reports have the same 62-key
  effective config digest, 300/300 games are natural, and CI low is 56.39%.
  The audit also exposes a useful data-quality boundary: `finalconfirm4` reports
  predate the `initialPlayer` fields, so their initial-player bucket is
  `unknown`; `finalconfirm5` records balanced initial teams.

## 2026-05-27 Payment/Overflow Promotion to Default

Purpose: re-evaluate the previously default-off auxiliary decision rules after
the individual `boardAwarePayment` and `boardAwareOverflowDiscard` screens each
looked too small in isolation.

Candidate:

- `paymentOverflowA-rfp` combines:
  - `monopoly.search.boardAwarePayment=true`
  - `monopoly.search.boardAwareOverflowDiscard=true`
  - All promoted `buildingA` play-decision parameters unchanged.
- Unlike remaining-turn rollout, this candidate does not run extra simulated
  turns. It changes only payment and overflow discard choices, so it is a
  lower-cost default candidate.

Evaluation:

- First 100 matched seeds:
  `training/data/models/evaluation/winrate/paymentOverflowA-rfp-lookahead-vs-hard-100-summary.json`
  = 71/100, 100/100 natural, 0 forced/unknown.
  Matched comparison:
  `training/data/models/evaluation/winrate/paymentOverflowA-rfp-vs-buildingA-rfp-first100-seed-comparison.json`
  = net +1, changedGames 5.
- Full 600 matched seed expansion:
  `training/data/models/evaluation/winrate/paymentOverflowA-rfp-lookahead-vs-hard-600-summary.json`
  = 402/600 = 67.0%, 95% CI = 63.14%-70.64%,
  600/600 natural, 0 forced, 0 unknown.
- Hard gate:
  `training/data/models/evaluation/winrate/paymentOverflowA-rfp-lookahead-vs-hard-600-gate.json`
  passed all checks, including CI low above 50%.
- Summary-level comparison against old `buildingA`:
  `training/data/models/evaluation/winrate/paymentOverflowA-rfp-vs-buildingA-600-summary-comparison.json`
  shows +3.0pp absolute lift, but the unpaired two-proportion p approximation
  is weak (p ~= 0.274). This is conservative because it ignores matched seeds.
- Matched seed comparison against old `buildingA`:
  `training/data/models/evaluation/winrate/paymentOverflowA-rfp-vs-buildingA-rfp-600-seed-comparison.json`
  covers all 600 common games, with 402 variant wins vs 384 baseline wins,
  net +18 and changedGames 50.
  Paired flips are 34 `L->W` vs 16 `W->L`, exact two-sided sign-test
  p ~= 0.0153 and McNemar continuity p ~= 0.0162.
- Default smoke after promotion:
  `training/data/models/evaluation/winrate/promotedPaymentOverflowDefault-smoke-lookahead-vs-hard-40-summary.json`
  = 30/40, 40/40 natural, 0 forced/unknown.
  `training/data/models/evaluation/winrate/promotedPaymentOverflowDefault-smoke-vs-paymentOverflowA-first40-seed-comparison.json`
  has net +0 and changedGames 0 against explicit `paymentOverflowA` on the
  same 40 seeds, confirming the default flags reproduce the candidate.

Implementation:

- Promoted defaults:
  - `monopoly.search.boardAwarePayment=true`
  - `monopoly.search.boardAwareOverflowDiscard=true`
- The JVM flags remain available, so either behavior can still be ablated with
  explicit `-D...=false`.
- `training/scripts/compare_mixed_reports_by_seed.py` now emits paired sign-test and
  McNemar-style p approximations in `pairedStats`, because matched-seed flips
  are the relevant evidence for this kind of promotion.

Self-checks:

- An initial gate command failed because it was launched in parallel before the
  summary writer had created the 600-game summary. The gate was rerun serially
  after the file existed; the final gate artifact is valid.
- Two 20-game default smoke runs failed when launched in parallel with Maven
  tests, then succeeded serially. A 1-game `-e` debug run also succeeded. Treat
  the earlier failure as a Maven/`target/` concurrency artifact, and avoid
  parallel Maven executions for final verification.
- Runtime cost is higher than plain `buildingA`, especially in large 200-game
  blocks, because board-aware payment enumerates payment combinations. The
  gain is large enough on 600 matched seeds to promote, but future work should
  watch latency and consider pruning if it becomes user-visible.

Interpretation:

- Promote `paymentOverflowA-rfp` as the new default local strong policy. The
  evidence is stronger than the old `buildingA` promotion evidence because it
  is a 600-game matched-seed lift with statistically favorable paired flips,
  not just an independent natural win-rate improvement.
- The goal is still not fully complete: this is clearly stronger than hard on
  the local evidence, but the user asked for continued optimization toward a
  very strong local AI. Next work should use the promoted default as the new
  baseline and continue trace/replay-backed improvements.

## 2026-05-27 Wild Color Replay and Summary Probe

Purpose: isolate whether the remaining `DEPLOY_WILD` signal is a target-color
choice problem rather than a broad action choice problem.

Data selection:

- Added `training/scripts/select_wild_color_replay_rows.py` to select `PLAY_CARD`
  rows with mementos and multi-color wild deployment candidates.
- Added `training/scripts/analyze_wild_color_replay.py` to summarize replay reports by
  wild target color, source/hard target choice, forced candidates, and simple
  color heuristics.
- Across four memento traces
  (`buildingA-memento-trace-seat1/seat2-30` and
  `rollout-gate-memento20-seat1/seat2`), the selector scanned 3351 rows,
  found 771 multi-wild rows, and only 75 rows where source and hard selected
  different wild colors. A loss-only filtered slice had 18 eligible rows.

Replay evidence:

- Full disagreement replay:
  `training/data/models/evaluation/counterfactual/wild-color-replay-75-hardroll-report.json`
  from `training/data/distillation/wild-color-replay-80.jsonl`
  produced 75 decisions, 73 informative, 0 hard-choice resolution errors,
  0 candidate errors, 0 incomplete candidates, and 115 forced candidates.
  Source beat hard 34 / 20 / 21 (source better / same / hard better), with
  natural wins 42 vs 37.
- Wild-only analysis:
  `training/data/models/evaluation/counterfactual/wild-color-replay-75-analysis.json`
  found 66 informative wild decisions and 60 clean wild decisions. Clean
  source vs hard was 30 / 12 / 18. The best simple heuristic
  (`avoid_overfull_then_complete`) matched only 36/75 top choices.
- Loss-only replay:
  `training/data/models/evaluation/counterfactual/wild-color-loss-replay-18-hardroll-report.json`
  from `training/data/distillation/wild-color-loss-replay-60.jsonl`
  produced 18 decisions, 17 informative, and 21 forced candidates. Source vs
  hard was 6 / 4 / 8, with natural wins 0 vs 1.

Implementation probe and self-check:

- Tried exposing wild target progress in candidate summaries. The first version
  used `completionScore=` and the second used `wildProgressScore=`.
  Both changed default gameplay on the same first-100 matched seeds:
  `wildSummaryA-rfp` and `wildSummaryB-rfp` were each 68/100 versus hard,
  net -2 versus `buildingA`, with 4 changed games. This showed that even
  diagnostic candidate text can affect runtime scoring and must not be changed
  on the default path without a matched seed screen.
- The progress summary is now behind
  `monopoly.ai.includeWildProgressInSummary=false` and the default text remains
  exactly `Deploy wild property as COLOR.`.
- Default-off sanity check:
  `training/data/models/evaluation/winrate/wildProgressSummaryOff-rfp-lookahead-vs-hard-100-summary.json`
  is 70/100, 100/100 natural, 0 forced/unknown. Matched comparison to
  `buildingA` first 100:
  `training/data/models/evaluation/winrate/wildProgressSummaryOff-rfp-vs-buildingA-rfp-first100-seed-comparison.json`
  is net +0 with changedGames 0.

Interpretation:

- Do not promote a wild color rule, hard fallback, or summary-field change.
  The full disagreement slice favors source over hard, the loss-only slice
  weakly favors hard, and simple target-color heuristics are not accurate
  enough to justify a static rule.
- Keep the selector/analyzer and opt-in summary field as diagnostics only.
  Future work should use state-conditioned or learned target-color selection
  with clean replay labels, not global wild-color heuristics.

## 2026-05-27 Wild Overfull Deployment Screen

Purpose: follow up the rollout-gate replay labels, where the strict clean
subset was dominated by `DEPLOY` and many errors were wild color/target
choices rather than broad action-type choices.

Local evidence review:

- The combined rollout-gate clean label file has only 31 rows, so it is still
  too small for a learned gate.
- Among those 31 rows, 19 best labels are `DEPLOY_WILD`; however the pattern is
  not a simple "deploy wild to the highest-progress color" rule. Some best
  labels choose a fresh or short color over an already complete/overfull color.
- A narrow, interpretable hypothesis is therefore only: penalize deploying a
  wild into a color that is already complete before the wild is deployed. This
  preserves default behavior when disabled and does not touch ordinary property
  deployment.

Implementation:

- Added default-off `monopoly.search.wildOverfullSetPenalty=0`.
- The adjustment applies only to `DEPLOY` requests whose card is a
  `PropertyWildCard` in hand and whose target color already has at least the
  required effective count before deployment.
- Self-check: the first screen used seed bases `267001/267051`, which did not
  overlap with the existing matched `buildingA` reports. It is retained only as
  an invalid seed-protocol artifact, not as strength evidence:
  `wildOverfull800A-rfp` = 55/100, 100/100 natural.

Valid screen:

- `wildOverfull800B-rfp`:
  - `monopoly.search.wildOverfullSetPenalty=800`
  - buildingA parameters unchanged.
  - Reports:
    - `training/data/models/evaluation/winrate/mixed-wildOverfull800B-rfp-lookahead-vs-hard-seat1-50x340.json`
    - `training/data/models/evaluation/winrate/mixed-wildOverfull800B-rfp-lookahead-vs-hard-seat2-50x340.json`
  - Summary:
    `training/data/models/evaluation/winrate/wildOverfull800B-rfp-lookahead-vs-hard-100-summary.json`
    = 70/100, 100/100 natural, 0 forced/unknown.
  - Matched comparison to `buildingA` first 100:
    `training/data/models/evaluation/winrate/wildOverfull800B-rfp-vs-buildingA-rfp-first100-seed-comparison.json`
    = net +0, changedGames 0.
  - Matched comparison to old `wildA`:
    `training/data/models/evaluation/winrate/wildOverfull800B-rfp-vs-wildA-rfp-first100-seed-comparison.json`
    = net +0, changedGames 0.

Interpretation:

- Do not promote or expand `wildOverfull800B-rfp`; on the matched first-100
  seed slice it is behaviorally inert at the game-outcome level.
- Keep `monopoly.search.wildOverfullSetPenalty` as a trace/counterfactual
  diagnostic knob only. The remaining wild signal likely needs richer
  state-conditioned color selection or targeted replay, not a global penalty.

## 2026-05-27 Rollout Override Margin Screen

Purpose: revisit the only recent direction that actually flipped multiple
matched games: `rolloutRemainingTurn=true, rolloutPolicy=search`. The earlier
plain rollout screen was 71/100 versus hard and net +1 over matched
`buildingA`, but it changed 11 games with both positive and negative flips.
The goal here was to keep only high-confidence rollout overrides.

Research/context check:

- Information-set search work on imperfect-information games warns that naive
  determinized rollout can be brittle because it reasons from a sampled hidden
  state. We already avoid playing newly drawn hidden cards in rollout, but the
  warning still applies: use rollout as a conservative perturbation, not an
  unconditional replacement for one-ply search.
- DAgger-style imitation learning motivates evaluating states visited by the
  current policy. The existing trace/replay evidence supports that direction,
  but the current strict clean labels are still too sparse after forced-candidate
  filtering, so this screen stays rule/gate-based.

Implementation:

- Added default-preserving rollout gate:
  `monopoly.search.rolloutOverrideMargin=0`.
- When `rolloutRemainingTurn=true` and the margin is positive, the rollout
  choice may replace the immediate one-ply best only if its rollout score lead
  over the immediate-best candidate is at least the configured margin.
- Self-checks:
  - The first implementation compared rollout score to immediate score
    directly, which mixes horizons. Those reports are retained only as
    exploratory artifacts.
  - The next implementation compared the two candidates in rollout scoring
    space, but immediate scoring accidentally added the same candidate
    adjustment twice. That `om3000b` report is also treated as exploratory.
  - The final `om3000c` implementation compares rollout scores in the same
    scoring space and computes the immediate-best baseline without duplicate
    candidate adjustment.
  - Immediate scoring is only computed when both `rolloutRemainingTurn=true`
    and `rolloutOverrideMargin>0`, so default `buildingA` does not pay the
    extra cost.

Screens:

- Exploratory pre-fix screens:
  - `rollremainsearch-om3000-rfp` and `rollremainsearch-om6000-rfp` both
    reached 71/100 but used the cross-horizon gate and are retained only as
    exploratory artifacts.
- Exploratory same-horizon screen:
  - `rollremainsearch-om3000b-rfp` reached 71/100, but the immediate baseline
    included duplicate candidate adjustment. Keep its trace/diff artifacts for
    diagnosis only; do not use it as promotion evidence.
- Corrected screen `rollremainsearch-om3000c-rfp`:
  - `monopoly.search.rolloutRemainingTurn=true`
  - `monopoly.search.rolloutPolicy=search`
  - `monopoly.search.rolloutOverrideMargin=3000`
  - Reports:
    - `training/data/models/evaluation/winrate/mixed-rollremainsearch-om3000c-rfp-lookahead-vs-hard-seat1-50x340.json`
    - `training/data/models/evaluation/winrate/mixed-rollremainsearch-om3000c-rfp-lookahead-vs-hard-seat2-50x340.json`
  - Summary:
    `training/data/models/evaluation/winrate/rollremainsearch-om3000c-rfp-lookahead-vs-hard-100-summary.json`
    = 70/100, 100/100 natural, 0 forced/unknown.
  - Hard gate:
    `training/data/models/evaluation/winrate/rollremainsearch-om3000c-rfp-lookahead-vs-hard-100-gate.json`
    passed.
  - Matched comparison to `buildingA`:
    `training/data/models/evaluation/winrate/rollremainsearch-om3000c-rfp-vs-buildingA-rfp-first100-comparison.json`
    = net +0, changedGames 6.
  - Matched comparison to plain rollout:
    `training/data/models/evaluation/winrate/rollremainsearch-om3000c-rfp-vs-rollremainsearch-rfp-first100-comparison.json`
    = net -1, changedGames 7.

Interpretation:

- Do not promote or expand `rollremainsearch-om3000c-rfp`. The valid gate
  passes the hard gate, but it does not beat matched `buildingA` on the same
  100 seeds and slightly underperforms the plain rollout perturbation.
- The next useful rollout direction needs state conditions or a learned
  override model; a simple global margin is not enough.

Follow-up state-condition screen:

- Replayed the six valid `om3000c` changed seeds with outcome traces:
  - W->L: `202605267003`, `202605267012`, `202605268044`.
  - L->W: `202605267038`, `202605268021`, `202605268022`.
  - Trace reports are under
    `training/data/models/evaluation/winrate/trace-om3000c-*-report.json`; trace rows are
    under `training/data/models/traces/rollremain-om3000c-diff-*.jsonl`.
- Self-audit bug: `candidateEffect(...)` did not recognize summaries beginning
  with `Deploy ...` as `DEPLOY`. This meant effect-based gates could silently
  miss deployed-property choices. Fixed the parser and added unit coverage.
- Added default-preserving diagnostic gate:
  `monopoly.search.rolloutOverrideAllowedEffects`.
  Empty default means unrestricted, so default `buildingA` behavior is
  unchanged.
- Invalidated diagnostic reports:
  - `rollremainsearch-deployonly-om3000-rfp`
  - `rollremainsearch-norent-om3000-rfp`
  Both were run before the `DEPLOY` effect parser fix.
- Corrected no-rent screen `rollremainsearch-norent-om3000-v2-rfp`:
  - Allowed effects:
    `DEPLOY,PASS_GO,DEBT_COLLECTOR,BIRTHDAY,STEAL_PROPERTY,FORCED_DEAL,DEAL_BREAKER,HOUSE,HOTEL,DEPOSIT`
  - Summary:
    `training/data/models/evaluation/winrate/rollremainsearch-norent-om3000-v2-rfp-lookahead-vs-hard-100-summary.json`
    = 70/100, 100/100 natural, 0 forced/unknown.
  - Gate:
    `training/data/models/evaluation/winrate/rollremainsearch-norent-om3000-v2-rfp-lookahead-vs-hard-100-gate.json`
    passed.
  - Matched comparison to `buildingA`:
    `training/data/models/evaluation/winrate/rollremainsearch-norent-om3000-v2-rfp-vs-buildingA-rfp-first100-comparison.json`
    = net +0, changedGames 6.
  - Matched comparison to `om3000c`:
    `training/data/models/evaluation/winrate/rollremainsearch-norent-om3000-v2-rfp-vs-om3000c-rfp-first100-comparison.json`
    = net +0, changedGames 0.
- Because the `DEPLOY` parser bug also affected the earlier hard-rent fallback
  diagnostic, reran it as `hardRentFallback3000B-rfp`:
  - Summary:
    `training/data/models/evaluation/winrate/hardRentFallback3000B-rfp-lookahead-vs-hard-100-summary.json`
    = 68/100, 100/100 natural, 0 forced/unknown.
  - Gate:
    `training/data/models/evaluation/winrate/hardRentFallback3000B-rfp-lookahead-vs-hard-100-gate.json`
    passed.
  - Matched comparison to `buildingA`:
    `training/data/models/evaluation/winrate/hardRentFallback3000B-rfp-vs-buildingA-rfp-first100-comparison.json`
    = net -2, changedGames 4.
  - Matched comparison to old `hardRentFallback3000A-rfp`:
    `training/data/models/evaluation/winrate/hardRentFallback3000B-rfp-vs-hardRentFallback3000A-rfp-first100-comparison.json`
    = net -1, changedGames 1.
- High-margin screen `rollremainsearch-om9000-v2-rfp`:
  - Summary:
    `training/data/models/evaluation/winrate/rollremainsearch-om9000-v2-rfp-lookahead-vs-hard-100-summary.json`
    = 70/100, 100/100 natural, 0 forced/unknown.
  - Matched comparison to `buildingA`:
    `training/data/models/evaluation/winrate/rollremainsearch-om9000-v2-rfp-vs-buildingA-rfp-first100-comparison.json`
    = net +0, changedGames 6.
  - Matched comparison to `om3000c`:
    `training/data/models/evaluation/winrate/rollremainsearch-om9000-v2-rfp-vs-om3000c-rfp-first100-comparison.json`
    = net +0, changedGames 0.
- Added rollout gate trace metadata for future replay-backed state gates:
  `rolloutRawBest*`, `rolloutImmediateBest*`, `rolloutGateChoice*`,
  `rolloutOverrideScoreGap`, `rolloutOverrideEffectAllowed`,
  `rolloutOverrideSameCandidate`, and `rolloutOverrideUsed`. Smoke artifacts:
  `training/data/models/evaluation/winrate/rollout-gate-meta-smoke-report.json` and
  `training/data/models/traces/rollout-gate-meta-smoke.jsonl`.
- Self-audit bug: generic deposit summaries were initially blank in the new
  metadata, while action-card deposits still need to retain their card effect
  such as `PASS_GO`. Fixed summary parsing and added unit coverage for both
  generic `DEPOSIT` and action-card deposit effects.
- Collected a 20-game memento trace for rollout-gate disagreements:
  - Trace:
    `training/data/models/traces/rollout-gate-memento20-seat1.jsonl`
  - Report:
    `training/data/models/evaluation/winrate/rollout-gate-memento20-seat1-report.json`
    = 10/20, 20/20 natural, 0 forced/unknown. This is a sampling trace, not a
    strength claim.
  - Selection:
    `training/data/models/evaluation/winrate/rollout-gate-memento20-seat1-selection.json`
    selected 40 rows from 75 raw/immediate disagreements; all selected rows
    had mementos.
  - Replay:
    `training/data/models/evaluation/counterfactual/rollout-gate-memento20-seat1-replay-40-hardroll-report.json`.
  - Analysis:
    `training/data/models/evaluation/counterfactual/rollout-gate-memento20-seat1-replay-40-analysis.json`
    = raw rollout best versus immediate best 11/12/17 (better/worse/same).
    On the clean subset where the selected raw/immediate/gate candidates did
    not hit `COUNTERFACTUAL_SNAPSHOT_LIMIT`, the split was 8/11/17.
    The replay itself was technically clean enough for diagnosis
    (0 candidate errors, 0 incomplete candidates), but still had
    `COUNTERFACTUAL_SNAPSHOT_LIMIT` on some selected candidates, so do not use
    it as supervised labels without stricter filtering.
  - Strict counterfactual-label selection:
    `training/data/models/evaluation/counterfactual/rollout-gate-memento20-seat1-clean-label-selection.json`
    selected only 10 rows after rejecting forced candidates and small/tied
    gaps. This is too sparse and too mixed by effect to train or enable a new
    state gate.
- Added diagnostic selector/analysis scripts:
  - `training/scripts/select_rollout_gate_replay_rows.py`
  - `training/scripts/analyze_rollout_gate_replay.py`
- Added default-preserving transition gate:
  `monopoly.search.rolloutOverrideAllowedTransitions`. Empty default means all
  transitions remain allowed; configured entries use `IMMEDIATE->ROLLOUT`, for
  example `PASS_GO->DEPLOY`.
- Invalidated transition screen `rollpassdeploy3000-rfp`:
  - Self-audit bug: the shell interpreted the unquoted `>` in
    `PASS_GO->DEPLOY` as output redirection, leaving
    `rolloutOverrideAllowedTransitions=PASS_GO-` in the report and writing
    runner stdout to an accidental local `DEPLOY` file. Do not use this report
    as `PASS_GO->DEPLOY` evidence.
- Corrected narrow transition screen `rollpassdeploy3000b-rfp`:
  - Configuration:
    `rolloutRemainingTurn=true`, `rolloutPolicy=search`,
    `rolloutOverrideMargin=3000`,
    `rolloutOverrideAllowedTransitions=PASS_GO->DEPLOY`.
  - Summary:
    `training/data/models/evaluation/winrate/rollpassdeploy3000b-rfp-lookahead-vs-hard-100-summary.json`
    = 70/100, 100/100 natural, 0 forced/unknown.
  - Matched comparison to `om3000c`:
    `training/data/models/evaluation/winrate/rollpassdeploy3000b-rfp-vs-om3000c-rfp-first100-comparison.json`
    = net +0, changedGames 0.
  - Matched comparison to `om9000-v2`:
    `training/data/models/evaluation/winrate/rollpassdeploy3000b-rfp-vs-om9000-v2-rfp-first100-comparison.json`
    = net +0, changedGames 0.
  - A direct comparison to `buildingA` could not be regenerated from raw
    `buildingA` first-100 mixed reports because those exact report files are
    not present in this checkout; existing evidence already shows `om3000c`
    was net +0 versus `buildingA`, so this candidate is negative relative to
    that line as well. The corrected transition gate is therefore neutral,
    not negative, relative to `buildingA`.
- Expanded rollout-gate replay sample:
  - Added seat-2 memento trace:
    `training/data/models/traces/rollout-gate-memento20-seat2.jsonl`
    and report
    `training/data/models/evaluation/winrate/rollout-gate-memento20-seat2-report.json`
    = 11/20, 20/20 natural, 0 forced/unknown. This is also sampling only.
  - Combined selection:
    `training/data/models/evaluation/winrate/rollout-gate-memento40-both-selection-allrows.json`
    selected all 137 raw/immediate disagreements from 1745 traced rows.
  - Full replay:
    `training/data/models/evaluation/counterfactual/rollout-gate-memento40-both-replay-137-hardroll-report.json`
    = 137 decisions, 0 candidate errors, 0 incomplete candidates, but
    190 forced candidates.
  - Full replay analysis:
    `training/data/models/evaluation/counterfactual/rollout-gate-memento40-both-replay-137-analysis.json`
    = raw rollout best versus immediate best 39/39/59 overall and
    31/34/53 on the clean selected-candidate subset.
  - Strict label selection:
    `training/data/models/evaluation/counterfactual/rollout-gate-memento40-both-clean-label-selection-all137.json`
    selected 31 rows, with best effects `DEPLOY=22`, `DEPOSIT=4`,
    `STEAL_PROPERTY=3`, `FORCED_DEAL=1`, `RENT_DUAL=1`.
    This is still too small for a new learned gate, and the signal now points
    more toward wild deployment target/color quality than toward trusting
    rollout globally.

Interpretation update:

- Blocking rent effects alone does not separate the positive and negative
  rollout flips; corrected no-rent gating produced exactly the same 100-seed
  outcomes as unrestricted `om3000c`.
- The repaired hard-rent fallback is worse than `buildingA`; do not promote it.
- Raising the rollout override margin from 3000 to 9000 does not remove the six
  changed games, so simple scalar margin tuning is exhausted.
- The corrected transition probe did not validate `PASS_GO->DEPLOY` as an
  improvement: it exactly reproduces `om3000c`/`om9000-v2` on the first 100
  seeds, so it has no promotion value.
- Expanding the memento replay pool did not make rollout trustable: raw versus
  immediate is almost balanced, and strict labels are sparse.
- Keep `rolloutOverrideAllowedEffects` and
  `rolloutOverrideAllowedTransitions` only as diagnostic switches. The next
  viable step is narrower target/color learning for wild deployment and swing
  actions, not another broad effect whitelist, transition whitelist, or global
  rollout margin.

## 2026-05-27 Hard Rent Fallback Negative Screen

Purpose: avoid another blind scalar sweep by checking whether the remaining
loss pattern contains a narrow, action-conditional bug. A win/loss trace
contrast over the 120-game default trace plus the 60-game memento trace showed
that many tempting losing-session patterns are not usable rules:

- High-margin `DEPLOY->DEPLOY` overrides are win-skewed, not loss-skewed.
  At `margin >= 2000`, they appear in 36 winning rows versus 8 losing rows.
- `BIRTHDAY->DEPLOY` and `RENT_DUAL->DEPLOY` appear in both wins and losses,
  so broad "trust hard instead" gates are unsafe.
- The one still-plausible narrow pattern was hard choosing a rent card while
  lookahead chose `DEPLOY` or `PASS_GO`, especially `RENT_DUAL->PASS_GO`.

Implementation:

- Added default-off diagnostic knob:
  `monopoly.search.hardRentFallbackMargin=-1`.
- When enabled, it only falls back to hard if hard's choice is `RENT` or
  `RENT_DUAL`, the lookahead best is `DEPLOY` or `PASS_GO`, and the lookahead
  score lead is below the configured margin.
- Default `buildingA` behavior remains unchanged.

Screens:

- `hardRentFallback3000A-rfp`:
  - `monopoly.search.hardRentFallbackMargin=3000`
  - Reports:
    - `training/data/models/evaluation/winrate/mixed-hardRentFallback3000A-rfp-lookahead-vs-hard-seat1-50x340.json`
    - `training/data/models/evaluation/winrate/mixed-hardRentFallback3000A-rfp-lookahead-vs-hard-seat2-50x340.json`
  - Summary:
    `training/data/models/evaluation/winrate/hardRentFallback3000A-rfp-lookahead-vs-hard-100-summary.json`
    = 69/100, 100/100 natural, 0 forced/unknown.
  - Hard gate:
    `training/data/models/evaluation/winrate/hardRentFallback3000A-rfp-lookahead-vs-hard-100-gate.json`
    passed.
  - Matched comparison:
    `training/data/models/evaluation/winrate/hardRentFallback3000A-rfp-vs-buildingA-rfp-first100-comparison.json`
    = net -1, changedGames 3.
- `hardRentFallback6000A-rfp`:
  - `monopoly.search.hardRentFallbackMargin=6000`
  - Reports:
    - `training/data/models/evaluation/winrate/mixed-hardRentFallback6000A-rfp-lookahead-vs-hard-seat1-50x340.json`
    - `training/data/models/evaluation/winrate/mixed-hardRentFallback6000A-rfp-lookahead-vs-hard-seat2-50x340.json`
  - Summary:
    `training/data/models/evaluation/winrate/hardRentFallback6000A-rfp-lookahead-vs-hard-100-summary.json`
    = 69/100, 100/100 natural, 0 forced/unknown.
  - Hard gate:
    `training/data/models/evaluation/winrate/hardRentFallback6000A-rfp-lookahead-vs-hard-100-gate.json`
    passed.
  - Matched comparison:
    `training/data/models/evaluation/winrate/hardRentFallback6000A-rfp-vs-buildingA-rfp-first100-comparison.json`
    = net -1, changedGames 3, same flipped seed set as the 3000 variant.

Interpretation:

- Do not promote or expand hard-rent fallback. It remains stronger than hard,
  but it is worse than matched `buildingA` on the first 100-game slice.
- The trace contrast was useful as a self-check: several attractive losing
  patterns were rejected because they also appear frequently in wins.
- A summary-dependent gate command raced the summary writer once during the
  `6000` run; the gate was rerun successfully after the summary existed.

## 2026-05-27 Property Count Value Screen

Purpose: test a narrow version of the remaining tempo/property-count failure
pattern without reusing sparse forced-filtered replay labels. The candidate
keeps current `buildingA` defaults but makes the hardcoded property-count
component of `SearchLookaheadAiPlayStrategy.playerValue(...)` configurable.

Implementation:

- Added default-preserving knob:
  `monopoly.search.propertyCountValue=120`.
- Default `buildingA` behavior is unchanged unless the JVM flag is set.

Screens:

- `propertyCount180A-rfp`:
  - `monopoly.search.propertyCountValue=180`
  - Reports:
    - `training/data/models/evaluation/winrate/mixed-propertyCount180A-rfp-lookahead-vs-hard-seat1-50x340.json`
    - `training/data/models/evaluation/winrate/mixed-propertyCount180A-rfp-lookahead-vs-hard-seat2-50x340.json`
  - Summary:
    `training/data/models/evaluation/winrate/propertyCount180A-rfp-lookahead-vs-hard-100-summary.json`
    = 70/100, 100/100 natural, 0 forced/unknown.
  - Hard gate:
    `training/data/models/evaluation/winrate/propertyCount180A-rfp-lookahead-vs-hard-100-gate.json`
    passed.
  - Matched comparison:
    `training/data/models/evaluation/winrate/propertyCount180A-rfp-vs-buildingA-rfp-first100-comparison.json`
    = net +0, changedGames 0.
- `propertyCount260A-rfp`:
  - `monopoly.search.propertyCountValue=260`
  - Reports:
    - `training/data/models/evaluation/winrate/mixed-propertyCount260A-rfp-lookahead-vs-hard-seat1-50x340.json`
    - `training/data/models/evaluation/winrate/mixed-propertyCount260A-rfp-lookahead-vs-hard-seat2-50x340.json`
  - Summary:
    `training/data/models/evaluation/winrate/propertyCount260A-rfp-lookahead-vs-hard-100-summary.json`
    = 70/100, 100/100 natural, 0 forced/unknown.
  - Hard gate:
    `training/data/models/evaluation/winrate/propertyCount260A-rfp-lookahead-vs-hard-100-gate.json`
    passed.
  - Matched comparison:
    `training/data/models/evaluation/winrate/propertyCount260A-rfp-vs-buildingA-rfp-first100-comparison.json`
    = net +0, changedGames 0.

Interpretation:

- Do not promote either property-count variant. Both are clearly stronger than
  hard on this first matched 100-game slice, but neither changes a single
  natural winner versus matched `buildingA`.
- This suggests the remaining blowout-loss pattern is not fixed by a simple
  global increase to the terminal board value of raw property count. Future
  work should focus on action-conditional rules or better replay/evaluation
  termination, not another scalar-only property-count sweep.
- Self-check: a first gate attempt used stale argument names and one
  `propertyCount260A` gate attempt raced the summary writer in parallel; both
  were rerun successfully after the summaries existed.

## 2026-05-27 BuildingA Core Replay Force-End Audit

Purpose: continue optimizing the current default `buildingA` by returning to
core `PLAY_CARD` counterfactual replay, now that forced-candidate filtering is
mandatory. The goal was to see whether loss-state replay still yields enough
clean labels for a narrow action gate or gameplay screen.

Replayed old core reports with the fixed `CounterfactualReplayRunner`:

- `training/data/models/evaluation/counterfactual/buildingA-v4-losing-tactical-replay-48-all-forcedfix.json`
  from `training/data/distillation/buildingA-v4-losing-tactical-replay-48-all.jsonl`.
  - 48 decisions, 46 informative, 0 candidate errors, 115 forced candidates.
  - Strict clean selection:
    `training/data/models/evaluation/counterfactual/buildingA-v4-losing-tactical-forcedfix-clean-selection.json`
    selected 6 rows, all beat hard; best effects were `DEPLOY=3` and
    `DEPOSIT=3`.
- `training/data/models/evaluation/counterfactual/buildingA-low-progress-deploy-replay-40-forcedfix.json`
  from `training/data/distillation/buildingA-low-progress-deploy-replay-40.jsonl`.
  - 40 decisions, 34 informative, 0 candidate errors, 99 forced candidates.
  - Strict clean selection:
    `training/data/models/evaluation/counterfactual/buildingA-low-progress-deploy-forcedfix-clean-selection.json`
    selected 0 rows.

Expanded the replay input using the existing 60-game memento trace:

- Input selection:
  - `training/data/distillation/buildingA-memento60-losing-tactical-replay-80.jsonl`
    selected 70 rows from 1606 `PLAY_CARD` rows.
  - `training/data/distillation/buildingA-memento60-low-progress-deploy-replay-80.jsonl`
    selected 33 low-progress deploy rows.
- Replay reports:
  - `training/data/models/evaluation/counterfactual/buildingA-memento60-losing-tactical-replay-70-forcedfix.json`
    had 70 decisions, 65 informative, 0 candidate errors, 160 forced
    candidates. Source was weaker than hard on this slice:
    sourceBetter 14, hardBetter 20, same 36.
  - `training/data/models/evaluation/counterfactual/buildingA-memento60-low-progress-deploy-replay-33-forcedfix.json`
    had 33 decisions, 25 informative, 0 candidate errors, 71 forced
    candidates. Source and hard were effectively tied: sourceBetter 5,
    hardBetter 4, same 24.
- Strict clean selections:
  - `training/data/models/evaluation/counterfactual/buildingA-memento60-losing-tactical-forcedfix-clean-selection.json`
    selected 7 rows, 6 beat hard. Best effects were `DEPOSIT=3`,
    `DEPLOY=2`, `STEAL_PROPERTY=1`, `DEBT_COLLECTOR=1`.
  - `training/data/models/evaluation/counterfactual/buildingA-memento60-low-progress-deploy-forcedfix-clean-selection.json`
    selected 0 rows.

Self-checks:

- Raising `maxSnapshotsPerCandidate` from 340 to 700 did not reduce forced
  candidates at all:
  - tactical stayed 160/670 forced, all `COUNTERFACTUAL_SNAPSHOT_LIMIT`;
  - low-progress stayed 71/237 forced, all `COUNTERFACTUAL_SNAPSHOT_LIMIT`.
- A one-row probe at 2000 snapshots
  (`training/data/models/evaluation/counterfactual/buildingA-memento60-forcedprobe-one-2000snap.json`)
  still had 16/16 forced candidates. These are not simple 340-snapshot
  truncations; they are unsafe replay labels.
- In most forced rows, source and hard choices were also forced, so ignoring
  only unrelated forced candidates would not safely recover many labels.

Interpretation:

- Do not train a core corrector or add a new action gate from this replay pool.
  After forced-candidate filtering, the label pool is too small and mixed:
  mostly `PASS_GO` deposit, `DEPLOY->DEPLOY`, and isolated target/action
  corrections.
- Do not rerun `passGoDepositBonus` or static low-progress deploy penalties
  from this evidence. Prior gameplay screens already showed these are weak or
  negative, and the new strict labels do not overturn that.
- Default `buildingA` remains unchanged. The next useful direction is a better
  replay termination/evaluation design or a fresh, larger label source, not
  forcing the current sparse clean labels into gameplay parameters.

## 2026-05-27 BuildingA Wild/Rollout Negative Screens

Purpose: continue optimizing the current default `buildingA` without reusing
the contaminated auxiliary counterfactual labels. The focus was narrow
`PLAY_CARD` behavior: wild deployment and whether simulating the remaining
turn can cheaply improve one-ply search.

Local evidence review:

- `buildingA` 600-game loss analysis still shows most losses are blowouts:
  181/216 losses were blowouts, and losing final complete sets were
  `0=99`, `1=82`, `2=35`.
- The 120-game outcome trace audit shows high-margin losing overrides often
  include `DEPLOY->DEPLOY`, `BIRTHDAY->DEPLOY`, `RENT_DUAL->DEPLOY`,
  `STEAL_PROPERTY->STEAL_PROPERTY`, and `RENT_DUAL->PASS_GO`.
- Additional trace slicing rejected two tempting static rules:
  low-margin `DEPLOY->DEPLOY` differences appear in both wins and losses, and
  "already at 2 sets but still deploying non-completion cards" appears much
  more often in winning sessions than losing sessions. A broad deploy rollback
  would likely damage existing winning lines.

Screens:

- `wildA-rfp` reused the existing default-off wild knobs from the older stress
  probe:
  - `monopoly.search.wildDeployPenalty=300`
  - `monopoly.search.wildShortSetPenalty=700`
  - `monopoly.search.wildCompletionPenalty=400`
  - Reports:
    - `training/data/models/evaluation/winrate/mixed-wildA-rfp-lookahead-vs-hard-seat1-50x340.json`
    - `training/data/models/evaluation/winrate/mixed-wildA-rfp-lookahead-vs-hard-seat2-50x340.json`
  - Summary:
    `training/data/models/evaluation/winrate/wildA-rfp-lookahead-vs-hard-100-summary.json`
    = 70/100, 100/100 natural, 0 forced/unknown.
  - Matched comparison:
    `training/data/models/evaluation/winrate/wildA-rfp-vs-buildingA-rfp-first100-seed-comparison.json`
    = net +0, changedGames 0. Do not expand or promote.
- `buildingA-rollremainsearch-rfp` enabled remaining-turn search rollout:
  - `monopoly.search.rolloutRemainingTurn=true`
  - `monopoly.search.rolloutPolicy=search`
  - Reports:
    - `training/data/models/evaluation/winrate/mixed-buildingA-rollremainsearch-rfp-lookahead-vs-hard-seat1-50x340.json`
    - `training/data/models/evaluation/winrate/mixed-buildingA-rollremainsearch-rfp-lookahead-vs-hard-seat2-50x340.json`
  - Summary:
    `training/data/models/evaluation/winrate/buildingA-rollremainsearch-rfp-lookahead-vs-hard-100-summary.json`
    = 71/100, 100/100 natural, 0 forced/unknown.
  - Matched comparison:
    `training/data/models/evaluation/winrate/buildingA-rollremainsearch-rfp-vs-buildingA-rfp-first100-seed-comparison.json`
    = net +1, changedGames 11, with 6 `L->W` and 5 `W->L`.
  - Diff traces for one negative and one positive flip:
    - `training/data/models/traces/buildingA-rollremain-diff-baseline-267003.jsonl`
    - `training/data/models/traces/buildingA-rollremain-diff-rollremain-267003.jsonl`
    - `training/data/models/traces/buildingA-rollremain-diff-baseline-267038.jsonl`
    - `training/data/models/traces/buildingA-rollremain-diff-rollremain-267038.jsonl`
    The negative flip changed a hard/default wild deployment into an ordinary
    ORANGE deploy on a tiny rollout margin; the positive flip changed an early
    `PASS_GO` into a large-margin BROWN deploy. This is useful diagnostic
    evidence but not a clean promotion signal.
- `buildingA-rollremainsearch-m500-rfp` added an existing hard-margin gate:
  - `monopoly.search.hardMargin=500`
  - Same rollout settings as above.
  - Reports:
    - `training/data/models/evaluation/winrate/mixed-buildingA-rollremainsearch-m500-rfp-lookahead-vs-hard-seat1-50x340.json`
    - `training/data/models/evaluation/winrate/mixed-buildingA-rollremainsearch-m500-rfp-lookahead-vs-hard-seat2-50x340.json`
  - Summary:
    `training/data/models/evaluation/winrate/buildingA-rollremainsearch-m500-rfp-lookahead-vs-hard-100-summary.json`
    = 67/100, 100/100 natural, 0 forced/unknown.
  - Matched comparison:
    `training/data/models/evaluation/winrate/buildingA-rollremainsearch-m500-rfp-vs-buildingA-rfp-first100-seed-comparison.json`
    = net -3, changedGames 9. Do not expand or promote.

Interpretation:

- Default `buildingA` remains unchanged.
- The wild penalty path is behaviorally inert on the first matched 100 seeds.
- Remaining-turn rollout can flip games, but the current implementation is a
  high-cost, high-variance perturbation rather than a robust improvement. A
  simple hard-margin gate did not stabilize it.
- Next useful direction should be either replay-backed, state-conditional
  action gates with clean forced-candidate filtering, or a more principled
  multi-step evaluator that avoids tiny-margin rollout changes. Do not promote
  `wildA-rfp`, `buildingA-rollremainsearch-rfp`, or
  `buildingA-rollremainsearch-m500-rfp`.

## 2026-05-26 Board-Aware Overflow Discard Screen

Purpose: test whether `buildingA` loses tempo when hand-overflow discard keeps
low-progress properties ahead of draw/action cards.

Implementation:

- Added a default-off overflow discard experiment:
  `monopoly.search.boardAwareOverflowDiscard=false`.
- When enabled, `SearchLookaheadAiPlayStrategy.chooseOverflowDiscards(...)`
  scores cards by retention value instead of delegating to the hard fallback:
  completing properties and high-impact actions are kept, while first-card
  low-progress properties can be discarded before `PASS_GO` and other tempo
  actions.
- The rollout-only `BlindHardRolloutStrategy` still returns the supplied
  fallback discard choice, so this experiment does not pollute one-ply hard
  rollout behavior.
- Default `buildingA` behavior is unchanged.

Candidate:

- `overflowA-rfp`:
  - `monopoly.search.boardAwareOverflowDiscard=true`
  - Matched random-first seed bases from the `buildingA` first-200 baseline.
- Reports:
  - `training/data/models/evaluation/winrate/mixed-overflowA-rfp-lookahead-vs-hard-seat1-100x340.json`
  - `training/data/models/evaluation/winrate/mixed-overflowA-rfp-lookahead-vs-hard-seat2-100x340.json`
- Summary:
  `training/data/models/evaluation/winrate/overflowA-rfp-lookahead-vs-hard-200-summary.json`.
  - Result: 131/200 = 65.5%, 95% CI = 58.68%-71.74%.
  - 200/200 natural-ended, 0 forced, 0 unknown.
  - Seat split: 73/100 as `lookahead,hard`, 58/100 as `hard,lookahead`.
- Gate:
  `training/data/models/evaluation/winrate/overflowA-rfp-lookahead-vs-hard-200-gate.json`
  passed the hard baseline checks.
- Comparison to matched `buildingA` first-200:
  `training/data/models/evaluation/winrate/overflowA-rfp-vs-buildingA-rfp-first200-comparison.json`
  = +1.0pp, rough p ~= 0.834; promotion comparison failed.
- Per-game matched diff:
  - Seat1 changed only 2 games, both from hard wins to lookahead wins.
  - Seat2 changed 0 games.
- Loss analysis:
  `training/data/models/evaluation/winrate/overflowA-rfp-lookahead-vs-hard-200-loss-analysis.json`.
  - Close losses = 9; blowout losses = 60.
  - Losses by final complete sets: 38 at 0 sets, 22 at 1 set, 9 at 2 sets.

Interpretation:

- Do not promote `overflowA-rfp`. It is still clearly stronger than hard on
  this 200-game screen, but the matched lift over `buildingA` is only 2 games
  out of 200 and fails both the practical +3pp threshold and the rough
  significance threshold.
- Keep the default-off overflow scorer and tests as a diagnostic/ablation
  tool; the empirical effect is too rare under matched seeds to justify making
  it part of the default champion.
- Self-check: all 200 games ended naturally, 0 unknown winner parses, and the
  seat2 matched diff was exactly zero, so the result is interpreted as a small
  behavioral perturbation rather than an evaluation artifact.

Follow-up 600-game expansion:

- Added the two extra 200-game seat blocks matched to the remaining
  `buildingA` 600-game seed ranges:
  - `training/data/models/evaluation/winrate/mixed-overflowA-rfp-lookahead-vs-hard-seat1-extra200-200x340.json`
  - `training/data/models/evaluation/winrate/mixed-overflowA-rfp-lookahead-vs-hard-seat2-extra200-200x340.json`
- Combined summary:
  `training/data/models/evaluation/winrate/overflowA-rfp-lookahead-vs-hard-600-summary.json`.
  - Result: 385/600 = 64.17%, 95% CI = 60.25%-67.90%.
  - 600/600 natural-ended, 0 forced, 0 unknown.
  - Hard gate passed:
    `training/data/models/evaluation/winrate/overflowA-rfp-lookahead-vs-hard-600-gate.json`.
- Matched seed comparison to the full `buildingA` 600:
  `training/data/models/evaluation/winrate/overflowA-rfp-vs-buildingA-rfp-600-seed-comparison.json`.
  - Common games: 600.
  - Variant wins: 385; baseline wins: 384; net +1.
  - Changed games: 11, with 6 `L->W` and 5 `W->L`.

Interpretation update:

- The 600-game expansion confirms that `boardAwareOverflowDiscard` is not a
  promotion candidate. It remains clearly stronger than hard, but the lift over
  default `buildingA` is only +0.17pp and is dominated by near-cancelling
  individual flips.
- Keep `monopoly.search.boardAwareOverflowDiscard=false` by default.

Follow-up: Just Say No response-threshold screen.

Purpose: test whether `buildingA` wastes Just Say No on low-impact 3M tenant
responses and should hold it for later swing effects.

Implementation:

- Added default-preserving response threshold knobs:
  - `monopoly.search.responseTenantThreshold` default `3`.
  - `monopoly.search.responseCounterThreshold` default `5`.
- With defaults, `SearchLookaheadAiPlayStrategy.chooseResponse(...)` still
  delegates to `AiHeuristics.chooseResponse(...)`; only explicit non-default
  thresholds use the lookahead-local threshold path.
- The `hard` baseline does not implement `AiChoiceAdvisor`, so these knobs only
  affect lookahead/search players in mixed battle screens.

Candidate:

- `responseTenant4A-rfp`:
  - `monopoly.search.responseTenantThreshold=4`
  - Counter threshold left at default `5`.
  - Matched random-first seed bases from the `buildingA` first-200 baseline.
- Reports:
  - `training/data/models/evaluation/winrate/mixed-responseTenant4A-rfp-lookahead-vs-hard-seat1-100x340.json`
  - `training/data/models/evaluation/winrate/mixed-responseTenant4A-rfp-lookahead-vs-hard-seat2-100x340.json`
- Summary:
  `training/data/models/evaluation/winrate/responseTenant4A-rfp-lookahead-vs-hard-200-summary.json`.
  - Result: 128/200 = 64.0%, 95% CI = 57.14%-70.33%.
  - 200/200 natural-ended, 0 forced, 0 unknown.
  - Seat split: 71/100 as `lookahead,hard`, 57/100 as `hard,lookahead`.
- Gate:
  `training/data/models/evaluation/winrate/responseTenant4A-rfp-lookahead-vs-hard-200-gate.json`
  passed the hard baseline checks.
- Comparison to matched `buildingA` first-200:
  `training/data/models/evaluation/winrate/responseTenant4A-rfp-vs-buildingA-rfp-first200-comparison.json`
  = -0.5pp, rough p ~= 0.917; promotion comparison failed.
- Per-game matched diff:
  - Seat1 changed 0 games.
  - Seat2 changed 1 game, from a lookahead win to a hard win.
- Loss analysis:
  `training/data/models/evaluation/winrate/responseTenant4A-rfp-lookahead-vs-hard-200-loss-analysis.json`.
  - Close losses = 11; blowout losses = 61.
  - Losses by final complete sets: 39 at 0 sets, 22 at 1 set, 11 at 2 sets.

Interpretation:

- Do not promote `responseTenant4A-rfp`. Raising the tenant Just Say No
  threshold from 3M to 4M is a tiny negative under matched seeds and changes
  only one of 200 outcomes.
- Keep the default-preserving knobs and tests as a narrow diagnostic tool, but
  simple response-threshold tuning is not the next high-leverage path.
- Self-check: I accidentally ran gate/comparison in parallel with the summary
  writer, which reproduced the earlier race where readers fail before the
  summary file exists. I reran gate and comparison serially after confirming the
  summary file exists; the final artifacts listed above are valid. Future
  summary-dependent tools should be sequenced, not parallelized.

Follow-up: board-aware payment screen.

Purpose: test whether lookahead should sometimes overpay when bank cannot cover
a bill, in order to avoid breaking a complete or near-complete property set.

Implementation:

- Added a default-off payment experiment:
  `monopoly.search.boardAwarePayment=false`.
- The experiment keeps the existing settlement boundary: if bank cards can
  cover the bill, it only considers bank cards and never sacrifices properties.
  If bank cards cannot cover the bill, it enumerates legal bank/property
  payment combinations and scores `amountPaid + boardDamage`, where complete
  set and near-complete set breaks carry extra penalties.
- Default `buildingA` behavior is unchanged.

Candidate:

- `paymentA-rfp`:
  - `monopoly.search.boardAwarePayment=true`
  - First 50+50 games from the matched random-first `buildingA` first-200
    seed bases.
- Reports:
  - `training/data/models/evaluation/winrate/mixed-paymentA-rfp-lookahead-vs-hard-seat1-50x340.json`
  - `training/data/models/evaluation/winrate/mixed-paymentA-rfp-lookahead-vs-hard-seat2-50x340.json`
- Summary:
  `training/data/models/evaluation/winrate/paymentA-rfp-lookahead-vs-hard-100-summary.json`.
  - Result: 71/100 = 71.0%, 95% CI = 61.46%-78.99%.
  - 100/100 natural-ended, 0 forced, 0 unknown.
  - Seat split: 40/50 as `lookahead,hard`, 31/50 as `hard,lookahead`.
- Gate:
  `training/data/models/evaluation/winrate/paymentA-rfp-lookahead-vs-hard-100-gate.json`
  passed the hard baseline checks.
- Comparison to full matched `buildingA` first-200:
  `training/data/models/evaluation/winrate/paymentA-rfp-vs-buildingA-rfp-first200-comparison.json`
  shows +6.5pp but fails the rough significance threshold (p ~= 0.260).
  This comparison is not the promotion decision because the variant only covers
  the first 100 games.
- Per-game matched diff against the same first 50+50 `buildingA` games:
  - Seat1: variant 40/50 vs baseline 39/50, net +1, 3 games changed.
  - Seat2: variant 31/50 vs baseline 31/50, net +0, 2 games changed.
  - Total: variant 71/100 vs baseline 70/100, net +1.
- Loss analysis:
  `training/data/models/evaluation/winrate/paymentA-rfp-lookahead-vs-hard-100-loss-analysis.json`.
  - Close losses = 6; blowout losses = 23.
  - Losses by final complete sets: 16 at 0 sets, 7 at 1 set, 6 at 2 sets.

Interpretation:

- Do not promote or expand `paymentA-rfp`. The apparent 71% win rate mostly
  comes from a favorable first-100 seed slice: the matched first-100 baseline is
  already 70%, and the true paired lift is only +1 game.
- Keep the default-off implementation and tests as an ablation tool, but
  board-aware payment is low-trigger/low-impact under the current matched
  seeds. Further payment work should be trace-driven rather than another static
  penalty tweak.

Follow-up: auxiliary memento trace audit.

Purpose: stop tuning non-play decisions from intuition alone by collecting
counterfactual mementos for Just Say No, payment, and overflow decisions.

Implementation:

- Added optional auxiliary memento capture behind
  `monopoly.search.trace.includeMemento=true`.
- `GameController` now captures a `GameSessionMemento` immediately before
  AI response, rent-payment, and overflow-discard choices and exposes it
  through `GameContext` only for trace recording.
- `SearchLookaheadAiPlayStrategy` now records auxiliary `JUST_SAY_NO`,
  `PAYMENT`, and `OVERFLOW_DISCARD` trace rows when a trace sink is installed.
  Default gameplay behavior is unchanged.
- Added `training/scripts/analyze_auxiliary_trace.py`, which parses embedded mementos,
  reconstructs player zones/card values/colors, and compares hard fallback
  payment/overflow choices against local structured static scores before any
  expensive counterfactual replay.

Smoke run:

- Trace:
  `training/data/distillation/buildingA-aux-memento-trace-seat1-10.jsonl`.
- Battle report:
  `training/data/models/evaluation/winrate/mixed-buildingA-aux-memento-trace-seat1-10x260.json`.
- Analysis:
  `training/data/models/evaluation/winrate/buildingA-aux-memento-trace-seat1-10-analysis.json`.
- Result shape:
  - 10 games, 10/10 natural-ended, lookahead 6/10.
  - Trace rows: 377 total.
  - Decision mix: `PLAY_CARD=260`, `JUST_SAY_NO=72`, `PAYMENT=42`,
    `OVERFLOW_DISCARD=3`.
  - Memento coverage: 377/377 rows.
  - `PAYMENT`: 42 scored rows; fallback differed from static best in 14 rows,
    including 6 losing rows.
  - `OVERFLOW_DISCARD`: 3 scored rows; fallback differed from static best in
    2 rows, including 1 losing row.
  - `JUST_SAY_NO`: 72 rows, but 62 had only the mandatory `PASS` candidate;
    the 10 actionable rows split evenly between play and pass.

Interpretation:

- The trace path works and covers auxiliary decisions with replayable
  mementos. This closes the earlier blind spot where `buildingA` memento traces
  only contained `PLAY_CARD` rows.
- The most concrete local issue found is payment fallback sometimes breaking a
  complete set to pay exact value when a slightly larger overpayment would keep
  the set intact. This matches the board-aware payment hypothesis, but the
  previous matched 100-game screen only lifted +1 game, so it should be replayed
  before changing defaults.
- Overflow signals exist but are rare in the smoke sample. JSN mostly produces
  non-actionable one-candidate rows, so future training/replay selection should
  filter those out.
- Self-check: the smoke trace contained `effectKind=RENT` with
  `effectLabel=DEBT_COLLECTOR`; this is expected because fixed-payment actions
  are represented as rent-like stack entries with labels such as
  `DEBT_COLLECTOR`/`BIRTHDAY`, not a trace corruption.

Follow-up: auxiliary counterfactual replay smoke.

Purpose: verify whether the auxiliary trace rows are actually replayable and
whether static payment/overflow suspicions survive deterministic roll-forward.

Implementation:

- `CounterfactualReplayRunner` now dispatches by `request.decisionKind`:
  - `PAYMENT` replays candidates as `RESPONSE_PASS` with `paymentCardIds`.
  - `OVERFLOW_DISCARD` replays candidates by submitting the selected `DISCARD`
    card IDs and then ending the turn.
  - `JUST_SAY_NO` replays either the serialized waiver request or a
    `RESPONSE_PASS`.
- Added `monopoly.counterfactual.decisionKinds` to filter replay input and
  `monopoly.counterfactual.maxCandidatesPerDecision` to cap expensive
  candidate sets while always keeping source/policy/hard choices.
- `training/scripts/select_counterfactual_training_rows.py` now recognizes auxiliary
  `PAYMENT`, `OVERFLOW_DISCARD`, and `JUST_SAY_NO` candidate summaries instead
  of reporting them as `UNKNOWN`.

Replay artifacts:

- Payment smoke:
  `training/data/models/evaluation/counterfactual/buildingA-aux-payment-smoke-3x12.json`.
  - 3 decisions, candidateErrors=0, incompleteCandidates=0.
- Overflow full replay:
  `training/data/models/evaluation/counterfactual/buildingA-aux-overflow-full-3.json`.
  - 3 decisions, candidateErrors=0, incompleteCandidates=0.
  - One overflow decision had a replay best that flipped from a source loss to
    a natural win.
- JSN smoke:
  `training/data/models/evaluation/counterfactual/buildingA-aux-jsn-smoke-10.json`.
  - 10 decisions, candidateErrors=0, incompleteCandidates=0.
  - informativeDecisions=0, consistent with the static finding that early JSN
    rows are mostly one-candidate PASS rows.
- Payment high-risk full replay:
  `training/data/models/evaluation/counterfactual/buildingA-aux-payment-risk-top7.json`.
  - 7 decisions, candidateErrors=0, incompleteCandidates=0.
  - source/hard choices were best in 2/7 rows.
  - 3 rows had positive replay reward gaps over source/hard; no natural-win
    flips, but board-score gaps reached +2499.
- Counterfactual payment labels:
  `training/data/distillation/buildingA-aux-payment-risk-top7-cf-labels.jsonl`.
  - Selection report:
    `training/data/models/evaluation/counterfactual/buildingA-aux-payment-risk-top7-cf-selection.json`.
  - 3 selected rows, all `PAYMENT->PAYMENT`, all best choices beat source/hard
    under the replay outcome key.

Interpretation:

- The auxiliary replay path is viable: payment, overflow, and JSN branches can
  restore the memento, apply the candidate, and roll forward without candidate
  errors in smoke runs.
- Payment replay partially supports set-preserving behavior, but not the exact
  previous static `boardAwarePayment` rule. Some replay-best rows overpay
  heavily to preserve multiple complete/near-complete sets, so the next payment
  candidate should value the post-payment board state, not only add a small
  per-card break penalty.
- Overflow replay exposed at least one high-impact miss, but the best discard
  sometimes throws away cards that the current retention heuristic marks as
  valuable. That argues for more replay data or learned auxiliary labels before
  promoting another hand-coded overflow heuristic.

Follow-up: larger auxiliary replay audit and paymentB screen.

Purpose: scale the auxiliary memento path enough to separate replayable signal
from the 10-game smoke sample, then test whether a stronger static payment
complete-set break penalty captures the signal.

Larger auxiliary trace:

- Trace:
  `training/data/distillation/buildingA-aux-memento-trace-seat1-30.jsonl`.
- Battle report:
  `training/data/models/evaluation/winrate/mixed-buildingA-aux-memento-trace-seat1-30x260.json`.
- Analysis:
  `training/data/models/evaluation/winrate/buildingA-aux-memento-trace-seat1-30-analysis.json`.
- Result shape:
  - 30 games, 30/30 natural-ended, lookahead 15/30.
  - Trace rows: 1336 total, 1336/1336 with mementos, 30 sessions.
  - Decision mix: `PLAY_CARD=881`, `JUST_SAY_NO=276`, `PAYMENT=168`,
    `OVERFLOW_DISCARD=11`.
  - `PAYMENT`: 168 scored rows; fallback differed from static best in 51
    rows, including 25 losing rows.
  - `OVERFLOW_DISCARD`: 11 scored rows; fallback differed from static best in
    5 rows, including 2 losing rows.
  - `JUST_SAY_NO`: 276 rows, 237 one-candidate rows, 39 actionable rows.

Counterfactual replay:

- Payment risk input:
  `training/data/distillation/buildingA-aux30-payment-risk-top25.jsonl`.
- Payment full replay:
  `training/data/models/evaluation/counterfactual/buildingA-aux30-payment-risk-top25.json`.
  - 25 decisions, 24 informative, candidateErrors=0,
    incompleteCandidates=0.
  - source/hard choices were best in 12/25 rows.
  - 13 rows had positive replay reward gaps; one replay best flipped from a
    source loss to a natural win.
- Payment selected labels:
  `training/data/distillation/buildingA-aux30-payment-risk-top25-cf-labels.jsonl`.
  - Selection report:
    `training/data/models/evaluation/counterfactual/buildingA-aux30-payment-risk-top25-cf-selection.json`.
  - 5 selected rows, all `PAYMENT->PAYMENT`, all best choices beat source/hard.
- Overflow risk input:
  `training/data/distillation/buildingA-aux30-overflow-risk-top4.jsonl`.
- Overflow full replay:
  `training/data/models/evaluation/counterfactual/buildingA-aux30-overflow-risk-top4.json`.
  - 4 decisions, 4 informative, candidateErrors=0,
    incompleteCandidates=0.
  - source/hard choices were best in 2/4 rows.
  - 2 rows had positive replay reward gaps; one strict selected row flipped
    from a source loss to a natural win.
- Overflow selected label:
  `training/data/distillation/buildingA-aux30-overflow-risk-top4-cf-labels.jsonl`.
  - Selection report:
    `training/data/models/evaluation/counterfactual/buildingA-aux30-overflow-risk-top4-cf-selection.json`.
  - 1 selected row.

PaymentB candidate:

- `paymentB-rfp`:
  - `monopoly.search.boardAwarePayment=true`
  - `monopoly.search.paymentCompleteSetBreakPenalty=8`
  - `monopoly.search.paymentNearSetBreakPenalty=1.5`
  - Same first 50+50 matched random-first seed slice used by `paymentA-rfp`.
- Reports:
  - `training/data/models/evaluation/winrate/mixed-paymentB-rfp-lookahead-vs-hard-seat1-50x340.json`
  - `training/data/models/evaluation/winrate/mixed-paymentB-rfp-lookahead-vs-hard-seat2-50x340.json`
- Summary:
  `training/data/models/evaluation/winrate/paymentB-rfp-lookahead-vs-hard-100-summary.json`.
  - Result: 71/100 = 71.0%, 95% CI = 61.46%-78.99%.
  - 100/100 natural-ended, 0 forced, 0 unknown.
  - Seat split: 40/50 as `lookahead,hard`, 31/50 as `hard,lookahead`.
- Gate:
  `training/data/models/evaluation/winrate/paymentB-rfp-lookahead-vs-hard-100-gate.json`
  failed only the sample-size check because this was intentionally a 100-game
  screen.
- Comparison to `paymentA-rfp`:
  `training/data/models/evaluation/winrate/paymentB-rfp-vs-paymentA-rfp-100-comparison.json`
  = +0.0pp, p = 1.0; promotion comparison failed.
- Loss analysis:
  `training/data/models/evaluation/winrate/paymentB-rfp-lookahead-vs-hard-100-loss-analysis.json`.
  - Close losses = 6; blowout losses = 23.
  - Losses by final complete sets: 16 at 0 sets, 7 at 1 set, 6 at 2 sets.

Interpretation:

- The larger auxiliary replay audit confirms real replayable signal in payment
  and overflow decisions, but the strict label pool is still small: 5 payment
  rows and 1 overflow row from this batch.
- Do not promote `paymentB-rfp`. It exactly matches `paymentA-rfp` on the same
  100-game matched seed slice and only improves by +1 game over the matched
  first-100 `buildingA` baseline. It is also much slower than default
  `buildingA`, taking roughly 280 seconds per 50-game seat run in this
  environment.
- The next auxiliary path should be more counterfactual labels or a learned
  auxiliary ranker. Another hand-coded static payment/overflow rule is not
  justified by this evidence.
- Self-check: the low 15/30 lookahead win rate in the memento trace collection
  is not used as strength evidence. It is a small seat-1 trace slice collected
  for replay rows; the strength baseline remains the 600-game `buildingA`
  natural evaluation.

Follow-up: seat2 auxiliary replay and trainable-context audit.

Purpose: balance the auxiliary sample across seats and verify whether the
counterfactual labels can safely enter the existing local-ranker training
pipeline.

Seat2 trace:

- Trace:
  `training/data/distillation/buildingA-aux-memento-trace-seat2-30.jsonl`.
- Battle report:
  `training/data/models/evaluation/winrate/mixed-buildingA-aux-memento-trace-seat2-30x260.json`.
- Analysis:
  `training/data/models/evaluation/winrate/buildingA-aux-memento-trace-seat2-30-analysis.json`.
- Result shape:
  - 30 games, 30/30 natural-ended, lookahead 17/30.
  - Trace rows: 1274 total, 1274/1274 with mementos.
  - Decision mix: `PLAY_CARD=861`, `JUST_SAY_NO=251`, `PAYMENT=148`,
    `OVERFLOW_DISCARD=14`.
  - `PAYMENT`: fallback differed from static best in 41/148 rows, including
    23 losing rows.
  - `OVERFLOW_DISCARD`: fallback differed from static best in 7/14 rows,
    including 4 losing rows.
  - `JUST_SAY_NO`: 251 rows, 212 one-candidate rows, 39 actionable rows.

Combined seat1+seat2 auxiliary analysis:

- Analysis:
  `training/data/models/evaluation/winrate/buildingA-aux-memento-trace-seat1-seat2-60-analysis.json`.
- Result shape:
  - 60 games, 2610 trace rows, all with mementos.
  - Decision mix: `PAYMENT=316`, `OVERFLOW_DISCARD=25`,
    `JUST_SAY_NO=527`, `PLAY_CARD=1742`.
  - `PAYMENT`: fallback differed from static best in 92/316 rows, including
    48 losing rows.
  - `OVERFLOW_DISCARD`: fallback differed from static best in 12/25 rows,
    including 6 losing rows.
  - `JUST_SAY_NO`: 449/527 one-candidate rows.

New replay labels:

- Seat2 overflow replay:
  `training/data/models/evaluation/counterfactual/buildingA-aux60-seat2-overflow-risk-top6.json`.
  - 6 decisions, 5 informative, candidateErrors=0,
    incompleteCandidates=0.
  - Strict selection:
    `training/data/models/evaluation/counterfactual/buildingA-aux60-seat2-overflow-risk-top6-cf-selection.json`.
  - 2 selected `OVERFLOW_DISCARD->OVERFLOW_DISCARD` labels.
- Seat2 payment top12 replay:
  `training/data/models/evaluation/counterfactual/buildingA-aux60-seat2-payment-risk-top12.json`.
  - 12 decisions, 11 informative, candidateErrors=0,
    incompleteCandidates=0.
  - Strict selection:
    `training/data/models/evaluation/counterfactual/buildingA-aux60-seat2-payment-risk-top12-cf-selection.json`.
  - 5 selected `PAYMENT->PAYMENT` labels.
- Seat2 payment tail9 replay:
  `training/data/models/evaluation/counterfactual/buildingA-aux60-seat2-payment-risk-tail9.json`.
  - 9 decisions, 9 informative, candidateErrors=0,
    incompleteCandidates=0.
  - Strict selection:
    `training/data/models/evaluation/counterfactual/buildingA-aux60-seat2-payment-risk-tail9-cf-selection.json`.
  - 5 selected `PAYMENT->PAYMENT` labels.
- Merged strict auxiliary label pool:
  `training/data/distillation/buildingA-aux-cf-labels-merged-21.jsonl`.
  - 21 rows, no duplicate/conflicting decision IDs.
  - Mix: `PAYMENT=18`, `OVERFLOW_DISCARD=3`.

Self-audit and fix:

- The merged 21-row pool is replay-valid, but the old auxiliary trace context
  is not train-valid for `distill_dataset.py`: it only had
  `roundNumber/currentTurnPhase/counterfactual/...` plus memento, not the
  standard MDSP `self`, `players`, and `decision` objects used at runtime by
  `LocalRankerAiPlayStrategy`.
- Therefore these 21 rows are recorded as counterfactual evidence only, not as
  training input for the existing ranker.
- Fixed the trace source for future data: auxiliary trace rows now use the same
  DeepSeek/MDSP response, payment, and discard prompt JSON that the runtime
  local ranker scores, with the memento attached under `counterfactual`.
- Added `training/scripts/export_auxiliary_risk_rows.py` to export raw trace rows from
  an auxiliary analysis report without ad hoc Python snippets.
- Smoke validation:
  - Trace: `training/data/distillation/buildingA-aux-trainable-smoke-seat1-3.jsonl`.
  - Aux-only rows:
    `training/data/distillation/buildingA-aux-trainable-smoke-seat1-3-aux-only.jsonl`.
  - Validator output:
    `backend/models/distillation/buildingA-aux-trainable-smoke-seat1-3-validation/dataset_validation.json`.
  - Result: 31 auxiliary rows passed `distill_dataset.py --mode validate`
    with `JUST_SAY_NO=19`, `PAYMENT=8`, `OVERFLOW_DISCARD=4`.

Interpretation:

- The best immediate path is not to train on the old 21-row pool. It is to
  collect a fresh train-valid auxiliary trace, replay payment-heavy risk rows,
  then train or screen an auxiliary ranker only after the strict labels are
  both replay-valid and feature-valid.
- This self-audit prevented a likely training bug: using replay labels with a
  context shape the runtime model would never see.

Follow-up: first train-valid auxiliary replay batch.

Purpose: collect a fresh auxiliary trace after the context-shape fix, replay
the payment/overflow risk rows, and verify that selected labels can pass the
normal distillation validator.

Trace collection:

- Seat1 trace:
  `training/data/distillation/buildingA-aux-trainable-trace-seat1-15.jsonl`.
  - Report:
    `training/data/models/evaluation/winrate/mixed-buildingA-aux-trainable-trace-seat1-15x260.json`.
  - 15/15 natural-ended, lookahead 6/15.
- Seat2 trace:
  `training/data/distillation/buildingA-aux-trainable-trace-seat2-15.jsonl`.
  - Report:
    `training/data/models/evaluation/winrate/mixed-buildingA-aux-trainable-trace-seat2-15x260.json`.
  - 15/15 natural-ended, lookahead 8/15.
- Combined analysis:
  `training/data/models/evaluation/winrate/buildingA-aux-trainable-trace-seat1-seat2-30-analysis.json`.
  - 1150 rows, all with mementos.
  - Decision mix: `PLAY_CARD=782`, `JUST_SAY_NO=223`, `PAYMENT=134`,
    `OVERFLOW_DISCARD=11`.
  - `PAYMENT`: fallback differed from static best in 37/134 rows, including
    22 losing rows.
  - `OVERFLOW_DISCARD`: fallback differed from static best in 5/11 rows,
    including 4 losing rows.
  - `JUST_SAY_NO`: 184/223 one-candidate rows.
- Aux-only validator:
  `backend/models/distillation/buildingA-aux-trainable-trace-seat1-seat2-30-validation/dataset_validation.json`.
  - 368 auxiliary rows passed `distill_dataset.py --mode validate`.

Replay and labels:

- Exported payment risk rows:
  `training/data/distillation/buildingA-auxtrain30-payment-risk-top20.jsonl`.
  - 20 rows, 2178 total candidates, max 513 candidates.
- Payment replay:
  `training/data/models/evaluation/counterfactual/buildingA-auxtrain30-payment-risk-top20.json`.
  - 20 decisions, 20 informative, candidateErrors=0,
    incompleteCandidates=0.
  - source/hard best count = 9/20; source and hard choices were identical for
    all 20 rows.
- Payment selected labels:
  `training/data/distillation/buildingA-auxtrain30-payment-risk-top20-cf-labels.jsonl`.
  - Selection report:
    `training/data/models/evaluation/counterfactual/buildingA-auxtrain30-payment-risk-top20-cf-selection.json`.
  - 4 selected `PAYMENT->PAYMENT` rows.
  - 2 selected rows ended in natural wins; selected reward gaps are weak
    (average 0.029), with most signal from board-score gaps.
- Exported overflow risk rows:
  `training/data/distillation/buildingA-auxtrain30-overflow-risk-all4.jsonl`.
- Overflow replay:
  `training/data/models/evaluation/counterfactual/buildingA-auxtrain30-overflow-risk-all4.json`.
  - 4 decisions, 3 informative, candidateErrors=0,
    incompleteCandidates=0.
  - Strict selection found 0 rows:
    `training/data/models/evaluation/counterfactual/buildingA-auxtrain30-overflow-risk-all4-cf-selection.json`.
- Counterfactual-label validator:
  `backend/models/distillation/buildingA-auxtrain30-payment-risk-top20-cf-validation/dataset_validation.json`.
  - The 4 selected payment rows passed `distill_dataset.py --mode validate`
    with `source=counterfactual_replay`.
- Training smoke:
  `backend/models/distillation/buildingA-auxtrain30-payment-cf4-smoke-linear/candidate_ranker_linear.json`.
  - Trained only to verify model export/load path on train-valid auxiliary
    labels.
  - Not a gameplay candidate: only 4 decisions, validation size 1, and training
    top-1 was 2/3.

Interpretation:

- The fixed trace format works: fresh auxiliary rows and selected
  counterfactual labels now pass the normal training validator.
- The current train-valid strict label pool is far too small for promotion or
  gameplay screening. It also appears payment-heavy, while overflow remains
  tied/gap-small under replay.
- Continue collecting fresh train-valid payment risk rows before training an
  auxiliary ranker. The old 21 replay-valid rows should not be mixed into this
  pool unless converted/re-emitted with the standard MDSP context.

Follow-up: second train-valid auxiliary replay batch and payment-only gate.

Purpose: check whether the small first train-valid label pool was caused by
risk-row selection bias, and prevent future auxiliary rankers from accidentally
ranking untrained decision kinds.

Trace collection:

- Seat1 trace:
  `training/data/distillation/buildingA-aux-trainable2-trace-seat1-20.jsonl`.
  - Report:
    `training/data/models/evaluation/winrate/mixed-buildingA-aux-trainable2-trace-seat1-20x260.json`.
  - 20/20 natural-ended, lookahead 13/20.
- Seat2 trace:
  `training/data/distillation/buildingA-aux-trainable2-trace-seat2-20.jsonl`.
  - Report:
    `training/data/models/evaluation/winrate/mixed-buildingA-aux-trainable2-trace-seat2-20x260.json`.
  - 20/20 natural-ended, lookahead 11/20.
- Combined analysis:
  `training/data/models/evaluation/winrate/buildingA-aux-trainable2-trace-seat1-seat2-40-analysis.json`.
  - 1534 rows, all with mementos.
  - Decision mix: `PLAY_CARD=1032`, `JUST_SAY_NO=296`, `PAYMENT=191`,
    `OVERFLOW_DISCARD=15`.
  - `PAYMENT`: fallback differed from static best in 50/191 rows, including
    19 losing rows.
  - `OVERFLOW_DISCARD`: fallback differed from static best in 5/15 rows.
  - 502 auxiliary rows passed `distill_dataset.py --mode validate`.

Replay and labels:

- Risk-only payment replay:
  `training/data/models/evaluation/counterfactual/buildingA-auxtrain2-payment-risk-top30.json`.
  - 22 decisions, 22 informative, candidateErrors=0,
    incompleteCandidates=0.
  - Default selection found 3 labels; strict `--require-reward-gap` found
    1 reward-positive label.
- All-payment replay:
  `training/data/models/evaluation/counterfactual/buildingA-auxtrain2-payment-all191.json`.
  - 191 decisions, 142 informative.
  - `candidateErrors=8` and `incompleteCandidates=8`, all from 2 direct-payment
    decision rows where replay used the response-window path and the restored
    state correctly reported that the payer was not awaiting response.
  - Source/hard best count = 156/191; source and hard choices were identical
    for all 191 rows.
  - Selection report:
    `training/data/models/evaluation/counterfactual/buildingA-auxtrain2-payment-all191-cf-selection.json`.
    13 selected `PAYMENT->PAYMENT` labels, 8 natural wins, average selected
    reward gap 0.285.
  - Strict reward-positive selection:
    `training/data/models/evaluation/counterfactual/buildingA-auxtrain2-payment-all191-reward-cf-selection.json`.
    4 selected labels, 3 natural wins, average selected reward gap 0.925.
- Merged train-valid payment label pool:
  `training/data/distillation/buildingA-auxtrain-payment-cf17-merged.jsonl`.
  - 17 rows, no duplicate/conflicting decision IDs.
  - Validator:
    `backend/models/distillation/buildingA-auxtrain-payment-cf17-merged-validation/dataset_validation.json`.
    Passed with `PAYMENT=17`.

Ranker smoke and safety gate:

- Added `monopoly.localRanker.rankedDecisionKinds`.
  - Default remains `PLAY_CARD` only unless
    `monopoly.localRanker.rankAuxiliaryDecisions=true` is explicitly used.
  - A payment-only model can now be run with
    `monopoly.localRanker.rankedDecisionKinds=PAYMENT` without also ranking
    `JUST_SAY_NO` or `OVERFLOW_DISCARD`.
- Trained payment-only smoke models:
  - Linear:
    `backend/models/distillation/buildingA-auxtrain-payment-cf17-linear/candidate_ranker_linear.json`.
    Train top-1 0.357, validation top-1 0.667 on only 3 validation decisions.
  - MLP:
    `backend/models/distillation/buildingA-auxtrain-payment-cf17-mlp/candidate_ranker_mlp.json`.
    Train top-1 0.929, validation top-1 0.667 on only 3 validation decisions.
- No gameplay promotion screen was run for these models. A standalone
  `LocalRankerAiPlayStrategy` payment-only configuration falls back to hard for
  `PLAY_CARD`, not to `buildingA` lookahead play, so it would not be a clean
  test of "buildingA plus payment ranker" without a separate composite strategy.

Building-parameter screen:

- `buildingB-rfp`:
  - `monopoly.search.buildingActionBonus=1050`
  - `monopoly.search.buildingRentBonusValue=380`
  - `monopoly.search.opponentBuildingThreatValue=300`
  - Matched first 50+50 `buildingA` random-first seeds.
- Summary:
  `training/data/models/evaluation/winrate/buildingB-rfp-lookahead-vs-hard-100-summary.json`.
  - Result: 70/100 = 70.0%, 100/100 natural-ended, 0 forced, 0 unknown.
- Seed comparison:
  `training/data/models/evaluation/winrate/buildingB-rfp-vs-buildingA-rfp-first100-seed-comparison.json`.
  - Net wins = 0, changedGames = 0.
  - Do not expand; the stronger building weights changed no matched outcomes.

Interpretation:

- Full-payment replay found more train-valid labels than risk-only replay, so
  future auxiliary data should export all rows for low-cost decision kinds
  before assuming label scarcity.
- The label pool is still too small and too payment-only for a gameplay
  candidate. The immediate next step is more fresh train-valid traces or a
  composite `lookahead + auxiliary ranker` adapter, not promoting the current
  payment smoke models.

Follow-up: payment replay fix and hybrid payment-ranker screen.

Purpose: fix the remaining PAYMENT replay errors, then test the cleanest
possible runtime integration: keep `buildingA` for normal card play and let a
local ranker override only PAYMENT decisions.

Replay fix:

- Added a simulation-only payment resolver for restored effect-stack states.
- The old PAYMENT replay branch always submitted `RESPONSE_PASS` as the payer.
  That is correct for tenant response windows, but wrong after a tenant has
  played Just Say No and the landlord is in `LANDLORD_COUNTER`: the payer is no
  longer the awaiting responder, yet the rent line can still become payable if
  the countered JSN chain resolves.
- `CounterfactualReplayRunner` now:
  - uses normal `RESPONSE_PASS + paymentCardIds` for tenant response windows;
  - uses `GameController.resolvePaymentChoiceForSimulation(...)` for
    landlord-counter / active-rent restored states;
  - still routes through `EffectStackResolver.resolveRentPayments(...)`, so
    cancellation and explicit-payment validation stay on the same code path as
    real gameplay.
- Added a controller test for rent + tenant JSN + landlord counter JSN followed
  by explicit simulation payment.

Fixed replay artifacts:

- Replay:
  `training/data/models/evaluation/counterfactual/buildingA-auxtrain2-payment-all191-paymentreplayfix.json`.
  - 191 decisions, 143 informative.
  - candidateErrors=0, incompleteCandidates=0.
  - source/hard best count = 156/191.
  - source/hard choices were identical for all rows.
- Selection:
  `training/data/models/evaluation/counterfactual/buildingA-auxtrain2-payment-all191-paymentreplayfix-cf-selection.json`.
  - 13 selected labels, same count as before, but with clean replay evidence.
- Strict reward selection:
  `training/data/models/evaluation/counterfactual/buildingA-auxtrain2-payment-all191-paymentreplayfix-reward-cf-selection.json`.
  - 4 selected labels.
- Merged fixed label pool:
  `training/data/distillation/buildingA-auxtrain-payment-cf17-paymentreplayfix-merged.jsonl`.
  - 17 rows, no duplicate/conflicting decision IDs.
  - Validator:
    `backend/models/distillation/buildingA-auxtrain-payment-cf17-paymentreplayfix-merged-validation/dataset_validation.json`.
    Passed with `PAYMENT=17`.

Hybrid runtime screen:

- Added `HybridLookaheadPaymentRankerAiPlayStrategy`:
  - `tryPlayOneCard`, JSN response, and overflow discard delegate to
    `SearchLookaheadAiPlayStrategy` (`buildingA` defaults).
  - PAYMENT delegates to `LocalRankerAiPlayStrategy`.
- `MixedAiBattleExperimentRunner` can install this composite for lookahead seats
  via:
  `monopoly.mixedBattle.lookaheadPaymentRankerModel=<ranker-json>`.
- Trained fixed 17-row payment smoke models:
  - Linear:
    `backend/models/distillation/buildingA-auxtrain-payment-cf17-paymentreplayfix-linear/candidate_ranker_linear.json`.
    Train top-1 0.357, validation top-1 0.667 on 3 validation decisions.
  - MLP:
    `backend/models/distillation/buildingA-auxtrain-payment-cf17-paymentreplayfix-mlp/candidate_ranker_mlp.json`.
    Train top-1 0.929, validation top-1 0.667; clear overfit risk.
- Gameplay smoke used the linear model only:
  `training/data/models/evaluation/winrate/mixed-paymentHybridLinearA-rfp-lookahead-vs-hard-seat1-50x340.json`.
  - 22/50 = 44.0%, 50/50 natural-ended, 0 forced, 0 unknown.
  - Gate failed:
    `training/data/models/evaluation/winrate/paymentHybridLinearA-rfp-lookahead-vs-hard-seat1-50-gate.json`.
  - Runtime cost was high: 310 seconds for 50 games in this environment.
- Matched first-30 comparison to same seedBase `buildingA` trace:
  `training/data/models/evaluation/winrate/paymentHybridLinearA-rfp-seat1-first30-vs-buildingA-auxtrace-seed-comparison.json`.
  - Net wins = -2.
  - Changed games: 3 W->L, 1 L->W.

Interpretation:

- The replay bug is fixed; the previous candidate errors were real replay-tool
  limitations, not bad labels. The fixed report is now clean enough to use as
  evidence.
- The current 17-label PAYMENT ranker is not a viable gameplay candidate. It
  makes `buildingA` worse on matched seeds and is much slower.
- Do not expand `paymentHybridLinearA` to seat2 or 200 games. Future auxiliary
  work needs more train-valid labels and/or a conservative override gate before
  another natural-game screen.

Follow-up: third train-valid auxiliary batch and block-holdout self-audit.

Purpose: add another fresh train-valid auxiliary batch after the PAYMENT replay
fix, then check whether the expanded auxiliary label pool is strong enough to
justify another gameplay screen.

Trace collection:

- Seat1 trace:
  `training/data/distillation/buildingA-aux-trainable3-trace-seat1-20.jsonl`.
  - Report:
    `training/data/models/evaluation/winrate/mixed-buildingA-aux-trainable3-trace-seat1-20x260.json`.
  - 20/20 natural-ended, lookahead 14/20.
- Seat2 trace:
  `training/data/distillation/buildingA-aux-trainable3-trace-seat2-20.jsonl`.
  - Report:
    `training/data/models/evaluation/winrate/mixed-buildingA-aux-trainable3-trace-seat2-20x260.json`.
  - 20/20 natural-ended, lookahead 8/20.
- Combined analysis:
  `training/data/models/evaluation/winrate/buildingA-aux-trainable3-trace-seat1-seat2-40-analysis.json`.
  - 1560 rows, all with mementos.
  - Decision mix: `PLAY_CARD=1055`, `JUST_SAY_NO=300`, `PAYMENT=188`,
    `OVERFLOW_DISCARD=17`.
  - Self-audit: decision traces do not include final outcome, so
    `analyze_auxiliary_trace.py` now writes schema v2 with `outcomeMissing`
    instead of counting missing outcome as a loss. `export_auxiliary_risk_rows.py`
    now treats unknown outcome separately, so `--natural-win false` does not
    match decision-only trace rows.

Replay and labels:

- PAYMENT replay:
  `training/data/models/evaluation/counterfactual/buildingA-auxtrain3-payment-all188.json`.
  - 188 decisions, 140 informative.
  - candidateErrors=0, incompleteCandidates=0.
  - source/hard best count = 140/188.
  - source/hard choices were identical for all rows.
- PAYMENT selection:
  `training/data/models/evaluation/counterfactual/buildingA-auxtrain3-payment-all188-beathard-cf-selection.json`.
  - 21 selected `PAYMENT->PAYMENT` labels with `bestBeatsHard=true`.
  - Strict reward-positive version:
    `training/data/models/evaluation/counterfactual/buildingA-auxtrain3-payment-all188-beathard-reward-cf-selection.json`
    selected 6 labels.
- OVERFLOW replay:
  `training/data/models/evaluation/counterfactual/buildingA-auxtrain3-overflow-all17.json`.
  - 17 decisions, 15 informative.
  - candidateErrors=0, incompleteCandidates=0.
- OVERFLOW selection:
  `training/data/models/evaluation/counterfactual/buildingA-auxtrain3-overflow-all17-beathard-cf-selection.json`.
  - 6 selected `OVERFLOW_DISCARD->OVERFLOW_DISCARD` labels.
- Expanded conservative pool:
  `training/data/distillation/buildingA-auxtrain-payment-overflow-cf44-merged.jsonl`.
  - 44 rows: `PAYMENT=38`, `OVERFLOW_DISCARD=6`.
  - Validator:
    `backend/models/distillation/buildingA-auxtrain-payment-overflow-cf44-merged-validation/dataset_validation.json`.
    Passed with 33 sessions, 22 natural-win labels and 22 unknown-outcome labels.
- Reward-payment pool:
  `training/data/distillation/buildingA-auxtrain-payment-overflow-cf29-rewardpay-merged.jsonl`.
  - 29 rows: `PAYMENT=23`, `OVERFLOW_DISCARD=6`.

Offline model checks:

- 44-row linear:
  `backend/models/distillation/buildingA-auxtrain-payment-overflow-cf44-linear/candidate_ranker_linear.json`.
  - Random session split validation top-1 0.375 on 8 decisions.
- 44-row MLP:
  `backend/models/distillation/buildingA-auxtrain-payment-overflow-cf44-mlp/candidate_ranker_mlp.json`.
  - Random session split validation top-1 0.375; train top-1 rose to 0.444,
    indicating overfit without validation gain.
- 44-row linear block holdout:
  `backend/models/distillation/buildingA-auxtrain-payment-overflow-cf44-prefixcheck-linear/candidate_ranker_linear.json`.
  - `--split-by session-prefix` validation top-1 0.300 on 20 decisions.
- 29-row reward-payment linear block holdout:
  `backend/models/distillation/buildingA-auxtrain-payment-overflow-cf29-rewardpay-prefixcheck-linear/candidate_ranker_linear.json`.
  - `--split-by session-prefix` validation top-1 0.300 on 10 decisions.

Interpretation:

- The replay path is now clean on another fresh batch, including overflow.
- The expanded pool is useful evidence and should be kept, but it is still too
  small and too weak to justify another natural-game screen. Block holdout is
  the decisive self-audit here: random session splits slightly overstate the
  signal, while run-prefix holdout falls to 0.300.
- Do not promote or expand auxiliary rankers from the 44-row/29-row pools.
  Continue collecting fresh train-valid auxiliary rows or switch to a more
  constrained hand-coded overflow/payment gate before running expensive
  gameplay screens.

## 2026-05-27 Auxiliary Counterfactual Force-End Self-Audit

Purpose: verify whether the weak auxiliary ranker result was caused by model
capacity, label scarcity, or a replay/reporting bug.

Implementation:

- `CounterfactualReplayRunner` now writes each candidate's `forceEndReason`
  and counts `forcedCandidates` in the report summary.
- `training/scripts/select_counterfactual_training_rows.py` now rejects candidates with
  missing `forceEndReason` fields by default, and rejects any decision where a
  candidate force-ended unless `--allow-forced-candidates` is explicit.
- The selector report now includes `candidateQuality` counts for candidate
  errors, forced candidates, missing `forceEndReason`, and incomplete
  candidates.

Self-audit findings:

- Old auxiliary reports did not include `forceEndReason`, so they could not
  distinguish natural terminal outcomes from `COUNTERFACTUAL_SNAPSHOT_LIMIT`.
  The selector now refuses those reports unless
  `--allow-missing-force-end-reason` is explicit.
- Replaying with the fixed report format exposed substantial forced-candidate
  contamination:
  - `buildingA-auxtrain3-payment-all188-forcedfix.json`: 675 forced
    candidates. Selection changed from 21 labels to 14 labels; strict
    reward-positive payment labels changed from 6 to 3.
  - `buildingA-auxtrain3-overflow-all17-forcedfix.json`: 386 forced
    candidates. Selection changed from 6 labels to 4 labels.
  - `buildingA-auxtrain2-payment-all191-forcedfix.json`: 639 forced
    candidates.
  - `buildingA-auxtrain30-payment-risk-top20-forcedfix.json`: 752 forced
    candidates.
- Raising `monopoly.counterfactual.maxSnapshotsPerCandidate` from 320 to 700
  did not reduce the forced counts for the main auxtrain2/auxtrain3 payment or
  overflow reports. The forced reason remained
  `COUNTERFACTUAL_SNAPSHOT_LIMIT`, so these are not safe supervised labels.

Clean v2 label pools:

- Conservative pool:
  `training/data/distillation/buildingA-auxtrain-payment-overflow-cf36-cleanv2-merged.jsonl`.
  - 36 rows: `PAYMENT=31`, `OVERFLOW_DISCARD=5`.
  - Validator:
    `backend/models/distillation/buildingA-auxtrain-payment-overflow-cf36-cleanv2-merged-validation/dataset_validation.json`.
  - Prefix holdout linear model:
    `backend/models/distillation/buildingA-auxtrain-payment-overflow-cf36-cleanv2-prefixcheck-linear/candidate_ranker_linear.json`.
  - Validation top-1 0.375 on 8 decisions; payment top-1 0.429, overflow
    top-1 0.000.
- Strict reward-payment pool:
  `training/data/distillation/buildingA-auxtrain-payment-overflow-cf11-cleanv2-rewardpay-merged.jsonl`.
  - 11 rows: `PAYMENT=7`, `OVERFLOW_DISCARD=4`.
  - Prefix holdout validation top-1 0.000 on 2 decisions.

Gameplay screen:

- `paymentOverflowA-rfp` combined the default-off deterministic payment and
  overflow rules:
  - `monopoly.search.boardAwarePayment=true`
  - `monopoly.search.boardAwareOverflowDiscard=true`
- Reports:
  - `training/data/models/evaluation/winrate/mixed-paymentOverflowA-rfp-lookahead-vs-hard-seat1-50x340.json`
  - `training/data/models/evaluation/winrate/mixed-paymentOverflowA-rfp-lookahead-vs-hard-seat2-50x340.json`
- Summary:
  `training/data/models/evaluation/winrate/paymentOverflowA-rfp-lookahead-vs-hard-100-summary.json`.
  - 71/100 = 71.0%, 100/100 natural-ended.
- Matched comparisons:
  - Versus `buildingA` first 100: +1 net win.
  - Versus `paymentA-rfp` first 100:
    `training/data/models/evaluation/winrate/paymentOverflowA-rfp-vs-paymentA-rfp-100-seed-comparison.json`
    = +0 net wins.

Interpretation:

- The old 44-row and 29-row auxiliary pools are deprecated. They were not just
  weak; they were partly trained from forced counterfactual candidates whose
  terminal value was a snapshot-limit board score.
- Do not run gameplay screens from any model trained on
  `buildingA-auxtrain-payment-overflow-cf44-merged.jsonl` or
  `buildingA-auxtrain-payment-overflow-cf29-rewardpay-merged.jsonl`.
- Future counterfactual-label runs must use reports with `forceEndReason` and
  should keep the selector's default `allowForcedCandidates=false`.
- The clean-v2 pools are useful only as diagnostic evidence. They are too small
  and still do not show enough holdout signal to justify an auxiliary ranker
  promotion path.
- The combined deterministic payment/overflow screen exactly matched
  `paymentA-rfp` on the same 100 seeds, so it should not be expanded.
- Added a default-hard replay diagnostic switch:
  `monopoly.counterfactual.rollForwardPolicy=hard|lookahead`.
  A 20-decision smoke on auxtrain3 payment showed lookahead continuation only
  reduced forced candidates from 14 to 13 while increasing runtime from about
  4 seconds to about 20 seconds, so it is not worth expanding as a cleanup
  strategy.

## 2026-05-26 Structured Steal Target Feature Screen

Purpose: test whether the same-effect target replay signal can be captured by
structured `STEAL_PROPERTY` target features instead of blindly falling back to
hard's target choice.

Implementation:

- `AiHeuristics.describeCandidate(...)` now emits structured
  `STEAL_PROPERTY` summaries when the target card is resolvable:
  `target`, `card`, `takeColor`, `takeValue`, `completionGain`,
  `oppCompletionLoss`, and `wild`.
- Added default-off adjustment knobs:
  - `monopoly.search.stealCompletionGainMultiplier` default `0`.
  - `monopoly.search.stealOppCompletionLossMultiplier` default `0`.
  - `monopoly.search.stealTakeValueMultiplier` default `0`.
  - `monopoly.search.stealWildBonus` default `0`.
- Default `buildingA` behavior is unchanged.

Candidate:

- `stealFeatureA`:
  - `monopoly.search.stealCompletionGainMultiplier=6`
  - `monopoly.search.stealOppCompletionLossMultiplier=4`
  - `monopoly.search.stealTakeValueMultiplier=120`
  - `monopoly.search.stealWildBonus=650`
- Reports:
  - `training/data/models/evaluation/winrate/mixed-stealFeatureA-lookahead-vs-hard-seat1-50x340.json`
  - `training/data/models/evaluation/winrate/mixed-stealFeatureA-lookahead-vs-hard-seat2-50x340.json`
- Summary:
  `training/data/models/evaluation/winrate/stealFeatureA-lookahead-vs-hard-100-summary.json`.
  - Result: 58/100 = 58.0%, 95% CI = 48.21%-67.20%.
  - 100/100 natural-ended, 0 forced, 0 unknown.
  - Seat split: 37/50 as seat1, 21/50 as seat2.
- Comparison to `buildingA` 600:
  `training/data/models/evaluation/winrate/stealFeatureA-vs-buildingA-600-comparison.json`
  = -6.0pp, rough p ~= 0.250; promotion comparison failed.
- Loss analysis:
  `training/data/models/evaluation/winrate/stealFeatureA-lookahead-vs-hard-100-loss-analysis.json`.
  - Losses by final complete sets: 20 at 0 sets, 13 at 1 set, 9 at 2 sets.
  - Close losses = 9; blowout losses = 33.

Interpretation:

- `stealFeatureA` is not promoted. The strong static steal-target boost helped
  one seat but collapsed the reverse seat, which is not acceptable for the
  natural seat-balanced evaluation policy.
- Keep the structured summaries and default-off knobs because they are useful
  for trace analysis, counterfactual labels, and later learned/conditional
  target ranking.
- The next target-choice attempt should be conditional or replay-trained, not a
  larger unconditional steal bonus.

Follow-up: structured tactical pruning/reserve screens.

Purpose: test whether the useful signal was being lost before one-ply search,
because target-specific steal/forced-deal candidates were pruned out. These
screens keep the default strategy unchanged and try default-off ways to expose
more structured tactical candidates to the existing search scorer.

Implementation:

- Added default-off candidate-pruning knobs:
  - `monopoly.search.structuredTacticalReservedCandidates` default `0`.
  - `monopoly.search.structuredPrunePriorityWeight` default `0`.
- `structuredTacticalReservedCandidates` appends a small number of
  high-priority structured `STEAL_PROPERTY`, `FORCED_DEAL`, and `DEAL_BREAKER`
  candidates after normal top-candidate pruning.
- `structuredPrunePriorityWeight` changes only the pruning order by adding a
  structured tactical priority to the existing local candidate score. It does
  not increase the number of searched candidates.
- Default `buildingA` behavior is unchanged.

Candidates:

- `structuredReserveA`:
  - `monopoly.search.structuredTacticalReservedCandidates=6`
  - Summary:
    `training/data/models/evaluation/winrate/structuredReserveA-lookahead-vs-hard-100-summary.json`.
    Result: 63/100 = 63.0%, 95% CI = 53.22%-71.82%.
  - 100/100 natural-ended, 0 forced, 0 unknown.
  - Seat split: 37/50 as seat1, 26/50 as seat2.
  - Comparison to `buildingA` 600:
    `training/data/models/evaluation/winrate/structuredReserveA-vs-buildingA-600-comparison.json`
    = -1.0pp, rough p ~= 0.847; promotion comparison failed.
  - Cost note: each 50-game seat run took several minutes because additional
    candidates trigger additional one-ply simulations.
- `structuredReserveB`:
  - `monopoly.search.structuredTacticalReservedCandidates=3`
  - Summary:
    `training/data/models/evaluation/winrate/structuredReserveB-lookahead-vs-hard-100-summary.json`.
    Result: 58/100 = 58.0%, 95% CI = 48.21%-67.20%.
  - 100/100 natural-ended, 0 forced, 0 unknown.
  - Seat split: 32/50 as seat1, 26/50 as seat2.
  - Comparison to `buildingA` 600:
    `training/data/models/evaluation/winrate/structuredReserveB-vs-buildingA-600-comparison.json`
    = -6.0pp, rough p ~= 0.250; promotion comparison failed.
  - Timed runs were about 290s and 276s for the two 50-game seats.
- `structuredPruneA`:
  - `monopoly.search.structuredPrunePriorityWeight=20`
  - Summary:
    `training/data/models/evaluation/winrate/structuredPruneA-lookahead-vs-hard-100-summary.json`.
    Result: 58/100 = 58.0%, 95% CI = 48.21%-67.20%.
  - 100/100 natural-ended, 0 forced, 0 unknown.
  - Seat split: 36/50 as seat1, 22/50 as seat2.
  - Comparison to `buildingA` 600:
    `training/data/models/evaluation/winrate/structuredPruneA-vs-buildingA-600-comparison.json`
    = -6.0pp, rough p ~= 0.250; promotion comparison failed.
  - Timed runs were about 296s and 299s for the two 50-game seats.

Interpretation:

- Do not promote structured tactical reserve/prune variants. The only near
  baseline result, `structuredReserveA`, is slower and still trails `buildingA`;
  the cheaper/narrower variants fall back to 58%.
- The self-check is clean: all screened games ended naturally with 0 unknown,
  and the failures are seat-distribution/performance issues rather than winner
  parsing or force-end bugs.
- The next useful direction should move away from online candidate expansion
  and toward replay-trained or conditional target ranking using the structured
  summaries already in traces.

Follow-up: v4 structured replay-label audit.

Purpose: collect traces with the new structured target summaries and test
whether deterministic counterfactual replay produces enough target-specific
labels to justify a learned target corrector.

Implementation:

- `training/scripts/distill_dataset.py` feature version advanced to
  `candidate-ranker-features-v4`.
- Runtime and training feature extractors now parse `takeValue=` and
  `giveValue=` from candidate summaries and add binary `wild=true/false`
  summary markers. Runtime feature length is 169; older v1/v2/v3 models remain
  loadable through compatible feature truncation.
- Added `training/scripts/select_lookahead_replay_rows.py`, which selects losing trace
  rows with memento, lookahead/hard disagreement, and either tactical
  transitions (`STEAL_PROPERTY`, `FORCED_DEAL`, `DEAL_BREAKER`), same-effect
  tactical target differences, or a large lookahead-vs-hard score gap.

Trace and replay:

- Source traces:
  - `training/data/distillation/buildingA-v4-memento-trace-seat1-20.jsonl`
  - `training/data/distillation/buildingA-v4-memento-trace-seat2-20.jsonl`
- Natural trace reports:
  - `training/data/models/evaluation/winrate/mixed-buildingA-v4-memento-trace-seat1-20x340.json`
  - `training/data/models/evaluation/winrate/mixed-buildingA-v4-memento-trace-seat2-20x340.json`
- Trace audit:
  `training/data/models/evaluation/winrate/buildingA-v4-memento-trace-40-audit.json`.
  - 40 sessions, 27 wins / 13 losses, 1112 traced decisions.
  - 1112/1112 rows carried counterfactual mementos.
  - Structured summaries were present for 192 `STEAL_PROPERTY` candidates and
    331 structured `FORCED_DEAL` candidates.
- Balanced replay subset:
  - Trace:
    `training/data/distillation/buildingA-v4-losing-tactical-replay-35-balanced.jsonl`
  - Selection report:
    `training/data/models/evaluation/winrate/buildingA-v4-losing-tactical-replay-35-balanced-selection.json`
  - Eligible rows = 48; selected rows = 35 after max 4 rows per session.
  - Selected reasons: 33 score-gap, 16 tactical-transition, 9 same-effect
    tactical target-diff.
  - Replay:
    `training/data/models/evaluation/winrate/buildingA-v4-losing-tactical-replay-35-balanced-counterfactual.json`.
    Source best count = 9, hard best count = 15, hard better than source = 9,
    source better than hard = 3; candidate errors and incomplete candidates = 0.
  - Strict selected labels:
    `training/data/models/traces/buildingA-v4-losing-tactical-cf-selected-35-balanced.jsonl`.
    Rows selected = 6; best effects = 3 `DEPLOY`, 2 `DEPOSIT`, 1 `PASS_GO`.
- All-eligible replay subset:
  - Trace:
    `training/data/distillation/buildingA-v4-losing-tactical-replay-48-all.jsonl`
  - Selection report:
    `training/data/models/evaluation/winrate/buildingA-v4-losing-tactical-replay-48-all-selection.json`
  - Replay:
    `training/data/models/evaluation/winrate/buildingA-v4-losing-tactical-replay-48-all-counterfactual.json`.
    Source best count = 15, hard best count = 20, hard better than source = 11,
    source better than hard = 5; candidate errors and incomplete candidates = 0.
  - Strict selected labels:
    `training/data/models/traces/buildingA-v4-losing-tactical-cf-selected-48-all.jsonl`.
    Rows selected = 7; best effects = 3 `DEPLOY`, 3 `DEPOSIT`, 1 `PASS_GO`.

Interpretation:

- The replay audit is a clean negative for learned steal-target correction:
  same-effect steal/forced-deal target rows often tie under deterministic
  reward/board replay, and strict non-tied labels mostly say to deploy, bank, or
  draw instead of choosing a different target.
- Do not train or enable a v4 corrector from these labels. The data would mainly
  recreate the older small-sample `DEPLOY`/`DEPOSIT` correction risk while not
  solving target ranking.
- Keep v4 summary features and the replay-row selector as diagnostics. A useful
  next attempt needs either richer tie-aware labels, more varied losing states,
  or a narrower supervised objective for deploy/wild-color timing rather than a
  broad action override.

Follow-up: search-width, hard-margin, and threat-protocol screen.

Purpose: test whether recent replay negatives were caused by candidate pruning,
over-aggressive lookahead overrides, or insufficient opponent-threat pressure.

Candidates:

- `wide24A`:
  - `monopoly.search.maxCandidates=24`
  - Fixed-first seat-balanced exploratory screen:
    `training/data/models/evaluation/winrate/wide24A-lookahead-vs-hard-100-summary.json`.
    Result: 61/100 = 61.0%, 95% CI = 51.20%-69.98%.
  - 100/100 natural-ended, 0 forced, 0 unknown.
  - Comparison to `buildingA` 600:
    `training/data/models/evaluation/winrate/wide24A-vs-buildingA-600-comparison.json`
    = -3.0pp, rough p ~= 0.564; promotion comparison failed.
  - Interpretation: larger candidate width is slower and did not recover the
    missing tactical signal.
- `hardMargin100A`:
  - `monopoly.search.hardMargin=100`
  - Fixed-first seat-balanced exploratory screen:
    `training/data/models/evaluation/winrate/hardMargin100A-lookahead-vs-hard-100-summary.json`.
    Result: 56/100 = 56.0%, 95% CI = 46.23%-65.33%.
  - 100/100 natural-ended, 0 forced, 0 unknown.
  - Hard gate failed the CI-low check. Comparison to `buildingA` 600:
    `training/data/models/evaluation/winrate/hardMargin100A-vs-buildingA-600-comparison.json`
    = -8.0pp, rough p ~= 0.125; promotion comparison failed.
  - Interpretation: a broad hard fallback suppresses useful lookahead choices.
- `buildingThreatA`:
  - `monopoly.search.threatWeight=1.35`
  - `monopoly.search.maxOpponentWeight=1.02`
  - Fixed-first exploratory expansion:
    `training/data/models/evaluation/winrate/buildingThreatA-lookahead-vs-hard-600-summary.json`.
    Result: 370/600 = 61.67%, 95% CI = 57.71%-65.47%.
  - 600/600 natural-ended, 0 forced, 0 unknown.
  - Seat split was unstable: 212/300 as `lookahead,hard`, but only 158/300 as
    `hard,lookahead`.
  - Direct comparison file:
    `training/data/models/evaluation/winrate/buildingThreatA-vs-buildingA-600-comparison.json`
    = -2.33pp, rough p ~= 0.403; promotion comparison failed. This comparison
    is diagnostic only because the `buildingA` 600 baseline used
    `randomizeFirstPlayer=true` while this fixed-first expansion used
    `randomizeFirstPlayer=false`.
- `buildingThreatA-rfp` protocol recheck:
  - Same params as `buildingThreatA`, but with `randomizeFirstPlayer=true` and
    the same seed bases as the first 200 `buildingA` baseline games.
  - Reports:
    - `training/data/models/evaluation/winrate/mixed-buildingThreatA-rfp-lookahead-vs-hard-seat1-100x340.json`
    - `training/data/models/evaluation/winrate/mixed-buildingThreatA-rfp-lookahead-vs-hard-seat2-100x340.json`
  - Summary:
    `training/data/models/evaluation/winrate/buildingThreatA-rfp-lookahead-vs-hard-200-summary.json`.
    Result: 129/200 = 64.5%, 95% CI = 57.65%-70.80%.
  - 200/200 natural-ended, 0 forced, 0 unknown.
  - Matched baseline:
    `training/data/models/evaluation/winrate/buildingA-rfp-lookahead-vs-hard-first200-summary.json`
    also produced 129/200 = 64.5%.
  - Comparison:
    `training/data/models/evaluation/winrate/buildingThreatA-rfp-vs-buildingA-rfp-first200-comparison.json`
    = +0.0pp, rough p = 1.0; promotion comparison failed.

Interpretation:

- No default parameter changes. `buildingA` remains the current default local
  strong AI: `buildingActionBonus=900`, `buildingRentBonusValue=320`,
  `opponentBuildingThreatValue=260`.
- The self-audit found a protocol mismatch in recent exploratory fixed-first
  screens versus the promoted `buildingA` 600 baseline. Fixed-first runs remain
  useful for seat-isolation diagnostics, but promotion comparisons should use a
  consistent `randomizeFirstPlayer=true` protocol or an explicitly matched
  baseline.
- Rechecking `buildingThreatA` under the matched random-first protocol exactly
  tied the `buildingA` first-200 baseline, so the earlier 200-game high point
  was not sufficient evidence of a stronger strategy.

Follow-up: rent/pass-go tempo and low-progress deploy audit.

Purpose: examine whether the remaining `buildingA` losses come from overvaluing
low-progress deployment or card draw over small cash pressure.

Trace audit:

- `buildingA` 600 loss analysis:
  `training/data/models/evaluation/winrate/buildingA-lookahead-vs-hard-600-loss-analysis.json`.
  - 216 losses, 181 blowouts.
  - Final complete sets in losses: 99 at 0 sets, 82 at 1 set, 35 at 2 sets.
- Existing outcome traces showed many losing overrides such as
  `BIRTHDAY->DEPLOY`, `RENT_DUAL->DEPLOY`, and `RENT_DUAL->PASS_GO`.
- Added `training/scripts/select_low_progress_deploy_replay_rows.py` to select losing
  rows where lookahead chose a low `completionScore` deployment over hard's
  different choice, with counterfactual mementos preserved.

Low-progress deploy replay:

- Selection:
  `training/data/models/evaluation/winrate/buildingA-low-progress-deploy-replay-40-selection.json`.
  - Input rows = 2718, all with mementos.
  - Eligible rows = 53; selected rows = 40 across 21 losing sessions.
  - Selected hard effects: 14 `BIRTHDAY`, 14 `DEPLOY`, 6 `RENT_DUAL`,
    4 `FORCED_DEAL`, 2 `RENT`.
  - Selected deploy completion score median = 50.
- Replay:
  `training/data/models/evaluation/winrate/buildingA-low-progress-deploy-replay-40-counterfactual.json`.
  - Decisions = 40, informative = 34.
  - Candidate errors = 0, incomplete candidates = 0, hard-choice resolution
    errors/unmatched = 0.
  - Source best = 20, hard best = 21.
  - Source better than hard = 6, hard better than source = 6, same = 28.
  - Source natural wins = 1, hard natural wins = 1.
  - Average source-minus-hard reward = -0.0058.
- Strict selected labels:
  `training/data/models/evaluation/winrate/buildingA-low-progress-deploy-cf-selected-40-selection.json`.
  - Rows selected = 12; 28 replay decisions were tied.
  - Best effects = 10 `DEPOSIT`, 2 `DEPLOY`.
  - Reward gap was 0 for selected rows; labels were selected only by small board
    score gaps.

Parameter screens:

- `rentNudgeA-rfp`:
  - `monopoly.search.rentActionBonus=450`
  - Matched `randomizeFirstPlayer=true` first-200 `buildingA` seed bases.
  - Summary:
    `training/data/models/evaluation/winrate/rentNudgeA-rfp-lookahead-vs-hard-200-summary.json`.
    Result: 127/200 = 63.5%, 95% CI = 56.63%-69.86%.
  - Gate:
    `training/data/models/evaluation/winrate/rentNudgeA-rfp-lookahead-vs-hard-200-gate.json`;
    passed hard gate.
  - Comparison:
    `training/data/models/evaluation/winrate/rentNudgeA-rfp-vs-buildingA-rfp-first200-comparison.json`.
    Result vs matched `buildingA` first-200 = -1.0pp, rough p ~= 0.835.
  - Per-game diff: 4 changed matched seeds, net -2 wins.
- `passGo3500A-rfp`:
  - `monopoly.search.passGoExpectedValue=3500`
  - Matched `randomizeFirstPlayer=true` first-200 `buildingA` seed bases.
  - Summary:
    `training/data/models/evaluation/winrate/passGo3500A-rfp-lookahead-vs-hard-200-summary.json`.
    Result: 129/200 = 64.5%, 95% CI = 57.65%-70.80%.
  - Gate:
    `training/data/models/evaluation/winrate/passGo3500A-rfp-lookahead-vs-hard-200-gate.json`;
    passed hard gate.
  - Comparison:
    `training/data/models/evaluation/winrate/passGo3500A-rfp-vs-buildingA-rfp-first200-comparison.json`.
    Result vs matched `buildingA` first-200 = +0.0pp, rough p = 1.0.
  - Per-game diff: 2 changed matched seeds, net 0 wins.

Interpretation:

- Do not add a low-progress deploy penalty. The replay evidence is tied-heavy,
  and the strict labels mostly prefer `DEPOSIT` by tiny board-score gaps rather
  than reward or natural-win changes.
- Do not promote `rentNudgeA-rfp` or `passGo3500A-rfp`; both remain at or below
  matched `buildingA`.
- Self-audit note: one `rentNudgeA` gate/comparison attempt failed because the
  checks were launched before the summary file existed. The checks were rerun
  after confirming the summary file was written; the final artifacts above are
  valid.

## 2026-05-26 Same-Effect Target Gate Screen

Purpose: follow up on memento replay evidence that some losing decisions differ
from hard only by target/color choice, especially `STEAL_PROPERTY` target cards
and wild/deploy colors. This tests whether a narrow hard-target fallback can
improve `buildingA`.

Target-difference replay:

- Source traces:
  - `training/data/distillation/buildingA-memento-trace-seat1-30.jsonl`
  - `training/data/distillation/buildingA-memento-trace-seat2-30.jsonl`
- Selected rows:
  `training/data/distillation/buildingA-memento-losing-sameeffect-targetdiff-80.jsonl`.
- Selection report:
  `training/data/models/evaluation/winrate/buildingA-memento-losing-sameeffect-targetdiff-80-selection.json`.
  - Eligible rows = 71; selected rows = 65.
  - Selected by effect: 49 `DEPLOY`, 14 `STEAL_PROPERTY`, 2 `FORCED_DEAL`.
  - 21 losing sessions covered; max 4 rows per session.
- Replay report:
  `training/data/models/evaluation/winrate/buildingA-memento-losing-sameeffect-targetdiff-65-counterfactual.json`.
  - Decisions = 65, informative = 59.
  - Hard-choice resolution errors = 0; unmatched = 0.
  - Candidate errors = 0; incomplete candidates = 0.
  - Source-choice best count = 25; hard-choice best count = 27.
  - Source better than hard = 11; hard better than source = 20; same = 34.
  - Source natural wins = 1; hard natural wins = 4.
  - Average source-minus-hard reward = -0.0605.
  - Average source-minus-hard board score = -181.23.

Implementation:

- Added default-off target fallback knobs:
  - `monopoly.search.sameEffectHardTargetMargin` default `-1`.
  - `monopoly.search.sameEffectHardTargetEffects` default
    `STEAL_PROPERTY,FORCED_DEAL`.
- When enabled, if lookahead's model-best candidate and hard's candidate use the
  same card/effect but choose different target/color/actor-card fields, hard's
  target can be chosen unless lookahead leads by the configured margin.
- Default `buildingA` behavior is unchanged.

Candidates:

- `targetGateA`:
  - `monopoly.search.sameEffectHardTargetMargin=3000`
  - `monopoly.search.sameEffectHardTargetEffects=STEAL_PROPERTY,FORCED_DEAL`
  - Summary:
    `training/data/models/evaluation/winrate/targetGateA-lookahead-vs-hard-200-summary.json`.
    Result: 120/200 = 60.0%, 95% CI = 53.08%-66.54%.
  - 200/200 natural-ended, 0 forced, 0 unknown.
  - Seat split: 68/100 as seat1, 52/100 as seat2.
  - Hard gate passed:
    `training/data/models/evaluation/winrate/targetGateA-lookahead-vs-hard-200-gate.json`.
  - Comparison to `buildingA` 600:
    `training/data/models/evaluation/winrate/targetGateA-vs-buildingA-600-comparison.json`
    = -4.0pp, rough p ~= 0.310.
  - Losses by final complete sets:
    `training/data/models/evaluation/winrate/targetGateA-lookahead-vs-hard-200-loss-analysis.json`
    = 41 at 0 sets, 22 at 1 set, 17 at 2 sets.
- `targetGateB`:
  - `monopoly.search.sameEffectHardTargetMargin=1000`
  - `monopoly.search.sameEffectHardTargetEffects=STEAL_PROPERTY`
  - Summary:
    `training/data/models/evaluation/winrate/targetGateB-lookahead-vs-hard-100-summary.json`.
    Result: 58/100 = 58.0%, 95% CI = 48.21%-67.20%.
  - 100/100 natural-ended, 0 forced, 0 unknown.
  - Comparison to `buildingA` 600:
    `training/data/models/evaluation/winrate/targetGateB-vs-buildingA-600-comparison.json`
    = -6.0pp, rough p ~= 0.250.
  - Losses by final complete sets:
    `training/data/models/evaluation/winrate/targetGateB-lookahead-vs-hard-100-loss-analysis.json`
    = 25 at 0 sets, 9 at 1 set, 8 at 2 sets.

Interpretation:

- Same-effect hard target fallback is not promoted. Replay showed a real local
  target-selection weakness, but a static hard-target gate did not generalize
  in natural play.
- The likely issue is not "always trust hard's target." The useful next step is
  to derive structured target features, such as whether a steal/forced deal
  completes our set, breaks an opponent near-complete/full set, takes a wild, or
  sacrifices a high-leverage own card.
- Keep the knobs default-off for diagnostics only.

## 2026-05-26 BuildingA Memento Replay And Pass Go Deposit Screen

Purpose: audit whether `buildingA` has a local, high-confidence decision bug in
remaining losses. This step uses memento-backed traces and deterministic
counterfactual replay before trying a very narrow heuristic.

Memento trace:

- Reports:
  - `training/data/models/evaluation/winrate/mixed-buildingA-memento-trace-seat1-30x340.json`
  - `training/data/models/evaluation/winrate/mixed-buildingA-memento-trace-seat2-30x340.json`
- Trace files:
  - `training/data/distillation/buildingA-memento-trace-seat1-30.jsonl`
  - `training/data/distillation/buildingA-memento-trace-seat2-30.jsonl`
- Summary:
  `training/data/models/evaluation/winrate/buildingA-memento-trace-lookahead-vs-hard-60-summary.json`.
  - Result: 35/60 = 58.33%, 95% CI = 45.73%-69.94%.
  - 60/60 natural-ended, 0 forced, 0 unknown.
- Trace audit:
  `training/data/models/evaluation/winrate/buildingA-memento-trace-60-trace-audit.json`.
  - 1606 traced decisions across 60 sessions.
  - 25 losses; losing final complete-set buckets: 18 at 0 sets, 5 at 1 set,
    2 at 2 sets.
  - High-margin losing override transitions included `DEPLOY->DEPLOY`,
    `BIRTHDAY->DEPLOY`, `STEAL_PROPERTY->STEAL_PROPERTY`,
    `FORCED_DEAL->DEPLOY`, and `RENT_DUAL->DEPLOY`.

Counterfactual replay:

- Selected 40 high-margin losing override rows:
  - Trace: `training/data/distillation/buildingA-memento-highmargin-losing-40.jsonl`
  - Selection report:
    `training/data/models/evaluation/winrate/buildingA-memento-highmargin-losing-40-selection.json`
  - Eligible rows = 85, selected rows = 40.
- Replay report:
  `training/data/models/evaluation/winrate/buildingA-memento-highmargin-losing-40-counterfactual.json`.
  - Decisions = 40, informative = 36.
  - Hard-choice resolution errors = 0; unmatched = 0.
  - Candidate errors = 0; incomplete candidates = 0.
  - Source-choice best count = 14; hard-choice best count = 13.
  - Source better than hard = 6; hard better than source = 7; same = 27.
  - Source natural wins = 1; hard natural wins = 1.
  - Average source-minus-hard reward = +0.005.
  - Average source-minus-hard board score = -84.975.

Replay interpretation:

- The high-margin override sample does not show a broad local bug where
  `buildingA` systematically rejects a better hard move. Source and hard mostly
  tie under deterministic replay, with mixed small advantages.
- A strict training-row selector found 7 usable counterfactual rows:
  `training/data/models/evaluation/winrate/buildingA-memento-highmargin-losing-40-counterfactual-selection.json`.
  - 7/7 selected best choices were `DEPOSIT`.
  - Original effects: `PASS_GO` 5, `DEPLOY` 1, `STEAL_PROPERTY` 1.
  - Transitions: `PASS_GO->DEPOSIT` 5, `DEPLOY->DEPOSIT` 1,
    `STEAL_PROPERTY->DEPOSIT` 1.
  - 3/7 selected choices produced natural wins in replay.
- Treat this as a narrow "sometimes bank Pass Go instead of playing it" signal,
  not as evidence for broad cash/deposit promotion.

Implementation:

- Added default-off knob:
  `monopoly.search.passGoDepositBonus` default `0`.
- It only adjusts `DEPOSIT` candidates whose action-card effect is `PASS_GO`.
- The action-effect adjustment path now guards non-deposit action bonuses so a
  deposited action card cannot accidentally receive play-action bonuses.
- Default `buildingA` behavior remains unchanged.

Candidate:

- `passGoDepositA`:
  - `monopoly.search.passGoDepositBonus=1600`
- Reports:
  - `training/data/models/evaluation/winrate/mixed-passGoDepositA-lookahead-vs-hard-seat1-100x340.json`
  - `training/data/models/evaluation/winrate/mixed-passGoDepositA-lookahead-vs-hard-seat2-100x340.json`
- Summary:
  `training/data/models/evaluation/winrate/passGoDepositA-lookahead-vs-hard-200-summary.json`.
  - Result: 117/200 = 58.5%, 95% CI = 51.57%-65.11%.
  - 200/200 natural-ended, 0 forced, 0 unknown.
  - Seat split: 66/100 as seat1, 51/100 as seat2.
  - Hard gate passed:
    `training/data/models/evaluation/winrate/passGoDepositA-lookahead-vs-hard-200-gate.json`.
  - Comparison to `buildingA` 200:
    `training/data/models/evaluation/winrate/passGoDepositA-vs-buildingA-200-comparison.json`
    = -6.0pp, rough p ~= 0.218.
  - Comparison to `buildingA` 600:
    `training/data/models/evaluation/winrate/passGoDepositA-vs-buildingA-600-comparison.json`
    = -5.5pp, rough p ~= 0.164.
  - Loss analysis:
    `training/data/models/evaluation/winrate/passGoDepositA-lookahead-vs-hard-200-loss-analysis.json`.
    Losses by lookahead final complete sets: 33 at 0 sets, 34 at 1 set,
    16 at 2 sets.

Interpretation:

- `passGoDepositA` is a negative result for promotion. It remains above the
  hard gate but underperforms the current `buildingA` default by a practical
  margin in this 200-game screen.
- Do not change the default strategy.
- Keep `passGoDepositBonus` default-off as a diagnostic ablation knob.
- The next useful direction should stay replay-driven: inspect concrete
  target/wild/third-set mistakes, not broad deposit or cash-pressure bumps.

## 2026-05-26 BuildingA Default Outcome Trace And Cash Pressure Screen

Purpose: audit the newly promoted `buildingA` default instead of assuming the
64% 600-game result is the end state. This run uses natural outcome traces to
inspect the remaining losses and then screens one narrow cash-pressure
hypothesis.

Trace tooling update:

- `training/scripts/analyze_lookahead_outcome_trace.py` now reports session-level
  aggregates in addition to decision-row aggregates:
  - session win/loss count and session win rate;
  - final complete-set buckets for winning and losing sessions;
  - rows per session percentiles;
  - losing-session late-window action effects and hard-override transitions;
  - high-margin losing overrides where lookahead strongly rejected the hard
    fallback.
- This avoids over-reading raw decision-row counts, where one long game can
  contribute dozens of ordinary deploy/deposit rows.

Default `buildingA` trace:

- Reports:
  - `training/data/models/evaluation/winrate/mixed-buildingA-default-trace-seat1-60x340.json`
  - `training/data/models/evaluation/winrate/mixed-buildingA-default-trace-seat2-60x340.json`
- Trace files:
  - `training/data/distillation/buildingA-default-trace-seat1-60.jsonl`
  - `training/data/distillation/buildingA-default-trace-seat2-60.jsonl`
- Summary:
  `training/data/models/evaluation/winrate/buildingA-default-trace-lookahead-vs-hard-120-summary.json`.
  - Result: 68/120 = 56.67%, 95% CI = 47.73%-65.19%.
  - 120/120 natural-ended, 0 forced, 0 unknown.
- Trace audit:
  `training/data/models/evaluation/winrate/buildingA-default-trace-120-trace-audit.json`.
  - 3264 traced decisions across 120 sessions.
  - Session outcomes: 68 wins, 52 losses.
  - Losing final complete-set buckets: 22 losses at 0 sets, 23 at 1 set,
    7 at 2 sets.
  - High-margin losing overrides often rejected immediate cash pressure for
    deploy/pass-go choices, especially `BIRTHDAY->DEPLOY`,
    `RENT_DUAL->DEPLOY`, and `RENT_DUAL->PASS_GO`.

Trace interpretation:

- The low 120-game trace win rate is not an engine or terminal-parser failure:
  every game ended naturally and unknown count is 0.
- Treat the low rate as a seed-window warning, not a replacement for the
  stronger 600-game evidence.
- The remaining failure shape is still mostly early/tempo collapse. Most losses
  end at 0-1 complete sets, even under the building-aware default.
- The actionable hypothesis was that `buildingA` might overvalue board
  development in one-ply search and undervalue immediate rent/birthday/debt
  pressure in some losing games.

Implementation:

- Added default-off knob:
  `monopoly.search.birthdayExpectedPaidMultiplier` default `0`.
- It adds `expectedPaid * multiplier` to Birthday action candidates only when
  explicitly enabled.
- Existing rent/debt knobs were reused for the screen:
  `monopoly.search.rentExpectedPaidMultiplier`,
  `monopoly.search.debtExpectedPaidMultiplier`.
- Default `buildingA` behavior is unchanged by this knob.

Candidate:

- `cashPressureA`:
  - `monopoly.search.birthdayExpectedPaidMultiplier=500`
  - `monopoly.search.rentExpectedPaidMultiplier=720`
  - `monopoly.search.debtExpectedPaidMultiplier=420`
- Reports:
  - `training/data/models/evaluation/winrate/mixed-cashPressureA-lookahead-vs-hard-seat1-100x340.json`
  - `training/data/models/evaluation/winrate/mixed-cashPressureA-lookahead-vs-hard-seat2-100x340.json`
- Summary:
  `training/data/models/evaluation/winrate/cashPressureA-lookahead-vs-hard-200-summary.json`.
  - Result: 124/200 = 62.0%, 95% CI = 55.11%-68.44%.
  - 200/200 natural-ended, 0 forced, 0 unknown.
  - Seat split was uneven: 70/100 as seat1, 54/100 as seat2.
  - Gate against hard passed:
    `training/data/models/evaluation/winrate/cashPressureA-lookahead-vs-hard-200-gate.json`.
  - Comparison to `buildingA` 200:
    `training/data/models/evaluation/winrate/cashPressureA-vs-buildingA-200-comparison.json`
    = -2.5pp, rough p ~= 0.604.
  - Comparison to `buildingA` 600:
    `training/data/models/evaluation/winrate/cashPressureA-vs-buildingA-600-comparison.json`
    = -2.0pp, rough p ~= 0.611.
  - Loss analysis:
    `training/data/models/evaluation/winrate/cashPressureA-lookahead-vs-hard-200-loss-analysis.json`.
    Losses by lookahead final complete sets: 31 at 0 sets, 28 at 1 set,
    17 at 2 sets.

Interpretation:

- `cashPressureA` is a negative result for promotion. It remains stronger than
  hard, but it underperforms the current `buildingA` default and has a large
  seat split.
- Do not change the default strategy.
- Keep `birthdayExpectedPaidMultiplier` as a default-off ablation knob for
  future targeted screens.
- Next useful direction is not a broad cash-pressure bump. The trace suggests
  either more targeted late-game completion/blocking logic or a replay-based
  audit of high-margin overrides where lookahead selected a different wild or
  property target than hard.

## 2026-05-26 Natural Win-Rate Promotion To BuildingA Default

Purpose: align the main strength claim with natural multi-game play instead of
same-seed paired comparisons. Same-seed fields and paired tools remain useful
for replay/debug, but the current promotion question is whether a strategy wins
enough independent natural games against `hard`, with clean terminal parsing
and seat-balanced samples.

Policy update:

- Primary claim: seat-balanced natural multi-game win rate against `hard`.
- Required self-audit checks: natural-ended rate, unknown-game count, loss
  shape, and comparison against the strongest available natural baseline.
- Same-seed/paired-seed results are not the main promotion gate in this cycle.
- "Tie" in older paired reports is not an engine draw. It usually means the
  same focus-seat natural-win/rank result under treatment and control; the
  natural runner's unresolved rows are instead `forced` or `unknown` rows from
  snapshot/timeout limits.

Default change:

- `SearchLookaheadAiPlayStrategy` now defaults to the `buildingA` parameters:
  - `monopoly.search.buildingActionBonus=900`
  - `monopoly.search.buildingRentBonusValue=320`
  - `monopoly.search.opponentBuildingThreatValue=260`
- Other experimental knobs remain default-off.

Promotion evidence:

- `buildingA` 600-game natural summary:
  `training/data/models/evaluation/winrate/buildingA-lookahead-vs-hard-600-summary.json`.
  - Result: 384/600 = 64.0%, 95% CI = 60.08%-67.74%.
  - 600/600 natural-ended, 0 forced, 0 unknown.
  - Gate passed:
    `training/data/models/evaluation/winrate/buildingA-lookahead-vs-hard-600-gate.json`.
  - Loss analysis:
    `training/data/models/evaluation/winrate/buildingA-lookahead-vs-hard-600-loss-analysis.json`.
    Losses by lookahead final complete sets: 99 at 0 sets, 82 at 1 set,
    35 at 2 sets.
- Comparison to older default 600:
  `training/data/models/evaluation/winrate/buildingA-vs-default-600-comparison.json`
  = +3.83pp, rough p ~= 0.171. Practical lift passed; rough significance did
  not.
- Comparison to winnerparsefix 600:
  `training/data/models/evaluation/winrate/buildingA-vs-winnerparsefix-600-comparison.json`
  = +2.83pp, rough p ~= 0.311. Promotion-comparison significance did not pass.

Building ablations:

- `buildingActionOnlyA` (`buildingActionBonus=900` only):
  `training/data/models/evaluation/winrate/buildingActionOnlyA-lookahead-vs-hard-600-summary.json`.
  - Result: 367/600 = 61.17%, 95% CI = 57.21%-64.98%.
  - 600/600 natural-ended, 0 forced, 0 unknown.
  - Gate passed:
    `training/data/models/evaluation/winrate/buildingActionOnlyA-lookahead-vs-hard-600-gate.json`.
  - Equal to winnerparsefix 600 and below full `buildingA` by 2.83pp:
    `training/data/models/evaluation/winrate/buildingActionOnlyA-vs-winnerparsefix-600-comparison.json`,
    `training/data/models/evaluation/winrate/buildingActionOnlyA-vs-buildingA-600-comparison.json`.
  - Loss analysis:
    `training/data/models/evaluation/winrate/buildingActionOnlyA-lookahead-vs-hard-600-loss-analysis.json`.
    Losses by lookahead final complete sets: 105 at 0 sets, 82 at 1 set,
    46 at 2 sets.
- `buildingValueOnlyA` (`buildingRentBonusValue=320`,
  `opponentBuildingThreatValue=260` only):
  `training/data/models/evaluation/winrate/buildingValueOnlyA-lookahead-vs-hard-200-summary.json`.
  - Result: 112/200 = 56.0%, 95% CI = 49.07%-62.70%.
  - Gate failed because the CI lower bound dropped below 50%:
    `training/data/models/evaluation/winrate/buildingValueOnlyA-lookahead-vs-hard-200-gate.json`.
  - Clearly below full `buildingA` 200:
    `training/data/models/evaluation/winrate/buildingValueOnlyA-vs-buildingA-200-comparison.json`
    = -8.5pp, rough p ~= 0.0824.
  - Loss analysis:
    `training/data/models/evaluation/winrate/buildingValueOnlyA-lookahead-vs-hard-200-loss-analysis.json`.

Interpretation:

- `buildingA` is now the current default local strong AI because it has the
  best natural 600-game win rate against `hard` and a clean 0-unknown
  terminal audit.
- The claim is deliberately bounded: `buildingA` is confidently stronger than
  `hard`; it is not statistically proven to be stronger than every older
  lookahead sample under the rough two-proportion comparison.
- The ablations matter. Static building valuation alone is weak, while the
  action-only bonus regresses to the winnerparsefix baseline. The useful signal
  appears to be the combination of being willing to play house/hotel and
  valuing the resulting rent/threat state.
- Next useful work: collect outcome traces under the new default and inspect
  the remaining 216/600 losses, especially the 99 zero-set collapses.

## 2026-05-26 Natural Outcome Trace And Building Screen

Purpose: move the next optimization cycle from broad scalar guessing to
replayable action-level evidence. The natural runner can now collect lookahead
decision traces with final outcomes, so future loss audits can inspect the real
candidate set, chosen action, hard fallback action, lookahead scores, and
per-game replay seeds.

Implementation:

- `GameController` now has a `setLookaheadAiStrategyFactory(...)` test/simulation
  hook. Normal sessions still default to `SearchLookaheadAiPlayStrategy`.
- `MixedAiBattleExperimentRunner` supports:
  - `monopoly.mixedBattle.tracePath`
  - `monopoly.mixedBattle.traceMode=overwrite|replace|truncate`
  - `monopoly.mixedBattle.traceSchema=decision|outcome`
- In `traceSchema=outcome`, the runner records final game outcome for lookahead
  decisions through `OutcomeDecisionTraceSink`.
- `JsonlDecisionTraceSink` now resolves terminal winners from
  `lastActionSummary` when multiple players have 3+ complete sets, matching the
  natural win-rate summary scripts.
- Added `JsonlDecisionTraceSinkTest` for the multiple-3-set terminal outcome
  case.
- Added `training/scripts/analyze_lookahead_outcome_trace.py` to summarize losing
  decision effects, hard override transitions, margins vs hard, and examples.

Trace smoke:

- Report:
  `training/data/models/evaluation/winrate/mixed-trace-smoke-lookahead-vs-hard-2x180.json`.
- Trace:
  `training/data/distillation/mixed-trace-smoke-lookahead-vs-hard.jsonl`.
- Audit:
  `training/data/models/evaluation/winrate/mixed-trace-smoke-lookahead-vs-hard-audit.json`.
- Result: 2/2 lookahead wins, 47 traced lookahead decisions, all rows include
  candidate scores and final outcome. This is tooling evidence only, not a
  strength claim.

Trace probe:

- Report:
  `training/data/models/evaluation/winrate/mixed-traceprobe-lookahead-vs-hard-seat1-20x340.json`.
- Trace:
  `training/data/distillation/mixed-traceprobe-lookahead-vs-hard-seat1-20.jsonl`.
- Summary:
  `training/data/models/evaluation/winrate/traceprobe-lookahead-vs-hard-seat1-20-summary.json`.
  - Result: 11/20 = 55.0%, 20/20 natural-ended, 0 unknown.
- Audit:
  `training/data/models/evaluation/winrate/traceprobe-lookahead-vs-hard-seat1-20-trace-audit.json`.
  - 521 traced decisions: 278 from winning games, 243 from losing games.
  - Losing decisions by chosen effect: DEPLOY 105, DEPOSIT 66, PASS_GO 21,
    RENT_DUAL 15.
  - Losing hard-override transitions included HOUSE->DEPLOY 7 and
    HOUSE->DEPOSIT 7, suggesting the current lookahead scorer may undervalue
    house/hotel actions in some losing games.

Candidate:

- `buildingA`:
  - `monopoly.search.buildingActionBonus=900`
  - `monopoly.search.buildingRentBonusValue=320`
  - `monopoly.search.opponentBuildingThreatValue=260`
- Reports:
  - `training/data/models/evaluation/winrate/mixed-buildingA-lookahead-vs-hard-seat1-100x340.json`
  - `training/data/models/evaluation/winrate/mixed-buildingA-lookahead-vs-hard-seat2-100x340.json`
- Summary:
  `training/data/models/evaluation/winrate/buildingA-lookahead-vs-hard-200-summary.json`.
  - Result: 129/200 = 64.5%, 95% CI = 57.65%-70.80%.
  - 200/200 natural-ended, 0 forced, 0 unknown.
  - Gate against hard passed:
    `training/data/models/evaluation/winrate/buildingA-lookahead-vs-hard-200-gate.json`.
  - Comparison to seeded default 200:
    `training/data/models/evaluation/winrate/buildingA-vs-seededdefault-200-comparison.json`
    = +7.0pp, rough p ~= 0.151. Practical lift passed, rough significance did
    not.
  - Comparison to winnerparsefix 200:
    `training/data/models/evaluation/winrate/buildingA-vs-winnerparsefix-200-comparison.json`
    = -1.5pp, rough p ~= 0.753.
  - Loss analysis:
    `training/data/models/evaluation/winrate/buildingA-lookahead-vs-hard-200-loss-analysis.json`.
    Losses by lookahead final complete sets: 39 at 0 sets, 22 at 1 set,
    10 at 2 sets.

Interpretation:

- The trace infrastructure is now good enough for action-level natural loss
  audits.
- `buildingA` is a promising but unproven direction: it improved over the new
  seeded default block and reduced losses, but did not beat the existing
  winnerparsefix 200-game baseline and did not pass promotion comparison.
- Do not change default `SearchLookaheadAiPlayStrategy` parameters yet.
- Next steps: either run a larger `buildingA` confirmation block or test a
  smaller building-only action bonus after collecting more trace evidence.

600-game confirmation:

- Added reports:
  - `training/data/models/evaluation/winrate/mixed-buildingA-lookahead-vs-hard-seat1-extra200-200x340.json`
  - `training/data/models/evaluation/winrate/mixed-buildingA-lookahead-vs-hard-seat2-extra200-200x340.json`
- 600-game summary:
  `training/data/models/evaluation/winrate/buildingA-lookahead-vs-hard-600-summary.json`.
  - Result: 384/600 = 64.0%, 95% CI = 60.08%-67.74%.
  - 600/600 natural-ended, 0 forced, 0 unknown.
  - Gate against hard passed:
    `training/data/models/evaluation/winrate/buildingA-lookahead-vs-hard-600-gate.json`.
  - Comparison to winnerparsefix 600:
    `training/data/models/evaluation/winrate/buildingA-vs-winnerparsefix-600-comparison.json`
    = +2.83pp, rough p ~= 0.311. Promotion comparison failed because the lift
    missed the +3pp practical threshold and was not significant.
  - Comparison to default 600:
    `training/data/models/evaluation/winrate/buildingA-vs-default-600-comparison.json`
    = +3.83pp, rough p ~= 0.171. Practical lift passed, rough significance did
    not.
  - Loss analysis:
    `training/data/models/evaluation/winrate/buildingA-lookahead-vs-hard-600-loss-analysis.json`.
    Losses by lookahead final complete sets: 99 at 0 sets, 82 at 1 set,
    35 at 2 sets.

600-game interpretation:

- `buildingA` is clearly strong against `hard` and improved the raw 600-game
  win rate over both older default and winnerparsefix samples.
- It still does not pass the current promotion comparison, so default
  `SearchLookaheadAiPlayStrategy` parameters stay unchanged.
- The result is useful evidence that building valuation is worth another,
  narrower follow-up, but not enough to claim a new champion.

## 2026-05-26 Seeded Natural Runner And Set Tempo Screen

Purpose: improve the self-audit loop for natural multi-game evaluation, then
screen a simple property-set tempo hypothesis. This keeps natural win rate as
the promotion signal, but records per-game seeds so suspicious games can be
replayed instead of guessed about.

Runner change:

- `MixedAiBattleExperimentRunner` now defaults to seeded independent natural
  games:
  - `monopoly.mixedBattle.seeded=true` by default.
  - Optional `monopoly.mixedBattle.seedBase` controls the first deck seed.
  - Each game report records `deckSeed`, `firstPlayerSeed`, and `aiSeed`.
  - The report records `seedPolicy` explaining this is not a same-seed paired
    comparison.
- The runner snapshots and restores existing seed properties
  (`monopoly.deck.seed`, `monopoly.firstPlayer.seed`, `monopoly.ai.seed`) so a
  run does not leak deterministic state into later tests.
- Smoke:
  `training/data/models/evaluation/winrate/mixed-seeded-smoke-lookahead-vs-hard-2x120.json`
  confirmed seed fields are written. The 120-snapshot cap intentionally caused
  turn-limit rows and is not strength evidence.

Seeded default sanity baseline:

- Reports:
  - `training/data/models/evaluation/winrate/mixed-seededdefault-lookahead-vs-hard-seat1-100x340.json`
  - `training/data/models/evaluation/winrate/mixed-seededdefault-lookahead-vs-hard-seat2-100x340.json`
- Summary:
  `training/data/models/evaluation/winrate/seededdefault-lookahead-vs-hard-200-summary.json`.
  - Result: 115/200 = 57.5%, 95% CI = 50.57%-64.15%.
  - 200/200 natural-ended, 0 forced, 0 unknown.
  - Gate against hard passed:
    `training/data/models/evaluation/winrate/seededdefault-lookahead-vs-hard-200-gate.json`.
  - Loss analysis:
    `training/data/models/evaluation/winrate/seededdefault-lookahead-vs-hard-200-loss-analysis.json`.
    Losses by lookahead final complete sets: 42 at 0 sets, 27 at 1 set,
    16 at 2 sets.

Candidate:

- `settempoA`:
  - `monopoly.search.completeSetValue=14500`
  - `monopoly.search.nearCompleteValue=1150`
  - `monopoly.search.cappedProgressValue=720`
- Reports:
  - `training/data/models/evaluation/winrate/mixed-settempoA-lookahead-vs-hard-seat1-100x340.json`
  - `training/data/models/evaluation/winrate/mixed-settempoA-lookahead-vs-hard-seat2-100x340.json`
- Summary:
  `training/data/models/evaluation/winrate/settempoA-lookahead-vs-hard-200-summary.json`.
  - Result: 108/200 = 54.0%, 95% CI = 47.08%-60.77%.
  - 200/200 natural-ended, 0 forced, 0 unknown.
  - Gate against hard failed:
    `training/data/models/evaluation/winrate/settempoA-lookahead-vs-hard-200-gate.json`.
    It missed both the 55% win-rate threshold and the CI-lower-bound-above-50%
    threshold.
  - Comparison to winnerparsefix 200:
    `training/data/models/evaluation/winrate/settempoA-vs-winnerparsefix-200-comparison.json`
    = -12.0pp, rough p ~= 0.0143. Promotion comparison failed.
  - Comparison to the new seeded default sanity baseline:
    `training/data/models/evaluation/winrate/settempoA-vs-seededdefault-200-comparison.json`
    = -3.5pp, rough p ~= 0.481. Promotion comparison failed.
  - Loss analysis:
    `training/data/models/evaluation/winrate/settempoA-lookahead-vs-hard-200-loss-analysis.json`.
    Losses by lookahead final complete sets: 44 at 0 sets, 28 at 1 set,
    20 at 2 sets.

Interpretation:

- The seeded runner sanity check is weaker than the older 200-game
  winnerparsefix sample but still passes the hard gate, so the runner change did
  not obviously break natural evaluation.
- `settempoA` is a clear negative result. Simple scalar pressure toward complete
  sets/near-complete colors appears to make the strategy more brittle rather
  than faster.
- Do not promote or expand `settempoA`.
- Next useful directions should be narrower action-level rules or replayable
  loss audits, not another broad board-value scalar increase.

## 2026-05-26 Pass Go Tempo Screen And Evaluation Policy

Purpose: finish the current natural multi-game evaluation cycle and move the
main promotion signal back to independent game count. Same-seed/paired-seed
comparisons are still useful diagnostics, but the primary "is this stronger?"
question is now answered by enough natural games, a clean natural-ended rate,
unknown-game checks, and comparison against the current natural-win baseline.

Evaluation policy:

- Primary screen: natural multi-game win rate against `hard`, with both seat
  orders represented.
- Promotion check: compare the variant's natural win-rate summary against the
  current baseline summary, requiring a practical lift before changing the
  default.
- Same-seed/paired-seed reports are deferred to future narrow diagnostics and
  are not the current main promotion gate.
- Paired "ties" are not engine-level drawn games. They usually mean the focus
  seat reached the same natural-win/rank result in treatment and control under
  the same shuffle.

Candidates:

- `passgoA`:
  - `monopoly.search.passGoExpectedValue=2500`
  - Reports:
    - `training/data/models/evaluation/winrate/mixed-passgoA-lookahead-vs-hard-seat1-100x340.json`
    - `training/data/models/evaluation/winrate/mixed-passgoA-lookahead-vs-hard-seat2-100x340.json`
  - Summary:
    `training/data/models/evaluation/winrate/passgoA-lookahead-vs-hard-200-summary.json`.
    - Result: 114/200 = 57.0%, 95% CI = 50.07%-63.67%.
    - 200/200 natural-ended, 0 forced, 0 unknown.
    - Gate against hard passed:
      `training/data/models/evaluation/winrate/passgoA-lookahead-vs-hard-200-gate.json`.
    - Comparison to winnerparsefix 200:
      `training/data/models/evaluation/winrate/passgoA-vs-winnerparsefix-200-comparison.json`
      = -9.0pp, rough p ~= 0.0644. Promotion comparison failed.
    - Loss analysis:
      `training/data/models/evaluation/winrate/passgoA-lookahead-vs-hard-200-loss-analysis.json`.
      Losses by lookahead final complete sets: 40 at 0 sets, 25 at 1 set,
      21 at 2 sets.
- `passgoB`:
  - `monopoly.search.passGoExpectedValue=3200`
  - Reports:
    - `training/data/models/evaluation/winrate/mixed-passgoB-lookahead-vs-hard-seat1-100x340.json`
    - `training/data/models/evaluation/winrate/mixed-passgoB-lookahead-vs-hard-seat2-100x340.json`
  - Summary:
    `training/data/models/evaluation/winrate/passgoB-lookahead-vs-hard-200-summary.json`.
    - Result: 131/200 = 65.5%, 95% CI = 58.68%-71.74%.
    - 200/200 natural-ended, 0 forced, 0 unknown.
    - Gate against hard passed:
      `training/data/models/evaluation/winrate/passgoB-lookahead-vs-hard-200-gate.json`.
    - Comparison to winnerparsefix 200:
      `training/data/models/evaluation/winrate/passgoB-vs-winnerparsefix-200-comparison.json`
      = -0.5pp, rough p ~= 0.916. Promotion comparison failed.
    - Loss analysis:
      `training/data/models/evaluation/winrate/passgoB-lookahead-vs-hard-200-loss-analysis.json`.
      Losses by lookahead final complete sets: 31 at 0 sets, 22 at 1 set,
      16 at 2 sets.

Interpretation:

- Lowering Pass Go value aggressively hurts natural win rate. `passgoA` remains
  above hard, but it is clearly worse than the current baseline.
- A mild reduction (`passgoB`) is essentially neutral against the current
  200-game baseline and does not meet the promotion bar.
- Do not change the default `monopoly.search.passGoExpectedValue=4000`.
- The remaining loss pattern is still tempo/property-set related: losses mostly
  occur with lookahead stuck at 0-2 complete sets while the hard winner reaches
  3+ sets. Money alone is not the primary failure mode.

## 2026-05-26 Tactical Candidate Reservation Screen

Purpose: test an action-level idea after the defensive scalar experiments
failed. Instead of changing the board evaluator, this screen checks whether the
lookahead search was pruning away important swing candidates before simulation.

Implementation:

- Added default-off JVM knob:
  `monopoly.search.tacticalReservedCandidates` default `0`.
- When enabled, `SearchLookaheadAiPlayStrategy.pruneToLimit()` keeps the normal
  top candidate set, then reserves up to N extra slots for tactical candidates:
  `DEAL_BREAKER`, `FORCED_DEAL`, `STEAL_PROPERTY`, and high-progress `DEPLOY`
  candidates.
- Default behavior is unchanged when the knob is `0`.

Candidate:

- `tacticalReserveA`:
  - `monopoly.search.tacticalReservedCandidates=6`
- Reports:
  - `training/data/models/evaluation/winrate/mixed-tacticalreserveA-lookahead-vs-hard-seat1-100x340.json`
  - `training/data/models/evaluation/winrate/mixed-tacticalreserveA-lookahead-vs-hard-seat2-100x340.json`
- Summary:
  `training/data/models/evaluation/winrate/tacticalreserveA-lookahead-vs-hard-200-summary.json`.
  - Result: 123/200 = 61.5%, 95% CI = 54.60%-67.97%.
  - 200/200 natural-ended, 0 forced, 0 unknown.
  - Gate against hard passed:
    `training/data/models/evaluation/winrate/tacticalreserveA-lookahead-vs-hard-200-gate.json`.
  - Comparison to winnerparsefix 200:
    `training/data/models/evaluation/winrate/tacticalreserveA-vs-winnerparsefix-200-comparison.json`
    = -4.5pp, rough p ~= 0.349.
  - Loss analysis:
    `training/data/models/evaluation/winrate/tacticalreserveA-lookahead-vs-hard-200-loss-analysis.json`.
    - Losses by lookahead final complete sets: 38 at 0 sets, 25 at 1 set,
      14 at 2 sets.

Interpretation:

- Reserving broad extra tactical candidates did not help. It increased search
  work and underperformed the current winnerparsefix baseline.
- Keep the knob default-off as an ablation/debug tool, but do not promote it.
- This suggests the next candidate should not simply evaluate more actions.
  It should either improve the quality of specific swing-action summaries/
  scores, or use a targeted rule for when a swing action directly completes or
  prevents the third set.

## 2026-05-26 Conditional Threat Screen

Purpose: test a narrower version of the defensive-pressure idea. The prior
ThreatA screen reduced zero-set collapse losses but did not improve win rate;
this cycle tested whether an extra threat penalty only in behind/high-danger
states would work better than global defensive weighting.

Implementation:

- Added default-off JVM knobs to `SearchLookaheadAiPlayStrategy`:
  - `monopoly.search.conditionalThreatWeight` default `0`.
  - `monopoly.search.conditionalThreatSelfMaxSets` default `1`.
  - `monopoly.search.conditionalThreatOpponentMinSets` default `2`.
  - `monopoly.search.conditionalThreatOpponentMinAlmost` default `2`.
- With the default weight at `0`, the promoted strategy behavior is unchanged.

Candidate:

- `condThreatA`:
  - `monopoly.search.conditionalThreatWeight=0.45`
  - `monopoly.search.conditionalThreatSelfMaxSets=1`
  - `monopoly.search.conditionalThreatOpponentMinSets=2`
  - `monopoly.search.conditionalThreatOpponentMinAlmost=2`
- Reports:
  - `training/data/models/evaluation/winrate/mixed-condthreatA-lookahead-vs-hard-seat1-100x340.json`
  - `training/data/models/evaluation/winrate/mixed-condthreatA-lookahead-vs-hard-seat2-100x340.json`
- Summary:
  `training/data/models/evaluation/winrate/condthreatA-lookahead-vs-hard-200-summary.json`.
  - Result: 109/200 = 54.5%, 95% CI = 47.58%-61.25%.
  - 200/200 natural-ended, 0 forced, 0 unknown.
  - Gate failed:
    `training/data/models/evaluation/winrate/condthreatA-lookahead-vs-hard-200-gate.json`.
    It missed both the 55% win-rate threshold and the CI-lower-bound-above-50%
    threshold.
  - Comparison to winnerparsefix 200:
    `training/data/models/evaluation/winrate/condthreatA-vs-winnerparsefix-200-comparison.json`
    = -11.5pp, rough p ~= 0.0188.
  - Loss analysis:
    `training/data/models/evaluation/winrate/condthreatA-lookahead-vs-hard-200-loss-analysis.json`.
    - Losses by lookahead final complete sets: 43 at 0 sets, 21 at 1 set,
      26 at 2 sets, 1 at 3 sets.

Interpretation:

- The current conditional threat formula is too blunt and clearly hurts
  gameplay. It should not be promoted or expanded.
- Keep the code path as a default-off experiment switch only, because it is
  useful for controlled ablations and does not affect default behavior.
- The negative result argues against adding more threat penalties at the
  evaluator level. Next useful directions are likely action-specific tactics
  for completing/stealing/blocking sets, or better candidate ordering, not
  another global or semi-global defensive scalar.

## 2026-05-26 Threat-Weight Defensive Screens

Purpose: use the richer natural-win loss diagnostics to pick a small
optimization direction, then screen it under the multi-game natural win-rate
protocol.

Diagnostics:

- Updated `training/scripts/analyze_mixed_winrate_losses.py` to include aggregate
  feature stats and complete-set buckets for wins/losses.
- Rebuilt
  `training/data/models/evaluation/winrate/winnerparsefix-lookahead-vs-hard-600-loss-analysis.json`.
- Winnerparsefix 600-game loss structure:
  - Wins/losses: 367/233.
  - Close losses: 34.
  - Blowout losses: 199.
  - Losses by lookahead final complete sets: 112 at 0 sets, 87 at 1 set,
    34 at 2 sets.
  - Average loss state: lookahead 0.67 complete sets and 7.39 properties;
    hard winner 3.11 complete sets and 14.55 properties.
- Interpretation: most remaining losses are not "one move short" endings; they
  are early/mid-game property-progress collapses. This made defensive pressure
  against opponent progress a reasonable next screen.

ThreatA screen:

- Candidate knobs:
  - `monopoly.search.threatWeight=1.35`
  - `monopoly.search.maxOpponentWeight=1.02`
- Reports:
  - `training/data/models/evaluation/winrate/mixed-threatA-lookahead-vs-hard-seat1-100x340.json`
  - `training/data/models/evaluation/winrate/mixed-threatA-lookahead-vs-hard-seat2-100x340.json`
- Summary:
  `training/data/models/evaluation/winrate/threatA-lookahead-vs-hard-200-summary.json`.
  - Result: 132/200 = 66.0%, 95% CI = 59.19%-72.21%.
  - 200/200 natural-ended, 0 forced, 0 unknown.
  - Gate passed:
    `training/data/models/evaluation/winrate/threatA-lookahead-vs-hard-200-gate.json`.
  - Comparison against winnerparsefix 200:
    `training/data/models/evaluation/winrate/threatA-vs-winnerparsefix-200-comparison.json`
    = 0.0pp lift, p = 1.0; no promotion.
  - Loss analysis:
    `training/data/models/evaluation/winrate/threatA-lookahead-vs-hard-200-loss-analysis.json`.
    - Losses by lookahead final complete sets: 19 at 0 sets, 27 at 1 set,
      22 at 2 sets.
    - This is structurally better than the winnerparsefix 600 loss mix, but it
      did not improve total wins in the 200-game screen.

ThreatB screen:

- Candidate knob:
  - `monopoly.search.threatWeight=1.35`
- Reports:
  - `training/data/models/evaluation/winrate/mixed-threatB-lookahead-vs-hard-seat1-100x340.json`
  - `training/data/models/evaluation/winrate/mixed-threatB-lookahead-vs-hard-seat2-100x340.json`
- Summary:
  `training/data/models/evaluation/winrate/threatB-lookahead-vs-hard-200-summary.json`.
  - Result: 122/200 = 61.0%, 95% CI = 54.09%-67.49%.
  - Gate against hard passed, but it is 10 wins behind winnerparsefix 200.
  - Comparison:
    `training/data/models/evaluation/winrate/threatB-vs-winnerparsefix-200-comparison.json`
    = -5.0pp, p ~= 0.299; no promotion.
  - Loss analysis:
    `training/data/models/evaluation/winrate/threatB-lookahead-vs-hard-200-loss-analysis.json`.
    - Losses by lookahead final complete sets: 37 at 0 sets, 18 at 1 set,
      23 at 2 sets.

Interpretation:

- The defensive-pressure idea has some diagnostic signal: ThreatA reduced
  zero-set collapse losses in the 200-game sample.
- It did not produce a win-rate lift over winnerparsefix, and ThreatB clearly
  regressed. Do not promote either parameter set.
- Keep the new loss-analysis feature stats; they are useful for detecting
  whether a candidate changes failure shape even when win rate does not move.
- Next work should avoid simply increasing global threat weight further. A more
  targeted candidate would need to activate only when an opponent is at 2+
  complete sets or when lookahead has 0-1 sets, rather than making the whole
  evaluator more defensive.

## 2026-05-26 Lookahead Natural Winner Parsing Fix

Purpose: continue the multi-game natural win-rate optimization loop and audit a
program-side inconsistency found while reading loss reports.

Finding:

- `training/scripts/summarize_mixed_winrate.py` and
  `training/scripts/analyze_mixed_winrate_losses.py` already resolve old ambiguous
  final snapshots by parsing `lastActionSummary` when both players have at
  least 3 complete sets.
- `SearchLookaheadAiPlayStrategy.stateValue()` still treated these simulated
  terminal snapshots as unknown because `naturalWinnerId()` only accepted a
  unique player with 3+ sets.
- This is not a game-engine win/loss bug. It is a strategy-evaluator mismatch:
  the engine records the real winner in `lastActionSummary`, while the
  lookahead scorer previously ignored that tie-break evidence during simulated
  candidate evaluation.

Change:

- `SearchLookaheadAiPlayStrategy.naturalWinnerId()` now falls back to
  `lastActionSummary` when the terminal snapshot has multiple 3+ set players.
- Added `SearchLookaheadAiPlayStrategyTest` coverage for:
  - unique 3-set natural winner,
  - multiple 3-set players resolved by `lastActionSummary`,
  - ambiguous terminal snapshot with no matching summary.

Evaluation:

- 200-game screen:
  `training/data/models/evaluation/winrate/winnerparsefix-lookahead-vs-hard-200-summary.json`.
  - Result: 132/200 = 66.0%, 95% CI = 59.19%-72.21%.
  - 200/200 natural-ended, 0 forced, 0 unknown.
  - Gate:
    `training/data/models/evaluation/winrate/winnerparsefix-lookahead-vs-hard-200-gate.json`;
    passed.
  - Promotion comparisons failed only on statistical confidence:
    - vs default 200:
      `training/data/models/evaluation/winrate/winnerparsefix-vs-default-200-comparison.json`
      = +5.5pp, rough p ~= 0.254.
    - vs default 600:
      `training/data/models/evaluation/winrate/winnerparsefix-vs-default-600-comparison.json`
      = +5.83pp, rough p ~= 0.142.
- 600-game expansion:
  `training/data/models/evaluation/winrate/winnerparsefix-lookahead-vs-hard-600-summary.json`.
  - Result: 367/600 = 61.17%, 95% CI = 57.21%-64.98%.
  - 600/600 natural-ended, 0 forced, 0 unknown.
  - Gate:
    `training/data/models/evaluation/winrate/winnerparsefix-lookahead-vs-hard-600-gate.json`;
    passed.
  - Direct comparison to the previous default 600 baseline:
    `training/data/models/evaluation/winrate/winnerparsefix-vs-default-600-comparison.json`
    = +1.0pp, rough p ~= 0.723; fails promotion comparison.
  - Loss analysis:
    `training/data/models/evaluation/winrate/winnerparsefix-lookahead-vs-hard-600-loss-analysis.json`.
    - 233 losses: 34 close losses, 199 blowout losses.

Interpretation:

- Keep the natural-winner parsing fix because it makes strategy scoring
  consistent with the engine/evaluation reports and removes a real blind spot.
- Do not claim a new significantly stronger model from this change alone. The
  600-game result remains confidently stronger than `hard`, but the lift over
  the prior default baseline is too small to promote as a distinct strength
  improvement.
- Next optimization work should target remaining blowout losses and avoid
  judging candidates only by a 200-game early spike.

## 2026-05-26 Natural Multi-Game Evaluation Cycle

Purpose: finish the current experiment cycle and make multi-game natural
win-rate the primary strength readout again. Same-seed paired/seed-control
experiments are retained as diagnostic evidence for future work, but promotion
now depends on enough independent games to average out draw/order luck.

Evaluation policy:

- Primary metric: natural wins over natural-ended games, with both seat orders
  covered and enough games to reduce luck noise.
- Gate against `hard`: minimum games, high natural-ended rate, low unknown rate,
  win rate above 55%, and Wilson 95% CI lower bound above 50%.
- Candidate promotion: pass the hard gate, then beat the current default
  lookahead by a practical margin using
  `training/scripts/compare_mixed_winrate_summaries.py`.
- Same-seed paired reports are not the main promotion metric for now. They are
  useful to inspect where a change alters a specific game path, but many
  paired "ties" simply mean the candidate and baseline reached the same final
  winner/rank under the same shuffle, not that the game engine produced drawn
  games.

Tooling and audit fixes:

- `MixedAiBattleExperimentRunner` now records strategy properties, natural
  winner team/player, natural-winner resolution, board score, action-zone
  count, near-complete color count, and closest missing-to-complete signal.
- `training/scripts/summarize_mixed_winrate.py` now resolves old ambiguous final
  snapshots by first using explicit natural-winner fields when present, then
  parsing `lastActionSummary`, then falling back to a unique 3-set player.
  This fixed the older default 200-game summary from 120/199 with 1 unknown to
  121/200 with 0 unknown.
- Added `training/scripts/analyze_mixed_winrate_losses.py` for natural-win loss
  analysis, including close-loss/blowout-loss counts and board/set deltas.

Current baseline evidence:

- Default lookahead 600-game natural win-rate:
  `training/data/models/evaluation/winrate/default-lookahead-vs-hard-600-summary.json`.
  - Result: 361/600 = 60.17%, 95% CI = 56.20%-64.01%.
  - 600/600 natural-ended, 0 forced, 0 unknown.
  - Gate:
    `training/data/models/evaluation/winrate/default-lookahead-vs-hard-600-gate.json`;
    passed.
- Corrected default lookahead 200-game summary:
  `training/data/models/evaluation/winrate/default-lookahead-vs-hard-200-summary.json`.
  - Result: 121/200 = 60.5%, 95% CI = 53.59%-67.02%.
  - Gate:
    `training/data/models/evaluation/winrate/default-lookahead-vs-hard-200-gate.json`;
    passed.
- Diagnostic 60-game report:
  `training/data/models/evaluation/winrate/default-lookahead-vs-hard-diagnostic-60-summary.json`.
  - Result: 38/60 = 63.33%, 0 unknown.
  - Loss analysis:
    `training/data/models/evaluation/winrate/default-lookahead-vs-hard-diagnostic-60-loss-analysis.json`.
    - 22 losses: 5 close losses, 17 blowout losses.

Property-tempo candidate:

- Candidate knobs:
  - `monopoly.search.bankValue=20`
  - `monopoly.search.nearCompleteValue=1400`
  - `monopoly.search.cappedProgressValue=780`
- Summary:
  `training/data/models/evaluation/winrate/propertytempoA-lookahead-vs-hard-200-summary.json`.
  - Result: 118/200 = 59.0%, 95% CI = 52.08%-65.58%.
  - Gate against hard passed, so it is still stronger than `hard`.
- Promotion comparisons:
  - `training/data/models/evaluation/winrate/propertytempoA-vs-default-200-comparison.json`.
    - Lift vs corrected default 200 = -1.5pp, rough p ~= 0.760.
  - `training/data/models/evaluation/winrate/propertytempoA-vs-default-600-comparison.json`.
    - Lift vs default 600 = -1.17pp, rough p ~= 0.771.
- Interpretation: propertyTempoA does not beat the default lookahead and should
  not be promoted.

Conclusion:

- Keep default `SearchLookaheadAiPlayStrategy` as the current promoted local
  strong AI.
- For the next cycle, run candidate screens with the multi-game natural
  win-rate gate first. Use seed/paired controls later as a secondary diagnostic
  when we specifically need to explain a regression or verify a narrow fix.

## 2026-05-26 Multi-Game Win-Rate Gate And Rollout Screens

Purpose: make the new natural-win-rate evaluation path reproducible, then
screen two remaining-turn rollout variants under the same 200-game protocol as
the default lookahead and cf72 MLP runs.

Evaluation tooling:

- `MixedAiBattleExperimentRunner` now records `randomizeFirstPlayer`,
  selected `monopoly.search.*` / `monopoly.mixedBattle.*` strategy properties,
  unique natural winner team/player fields per game, and compact stdout when
  `monopoly.mixedBattle.quiet=true`.
- Added `training/scripts/check_mixed_winrate_gate.py`.
  - Gate checks: minimum games, natural-ended game rate, unknown-game rate,
    natural win rate, and Wilson 95% CI lower bound above the 50% hard baseline.
- Added `training/scripts/compare_mixed_winrate_summaries.py`.
  - Promotion comparison checks practical absolute lift over the current
    default plus a rough two-proportion significance screen.
  - This prevents treating a one- or two-game 200-game difference as a real
    promotion signal.

Baseline gates:

- Default lookahead:
  `training/data/models/evaluation/winrate/default-lookahead-vs-hard-200-gate.json`.
  - Passed gate: 120/199 = 60.3%, 95% CI = 53.4%-66.8%.
- cf72 MLP:
  `training/data/models/evaluation/winrate/cf72mlp-lookahead-vs-hard-200-gate.json`.
  - Passed gate: 123/200 = 61.5%, 95% CI = 54.6%-68.0%.
  - Direct comparison to default:
    `training/data/models/evaluation/winrate/cf72mlp-vs-default-200-comparison.json`
    fails promotion: absolute lift +1.2pp, rough p ~= 0.806.

Remaining-turn rollout screens:

- `monopoly.search.rolloutRemainingTurn=true`,
  `monopoly.search.rolloutPolicy=search`:
  - Reports:
    `training/data/models/evaluation/winrate/mixed-rollremainsearch-lookahead-vs-hard-seat1-100x340.json`,
    `training/data/models/evaluation/winrate/mixed-rollremainsearch-lookahead-vs-hard-seat2-100x340.json`.
  - Summary:
    `training/data/models/evaluation/winrate/rollremainsearch-lookahead-vs-hard-200-summary.json`.
  - Result: 122/200 = 61.0%, 95% CI = 54.1%-67.5%.
  - Gate passed against hard, but direct comparison to default fails promotion:
    `training/data/models/evaluation/winrate/rollremainsearch-vs-default-200-comparison.json`
    has absolute lift +0.7pp and rough p ~= 0.886.
- `monopoly.search.rolloutRemainingTurn=true`,
  `monopoly.search.rolloutPolicy=hard`:
  - Reports:
    `training/data/models/evaluation/winrate/mixed-rollremainhard-lookahead-vs-hard-seat1-100x340.json`,
    `training/data/models/evaluation/winrate/mixed-rollremainhard-lookahead-vs-hard-seat2-100x340.json`.
  - Summary:
    `training/data/models/evaluation/winrate/rollremainhard-lookahead-vs-hard-200-summary.json`.
  - Result: 117/200 = 58.5%, 95% CI = 51.6%-65.1%.
  - Gate passed against hard, but direct comparison to default fails promotion:
    `training/data/models/evaluation/winrate/rollremainhard-vs-default-200-comparison.json`
    has absolute lift -1.8pp and rough p ~= 0.714.

Interpretation:

- The current default `SearchLookaheadAiPlayStrategy` remains the safest
  promoted local strong AI. It passes the natural win-rate gate against hard.
- Both remaining-turn rollout variants are slower, and neither shows practical
  or statistical lift over the default at 200 games. Do not promote them.
- Future candidate promotion should require two levels:
  1. Pass the mixed win-rate gate against hard.
  2. Beat the default lookahead by a practical margin using
     `training/scripts/compare_mixed_winrate_summaries.py`.

## 2026-05-26 Lossprobe V2 And Win-Rate Evaluation Pivot

Purpose: finish the larger loss-state counterfactual corrector cycle, preserve
the same-seed paired diagnostics, and switch the primary gameplay readout back
to multi-game natural win rate.

Loss-state dataset:

- Full core loss pool:
  `training/data/models/evaluation/lookahead-rent600-core-failure-analysis-largest40.json`.
  - Rank-only losses found across the current rent600 core reports = 29.
  - Split file: `training/data/models/evaluation/lossprobe-v2/loss-seed-split-v1.json`
    with 15 train losses and 14 holdout losses.
- Train trace collection:
  `training/data/models/traces/lossprobe-v2/train-combined-lookahead-losses.jsonl`.
  - Rows = 431, all with memento, covering 15 reproduced train losses.
  - Reproduction summary:
    `training/data/models/evaluation/lossprobe-v2/train-default-losses-summary.json`
    = 0/15/0 for lookahead treatment vs hard control.

Counterfactual labels and students:

- Counterfactual replay:
  `training/data/models/evaluation/lossprobe-v2/train-counterfactual-replay.json`.
  - Decisions = 431, informative = 312.
  - `candidateErrors=0`, `incompleteCandidates=0`.
  - Source/lookahead natural wins = 86; hard natural wins = 95.
  - Average source-minus-hard reward = -0.0239.
- Selected high-confidence labels:
  `training/data/models/traces/lossprobe-v2/train-counterfactual-disagree-gap100.jsonl`.
  - Rows selected = 72.
  - Selected best effects = 38 `DEPOSIT`, 28 `DEPLOY`, 4 `STEAL_PROPERTY`,
    2 `FORCED_DEAL`.
  - 47/72 selected labels produce natural wins under counterfactual replay.
- Trained students:
  - MLP:
    `backend/models/distillation/lookahead-lossprobe-v2-cf72-mlp/candidate_ranker_mlp.json`,
    session validation top-1 = 0.500, MRR = 0.588.
  - Linear:
    `backend/models/distillation/lookahead-lossprobe-v2-cf72-linear/candidate_ranker_linear.json`,
    session validation top-1 = 0.500, MRR = 0.580.

Held-out loss evaluation:

- Default holdout reproduction:
  `training/data/models/evaluation/lossprobe-v2/holdout/default-summary.json`.
  - 14/14 holdout losses reproduced as hard/control wins.
- MLP corrector holdout:
  `training/data/models/evaluation/lossprobe-v2/holdout/cf72mlp-summary.json`.
  - Absolute result vs hard = 0 treatment wins, 7 control wins, 7 ties.
  - Direct treatment-policy compare to default:
    `training/data/models/evaluation/lossprobe-v2/holdout/cf72mlp-vs-default-treatment-compare.json`
    = 7/0/7 for MLP vs default, so the corrector fixed half of the held-out
    loss states into rank-only ties/wins relative to default behavior.
- Linear corrector holdout:
  `training/data/models/evaluation/lossprobe-v2/holdout/cf72linear-summary.json`.
  - Absolute result vs hard = 0/14/0; direct compare to default = 0/0/14.
  - Not useful.
- Interpretation: the 72-row MLP corrector has real loss-state signal, but it
  still does not beat hard on the held-out loss pool and is too targeted to
  promote.

Broad paired diagnostic:

- Fixed-seat 80-pair broad AB at seed base `2026370101`:
  - Default:
    `training/data/models/evaluation/lossprobe-v2/broad/default-fixed-80-summary.json`
    = 9/3/68 versus hard.
  - cf72 MLP:
    `training/data/models/evaluation/lossprobe-v2/broad/cf72mlp-fixed-80-summary.json`
    = 10/6/64 versus hard.
  - Direct default-vs-MLP treatment compare:
    `training/data/models/evaluation/lossprobe-v2/broad/cf72mlp-vs-default-fixed-80-treatment-compare.json`
    = 5/7/68, MLP margin -2.
- Tie audit:
  - Default and MLP reports both had 80/80 pairs end with
    `(NATURAL_WIN, NATURAL_WIN)`.
  - Default ties = 35 both focus-seat wins + 33 both focus-seat losses.
  - MLP ties = 32 both focus-seat wins + 32 both focus-seat losses.
  - No `PAIRED_SEED_TIMEOUT`, snapshot-limit, or no-snapshot artifact was found.
- Interpretation: same-seed paired ties mostly mean "the policy change did not
  change the focus seat from win to loss or loss to win." They are not evidence
  of a backend/game-engine bug when end reasons are natural wins.

Multi-game win-rate pivot:

- Added `training/scripts/summarize_mixed_winrate.py`.
  - Primary metric: target team's natural wins divided by natural-ended games.
  - It normalizes `AI-Lookahead-*` display names because older
    `MixedAiBattleExperimentRunner` reports tagged them as `human`.
- Fixed future `MixedAiBattleExperimentRunner` team tagging so lookahead/search
  seats report `team=lookahead`.
- Added `monopoly.mixedBattle.quiet` for future long win-rate runs, avoiding
  huge action logs and slow stdout redirection.
- Default lookahead vs hard, random non-seeded multi-game evaluation:
  `training/data/models/evaluation/winrate/default-lookahead-vs-hard-200-summary.json`.
  - 200 requested games, 199 natural scored games.
  - Lookahead wins = 120/199 = 0.603, 95% CI = 0.534-0.668.
  - Seat1: 64/100; seat2: 56/99.
- cf72 MLP corrector vs hard, same win-rate protocol:
  `training/data/models/evaluation/winrate/cf72mlp-lookahead-vs-hard-200-summary.json`.
  - 200 requested games, 200 natural scored games.
  - Lookahead wins = 123/200 = 0.615, 95% CI = 0.546-0.680.
  - Seat1: 61/100; seat2: 62/100.
- Interpretation: cf72 MLP is only +1.2 percentage points over default in this
  200-game natural-win readout, with heavily overlapping confidence intervals,
  and it is materially slower. It is not a promotable default.

Conclusion:

- Keep default `SearchLookaheadAiPlayStrategy` as the only safe local AI that
  can be described as stronger than `hard`.
- Retain same-seed paired reports as diagnostics for targeted regressions and
  loss-state analysis, but use multi-game natural win rate as the main
  intuitive strength readout going forward.

## 2026-05-26 Lookahead Failure Analysis And Corrector Audit

Purpose: audit where the proven default lookahead/search champion still loses,
then test small correction mechanisms without weakening the existing default.

Core failure analysis:

- Tool added: `training/scripts/analyze_paired_report_failures.py`.
  - It recomputes rank-only paired outcomes from one or more paired reports,
    summarizes natural-win signatures, end reasons, score/rank/snapshot deltas,
    and lists losses plus large score swings.
- Core 600-pair audit:
  `training/data/models/evaluation/lookahead-rent600-core-failure-analysis.json`.
  - Rank-only outcome = 92/29/479 for lookahead treatment vs hard control.
  - Natural signatures = `L->L` 208, `W->W` 271, `L->W` 92, `W->L` 29.
  - End reasons = 1200/1200 `NATURAL_WIN`, so the 29 control wins are real
    paired losses, not snapshot/time-budget artifacts.
- Refreshed summary:
  `training/data/models/evaluation/lookahead-rent600-core-600-summary-refresh.json`.
  - Decisive paired treatment rate = 0.760, 95% CI = 0.677-0.828.
  - Paired margin = +63.
  - Interpretation: the champion remains robustly stronger than hard, but the
    loss cases are reproducible enough to study.

Loss probes and counterfactual replay:

- Probed fixed-seat loss seeds:
  `2026230126`, `2026230128`, `2026230143`, `2026230151`,
  `2026230165`, `2026230189`, `2026230194`, `2026230196`.
- Reports:
  `training/data/models/evaluation/lossprobe/paired-hard-vs-lookahead-lossprobe-seat2-seed*.json`.
  - All 8 reproduced as hard/control wins with 16/16 `NATURAL_WIN`.
- Combined trace:
  `/tmp/lookahead-lossprobe-seat2-top8-traces.jsonl`.
- Counterfactual report:
  `training/data/models/evaluation/lossprobe/lookahead-lossprobe-seat2-top8-counterfactual.json`.
  - Decisions = 187, informative = 144.
  - `candidateErrors=0`, `incompleteCandidates=0`.
  - Source/lookahead best count = 77; hard best count = 78.
  - Source better than hard = 13; hard better than source = 19; same = 155.
  - Source natural wins = 32; hard natural wins = 36.
  - Average source-minus-hard reward = -0.0287; average board score = -58.4.
- Interpretation: these failures are not dominated by one obvious bad action.
  Hard is slightly better across the probed routes, which suggests a deep
  multi-step planning gap rather than a single local scoring typo.

Default-off correction knobs:

- `SearchLookaheadAiPlayStrategy` gained experimental zero-default knobs:
  `monopoly.search.stealPropertyBonus`,
  `monopoly.search.forcedDealBonus`,
  `monopoly.search.buildingActionBonus`,
  `monopoly.search.rentActionBonus`,
  `monopoly.search.wildDeployPenalty`,
  `monopoly.search.wildShortSetPenalty`,
  `monopoly.search.wildCompletionPenalty`.
- Defaults are all zero, so the promoted default champion behavior is unchanged.
- Probe results on the 8 reproduced losses:
  - `hardMargin=100`: 0/8 fixed, average score delta -2913.6.
  - `tacticalA` (`steal=1200`, `forced=1200`, `building=900`,
    `rent=500`): 0/7/1, average score delta -2834.4.
  - `wildA` (`wildDeploy=300`, `wildShortSet=700`,
    `wildCompletion=400`): 0/8 fixed, average score delta -2934.6.
- `hardMargin=100` same-seed AB test:
  - Default report:
    `training/data/models/evaluation/paired-hard-vs-lookahead-default-abtest2-fixed-40x340-seat2-seed2026366901.json`
    = 3/5/32 versus hard.
  - Variant report:
    `training/data/models/evaluation/paired-hard-vs-lookahead-m100-abtest2-fixed-40x340-seat2-seed2026366901.json`
    = 4/5/31 versus hard.
  - Direct treatment-policy compare:
    `training/data/models/evaluation/lookahead-m100-vs-default-abtest2-fixed-treatment-compare.json`
    = 1/0/39, variant margin +1, but only one decisive policy difference.
  - Side summary:
    `training/data/models/evaluation/lookahead-m100-abtest2-fixed-side-summary.json`
    still has combined paired margin -3 versus hard.
- Interpretation: `hardMargin=100` is not a safe promotion. It only flips one
  fixed-seat same-seed policy result in this 40-pair AB test and does not fix
  the stress seeds.

Corrector re-audit:

- Rechecked existing learned corrector reports against same-seed default
  lookahead baselines rather than only against hard:
  - `lookahead-corrector780-livecontext-vs-default-80x340-seat2-seed2026290101-treatment-compare.json`
    = 0/0/80.
  - `lookahead-corrector780-livecontext-vs-default-80x340-seat2-seed2026280101-treatment-compare.json`
    = 0/0/80.
  - `lookahead-corrector-mlp-gap08-vs-default-100x340-seat2-seed2026280101-treatment-compare.json`
    = 5/9/86, variant margin -4.
  - `lookahead-corrector-mlp-gap15-vs-default-80x340-seat2-seed2026280101-treatment-compare.json`
    = 3/5/72, variant margin -2.
- Interpretation: the previous corrector family can look healthy when compared
  independently to hard, but direct same-seed treatment comparison shows either
  no rank-only change or regression versus default lookahead. Do not promote it
  without a new direct default-vs-variant gate.

Lossprobe cf40 corrector:

- Added default-preserving runtime switch
  `monopoly.search.correctorAllowedActionTypes`; default is still `ACTION`.
  Experiments can opt into `ACTION,DEPLOY,DEPOSIT`, which is needed because the
  lossprobe counterfactual labels are mostly deposit/deploy corrections.
- Counterfactual selection:
  `training/data/models/traces/lossprobe/lookahead-lossprobe-top8-counterfactual-disagree-gap100.jsonl`.
  - Selected rows = 40 from 187 decisions.
  - All 40 selected labels beat both the source lookahead choice and the hard
    choice under deterministic counterfactual replay.
  - Label mix = 28 `DEPOSIT`, 9 `DEPLOY`, 1 `FORCED_DEAL`,
    1 `DOUBLE_RENT`, 1 `STEAL_PROPERTY`.
- Models:
  - Linear:
    `backend/models/distillation/lookahead-lossprobe-cf40-linear/candidate_ranker_linear.json`,
    validation top-1 = 0.300.
  - MLP:
    `backend/models/distillation/lookahead-lossprobe-cf40-mlp/candidate_ranker_mlp.json`,
    validation top-1 = 0.500, MRR = 0.653.
  - Forest baseline validation top-1 = 0.200.
- Stress test with the MLP corrector and
  `correctorAllowedActionTypes=ACTION,DEPLOY,DEPOSIT`,
  `correctorAllowLowerLookaheadScore=true`:
  - Training-source seat2 losses:
    `training/data/models/evaluation/lossprobe/lookahead-cf40mlp-vs-default-lossprobe-top8-treatment-compare.json`
    = 4/0/4 versus default lookahead. It turns seeds 2026230151,
    2026230165, 2026230189, and 2026230194 from losses into rank-only ties
    versus hard.
  - Out-of-sample seat1 losses:
    `training/data/models/evaluation/lossprobe/lookahead-cf40mlp-vs-default-lossprobe-seat1-oos-treatment-compare.json`
    = 1/0/3, but two of the tied seeds have worse score deltas than default
    (`2026230103`: -3923 vs -2923; `2026230120`: -3370 vs -2359).
- Interpretation: this is a useful diagnostic signal that deposit/deploy
  choices are part of some lookahead failure routes, but the 40-row corrector is
  too small and seat2-specific to promote. It should not be used in the
  default `lookahead` strategy or described as stronger than the current
  champion. A future version needs a larger, held-out counterfactual dataset
  before ordinary paired AB expansion.

Conclusion:

- Keep default `SearchLookaheadAiPlayStrategy` / `lookahead` / `search` as the
  current safe strong local AI.
- The next useful optimization should generate stronger evidence for rare
  multi-step failures, such as larger loss-state counterfactual labels or a
  learned gate that first proves direct same-seed improvement over default
  lookahead, not only over hard.

## 2026-05-26 Lookahead Remaining-Turn Rollout Negative Result

Purpose: test whether allowing the lookahead evaluator to roll out the rest of
the current turn improves the proven default search/lookahead policy, and audit
whether high tie counts are caused by too small a snapshot budget.

Budget/tie audit:

- `maxSnapshotsPerGame` is a snapshot budget, not a strict round count. The
  paired runner force-ends only when the game has not naturally finished before
  the snapshot cap or timeout.
- Same-seed rank-only ties are expected to be common: if the focus seat has the
  same natural-win status and same board rank in both runs, the pair is a tie.
  Board score remains diagnostic only because complete-set steals can cause
  large late swings.
- In the default expanded baselines below, every run ended with `NATURAL_WIN`:
  - Fixed 40:
    `training/data/models/evaluation/paired-hard-vs-lookahead-default-expand-fixed-40x340-seat2-seed2026366701.json`
    had 80/80 natural endings and rank-only outcome 6/0/34 versus hard.
  - Random-first 40:
    `training/data/models/evaluation/paired-hard-vs-lookahead-default-expand-randomfirst-40x340-seat2-seed2026366801.json`
    had 80/80 natural endings and rank-only outcome 3/1/36 versus hard.
- Interpretation: the high tie count in these reports is not caused by
  `maxSnapshotsPerGame=340`; increasing the cap would not change already
  natural-ended games. For future candidates, raise the cap only when reports
  show `MAX_SNAPSHOTS`, `PAIRED_SEED_TIMEOUT`, or `NoNaturalWinner`.

Candidate: `monopoly.search.rolloutRemainingTurn=true` with
`monopoly.search.rolloutPolicy=search`.

- Initial 40-pair smoke summary:
  `training/data/models/evaluation/lookahead-rollremainsearch-abtest-40pairs-summary.json`.
  - Combined rank-only outcome versus hard = 10/1/29.
  - This was promising but too small and only indirectly compared to default
    lookahead.
- Expanded 80-pair summary:
  `training/data/models/evaluation/lookahead-rollremainsearch-expand-80pairs-summary.json`.
  - Combined rank-only outcome versus hard = 11/5/64.
  - Decisive rate = 0.6875; 95% CI = 0.444-0.858.
  - Median score delta = 0 and random-first median score delta = -50.
- Direct same-seed treatment-policy compare against default lookahead:
  - Fixed:
    `training/data/models/evaluation/lookahead-rollremainsearch-vs-default-expand-fixed-treatment-compare.json`
    = 2/5/33, variant margin -3.
  - Random-first:
    `training/data/models/evaluation/lookahead-rollremainsearch-vs-default-expand-randomfirst-treatment-compare.json`
    = 2/1/37, variant margin +1.
  - Combined = 4/6/70, variant margin -2.
- Interpretation: `rolloutRemainingTurn=search` is not a safe replacement for
  default lookahead. The default `SearchLookaheadAiPlayStrategy` remains the
  only promotable local strong AI, and the next improvement attempt should focus
  on targeted corrections or learned gates rather than extending this rollout
  setting.

## 2026-05-26 Action-Gate DAgger V13/V14 Negative Result

Purpose: test whether action-gate A can provide safer current-policy states for
lookahead relabeling, and audit a suspected train/runtime feature shift from
traced `sourcePolicy` fields.

Action-gate A trace collection:

- Runtime policy: v8 soft-score student with action-gate A margins
  (`global=0.5`, `deploy=1.0`, `deposit=1.0`, `passGo=1.5`,
  `swingAction=0.1`, `cashAction=0.5`).
- Random-first trace report:
  `training/data/models/evaluation/lookahead-student-v8-actiongateA-trace-randomfirst-20x260-seat2-seed2026365901.json`.
  - Rank-only paired outcome = 1/3/16.
  - Trace: `training/data/models/traces/student-v8-actiongateA-randomfirst-memento-20x260-seat2-seed2026365901.jsonl`.
  - Rows = 587, all `PLAY_CARD`, 587/587 with memento.
  - Hybrid fallback = 540/587; only 47 model overrides were actually played.
- Fixed-seat trace report:
  `training/data/models/evaluation/lookahead-student-v8-actiongateA-trace-fixed-20x260-seat2-seed2026366001.json`.
  - Rank-only paired outcome = 1/2/17.
  - Trace: `training/data/models/traces/student-v8-actiongateA-fixed-memento-20x260-seat2-seed2026366001.jsonl`.
  - Rows = 582, all `PLAY_CARD`, 582/582 with memento.
  - Hybrid fallback = 541/582; only 41 model overrides were actually played.
- Combined trace smoke:
  `training/data/models/evaluation/lookahead-student-v8-actiongateA-trace-40pairs-summary.json`.
  - Rank-only paired outcome = 2/5/33.
  - Interpretation: this runtime gate is still not a promotable policy; it is
    useful only as a state-distribution collector.

Lookahead relabel:

- Random-first relabel:
  `training/data/models/traces/student-v8-actiongateA-randomfirst-dagger-lookahead-relabel-587-seat2-seed2026365901.jsonl`.
  - 587/587 rows relabeled with source `lookahead_dagger_actiongateA`.
  - Lookahead changed 176/587 source-policy choices.
- Fixed-seat relabel:
  `training/data/models/traces/student-v8-actiongateA-fixed-dagger-lookahead-relabel-582-seat2-seed2026366001.jsonl`.
  - 582/582 rows relabeled with source `lookahead_dagger_actiongateA`.
  - Lookahead changed 156/582 source-policy choices.
- Combined new DAgger rows: 1169 rows, 40 sessions, all 2-player `PLAY_CARD`.

V13 direct student:

- Model:
  `backend/models/distillation/lookahead-student-v13-actiongateA-dagger3125-w3-listwise-mlp/candidate_ranker_mlp.json`.
- Training data: 8569 rows total, including 1169 `lookahead_dagger_actiongateA`
  rows with source multiplier 3.
- Loss: listwise one-hot.
- Validation: top-1 = 0.765, MRR = 0.858.
- Smoke summary:
  `training/data/models/evaluation/lookahead-student-v13-actiongateA-dagger3125-w3-listwise-smoke-summary.json`.
  - Fixed 20 pairs = 2/2/16.
  - Random-first 20 pairs = 1/3/16.
  - Combined rank-only paired outcome = 3/5/32.
  - Average score delta = +51.75, but paired margin = -2.
- Interpretation: v13 is a negative gameplay result despite strong offline
  imitation metrics. The positive average score delta is diagnostic only and
  must not override the rank-only paired loss.

Feature-shift audit and V14:

- Finding: all current DAgger relabel traces include `request.context.sourcePolicy`,
  and `distill_dataset.py` includes source-policy features. Runtime
  `LocalRankerAiPlayStrategy` normally has no `sourcePolicy`, so this is a
  plausible train/runtime feature shift.
- Script change: `training/scripts/distill_dataset.py` now supports
  `--drop-source-policy-features`, which strips `sourcePolicy` and
  `relabelInputPolicy` before feature extraction while preserving teacher
  labels and source weighting.
- Model:
  `backend/models/distillation/lookahead-student-v14-actiongateA-dagger3125-w3-nosource-listwise-mlp/candidate_ranker_mlp.json`.
- Validation: top-1 = 0.767, MRR = 0.859, with `dropSourcePolicyFeatures=true`.
- Smoke summary:
  `training/data/models/evaluation/lookahead-student-v14-actiongateA-dagger3125-w3-nosource-listwise-smoke-summary.json`.
  - Fixed 20 pairs = 1/3/16.
  - Random-first 20 pairs = 0/2/18.
  - Combined rank-only paired outcome = 1/5/34.
  - Median score delta = +9.5, but paired margin = -4.
- Interpretation: removing `sourcePolicy` features makes the training path
  cleaner but does not fix gameplay. The next compression attempt should not be
  another simple listwise DAgger MLP with more rows. Better directions are a
  learned override gate against hard/lookahead disagreement, a residual/corrector
  model used by the proven lookahead policy, or a lighter local search policy
  rather than pure direct imitation.

## 2026-05-26 Rank-Only Paired Gate and V7 DAgger Negative Result

Purpose: audit whether same-seed paired evaluation was still too score-sensitive after late Deal Breaker / complete-set steal events.

Evaluation rule update:

- `PairedSeedPolicyExperimentRunner` now treats same natural-win status plus same board rank as a paired tie. Board score is kept in the report, but it no longer breaks same-rank ties.
- `training/scripts/summarize_paired_reports.py` and `training/scripts/summarize_ai_robustness.py` recompute rank-only paired outcomes from historical reports, so old JSON reports can be re-audited without rerunning games.
- `training/scripts/check_ai_robustness_gate.py` now gates on decisive rank-only pairs: natural win first, then board rank. Score and score-tiebreak outcomes are diagnostic only.

Champion re-audit:

- Summary: `training/data/models/evaluation/lookahead-rent600-robustness-summary.json`.
- Rank-only paired outcome = 357/134/1029 over 1520 paired seeds.
- Decisive paired treatment rate = 0.727; 95% CI = 0.686-0.765.
- Paired margin = +223.
- Gate: `training/data/models/evaluation/lookahead-rent600-robustness-gate-refresh.json` passes.
- Interpretation: the local search champion remains the only safe "stronger than hard" claim under the stricter rank-only metric.

V6 re-audit:

- Random-first 80-pair aggregate: `training/data/models/evaluation/lookahead-student-v6-dagger1394-randomfirst-w4-listwise-randomfirst-80pairs-summary.json`.
  - Rank-only paired outcome = 10/9/61.
  - Decisive paired treatment rate = 0.526; 95% CI = 0.317-0.727.
- Multiscenario summary: `training/data/models/evaluation/lookahead-student-v6-dagger1394-randomfirst-w4-listwise-multiscenario-summary.json`.
  - Rank-only paired outcome = 33/19/108.
  - Decisive paired treatment rate = 0.635; 95% CI = 0.499-0.752.
  - Gate fails on decisive paired CI lower bound and random-first decisive rate.
- Interpretation: v6 is still the best distilled student candidate, but much of its old 44/33/3 random-first edge came from same-rank board-score tie-breaks.

V7 DAgger attempt:

- Current-policy trace: `training/data/models/traces/student-v6-randomfirst-memento-20x240-seat2-seed2026363301.jsonl`.
  - 562 `PLAY_CARD` rows, 562/562 with memento.
- Lookahead relabel: `training/data/models/traces/student-v6-randomfirst-dagger-lookahead-relabel-562-seat2-seed2026363301.jsonl`.
  - 562/562 rows relabeled; v6/student disagreement = 141/562.
- Model: `backend/models/distillation/lookahead-student-v7-dagger1956-randomfirst-w4-listwise-mlp/candidate_ranker_mlp.json`.
  - Rows: 7400.
  - Validation top-1: 0.743.
  - Validation MRR: 0.842.
- Gameplay: `training/data/models/evaluation/paired-hard-vs-lookahead-student-v7-dagger1956-randomfirst-w4-listwise-randomfirst-40x260-seat2-seed2026363401.json`.
  - Natural wins = 16/20.
  - Rank-only paired outcome = 5/9/26.
  - Decisive paired treatment rate = 0.357.
  - Score delta range = -3993 to +2994.
- Interpretation: v7 is a negative result despite better offline imitation metrics. Do not promote it.

V8 soft-score listwise attempt:

- Model: `backend/models/distillation/lookahead-student-v8-dagger1956-randomfirst-w4-softscore-listwise-mlp/candidate_ranker_mlp.json`.
- Training change: same 7400 rows as v7, but listwise target uses `result.metadata.candidateScores` with `--soft-label-source teacher_scores --teacher-score-temperature 4000 --teacher-score-mix 0.7`.
- Offline validation:
  - Top-1: 0.716.
  - MRR: 0.828.
- Random-first 80-pair aggregate: `training/data/models/evaluation/lookahead-student-v8-dagger1956-randomfirst-w4-softscore-listwise-randomfirst-80pairs-summary.json`.
  - Rank-only paired outcome = 12/2/66.
  - Decisive paired treatment rate = 0.857; 95% CI = 0.601-0.960.
  - Natural wins = 54/44.
  - End reasons: 160/160 `NATURAL_WIN`.
- Fixed-seat sanity: `training/data/models/evaluation/lookahead-student-v8-dagger1956-randomfirst-w4-softscore-listwise-fixed-40pairs-summary.json`.
  - Rank-only paired outcome = 4/7/29.
  - Decisive paired treatment rate = 0.364.
  - Natural wins = 16/19.
  - End reasons: 80/80 `NATURAL_WIN`.
- Interpretation: v8 successfully repairs the random-first weakness but over-corrects and regresses fixed-seat seat2. Do not promote it. Next student attempt should reduce random-first DAgger weighting and/or lower the teacher-score soft-label mix, then screen both random-first and fixed-seat before any 4-player expansion.

V9 balanced soft-score attempt:

- Model: `backend/models/distillation/lookahead-student-v9-dagger1956-balanced-softscore-listwise-mlp/candidate_ranker_mlp.json`.
- Training change relative to v8:
  - `--teacher-score-mix 0.35` instead of 0.7.
  - `lookahead_dagger_randomfirst` and `lookahead_dagger_randomfirst_v6` source multipliers reduced from 4.0 to 2.5.
  - `lookahead_dagger` stayed at 3.0.
- Offline validation:
  - Top-1: 0.722.
  - MRR: 0.831.
- Fixed-seat smoke: `training/data/models/evaluation/lookahead-student-v9-dagger1956-balanced-softscore-listwise-fixed-20pairs-summary.json`.
  - Rank-only paired outcome = 2/1/17.
  - Natural wins = 9/8.
  - End reasons: 40/40 `NATURAL_WIN`.
- Random-first smoke: `training/data/models/evaluation/lookahead-student-v9-dagger1956-balanced-softscore-listwise-randomfirst-20pairs-summary.json`.
  - Rank-only paired outcome = 2/3/15.
  - Natural wins = 11/12.
  - End reasons: 40/40 `NATURAL_WIN`.
- Interpretation: v9 partly fixes v8's fixed-seat regression but loses the random-first repair. Do not promote it. The next search should use a small parameter grid around `teacher-score-mix` 0.45-0.6 and random-first DAgger multiplier 3.0-3.5, with every candidate screened on both fixed-seat and random-first 20-pair smoke before larger runs.

V10 mid soft-score attempt:

- Model: `backend/models/distillation/lookahead-student-v10-dagger1956-mid-softscore-listwise-mlp/candidate_ranker_mlp.json`.
- Training change relative to v8/v9:
  - `--teacher-score-mix 0.55`.
  - `lookahead_dagger_randomfirst` and `lookahead_dagger_randomfirst_v6` source multipliers set to 3.3.
- Offline validation:
  - Top-1: 0.725.
  - MRR: 0.833.
- Fixed-seat smoke: `training/data/models/evaluation/lookahead-student-v10-dagger1956-mid-softscore-listwise-fixed-20pairs-summary.json`.
  - Rank-only paired outcome = 2/5/13.
  - Decisive paired treatment rate = 0.286.
  - Natural wins = 9/12.
  - Median score delta = +85, but mean score delta = -299.4.
- Interpretation: v10 has the best soft-score offline metrics, but gameplay gets worse on the fixed-seat smoke. Offline top-1/MRR remain insufficient for promotion without paired gameplay evidence. Do not promote it.

V11 v6/v8 ensemble smoke:

- Model: `backend/models/distillation/lookahead-student-v11-v6v8-ensemble/candidate_ranker_ensemble.json`.
- Ensemble members:
  - v6 listwise one-hot student at weight 0.55.
  - v8 soft-score random-first student at weight 0.45.
- Fixed-seat smoke: `training/data/models/evaluation/lookahead-student-v11-v6v8-ensemble-fixed-20pairs-summary.json`.
  - Rank-only paired outcome = 1/0/19.
  - Natural wins = 8/7.
  - Mean score delta = -39.35; 10% trimmed mean = +1.375.
- Random-first smoke: `training/data/models/evaluation/lookahead-student-v11-v6v8-ensemble-randomfirst-20pairs-summary.json`.
  - Rank-only paired outcome = 4/2/14.
  - Natural wins = 14/12.
  - Mean score delta = +278.55; 10% trimmed mean = +280.0.
- Combined smoke: `training/data/models/evaluation/lookahead-student-v11-v6v8-ensemble-smoke-summary.json`.
  - Rank-only paired outcome = 5/2/33 over 40 pairs.
  - Decisive paired treatment rate = 0.714; 95% CI = 0.359-0.918.
  - Paired margin = +3.
- Fixed-seat expansion: `training/data/models/evaluation/lookahead-student-v11-v6v8-ensemble-fixed-40pairs-seed2026364401-summary.json`.
  - Rank-only paired outcome = 2/4/34.
  - Decisive paired treatment rate = 0.333.
  - Natural wins = 17/19.
  - Mean score delta = +151.375, despite negative paired margin.
- Fixed-seat aggregate: `training/data/models/evaluation/lookahead-student-v11-v6v8-ensemble-fixed-60pairs-summary.json`.
  - Rank-only paired outcome = 3/4/53.
  - Decisive paired treatment rate = 0.429.
  - Natural wins = 25/26.
  - Paired margin = -1.
- Interpretation: v11 is another example where score diagnostics can look acceptable while rank-only paired outcomes are not. Do not promote it and do not spend larger random-first samples on it. The next ensemble attempt should shift more weight back to v6, using v8 only as a small random-first correction.

V12 conservative v6/v8 ensemble:

- Model: `backend/models/distillation/lookahead-student-v12-v6v8-ensemble/candidate_ranker_ensemble.json`.
- Ensemble members:
  - v6 listwise one-hot student at weight 0.70.
  - v8 soft-score random-first student at weight 0.30.
- Fixed-seat smoke: `training/data/models/evaluation/lookahead-student-v12-v6v8-ensemble-fixed-20pairs-summary.json`.
  - Rank-only paired outcome = 2/3/15.
  - Decisive paired treatment rate = 0.400.
  - Natural wins = 11/12.
  - Mean score delta = +68.15; median score delta = +85.0.
  - Paired margin = -1.
- Interpretation: even a more conservative v6-heavy ensemble remains negative on fixed-seat rank-only paired outcome, despite positive score diagnostics. Do not promote it and skip random-first expansion. The random-first improvement from v8 is not safely composable through simple linear ensembling; future compression work should collect better fixed/random mixed DAgger states or use a context-aware model rather than static averaging.

V8 runtime hybrid/action-gate screening:

- Implementation:
  - `LocalRankerAiPlayStrategy` now supports action-specific hybrid margins:
    - global: `monopoly.localRanker.hybridMargin`
    - action families: `.deploy`, `.deposit`, `.discard`, `.action`, `.passGo`, `.swingAction`, `.cashAction`
  - `training/scripts/analyze_local_ranker_trace.py` audits local-ranker hard overrides and score margins.
  - `PairedSeedPolicyExperimentRunner` accepts `monopoly.pairedBattle.progressEvery` for long quiet runs.
- Trace audit:
  - Source trace: `training/data/models/traces/student-v6-randomfirst-memento-20x240-seat2-seed2026363301.jsonl`.
  - Local ranker overrode hard in 205/562 decisions.
  - Override margin median = 1.350; p10 = 0.178; p90 = 4.399.
  - Low-margin overrides were often deploy color choice, Pass Go vs rent, or specific steal target choices, which supports using a conservative hard fallback rather than static ensembling.
- Uniform hybrid margin 1.0:
  - Fixed 20: `training/data/models/evaluation/lookahead-student-v8-hybridm1-fixed-20pairs-summary.json` = 0/0/20.
  - Random-first 20: `training/data/models/evaluation/lookahead-student-v8-hybridm1-randomfirst-20pairs-summary.json` = 1/2/17.
  - Interpretation: too conservative and slightly negative overall.
- Uniform hybrid margin 0.5:
  - Fixed 20: `training/data/models/evaluation/lookahead-student-v8-hybridm05-fixed-20pairs-summary.json` = 1/1/18.
  - Random-first 20: `training/data/models/evaluation/lookahead-student-v8-hybridm05-randomfirst-20pairs-summary.json` = 2/1/17.
  - Combined smoke: `training/data/models/evaluation/lookahead-student-v8-hybridm05-smoke-summary.json` = 3/2/35 over 40 pairs.
  - Interpretation: stops the fixed-seat regression but produces too few decisive pairs to be a strong upgrade.
- Action-gate A:
  - Runtime settings:
    - global margin 0.5
    - deploy 1.0
    - deposit 1.0
    - passGo 1.5
    - swingAction 0.1
    - cashAction 0.5
  - First fixed/random smoke: `training/data/models/evaluation/lookahead-student-v8-actiongateA-smoke-summary.json` = 2/0/38 over 40 pairs.
  - Expanded 80-pair smoke: `training/data/models/evaluation/lookahead-student-v8-actiongateA-80pairs-summary.json`.
    - Overall rank-only paired outcome = 4/1/75.
    - Decisive paired treatment rate = 0.800; 95% CI = 0.376-0.964.
    - Fixed-seat group = 1/0/39.
    - Random-first group = 3/1/36.
    - Average score delta = +34.5; median = 0.
- Interpretation: action-gate A is the healthiest distilled-student runtime variant so far because it no longer shows the fixed-seat loss of raw v8 and keeps a small random-first edge. It is still not promotable: only 5/80 pairs are decisive, the Wilson lower bound is weak, and the candidate is mostly a conservative hard fallback. Next work should either make the gate more selectively aggressive or collect action-gated DAgger states for relabeling by the lookahead champion.

Action-gate B/C follow-up:

- Action-gate B settings:
  - global margin 0.35
  - deploy 0.8
  - deposit 1.0
  - passGo 1.2
  - swingAction 0.0
  - cashAction 0.35
- B fixed smoke: `training/data/models/evaluation/lookahead-student-v8-actiongateB-fixed-20pairs-summary.json`.
  - Rank-only paired outcome = 2/0/18.
  - Natural wins = 7/5.
  - Mean score delta = +37.95.
- B random-first smoke: `training/data/models/evaluation/lookahead-student-v8-actiongateB-randomfirst-20pairs-summary.json`.
  - Rank-only paired outcome = 1/2/17.
  - Natural wins = 11/12.
  - Mean score delta = -205.05.
- B combined smoke: `training/data/models/evaluation/lookahead-student-v8-actiongateB-smoke-summary.json`.
  - Rank-only paired outcome = 3/2/35.
  - Interpretation: lowering global/deploy/passGo/cash margins increased fixed-seat decisive wins but reintroduced random-first regression. Do not promote B.
- Action-gate C settings:
  - same as A, except `swingAction` = 0.0 instead of 0.1.
- C fixed smoke: `training/data/models/evaluation/lookahead-student-v8-actiongateC-fixed-20pairs-summary.json`.
  - Rank-only paired outcome = 1/0/19.
  - Natural wins = 9/8.
  - Mean score delta = -31.6.
- C random-first smoke: `training/data/models/evaluation/lookahead-student-v8-actiongateC-randomfirst-20pairs-summary.json`.
  - Rank-only paired outcome = 0/0/20.
  - Natural wins = 14/14.
- C combined smoke: `training/data/models/evaluation/lookahead-student-v8-actiongateC-smoke-summary.json`.
  - Rank-only paired outcome = 1/0/39.
  - Interpretation: C is safe but even more tie-heavy than A. Do not promote C over A.
- Current interpretation: hand-tuned runtime gates have found a useful safety tradeoff but not enough strength. The best next step is to collect action-gate A runtime memento traces and relabel them with `SearchLookaheadAiPlayStrategy`, so the student can learn context-specific override decisions instead of relying on static margin rules.

## 2026-05-26 Strong Local AI Runtime Entry

Purpose: make the current proven local champion available as a normal runtime option, not only through paired-evaluation runner settings.

Change:

- `HVM` now accepts `aiDifficulty=STRONG`, `LOOKAHEAD`, or `SEARCH`.
- These aliases instantiate `SearchLookaheadAiPlayStrategy`.
- `CUSTOM` already accepted `lookahead`, `search`, `local_strong`, and `strong`; the error message and docs now expose those aliases explicitly.
- The Web client HVM difficulty selector includes `STRONG`.
- The JavaFX HVM difficulty selector includes `STRONG`, and custom-lineup presets include strong local AI examples.
- `docs/interface/websocket-protocol.md` and `docs/ai-delivery-checklist.md` document the official runtime entry points.

Verification:

```bash
mvn -q test -Dtest=GameControllerPlayerNamingTest,TraceRelabelerTest,LocalLinearRankerAiPlayStrategyTest

npm run build --prefix frontend

python3 training/scripts/check_ai_robustness_gate.py \
  training/data/models/evaluation/lookahead-rent600-robustness-summary.json \
  --output training/data/models/evaluation/lookahead-rent600-robustness-gate-refresh.json

git diff --check
```

Runtime smoke:

- Report: `training/data/models/evaluation/paired-hard-vs-lookahead-strong-entry-smoke-4x220-seat2-randomfirst-seed2026362801.json`.
- Setup: 4 random-first paired seeds, `hard,hard` vs `hard,llm`, `treatmentLlmStrategy=lookahead`, focus seat 2.
- Result: paired treatment/control/tie = 2/2/0.
- Interpretation: this is only an entry-point smoke test. The strength claim still comes from `training/data/models/evaluation/lookahead-rent600-robustness-summary.json` and its paired gate, not this 4-pair sample.

Claim boundary:

- Safe: users can now run the current strong local AI directly through `HVM` with `STRONG`, or through `CUSTOM` with `lookahead` / `strong`.
- Still not safe: claiming the distilled MLP student is definitely stronger than `hard`; v4 still fails random-first paired gate and v5 is a negative result.

## 2026-05-26 V6 Listwise Student Improves Random-First But Is Not Final

Purpose: test whether the v5 failure was partly caused by BCE treating legal candidates independently instead of optimizing the within-decision candidate ranking directly.

Training:

```bash
python3 training/scripts/distill_dataset.py \
  training/data/models/traces/lookahead-train-fixed100.jsonl \
  training/data/models/traces/lookahead-rent600-seat2-weak100-outcome.jsonl \
  training/data/models/traces/counterfactual-training-10x220-gap100-beats-hard-no-deposit-best.jsonl \
  training/data/models/traces/student-w8-dagger-lookahead-relabel-795-seat2-seed2026361701.jsonl \
  training/data/models/traces/student-v4-randomfirst-dagger-lookahead-relabel-599-seat2-seed2026362601.jsonl \
  --output-dir backend/models/distillation/lookahead-student-v6-dagger1394-randomfirst-w4-listwise-mlp \
  --model-type mlp --loss-type listwise --epochs 32 --batch-size 256 --lr 0.001 \
  --balance-by-kind --kind-balance-max 3 \
  --split-by session --validation-ratio 0.2 \
  --include-sources lookahead,counterfactual_replay,lookahead_dagger,lookahead_dagger_randomfirst \
  --duplicate-policy prefer-later \
  --include-player-counts 2 \
  --source-multiplier counterfactual_replay=8 \
  --source-multiplier lookahead_dagger=3 \
  --source-multiplier lookahead_dagger_randomfirst=4 \
  --min-rows 4000
```

Offline result:

- Model: `backend/models/distillation/lookahead-student-v6-dagger1394-randomfirst-w4-listwise-mlp/candidate_ranker_mlp.json`.
- Rows: 6838 `PLAY_CARD` decisions.
- Source mix: 5418 `lookahead`, 795 `lookahead_dagger`, 599 `lookahead_dagger_randomfirst`, 26 `counterfactual_replay`.
- Loss: `listwise`.
- Validation top-1: 0.714.
- Validation MRR: 0.828.

Gameplay evaluation:

- First random-first 40 pairs: `training/data/models/evaluation/paired-hard-vs-lookahead-student-v6-dagger1394-randomfirst-w4-listwise-randomfirst-40x260-seat2-seed2026362901.json`.
  - Rank-only paired treatment/control/tie = 6/4/30.
  - Legacy score-tiebreak paired treatment/control/tie = 25/14/1.
  - Natural wins = 24/22.
  - Median score delta = +124.5.
  - 10% trimmed score delta = +291.53.
  - End reasons: 80/80 `NATURAL_WIN`.
- Second random-first 40 pairs: `training/data/models/evaluation/paired-hard-vs-lookahead-student-v6-dagger1394-randomfirst-w4-listwise-randomfirst-40x260-seat2-seed2026363001.json`.
  - Rank-only paired treatment/control/tie = 4/5/31.
  - Legacy score-tiebreak paired treatment/control/tie = 19/19/2.
  - Natural wins = 26/27.
  - Median score delta = 0.
  - 10% trimmed score delta = -155.88.
  - End reasons: 80/80 `NATURAL_WIN`.
- Random-first 80-pair aggregate: `training/data/models/evaluation/lookahead-student-v6-dagger1394-randomfirst-w4-listwise-randomfirst-80pairs-summary.json`.
  - Rank-only paired treatment/control/tie = 10/9/61.
  - Decisive paired treatment rate = 0.526; 95% CI = 0.317-0.727.
  - Paired margin = +1.
  - Median score delta = +20.5.
  - 10% trimmed score delta = +68.06.
- Fixed-seat sanity: `training/data/models/evaluation/paired-hard-vs-lookahead-student-v6-dagger1394-randomfirst-w4-listwise-fixed-40x260-seat2-seed2026363101.json`.
  - Rank-only paired treatment/control/tie = 4/1/35.
  - Legacy score-tiebreak paired treatment/control/tie = 23/17/0.
  - Natural wins = 16/12.
  - Median score delta = +90.5.
- Four-player sanity: `training/data/models/evaluation/paired-hard4-vs-lookahead-student-v6-dagger1394-randomfirst-w4-listwise-seat2-40x320-seed2026363201.json`.
  - Rank-only paired treatment/control/tie = 19/9/12.
  - Legacy score-tiebreak paired treatment/control/tie = 25/15/0.
  - Natural wins = 14/8.
  - Median score delta = +416.
  - End reasons: 59 `NATURAL_WIN`, 21 `PAIRED_SEED_SNAPSHOT_LIMIT`; treat as sanity, not final proof.
- Multiscenario summary: `training/data/models/evaluation/lookahead-student-v6-dagger1394-randomfirst-w4-listwise-multiscenario-summary.json`.
  - Overall rank-only paired treatment/control/tie = 33/19/108.
  - Decisive paired treatment rate = 0.635; 95% CI = 0.499-0.752.
  - Paired margin = +14.
- Strict paired gate: `training/data/models/evaluation/lookahead-student-v6-dagger1394-randomfirst-w4-listwise-multiscenario-paired-gate.json`.
  - Failed on overall decisive paired CI lower bound: 0.4987 < 0.52.
  - Failed on random-first decisive paired treatment rate: 0.526 < 0.55.

Interpretation:

V6 is the best distilled student candidate, but the rank-only audit shows its earlier edge was overstated by score tie-breaks. It is still not strong enough for the final "definitely stronger than hard" claim: the random-first aggregate is only 10/9/61 after removing same-rank score wins, and the strict multiscenario paired gate fails on CI lower bound. Next student work should either collect more current-policy random-first DAgger states or use the proven `SearchLookaheadAiPlayStrategy` as the deployable champion while treating V6 as a compression candidate.

## 2026-05-26 Random-First DAgger V5 Negative Result

Purpose: investigate whether the v4 DAgger student remains strong when first player is randomized. The first random-first sanity block was ambiguous, so a second random-first block was run before deciding whether to collect more DAgger data.

Evaluation rule update:

- Final Monopoly Deal board scores are heavy-tailed. A single Deal Breaker or late complete-set steal can swing board score by thousands, so average score delta is only an auxiliary diagnostic.
- This section used the older same-seed paired focus-seat outcome where board score broke same-rank ties. The 2026-05-26 rank-only gate above supersedes that policy for current claims.
- `training/scripts/summarize_ai_robustness.py` now records this primary metric explicitly, plus paired treatment rate, paired Wilson interval, paired margin, and score/rank delta distributions.
- `training/scripts/check_ai_robustness_gate.py` now defaults to paired outcome/rate/margin/CI gates. Natural-win rate and average score delta are still reported but are disabled as hard gates unless thresholds are passed explicitly.
- This matters for the v4 random-first audit: the aggregate has positive natural wins (43/37) and positive average score delta (+120.03), but paired outcome is 34/39/7, so the model is correctly judged unstable.

V4 random-first audit:

- First random-first report: `training/data/models/evaluation/paired-hard-vs-lookahead-student-v4-dagger795-w4-randomfirst-40x260-seat2-seed2026362201.json`.
  - Paired treatment/control/tie = 20/19/1.
  - Natural wins = 29/22.
  - Median score delta = +5.
  - 10% trimmed score delta = +156.31.
- Second random-first report: `training/data/models/evaluation/paired-hard-vs-lookahead-student-v4-dagger795-w4-randomfirst-40x260-seat2-seed2026362501.json`.
  - Paired treatment/control/tie = 14/20/6.
  - Natural wins = 14/15.
  - Median score delta = -5.
  - 10% trimmed score delta = +25.53.
- Aggregate: `training/data/models/evaluation/lookahead-student-v4-dagger795-w4-randomfirst-80pairs-summary.json`.
  - Paired treatment/control/tie = 34/39/7.
  - Paired treatment rate = 0.425; 95% CI = 0.323-0.534.
  - Paired margin = -5.
  - Natural wins = 43/37.
  - Median score delta = 0.
  - 10% trimmed score delta = +91.22.

Diagnosis:

The v4 student is not robust under random first-player evaluation. Natural wins and score deltas are mildly positive, but same-seed paired outcomes are negative. Given the high variance in final scores, this should be treated as a real weakness rather than explained away by average score.

Random-first DAgger collection:

```bash
mvn -q exec:java \
  -Dexec.mainClass=com.monopoly.tools.PairedSeedPolicyExperimentRunner \
  -Dmonopoly.pairedBattle.games=20 \
  -Dmonopoly.pairedBattle.maxSnapshotsPerGame=240 \
  -Dmonopoly.pairedBattle.seedBase=2026362601 \
  -Dmonopoly.pairedBattle.controlLineup=hard,hard \
  -Dmonopoly.pairedBattle.treatmentLineup=hard,llm \
  -Dmonopoly.pairedBattle.focusSeat=2 \
  -Dmonopoly.pairedBattle.randomizeFirstPlayer=true \
  -Dmonopoly.pairedBattle.llmStrategy=local_ranker \
  -Dmonopoly.localRanker.modelPath=backend/models/distillation/lookahead-student-v4-dagger795-w4-mlp/candidate_ranker_mlp.json \
  -Dmonopoly.localRanker.trace.includeMemento=true \
  -Dmonopoly.pairedBattle.tracePath=training/data/models/traces/student-v4-randomfirst-memento-20x240-seat2-seed2026362601.jsonl \
  -Dmonopoly.pairedBattle.traceSchema=decision \
  -Dmonopoly.pairedBattle.traceMode=overwrite \
  -Dmonopoly.pairedBattle.output=training/data/models/evaluation/paired-hard-vs-student-v4-randomfirst-memento-20x240-seat2-seed2026362601.json \
  -Dmonopoly.pairedBattle.quiet=true
```

Trace and relabel audit:

- Trace: `training/data/models/traces/student-v4-randomfirst-memento-20x240-seat2-seed2026362601.jsonl`.
- Rows: 599.
- Memento coverage: 599/599.
- Relabel output: `training/data/models/traces/student-v4-randomfirst-dagger-lookahead-relabel-599-seat2-seed2026362601.jsonl`.
- Rows relabeled: 599/599.
- Error count: 0.
- Source: `lookahead_dagger_randomfirst`.
- Student/lookahead disagreement: 173/599 = 28.9%.

V5 training:

```bash
python3 training/scripts/distill_dataset.py \
  training/data/models/traces/lookahead-train-fixed100.jsonl \
  training/data/models/traces/lookahead-rent600-seat2-weak100-outcome.jsonl \
  training/data/models/traces/counterfactual-training-10x220-gap100-beats-hard-no-deposit-best.jsonl \
  training/data/models/traces/student-w8-dagger-lookahead-relabel-795-seat2-seed2026361701.jsonl \
  training/data/models/traces/student-v4-randomfirst-dagger-lookahead-relabel-599-seat2-seed2026362601.jsonl \
  --output-dir backend/models/distillation/lookahead-student-v5-dagger1394-randomfirst-w4-mlp \
  --model-type mlp --epochs 32 --batch-size 512 --lr 0.001 \
  --balance-by-kind --kind-balance-max 3 \
  --split-by session --validation-ratio 0.2 \
  --include-sources lookahead,counterfactual_replay,lookahead_dagger,lookahead_dagger_randomfirst \
  --duplicate-policy prefer-later \
  --include-player-counts 2 \
  --source-multiplier counterfactual_replay=8 \
  --source-multiplier lookahead_dagger=3 \
  --source-multiplier lookahead_dagger_randomfirst=4 \
  --min-rows 4000
```

V5 result:

- Model: `backend/models/distillation/lookahead-student-v5-dagger1394-randomfirst-w4-mlp/candidate_ranker_mlp.json`.
- Dataset: 6838 rows: 5418 `lookahead`, 795 `lookahead_dagger`, 599 `lookahead_dagger_randomfirst`, 26 `counterfactual_replay`.
- Validation top-1: 0.699.
- Validation MRR: 0.817.
- Gameplay report: `training/data/models/evaluation/paired-hard-vs-lookahead-student-v5-dagger1394-randomfirst-w4-randomfirst-40x260-seat2-seed2026362701.json`.
- Gameplay summary: paired treatment/control/tie = 17/22/1; natural wins = 22/21; median score delta = -30.5; 10% trimmed score delta = -32.22.
- Gate summary: `training/data/models/evaluation/lookahead-student-v5-dagger1394-randomfirst-w4-randomfirst-40pairs-summary.json`, failed paired-rate, paired-margin, median-score, and trimmed-score checks.

Interpretation:

Do not promote v5. Adding one 599-row random-first DAgger block with the current weighting did not fix random-first robustness and slightly hurt score-based robust metrics. This is another example where offline imitation metrics were not predictive enough. Current best distilled student remains v4, but the final "definitely stronger than hard" local AI claim is still better supported by `SearchLookaheadAiPlayStrategy` than by any distilled MLP student.

## 2026-05-26 DAgger Student V4 From Student-Visited States

Purpose: test whether the current best distilled student fails because it visits states that pure lookahead self-play did not cover, then relabel those student-visited states with the lookahead champion.

Tooling changes:

- `LocalRankerAiPlayStrategy` can optionally write `context.counterfactual.mementoJson` when `-Dmonopoly.localRanker.trace.includeMemento=true`.
- `TraceRelabeler` supports `-Dmonopoly.relabel.teacher=lookahead`.
- `LookaheadDecisionTeacher` restores each traced memento, lets `SearchLookaheadAiPlayStrategy` choose from the original legal candidate envelope, and writes labels with configurable source `monopoly.lookaheadTeacher.resultSource`.

Student-state trace:

```bash
mvn -q exec:java \
  -Dexec.mainClass=com.monopoly.tools.PairedSeedPolicyExperimentRunner \
  -Dmonopoly.pairedBattle.games=30 \
  -Dmonopoly.pairedBattle.maxSnapshotsPerGame=220 \
  -Dmonopoly.pairedBattle.seedBase=2026361701 \
  -Dmonopoly.pairedBattle.controlLineup=hard,hard \
  -Dmonopoly.pairedBattle.treatmentLineup=hard,llm \
  -Dmonopoly.pairedBattle.focusSeat=2 \
  -Dmonopoly.pairedBattle.llmStrategy=local_ranker \
  -Dmonopoly.localRanker.modelPath=backend/models/distillation/lookahead-student-v3-cf-nodeposit-w8-mlp/candidate_ranker_mlp.json \
  -Dmonopoly.localRanker.trace.includeMemento=true \
  -Dmonopoly.pairedBattle.tracePath=training/data/models/traces/student-w8-memento-30x220-seat2-seed2026361701.jsonl \
  -Dmonopoly.pairedBattle.traceSchema=decision \
  -Dmonopoly.pairedBattle.traceMode=overwrite \
  -Dmonopoly.pairedBattle.output=training/data/models/evaluation/paired-hard-vs-student-w8-memento-30x220-seat2-seed2026361701.json \
  -Dmonopoly.pairedBattle.quiet=true
```

Trace audit:

- Trace: `training/data/models/traces/student-w8-memento-30x220-seat2-seed2026361701.jsonl`.
- Rows: 795.
- Sessions: 30.
- Decision kinds: 795 `PLAY_CARD`.
- Memento coverage: 795/795.
- Source student on the trace block: paired treatment/control/tie = 15/12/3; median score delta +9; 10% trimmed score delta -8.46.

Lookahead DAgger relabel:

```bash
mvn -q exec:java \
  -Dexec.mainClass=com.monopoly.tools.TraceRelabeler \
  -Dmonopoly.relabel.inputPath=training/data/models/traces/student-w8-memento-30x220-seat2-seed2026361701.jsonl \
  -Dmonopoly.relabel.outputPath=training/data/models/traces/student-w8-dagger-lookahead-relabel-795-seat2-seed2026361701.jsonl \
  -Dmonopoly.relabel.teacher=lookahead \
  -Dmonopoly.lookaheadTeacher.resultSource=lookahead_dagger \
  -Dmonopoly.relabel.batchSize=1 \
  -Dmonopoly.relabel.maxRows=0 \
  -Dmonopoly.relabel.traceMode=overwrite \
  -Dmonopoly.relabel.requiredResultSource=lookahead_dagger \
  -Dmonopoly.relabel.continueOnError=true
```

Relabel result:

- Output: `training/data/models/traces/student-w8-dagger-lookahead-relabel-795-seat2-seed2026361701.jsonl`.
- Rows relabeled: 795/795.
- Error count: 0.
- Relabel mode: 795 `lookahead_strategy_replay`.
- Student/lookahead disagreement: 199/795 = 25.0%, which confirms the DAgger rows carry corrective signal instead of duplicating the student policy.

Training:

```bash
python3 training/scripts/distill_dataset.py \
  training/data/models/traces/lookahead-train-fixed100.jsonl \
  training/data/models/traces/lookahead-rent600-seat2-weak100-outcome.jsonl \
  training/data/models/traces/counterfactual-training-10x220-gap100-beats-hard-no-deposit-best.jsonl \
  training/data/models/traces/student-w8-dagger-lookahead-relabel-795-seat2-seed2026361701.jsonl \
  --output-dir backend/models/distillation/lookahead-student-v4-dagger795-w4-mlp \
  --model-type mlp --epochs 32 --batch-size 512 --lr 0.001 \
  --balance-by-kind --kind-balance-max 3 \
  --split-by session --validation-ratio 0.2 \
  --include-sources lookahead,counterfactual_replay,lookahead_dagger \
  --duplicate-policy prefer-later \
  --include-player-counts 2 \
  --source-multiplier counterfactual_replay=8 \
  --source-multiplier lookahead_dagger=4 \
  --min-rows 3500
```

Offline metrics:

- Model: `backend/models/distillation/lookahead-student-v4-dagger795-w4-mlp/candidate_ranker_mlp.json`.
- Dataset: 6239 `PLAY_CARD` rows from 240 sessions.
- Source mix: 5418 `lookahead`, 795 `lookahead_dagger`, 26 `counterfactual_replay`.
- Validation top-1: 0.700.
- Validation MRR: 0.814.

Fixed-seat gameplay:

- Summary: `training/data/models/evaluation/lookahead-student-v4-dagger795-w4-120pairs-summary.json`.
- Reports: seeds `2026361801`, `2026361901`, `2026362001`, `2026362101`; 30 paired seeds each.
- Total: 120 paired seeds.
- Paired treatment/control/tie = 77/38/5.
- Paired treatment rate = 0.642; 95% CI = 0.553-0.722.
- Paired margin = +39.
- Treatment/control natural wins = 59/54.
- Median score delta = +90.
- 10% trimmed score delta = +184.70.

Robustness sanity:

- Random first player, still focus seat 2: `training/data/models/evaluation/paired-hard-vs-lookahead-student-v4-dagger795-w4-randomfirst-40x260-seat2-seed2026362201.json`.
  - Paired treatment/control/tie = 20/19/1.
  - Natural wins = 29/22.
  - Median score delta = +5.
  - 10% trimmed score delta = +156.31.
- Seat 1 deployment: `training/data/models/evaluation/paired-hard-vs-lookahead-student-v4-dagger795-w4-seat1-40x260-seed2026362301.json`.
  - Paired treatment/control/tie = 28/10/2.
  - Natural wins = 26/23.
  - Median score delta = +105.
  - 10% trimmed score delta = +278.31.
- Four-player hard table: `training/data/models/evaluation/paired-hard4-vs-lookahead-student-v4-dagger795-w4-seat2-40x320-seed2026362401.json`.
  - Paired treatment/control/tie = 24/14/2.
  - Natural wins = 6/4.
  - Median score delta = +120.
  - 10% trimmed score delta = +381.84.

Interpretation:

`lookahead-student-v4-dagger795-w4-mlp` is now the strongest distilled local student candidate. The main evidence should be the same-seed paired outcome and natural-win/rank summaries, not average final score alone. The score distribution remains high variance due to Monopoly Deal swing events such as Deal Breaker and full-set steals: in the 120-pair fixed-seat aggregate, score deltas range from -3310 to +4291 even though paired outcomes are 77/38/5. Keep score delta as a secondary robustness check using median and trimmed mean.

Do not mark the overall AI goal complete yet. This student has strong fixed-seat evidence, seat1 evidence, and a positive four-player sanity check, but the 80-pair random-first aggregate is negative on same-seed paired outcomes. It still lacks a full robustness matrix comparable to the lookahead champion's 1520-pair gate.

## 2026-05-26 Fresh Lookahead Data And Prefix Validation

Purpose: test whether adding a fresh current-code lookahead outcome trace improves the distilled student, and audit whether the offline validation split is still trustworthy.

Fresh trace collection:

```bash
mvn -q exec:java \
  -Dexec.mainClass=com.monopoly.tools.PairedSeedPolicyExperimentRunner \
  -Dmonopoly.pairedBattle.games=60 \
  -Dmonopoly.pairedBattle.maxSnapshotsPerGame=260 \
  -Dmonopoly.pairedBattle.seedBase=2026361401 \
  -Dmonopoly.pairedBattle.controlLineup=hard,hard \
  -Dmonopoly.pairedBattle.treatmentLineup=hard,llm \
  -Dmonopoly.pairedBattle.focusSeat=2 \
  -Dmonopoly.pairedBattle.llmStrategy=lookahead \
  -Dmonopoly.pairedBattle.tracePath=training/data/models/traces/lookahead-current-seat2-60x260-seed2026361401-outcome.jsonl \
  -Dmonopoly.pairedBattle.traceSchema=outcome \
  -Dmonopoly.pairedBattle.traceMode=overwrite \
  -Dmonopoly.pairedBattle.output=training/data/models/evaluation/paired-hard-vs-lookahead-current-60x260-seat2-seed2026361401.json \
  -Dmonopoly.pairedBattle.quiet=true
```

Data audit:

- Trace: `training/data/models/traces/lookahead-current-seat2-60x260-seed2026361401-outcome.jsonl`.
- Rows: 1646.
- Sessions: 60.
- Decision kinds: 1646 `PLAY_CARD`.
- Teacher source: 1646 `lookahead`.
- Outcomes: 946 natural-win rows, 700 rank-2 rows.
- Force-end rows: 0.
- Candidate-score metadata present: 1646/1646.
- Source lookahead paired report: treatment/control/tie = 37/21/2; natural wins 33/21; median score delta +89.5; 10% trimmed score delta +309.27.
- Summary artifact: `training/data/models/evaluation/lookahead-current-60x260-seat2-seed2026361401-summary.json`.

Fresh60 training:

```bash
python3 training/scripts/distill_dataset.py \
  training/data/models/traces/lookahead-train-fixed100.jsonl \
  training/data/models/traces/lookahead-rent600-seat2-weak100-outcome.jsonl \
  training/data/models/traces/lookahead-current-seat2-60x260-seed2026361401-outcome.jsonl \
  training/data/models/traces/counterfactual-training-10x220-gap100-beats-hard-no-deposit-best.jsonl \
  --output-dir backend/models/distillation/lookahead-student-v3-cf-nodeposit-w8-fresh60-mlp \
  --model-type mlp --epochs 32 --batch-size 512 --lr 0.001 \
  --balance-by-kind --kind-balance-max 3 \
  --split-by session --validation-ratio 0.2 \
  --include-sources lookahead,counterfactual_replay \
  --duplicate-policy prefer-later \
  --include-player-counts 2 \
  --source-multiplier counterfactual_replay=8 \
  --min-rows 4500
```

Offline metrics:

- Artifact: `backend/models/distillation/lookahead-student-v3-cf-nodeposit-w8-fresh60-mlp/candidate_ranker_mlp.json`.
- Dataset after duplicate handling: 7090 rows, 7064 `lookahead`, 26 `counterfactual_replay`.
- Random session validation top-1: 0.781.
- Random session validation MRR: 0.869.

Gameplay check:

- Report: `training/data/models/evaluation/paired-hard-vs-lookahead-student-v3-cf-nodeposit-w8-fresh60-30x260-seat2-seed2026361501.json`.
- 30 paired seeds: treatment/control/tie = 13/13/4.
- Natural wins: treatment 11, control 14.
- Average score delta: -103.53.
- Median score delta: 0.0.
- 10% trimmed score delta: -132.25.
- The candidate failed the lightweight paired gate.

Diagnosis:

The random session split strongly overestimated the model. The fresh trace is one coherent collection block, so random session validation can put highly related same-run states on both sides of the split. `training/scripts/distill_dataset.py` now supports `--split-by session-prefix`, which groups sessions by collection run prefix and can hold out an entire trace block.

Prefix validation check:

```bash
python3 training/scripts/distill_dataset.py \
  training/data/models/traces/lookahead-train-fixed100.jsonl \
  training/data/models/traces/lookahead-rent600-seat2-weak100-outcome.jsonl \
  training/data/models/traces/lookahead-current-seat2-60x260-seed2026361401-outcome.jsonl \
  training/data/models/traces/counterfactual-training-10x220-gap100-beats-hard-no-deposit-best.jsonl \
  --output-dir backend/models/distillation/lookahead-student-v3-cf-nodeposit-w8-fresh60-prefixcheck-mlp \
  --model-type mlp --epochs 12 --batch-size 512 --lr 0.001 \
  --balance-by-kind --kind-balance-max 3 \
  --split-by session-prefix --validation-ratio 0.25 \
  --include-sources lookahead,counterfactual_replay \
  --duplicate-policy prefer-later \
  --include-player-counts 2 \
  --source-multiplier counterfactual_replay=8 \
  --min-rows 4500
```

Result:

- Prefix validation top-1: 0.513.
- Prefix validation MRR: 0.696.
- Held-out validation decisions: 1646, matching the fresh trace block.

Interpretation:

Do not promote the fresh60 student. The fresh lookahead trace is valid and the teacher itself is strong on that block, but direct mixing produced a student that overfit distribution-specific patterns and failed gameplay. Future offline comparisons that mix collection runs should use `--split-by session-prefix` or another block holdout, then still require paired-seed gameplay gates.

## 2026-05-25 Student V3 Counterfactual Blend W8

Purpose: test whether a small number of high-confidence counterfactual labels can improve the distilled student beyond pure lookahead imitation.

Data:

- Base lookahead rows: `training/data/models/traces/lookahead-train-fixed100.jsonl` plus `training/data/models/traces/lookahead-rent600-seat2-weak100-outcome.jsonl`.
- Counterfactual rows: `training/data/models/traces/counterfactual-training-10x220-gap100-beats-hard-no-deposit-best.jsonl`.
- Dataset after duplicate handling: 5444 `PLAY_CARD` rows from 210 sessions.
- Teacher source mix: 5418 `lookahead`, 26 `counterfactual_replay`.
- Counterfactual sample weight: `--source-multiplier counterfactual_replay=8`.
- Feature dimension: 165.

Training command:

```bash
python3 training/scripts/distill_dataset.py \
  training/data/models/traces/lookahead-train-fixed100.jsonl \
  training/data/models/traces/lookahead-rent600-seat2-weak100-outcome.jsonl \
  training/data/models/traces/counterfactual-training-10x220-gap100-beats-hard-no-deposit-best.jsonl \
  --output-dir backend/models/distillation/lookahead-student-v3-cf-nodeposit-w8-mlp \
  --model-type mlp --epochs 32 --batch-size 512 --lr 0.001 \
  --balance-by-kind --kind-balance-max 3 \
  --split-by session --validation-ratio 0.2 \
  --include-sources lookahead,counterfactual_replay \
  --duplicate-policy prefer-later \
  --include-player-counts 2 \
  --source-multiplier counterfactual_replay=8 \
  --min-rows 3000
```

Offline metrics:

- Artifact: `backend/models/distillation/lookahead-student-v3-cf-nodeposit-w8-mlp/candidate_ranker_mlp.json`.
- Validation top-1: 0.705.
- Validation MRR: 0.818.
- First-candidate baseline: 0.272.
- Random expected top-1: 0.224.

Gameplay checks against hard:

- `training/data/models/evaluation/paired-hard-vs-lookahead-student-v3-cf-nodeposit-w8-20x260-seat2-seed2026360701.json`: treatment/control = 13/7, natural wins 11/8, average score delta +783.05.
- `training/data/models/evaluation/paired-hard-vs-lookahead-student-v3-cf-nodeposit-w8-30x260-seat2-seed2026360801.json`: treatment/control/tie = 15/12/3, natural wins 15/12, average score delta +155.03.
- `training/data/models/evaluation/paired-hard-vs-lookahead-student-v3-cf-nodeposit-w8-50x260-seat2-seed2026360901.json`: treatment/control/tie = 28/18/4, natural wins 25/25, average score delta +46.98.
- `training/data/models/evaluation/paired-hard-vs-lookahead-student-v3-cf-nodeposit-w8-30x260-seat2-seed2026361101.json`: treatment/control = 18/12, natural wins 15/10, average score delta +805.77.
- Aggregate over 130 paired seeds: treatment/control/tie = 74/49/7, natural wins 66/55, paired treatment rate 0.569 with 95% CI 0.483-0.651, median score delta +30.0, 10% trimmed score delta +231.62.
- Aggregate artifact: `training/data/models/evaluation/lookahead-student-v3-cf-nodeposit-w8-130pairs-summary.json`.

Interpretation:

This is the best distilled-student checkpoint so far and fixes the prior problem where a 20-pair positive smoke failed the next replication. The 130-pair aggregate is directionally positive, but the natural-win confidence interval still includes parity and the median score edge is only +30, so the model should be treated as a promising candidate, not yet a robust "stronger than hard" artifact comparable to `SearchLookaheadAiPlayStrategy`. Paired outcomes and natural wins are the primary criteria; average score delta is useful only as an auxiliary magnitude indicator because Monopoly Deal has high-swing events such as complete-set steals.

### Counterfactual Label-Pool Ablation: No-PassGo W8

Purpose: test whether using a larger 39-row counterfactual label pool improves over the conservative 26-row no-deposit-best pool.

Training:

- Artifact: `backend/models/distillation/lookahead-student-v3-cf-nopassgo-w8-mlp/candidate_ranker_mlp.json`.
- Rows: 5418 `lookahead`, 39 `counterfactual_replay`.
- Source multiplier: `--source-multiplier counterfactual_replay=8`.
- Validation top-1: 0.692.
- Validation MRR: 0.809.

Gameplay:

- Report: `training/data/models/evaluation/paired-hard-vs-lookahead-student-v3-cf-nopassgo-w8-30x260-seat2-seed2026361101.json`.
- 30 paired seeds: treatment/control/tie = 19/10/1.
- Natural wins: treatment 13, control 10.
- Average score delta: +726.10.
- Median score delta: +102.5.
- 10% trimmed score delta: +625.63.
- Same-seed nodeposit W8 baseline: treatment/control = 18/12, natural wins 15/10, average score delta +805.77, median score delta +60.5.

Interpretation:

The larger 39-row pool is not a clear upgrade. It slightly improves paired outcomes on this seed block but has lower validation accuracy and fewer natural wins than the 26-row baseline on the same seeds. Keep the 26-row no-deposit W8 model as the current best student and treat the no-pass-go pool as a neutral ablation.

### Paired Evaluation Robustness Update

Finding:

- Final board score is high variance: in the no-pass-go 30-pair block, the mean score delta was +726.10 but the median was only +102.5, with individual deltas from -2120 to +4860.
- This confirms that average score delta can be dominated by a few large Monopoly Deal swings, especially complete-set steals.

Tooling change:

- `PairedSeedPolicyExperimentRunner` now writes median score delta, 10% trimmed score delta, score P10/P90, and rank-delta summaries into new reports.
- `training/scripts/summarize_paired_reports.py` now recomputes these robust metrics for old reports and can gate on paired treatment rate, median score delta, and trimmed score delta.

Evaluation policy:

Use paired same-seed outcomes as the primary model-selection signal. Use natural wins and board rank as secondary signals. Treat score delta mean as an auxiliary magnitude metric, and prefer median or trimmed score delta when deciding whether a result is stable.

### Listwise Loss Ablation

Purpose: test whether training directly over each decision's legal-candidate set improves the distilled student over the legacy per-candidate BCE objective.

Tooling:

- `training/scripts/distill_dataset.py` now supports `--loss-type listwise`.
- The listwise objective batches complete decision groups, masks padded candidates, and optimizes per-decision softmax cross entropy.
- The exported JSON format is unchanged, so `LocalRankerAiPlayStrategy` can load listwise-trained MLPs without runtime changes.

One-hot listwise training:

```bash
python3 training/scripts/distill_dataset.py \
  training/data/models/traces/lookahead-train-fixed100.jsonl \
  training/data/models/traces/lookahead-rent600-seat2-weak100-outcome.jsonl \
  training/data/models/traces/counterfactual-training-10x220-gap100-beats-hard-no-deposit-best.jsonl \
  --output-dir backend/models/distillation/lookahead-student-v3-cf-nodeposit-w8-listwise-mlp \
  --model-type mlp --loss-type listwise --epochs 32 --batch-size 128 --lr 0.001 \
  --balance-by-kind --kind-balance-max 3 \
  --split-by session --validation-ratio 0.2 \
  --include-sources lookahead,counterfactual_replay \
  --duplicate-policy prefer-later \
  --include-player-counts 2 \
  --source-multiplier counterfactual_replay=8 \
  --min-rows 3000
```

Result:

- Artifact: `backend/models/distillation/lookahead-student-v3-cf-nodeposit-w8-listwise-mlp/candidate_ranker_mlp.json`.
- Validation top-1: 0.709.
- Validation MRR: 0.818.
- Paired report: `training/data/models/evaluation/paired-hard-vs-lookahead-student-v3-cf-nodeposit-w8-listwise-30x260-seat2-seed2026361301.json`.
- 30 paired seeds: treatment/control = 16/14.
- Natural wins: treatment 17, control 15.
- Median score delta: +25.0.
- 10% trimmed score delta: +106.67.
- Same-seed BCE W8 baseline: treatment/control = 14/16, natural wins 13/15, median score delta -30.0, 10% trimmed score delta -128.33.
- 50-pair recheck on the old W8 seed block: `training/data/models/evaluation/paired-hard-vs-lookahead-student-v3-cf-nodeposit-w8-listwise-50x260-seat2-seed2026360901.json` gave treatment/control/tie = 25/22/3, natural wins 27/25, median score delta +5.0.
- Aggregate artifact: `training/data/models/evaluation/lookahead-student-v3-cf-nodeposit-w8-listwise-80pairs-summary.json`.
- Aggregate over 80 paired seeds: treatment/control/tie = 41/36/3, natural wins 44/40, paired treatment rate 0.5125, median score delta +10.0, 10% trimmed score delta +132.25.

Interpretation:

Listwise training improves the 30-pair seed block and slightly improves offline top-1, but the larger 80-pair aggregate misses the paired treatment-rate gate and does not clearly beat the BCE W8 baseline. Treat it as a useful objective ablation, not a replacement for the current best student.

Soft-score listwise training:

```bash
python3 training/scripts/distill_dataset.py \
  training/data/models/traces/lookahead-train-fixed100.jsonl \
  training/data/models/traces/lookahead-rent600-seat2-weak100-outcome.jsonl \
  training/data/models/traces/counterfactual-training-10x220-gap100-beats-hard-no-deposit-best.jsonl \
  --output-dir backend/models/distillation/lookahead-student-v3-cf-nodeposit-w8-softscore-listwise-mlp \
  --model-type mlp --loss-type listwise --soft-label-source teacher_scores \
  --teacher-score-temperature 600 --teacher-score-mix 0.5 \
  --epochs 32 --batch-size 128 --lr 0.001 \
  --balance-by-kind --kind-balance-max 3 \
  --split-by session --validation-ratio 0.2 \
  --include-sources lookahead,counterfactual_replay \
  --duplicate-policy prefer-later \
  --include-player-counts 2 \
  --source-multiplier counterfactual_replay=8 \
  --min-rows 3000
```

Result:

- Artifact: `backend/models/distillation/lookahead-student-v3-cf-nodeposit-w8-softscore-listwise-mlp/candidate_ranker_mlp.json`.
- Validation top-1: 0.704.
- Validation MRR: 0.815.

Interpretation:

Soft-score listwise did not beat the one-hot listwise or BCE checkpoints offline, so it was not promoted to paired gameplay screening. The candidate-score scale appears noisy enough that naive soft-label mixing is not currently useful.

### Counterfactual Weight Ablation: W16

Purpose: test whether increasing counterfactual weight from 8 to 16 improves the student or overfits the tiny high-confidence label set.

Training:

- Artifact: `backend/models/distillation/lookahead-student-v3-cf-nodeposit-w16-mlp/candidate_ranker_mlp.json`.
- Same rows as W8: 5418 `lookahead`, 26 `counterfactual_replay`.
- Source multiplier: `--source-multiplier counterfactual_replay=16`.
- Validation top-1: 0.701.
- Validation MRR: 0.814.

Gameplay:

- Report: `training/data/models/evaluation/paired-hard-vs-lookahead-student-v3-cf-nodeposit-w16-30x260-seat2-seed2026361001.json`.
- 30 paired seeds: treatment/control/tie = 12/17/1.
- Natural wins: treatment 7, control 9.
- Average score delta: -377.67.

Interpretation:

W16 is worse than W8 both offline and in gameplay. The counterfactual set is useful as a moderate correction, but overweighting 26 rows pulls the student away from generally useful lookahead behavior. Keep W8 as the current best distilled checkpoint.

## 2026-05-25 Counterfactual Replay Clock Repair

Purpose: audit a suspicious counterfactual replay result before using it as training data.

Finding:

- A current 20-row smoke initially produced `candidateErrors=132` and `incompleteCandidates=132`; every candidate failed with `IllegalStateException: 对局已超过单局时长上限，已强制结束。`.
- Root cause: replay restores old `GameSessionMemento.sessionStartEpochMs`; because the replay is run much later than the original game, normal player-facing wall-clock timeout can fire before candidate rollout.
- Fix: `CounterfactualReplayRunner` now calls `GameController.resetSessionClockForSimulation()` immediately after memento restore in hard-choice resolution and candidate replay.

Verification:

```bash
mvn -q exec:java \
  -Dexec.mainClass=com.monopoly.tools.CounterfactualReplayRunner \
  -Dmonopoly.counterfactual.inputPath=training/data/models/traces/counterfactual-lookahead-memento-10x220-seat2-seed2026310101.jsonl \
  -Dmonopoly.counterfactual.outputPath=training/data/models/evaluation/counterfactual-current-smoke-20rows-report.json \
  -Dmonopoly.counterfactual.maxRows=20 \
  -Dmonopoly.counterfactual.maxSnapshotsPerCandidate=260 \
  -Dmonopoly.counterfactual.quiet=true
```

Evidence:

- Report: `training/data/models/evaluation/counterfactual-current-smoke-20rows-report.json`.
- Full report: `training/data/models/evaluation/counterfactual-current-full257-report.json`.
- Decisions: 20.
- Informative decisions: 12.
- Hard-choice resolution errors: 0.
- Candidate errors: 0.
- Incomplete candidates: 0.
- Source better than hard: 2.
- Hard better than source: 2.
- Average source-minus-hard reward: 0.0.
- Average source-minus-hard board score: -2.45.
- Full 257-row audit: candidate errors 0, incomplete candidates 0, informative decisions 185, source better than hard 17, hard better than source 20, average source-minus-hard reward +0.0053.

Interpretation:

The replay tool is usable again, but this old sample has sparse counterfactual signal: lookahead and hard often tie under deterministic hard rollout, so this dataset should be treated as a diagnostic source unless scaled or combined with stronger outcome labeling.

## 2026-05-25 Lookahead Champion And Student V3

Purpose: separate the current strong local gameplay AI from the still-experimental distilled student.

Evidence:

- Current champion: `SearchLookaheadAiPlayStrategy` (`CUSTOM` role `lookahead` / `search`).
- Robustness summary: `training/data/models/evaluation/lookahead-rent600-robustness-summary.json`.
- Refreshed gate: `training/data/models/evaluation/lookahead-rent600-robustness-gate-refresh.json`.
- Overall lookahead vs hard, re-audited by the 2026-05-26 rank-only gate: 1520 pairs, natural-win rate 0.588 vs hard 0.450, rank-only paired outcome 357 / 134 / 1029, decisive paired treatment rate 0.727, decisive Wilson lower bound 0.686, average score delta +440.24 as diagnostic only.
- Gate command:

```bash
python3 training/scripts/check_ai_robustness_gate.py \
  training/data/models/evaluation/lookahead-rent600-robustness-summary.json \
  --output training/data/models/evaluation/lookahead-rent600-robustness-gate-refresh.json
```

Student V3 changes:

- Added v3 runtime-visible tactics features to `training/scripts/distill_dataset.py` and `LocalRankerAiPlayStrategy.FeatureExtractor`.
- Trained `backend/models/distillation/lookahead-student-fixed100-rent600-mlp-v3-tactics/candidate_ranker_mlp.json`.
- Dataset: 5418 lookahead-labeled `PLAY_CARD` decisions from 200 two-player sessions.
- Feature dimension: 165.
- Validation top-1: 0.699.
- Validation MRR: 0.818.
- Play-card-only 20-pair smoke: treatment/control/tie = 11/7/2, average score delta +564.2.
- Play-card-only 30-pair replication: treatment/control = 13/17, average score delta +116.9.

Interpretation:

The v3 student improved over the previous student and exposed a real deployment bug: ranking untrained auxiliary decisions hurt gameplay. `LocalRankerAiPlayStrategy` now defaults to hard fallback for `PAYMENT`, `JUST_SAY_NO`, and `OVERFLOW_DISCARD`; auxiliary ranking requires `-Dmonopoly.localRanker.rankAuxiliaryDecisions=true`. The student is not yet robust enough to claim stronger-than-hard status. The safe local strong-AI claim belongs to `lookahead`.

## 2026-05-24 Local Smoke

Purpose: prove the end-to-end no-cost pipeline works on the Mac.

Command:

```bash
training/scripts/run_distillation_smoke.sh
```

Evidence:

- Dataset: `training/data/distillation/smoke.jsonl` (ignored by Git)
- Rows: 91 decisions
- Sessions: 4
- Teacher source: `local_heuristic`
- Decision mix:
  - `PLAY_CARD`: 71
  - `PAYMENT`: 9
  - `JUST_SAY_NO`: 6
  - `OVERFLOW_DISCARD`: 5
- Average legal candidates: 7.60
- Model artifact: `backend/models/distillation/smoke/candidate_ranker.pt` (ignored by Git)
- Device: Apple `mps`
- Feature dimension: 119
- Validation top-1: 0.722 on a tiny heuristic-teacher smoke set
- Validation MRR: 0.800
- First-candidate baseline: 0.278
- Random expected top-1: 0.184
- Linear JSON ranker export: passed.
- Java local-ranker battle smoke: passed with `training/scripts/evaluate_local_ranker.sh`.
- JSON gameplay evaluation runner: passed with `training/scripts/evaluate_distilled_ranker.sh`.
- Quality report generation: passed with `training/scripts/report_distillation_quality.py`.

Interpretation:

This is not a quality model. It proves that legal-state generation, JSONL validation, feature extraction, MPS training, metrics, and artifact writing all work.
The local heuristic teacher avoids the earlier first-candidate label bias, but it is still only a baseline.

## 2026-05-24 DeepSeek Probe

Purpose: verify the paid teacher path before running a larger overnight collection.

Command:

```bash
mvn -q exec:java \
  -Dexec.mainClass=com.monopoly.tools.SimulationBatchRunner \
  -Dmonopoly.ai.decisionDelayMs=0 \
  -Dmonopoly.simulation.teacher=deepseek \
  -Dmonopoly.simulation.games=1 \
  -Dmonopoly.simulation.parallel=1 \
  -Dmonopoly.simulation.playerCounts=2 \
  -Dmonopoly.simulation.maxSnapshots=4 \
  -Dmonopoly.simulation.batchSize=2 \
  -Dmonopoly.simulation.batchWaitMs=10 \
  -Dmonopoly.simulation.runtimeSeconds=20 \
  -Dmonopoly.deepseek.maxTokens=2048 \
  -Dmonopoly.simulation.tracePath=/tmp/monopoly-deepseek-probe.jsonl
```

Evidence:

- Rows: 1 decision
- Dataset validation: pass
- Teacher source: `deepseek`
- Teacher model metadata: `deepseek-v4-flash`
- Candidate count: 8
- Raw response shape: `{"decisions":[{"decisionId":"...","choiceId":"c2"}]}`

Interpretation:

DeepSeek batch labeling is wired correctly. Larger collection can use `training/scripts/collect_deepseek_distillation.sh`; train with `--include-sources deepseek` so transient fallback rows are excluded from the primary student model.
The DeepSeek API key must come from `DEEPSEEK_API_KEY` or `-Dmonopoly.deepseek.apiKey=...`; source code intentionally has no embedded key.

## Next Overnight Run

Recommended first paid run:

```bash
DEEPSEEK_API_KEY=... \
training/scripts/overnight_distillation_run.sh \
  training/data/distillation/deepseek-run1.jsonl \
  backend/models/distillation/deepseek-run1
```

Equivalent manual collection command:

```bash
MONOPOLY_SIM_GAMES=200 \
MONOPOLY_SIM_PARALLEL=8 \
MONOPOLY_SIM_PLAYER_COUNTS=2,3,4,5 \
MONOPOLY_SIM_BATCH_SIZE=24 \
MONOPOLY_SIM_MAX_BY_KIND=PLAY_CARD:20000,PAYMENT:5000,JUST_SAY_NO:5000,OVERFLOW_DISCARD:5000 \
MONOPOLY_SIM_MAX_SNAPSHOTS=240 \
DEEPSEEK_API_KEY=... \
training/scripts/collect_deepseek_distillation.sh training/data/distillation/deepseek-run1.jsonl
```

Trace paths are protected by default. If `training/data/distillation/deepseek-run1.jsonl` already exists, collection exits before spending tokens. Set `MONOPOLY_TRACE_MODE=append` only when intentionally resuming the same run, or `MONOPOLY_TRACE_MODE=overwrite` when intentionally replacing it.

Then train:

```bash
training/scripts/train_distilled_rankers.sh \
  training/data/distillation/deepseek-run1.jsonl \
  backend/models/distillation/deepseek-run1
```

Primary report paths:

- `backend/models/distillation/deepseek-run1-dataset_manifest.json`
- `backend/models/distillation/deepseek-run1-quality_report.md`
- `backend/models/distillation/deepseek-run1-mlp/metrics.json`
- `backend/models/distillation/deepseek-run1-mlp/gameplay_vs_hard.json`
- `backend/models/distillation/deepseek-run1-linear/candidate_ranker_linear.json`

Minimum quality gates before trusting the student:

- Dataset validation passes.
- `byTeacherSource.deepseek` is at least 95% of rows.
- At least 5k labeled decisions for the first meaningful model.
- All four decision kinds appear.
- Rare decision kinds should each have enough rows for validation; use `MONOPOLY_SIM_MAX_BY_KIND` to avoid `PLAY_CARD` dominating paid collection.
- Validation top-1 beats first-candidate baseline and random baseline.
- Java local-ranker battle smoke completes without invalid-action loops.
- Gameplay win-rate evaluation is added before replacing the current AI in normal play.

## 2026-05-24 Local Baseline Run

Purpose: create a larger no-cost baseline dataset and verify trainer/evaluator behavior without a DeepSeek key.

Command:

```bash
mvn -q compile exec:java \
  -Dexec.mainClass=com.monopoly.tools.SimulationBatchRunner \
  -Dmonopoly.deepseek.enabled=false \
  -Dmonopoly.ai.decisionDelayMs=0 \
  -Dmonopoly.simulation.teacher=heuristic \
  -Dmonopoly.simulation.games=48 \
  -Dmonopoly.simulation.parallel=8 \
  -Dmonopoly.simulation.playerCounts=2,3,4,5 \
  -Dmonopoly.simulation.maxSnapshots=100 \
  -Dmonopoly.simulation.batchSize=24 \
  -Dmonopoly.simulation.batchWaitMs=50 \
  -Dmonopoly.simulation.runtimeSeconds=20 \
  -Dmonopoly.simulation.tracePath=/tmp/monopoly-local-baseline.jsonl

MONOPOLY_MIN_TRAIN_ROWS=2000 \
MONOPOLY_TRAIN_SOURCES=local_heuristic \
MONOPOLY_TRAIN_EPOCHS=8 \
MONOPOLY_EVAL_GAMES=4 \
MONOPOLY_EVAL_PLAYERS=3 \
MONOPOLY_EVAL_SNAPSHOTS=100 \
training/scripts/train_distilled_rankers.sh \
  /tmp/monopoly-local-baseline.jsonl \
  /tmp/monopoly-local-baseline-model
```

Evidence:

- Dataset: `/tmp/monopoly-local-baseline.jsonl`
- Rows: 2453 decisions
- Sessions: 48
- Teacher source: `local_heuristic`
- Decision mix:
  - `PLAY_CARD`: 1916
  - `PAYMENT`: 409
  - `JUST_SAY_NO`: 93
  - `OVERFLOW_DISCARD`: 35
- Average legal candidates: 8.75
- MLP validation top-1: 0.764
- MLP validation MRR: 0.865
- First-candidate baseline: 0.299
- Random expected top-1: 0.212
- Linear validation top-1: 0.650
- Java linear ranker battle smoke: passed.
- JSON gameplay evaluation: 4/4 games hit the 100-snapshot limit; natural win rate 0.0.
- Quality report: `/tmp/monopoly-local-baseline-model-quality_report.md`
- MLP model: `/tmp/monopoly-local-baseline-model-mlp/candidate_ranker.pt`
- Java-loadable MLP model: `/tmp/monopoly-local-baseline-model-mlp/candidate_ranker_mlp.json`
- Java-loadable linear model: `/tmp/monopoly-local-baseline-model-linear/candidate_ranker_linear.json`

Interpretation:

This is a useful baseline and pipeline stress test, not a production opponent. The imitation metrics are strong against the heuristic teacher. The Java-loadable MLP export runs in backend battles, but the local student still did not finish games within the small 100-snapshot evaluation window. DeepSeek labels and stronger evaluation are still required before replacing the normal AI.

## 2026-05-24 Java MLP Runtime

Purpose: avoid being limited to the weaker linear JSON model.

Evidence:

- `training/scripts/distill_dataset.py` now exports `candidate_ranker_mlp.json`.
- `LocalRankerAiPlayStrategy` loads both `monopoly-deal-mlp-ranker-v1` and `monopoly-deal-linear-ranker-v1`.
- `LocalRankerAiPlayStrategy` implements `AiChoiceAdvisor`, so the same Java model now ranks `PLAY_CARD`, `PAYMENT`, `JUST_SAY_NO`, and `OVERFLOW_DISCARD` candidates at runtime.
- MLP JSON smoke artifact: `/tmp/monopoly-mlp-json-check/candidate_ranker_mlp.json`
- MLP JSON shape: `119 -> 238 -> 119 -> 1`
- Java battle smoke with MLP JSON: passed.
- JSON gameplay evaluation with MLP JSON: passed; 2/2 games hit the 40-snapshot limit, natural win rate 0.0.
- Targeted Java tests for play, payment, overflow discard, and Just Say No local-ranker entry points: passed.
- Full Maven test suite: passed at `2026-05-24 06:20 CST`.

Interpretation:

The backend can now use the stronger MLP student format without Python at runtime, and the runtime decision surface matches the four decision kinds collected by the simulator. The next important quality step is paid DeepSeek collection and longer gameplay evaluation; the current local baseline is still heuristic-labeled.

## 2026-05-24 Multi-Session Backend Check

Purpose: confirm that one backend process can host multiple independent games, which is required for efficient offline collection and possible future server-side multi-room play.

Evidence:

- `SimulationBatchRunner` runs many `SimulationWorker` instances in one JVM. Each worker owns a distinct `GameController`; every controller owns an isolated `GameEngineSingleton.createIsolated()` deck engine.
- A shared `DecisionBroker` collects decisions from those independent games and batches them into one teacher call stream.
- `GameServer` keeps a `sessions` map keyed by `sessionId` and routes commands to the matching controller.
- `GameServerMultiSessionIsolationTest`: passed. It verifies that `STATE_UPDATE` messages for `room-a` are not sent to `room-b`, and vice versa.
- Network regression group: passed after the broadcast fallback was tightened for real multi-controller sessions.

Interpretation:

The data collector should use one backend/JVM with many independent controllers plus one broker, not many backend processes. This keeps all legal-state generation inside the same Java rule engine while still allowing DeepSeek requests to be batched across games.

## 2026-05-24 Overnight Script Smoke

Purpose: verify the one-command overnight pipeline without a DeepSeek key.

Command:

```bash
MONOPOLY_OVERNIGHT_MODE=local \
MONOPOLY_SIM_GAMES=4 \
MONOPOLY_SIM_PARALLEL=2 \
MONOPOLY_SIM_MAX_SNAPSHOTS=40 \
MONOPOLY_SIM_RUNTIME_SECONDS=10 \
MONOPOLY_MIN_TRAIN_ROWS=20 \
MONOPOLY_TRAIN_EPOCHS=2 \
MONOPOLY_EVAL_GAMES=1 \
MONOPOLY_EVAL_SNAPSHOTS=20 \
MONOPOLY_EVAL_OPPONENT_STRATEGY=hard \
training/scripts/overnight_distillation_run.sh \
  /tmp/monopoly-overnight-smoke.jsonl \
  /tmp/monopoly-overnight-smoke-model
```

Evidence:

- Dataset rows: 82
- Sessions: 4
- Teacher source: `local_heuristic`
- Decision mix:
  - `PLAY_CARD`: 60
  - `PAYMENT`: 15
  - `OVERFLOW_DISCARD`: 4
  - `JUST_SAY_NO`: 3
- MLP device: `mps`
- MLP validation top-1: 0.688
- MLP validation MRR: 0.818
- First-candidate baseline: 0.375
- Random expected top-1: 0.206
- Quality report: `/tmp/monopoly-overnight-smoke-model-quality_report.md`
- Java-loadable MLP: `/tmp/monopoly-overnight-smoke-model-mlp/candidate_ranker_mlp.json`
- Ranker-seat vs Hard smoke: `/tmp/monopoly-overnight-smoke-model-mlp/gameplay_vs_hard.json`, 1/1 game hit the 20-snapshot smoke limit.

Interpretation:

The overnight wrapper is runnable end-to-end. This smoke is intentionally tiny and local-teacher only, so it is not a model-quality claim. It proves that, once a DeepSeek key is present, the same script can produce a trace, train Java-loadable students, run ranker-seat gameplay evaluation, and write the quality report.

## 2026-05-24 Dataset Manifest and Session Split

Purpose: make dataset quality and validation metrics more trustworthy for overnight runs.

Changes:

- `training/scripts/distill_dataset.py` now defaults to `--split-by session`, so train/validation are separated by whole game session instead of only by decision id.
- `training/scripts/dataset_manifest.py` writes a dataset manifest with SHA-256, row count, source mix, decision coverage, player-count coverage, round coverage, and candidate-count percentiles.
- `training/scripts/train_distilled_rankers.sh` and `training/scripts/overnight_distillation_run.sh` write `<output-prefix>-dataset_manifest.json`.

Evidence:

- Python unit test: `python3 -m unittest tests.test_distill_dataset` passed.
- Manifest smoke on `/tmp/monopoly-overnight-smoke.jsonl`: SHA-256 `a96f700f5d8611893536e8d3adc6fc9aab17920db104a4c1597221f233107670`, 82 rows, 4 sessions, all four decision kinds covered.
- Session-split training smoke: `splitBy=session`, 510 train examples, 141 validation examples, validation top-1 0.737 on the tiny local heuristic dataset.

Interpretation:

Future DeepSeek metrics should be treated as more credible than the earlier decision-level split, because adjacent states from the same simulated game no longer leak across train and validation by default.

## 2026-05-24 KNN Offline Baseline

Purpose: add a no-dependency non-neural baseline for model-family comparisons and possible paper ablations.

Changes:

- `training/scripts/distill_dataset.py --model-type knn` evaluates a nearest-neighbor candidate ranker over the same 119-dim candidate features.
- `training/scripts/train_distilled_rankers.sh` now writes `<output-prefix>-knn/metrics.json`.
- `training/scripts/report_distillation_quality.py` accepts multiple `--metrics` files and renders a model-comparison table.

Evidence:

- KNN smoke on `/tmp/monopoly-overnight-smoke.jsonl`: passed, `splitBy=session`, no sklearn dependency required.
- Multi-metrics report smoke: passed with MLP + KNN metrics and rendered a `Model Comparison` table.
- Full `training/scripts/train_distilled_rankers.sh` smoke on `/tmp/monopoly-overnight-smoke.jsonl`: passed with MLP, linear, KNN, dataset manifest, Java gameplay vs Hard, and multi-model quality report.
- Example comparison from the smoke report: MLP validation top-1 0.684, linear 0.263, KNN 0.579.

Interpretation:

The KNN baseline is not intended for Java gameplay runtime. It gives us a traditional-model comparator against MLP and linear students, useful for deciding whether feature quality or neural capacity is the bottleneck.

## 2026-05-24 Collection Quotas

Purpose: control label mix during paid collection.

Changes:

- `DecisionBroker` now accepts per-decision-kind record quotas.
- `SimulationBatchRunner` reads `-Dmonopoly.simulation.maxByKind=PLAY_CARD:...,PAYMENT:...,JUST_SAY_NO:...,OVERFLOW_DISCARD:...`.
- `collect_deepseek_distillation.sh` and `overnight_distillation_run.sh` expose this as `MONOPOLY_SIM_MAX_BY_KIND`.
- `SimulationBatchRunner` defaults `-Dmonopoly.simulation.traceMode=fail_if_exists`; wrappers expose it as `MONOPOLY_TRACE_MODE`.

Interpretation:

This is mainly a cost-control feature. When one kind reaches its quota, the broker uses a local fallback to keep the game moving but does not record or send that decision to DeepSeek. This should improve the paid dataset's balance without corrupting game legality.

Trace path protection is a data-quality feature. It prevents a repeated command from appending new rows into an old JSONL file and making quota/manifest numbers look larger than the current run actually produced.

## 2026-05-24 Strict Trace Validation

Purpose: fail before training if a trace contains structurally valid JSON but inconsistent game context.

Changes:

- `training/scripts/distill_dataset.py --mode validate` now checks game meta against the request, self/player identity consistency, candidate-id consistency between `request.candidates` and `context.decision.legalCandidates`, duplicate visible card ids, and basic payload legality for play/payment/discard decisions.
- `training/scripts/train_distilled_rankers.sh` already runs this validation first, so strict trace validation is now part of every training run.

Evidence:

- Python validation unit tests cover valid rows, duplicate visible cards, missing hand cards in play candidates, and legal-candidate mismatch.
- Fresh quota smoke trace `/tmp/monopoly-quota-fresh-1779580305.jsonl` passed strict validation with 12 rows and all four decision kinds covered.

## 2026-05-24 Local Baseline Artifact

Command:

```bash
MONOPOLY_OVERNIGHT_MODE=local \
MONOPOLY_SIM_GAMES=60 \
MONOPOLY_SIM_PARALLEL=8 \
MONOPOLY_SIM_PLAYER_COUNTS=2,3,4,5 \
MONOPOLY_SIM_MAX_SNAPSHOTS=160 \
MONOPOLY_SIM_RUNTIME_SECONDS=25 \
MONOPOLY_MIN_TRAIN_ROWS=500 \
MONOPOLY_TRAIN_EPOCHS=5 \
MONOPOLY_EVAL_GAMES=5 \
MONOPOLY_EVAL_PLAYERS=3 \
MONOPOLY_EVAL_SNAPSHOTS=160 \
MONOPOLY_EVAL_OPPONENT_STRATEGY=hard \
training/scripts/overnight_distillation_run.sh \
  training/data/distillation/local-baseline-20260524-080604.jsonl \
  backend/models/distillation/local-baseline-20260524-080604
```

Artifacts:

- Trace: `training/data/distillation/local-baseline-20260524-080604.jsonl`
- Manifest: `backend/models/distillation/local-baseline-20260524-080604-dataset_manifest.json`
- Report: `backend/models/distillation/local-baseline-20260524-080604-quality_report.md`
- MLP: `backend/models/distillation/local-baseline-20260524-080604-mlp/candidate_ranker_mlp.json`
- Linear: `backend/models/distillation/local-baseline-20260524-080604-linear/candidate_ranker_linear.json`

Result:

- 4774 rows from 60 real backend sessions.
- Decision mix: `PLAY_CARD=3690`, `PAYMENT=840`, `JUST_SAY_NO=178`, `OVERFLOW_DISCARD=66`.
- Player-count mix covers 2, 3, 4, and 5 players.
- MLP validation top-1 against the local heuristic teacher: 0.827.
- Linear validation top-1: 0.659.
- KNN validation top-1: 0.393.
- Gameplay vs hard after longer evaluation: 12 games, 12 natural finishes within 400 snapshots, ranker seat won 4/12, natural win rate 1.0 for completed games overall, average snapshots 204.9, average ranker board rank 2.17, board lead rate 0.333.
- Gameplay artifact: `backend/models/distillation/local-baseline-20260524-080604-mlp/gameplay_vs_hard_12x400.json`.
- The refreshed manifest/report include token usage fields. This local baseline has no DeepSeek usage metadata, so `rowsWithUsage=0`; paid traces should populate these fields.
- Artifact package smoke passed with `training/scripts/package_distillation_artifacts.sh backend/models/distillation/local-baseline-20260524-080604 /tmp/local-baseline-artifacts.tar.gz`.
- Cost estimator smoke passed on the local manifest; it reports zero usage for local labels as expected.
- Paid preflight missing-key check passed: it exits with code 2 before running collection when no DeepSeek key is configured.
- Paid probe missing-key check passed: `training/scripts/run_paid_probe.sh` exits with code 2 through the preflight step before spending tokens.
- Windows/5090 handoff package smoke passed: `training/scripts/package_training_handoff.sh training/data/distillation/local-baseline-20260524-080604.jsonl backend/models/distillation/local-baseline-20260524-080604 /tmp/local-baseline-training-handoff.tar.gz`.

Interpretation:

This is a pipeline artifact, not a DeepSeek-quality dataset. It proves collection, strict validation, session split, MLP/linear/KNN training, Java-loadable model export, gameplay evaluation, manifest generation, and quality reporting all work on the Mac.

## 2026-05-24 Kind-Balanced Local Baseline

Purpose: keep rare but strategically important decision kinds from being drowned by common `PLAY_CARD` rows during student training.

Command:

```bash
MONOPOLY_TRAIN_SOURCES=local_heuristic \
MONOPOLY_MIN_TRAIN_ROWS=1000 \
MONOPOLY_TRAIN_EPOCHS=5 \
MONOPOLY_TRAIN_BALANCE_BY_KIND=true \
MONOPOLY_EVAL_GAMES=6 \
MONOPOLY_EVAL_PLAYERS=3 \
MONOPOLY_EVAL_SNAPSHOTS=300 \
MONOPOLY_EVAL_OPPONENT_STRATEGY=hard \
training/scripts/train_distilled_rankers.sh \
  training/data/distillation/local-baseline-20260524-080604.jsonl \
  backend/models/distillation/local-baseline-20260524-080604-balanced
```

Evidence:

- Training now records `balanceByKind`, `kindBalanceMax`, and `kindWeights` in `metrics.json`.
- Default weights on the 4774-row local baseline: `PLAY_CARD=1.0`, `PAYMENT=2.095914`, `JUST_SAY_NO=4.0`, `OVERFLOW_DISCARD=4.0`.
- MLP validation top-1: `0.830`, MRR: `0.904`.
- Per-kind MLP validation top-1:
  - `PLAY_CARD`: `0.831` on 720 validation decisions.
  - `PAYMENT`: `0.922` on 167 validation decisions.
  - `JUST_SAY_NO`: `0.543` on 35 validation decisions.
  - `OVERFLOW_DISCARD`: `0.471` on 17 validation decisions.
- Compared with the unbalanced local MLP on the same split, `OVERFLOW_DISCARD` improved from `0.412` to `0.471`; `PAYMENT` improved from `0.916` to `0.922`; overall top-1 stayed essentially flat (`0.827` to `0.830`).
- Gameplay vs hard: 6 games, all natural/limit-normal end reason, average 213.7 snapshots, average ranker board rank 2.00, board lead rate 0.333.
- Quality report: `backend/models/distillation/local-baseline-20260524-080604-balanced-quality_report.md`.

Interpretation:

Kind balancing is now a sensible default for paid training. It slightly improves rare-kind imitation without harming common play-card accuracy on the local baseline. Keep `MONOPOLY_TRAIN_BALANCE_BY_KIND=false` available for paper-style ablations.

## 2026-05-24 Multi-Session Save/Load Isolation

Purpose: close the remaining gap in one-backend/many-room support.

Changes:

- `GameServer` now stores pending save and load votes by normalized `sessionId`, instead of using one global pending vote.
- Explicit `payload.sessionId` that does not map to an active room no longer silently falls back to the default controller.
- `SessionRegistry` can rebind all connections from the old room id to the loaded save's `sessionId` after `LOAD_GAME`.
- `GameController.pushCurrentState(...)` gives the server a public way to refresh the loaded room after rebinding.

Evidence:

- `mvn -q test -Dtest=GameServerSaveLoadIntegrationTest,GameServerMultiSessionIsolationTest` passed.
- New regression: two rooms can start independent `LOAD_GAME` votes; completing room A does not send `LOAD_GAME_RESULT` to room B or complete room B's pending vote.

Interpretation:

One JVM is now a better fit for batched data collection and future multi-room play. The collection runner already used isolated controllers; this fixes the normal WebSocket server's pending confirmation state to match that model.

## 2026-05-24 Player-Count Generalization Split

Purpose: add a stronger evaluation mode for paper-style experiments.

Changes:

- `training/scripts/distill_dataset.py --split-by player-count` now holds out configured player counts for validation.
- `--validation-player-counts` defaults to `4,5`, so the intended experiment is 2/3-player training and 4/5-player validation.
- `training/scripts/train_distilled_rankers.sh` exposes this via `MONOPOLY_TRAIN_SPLIT_BY=player-count` and `MONOPOLY_VALIDATION_PLAYER_COUNTS=4,5`.
- Metrics and quality reports now record the split type and held-out player counts.

Evidence:

```bash
python3 training/scripts/distill_dataset.py \
  training/data/distillation/local-baseline-20260524-080604.jsonl \
  --output-dir /tmp/player-count-split-smoke \
  --include-sources local_heuristic \
  --min-rows 1000 \
  --epochs 1 \
  --split-by player-count \
  --validation-player-counts 4,5 \
  --balance-by-kind
```

Result:

- Train decisions: 2322 from 2/3-player games.
- Validation decisions: 2452 from 4/5-player games.
- One-epoch MLP smoke validation top-1: `0.696`, MRR: `0.815`.
- Per-kind validation top-1: `PLAY_CARD=0.669`, `PAYMENT=0.839`, `JUST_SAY_NO=0.618`, `OVERFLOW_DISCARD=0.410`.

Interpretation:

This mode is not a production model run; it proves the evaluation split works. Use it after the first DeepSeek run to measure whether the distilled model learned general Monopoly Deal principles or only memorized table-size-specific patterns.

## 2026-05-24 Scaling Subset Tool

Purpose: make label-efficiency curves reproducible without paying the teacher multiple times.

Changes:

- Added `training/scripts/make_scaling_subsets.py`.
- Added `training/scripts/run_scaling_curve.sh`.
- Subsets are deterministic, cumulative, and stratified by `decisionKind,playerCount` by default.
- Each subset keeps the original JSONL row payload unchanged.

Evidence:

```bash
python3 training/scripts/make_scaling_subsets.py \
  training/data/distillation/local-baseline-20260524-080604.jsonl \
  --output-dir /tmp/monopoly-scaling-subsets \
  --sizes 100,500,1200 \
  --prefix local-baseline
```

Result:

- Source rows: 4774.
- Generated subsets: 100, 500, and 1200 rows.
- The 100-row subset covered all four decision kinds and all four player counts.
- The 500-row subset manifest passed `training/scripts/dataset_manifest.py`, with all decision kinds covered.
- Python tests: `python3 -m unittest tests.test_distill_dataset` passed.

Interpretation:

After a paid DeepSeek run, use this tool to produce 1k/5k/20k/100k cumulative subsets and train the scaling curve from one paid trace. This is both cheaper and more defensible than recollecting separate traces for each curve point.

## 2026-05-24 Gameplay Matrix Evaluation

Purpose: move from one-off gameplay checks to a reusable evaluation matrix across player counts and opponent strengths.

Changes:

- Added `training/scripts/evaluate_gameplay_matrix.sh`.
- Added `training/scripts/summarize_gameplay_matrix.py`.
- Training handoff packages now include matrix evaluation and scaling-curve scripts.

Smoke command:

```bash
MONOPOLY_MATRIX_GAMES=1 \
MONOPOLY_MATRIX_SNAPSHOTS=80 \
MONOPOLY_MATRIX_PLAYERS=2,3 \
MONOPOLY_MATRIX_OPPONENTS=easy,hard \
MONOPOLY_MATRIX_SEATS=1 \
training/scripts/evaluate_gameplay_matrix.sh \
  backend/models/distillation/local-baseline-20260524-080604-balanced-mlp/candidate_ranker_mlp.json \
  /tmp/monopoly-gameplay-matrix-smoke
```

Evidence:

- Generated 4 condition JSON files and `/tmp/monopoly-gameplay-matrix-smoke/summary.md`.
- Summary grouped results by opponent and player count.
- Smoke used 80-snapshot games; all 4 hit the snapshot limit, which is expected for this tiny runtime check.

Interpretation:

This is the evaluation harness to use after paid DeepSeek training. Reportable runs should use at least 50 games per condition for cheap checkpoints and 200+ games per condition for stronger claims.

## 2026-05-24 Forest-Style Offline Baseline

Purpose: add a non-neural model-family comparator without requiring sklearn.

Changes:

- `training/scripts/distill_dataset.py --model-type forest` trains a small bagged linear ensemble over random feature subsets.
- `training/scripts/train_distilled_rankers.sh` trains forest metrics by default; set `MONOPOLY_TRAIN_FOREST=false` to skip it.
- Artifact packaging includes `<output-prefix>-forest/metrics.json` when present.

Smoke command:

```bash
python3 training/scripts/distill_dataset.py \
  training/data/distillation/local-baseline-20260524-080604.jsonl \
  --output-dir /tmp/forest-smoke-stable3 \
  --include-sources local_heuristic \
  --min-rows 1000 \
  --model-type forest \
  --forest-trees 4 \
  --forest-features-per-tree 16 \
  --balance-by-kind
```

Result:

- Validation top-1: `0.491`.
- Validation MRR: `0.664`.
- Per-kind validation top-1: `PLAY_CARD=0.436`, `PAYMENT=0.731`, `JUST_SAY_NO=0.457`, `OVERFLOW_DISCARD=0.529`.
- No sklearn dependency.

Interpretation:

This is not intended for Java runtime play. It gives the report a traditional-model comparator beyond linear and KNN, which helps diagnose whether gains come from neural capacity or from feature/label quality.

## 2026-05-24 Trace Audit

Purpose: fail fast after the first paid DeepSeek probe if the trace is not worth scaling.

Changes:

- Added `training/scripts/audit_distillation_trace.py`.
- `training/scripts/run_paid_probe.sh` writes `<output-prefix>-trace_audit.json` and requires token usage metadata.
- `training/scripts/overnight_distillation_run.sh` writes `<output-prefix>-trace_audit.json`.
- Paid preflight now compiles the audit script.

Checks:

- minimum row count
- preferred teacher-source ratio
- all decision kinds covered
- rare decision kind row counts
- valid `choiceId`
- duplicate `decisionId`
- first-choice bias
- token usage metadata

Smoke command:

```bash
python3 training/scripts/audit_distillation_trace.py \
  training/data/distillation/local-baseline-20260524-080604.jsonl \
  --preferred-source local_heuristic \
  --min-rows 1000 \
  --min-rare-kind-rows 1 \
  --output /tmp/local-baseline-trace-audit.json
```

Result:

- Audit passed on the local baseline.
- Warning: no token usage metadata, expected for local heuristic labels.
- First-choice ratio: `34.3%`, below the default bias gate.

Interpretation:

For a paid DeepSeek probe, treat trace-audit failure as a stop signal. Fix prompt parsing, source ratio, token usage, or collection quotas before spending on overnight-scale labels.

## 2026-05-24 Trace Merge Gate

Purpose: support multiple probes, overnight runs, machines, or accounts without training on duplicate or contradictory labels.

Changes:

- Added `training/scripts/merge_distillation_traces.py`.
- Paid preflight now compiles the merge script.
- Training handoff packages include the merge script.

Behavior:

- Preserves raw JSONL rows in first-seen order.
- Filters teacher source with `--include-sources`, for example `deepseek`.
- Deduplicates by `request.decisionId`.
- Skips canonical JSON-equivalent duplicate decisions.
- Fails by default when the same `decisionId` has different JSON content.
- Writes a merge report with source/kind/player-count/session coverage and output SHA-256.

Smoke command:

```bash
python3 training/scripts/merge_distillation_traces.py \
  training/data/distillation/local-baseline-20260524-080604.jsonl \
  training/data/distillation/local-baseline-20260524-080604.jsonl \
  --output /tmp/local-baseline-merged.jsonl \
  --report /tmp/local-baseline-merged-report.json
```

Interpretation:

Run merge before audit and training whenever there is more than one trace file. Do not override conflicts during paid-data training unless the exact source of the disagreement has been inspected.

## 2026-05-24 Training Readiness Gate

Purpose: make the final handoff status explicit instead of relying on scattered artifacts.

Changes:

- Added `training/scripts/check_training_readiness.py`.
- `training/scripts/train_distilled_rankers.sh` now writes `<output-prefix>-trace_audit.json` before `<output-prefix>-readiness.json`.
- Artifact and training handoff packages now include readiness reports and carry trace audits when available.
- Paid preflight compiles the readiness script.

Gate semantics:

- `--mode local` accepts local heuristic labels and verifies trace coverage, manifest, quality report, metrics, Java JSON model, and gameplay evaluation.
- `--mode production` additionally requires 95%+ `deepseek` rows, token usage metadata, production quality report status, trace audit success, and completed gameplay evaluation.
- Gameplay readiness now checks `evaluatedGames` or the `games[]` length, not natural-win `completedGames`. Use `--require-natural-gameplay` or `MONOPOLY_READINESS_REQUIRE_NATURAL_GAMEPLAY=true` when natural finishes should be mandatory.

Local baseline check:

```bash
python3 training/scripts/check_training_readiness.py \
  training/data/distillation/local-baseline-20260524-080604.jsonl \
  backend/models/distillation/local-baseline-20260524-080604-balanced \
  --mode local \
  --min-rows 1000 \
  --min-rare-kind-rows 1 \
  --min-gameplay-games 6 \
  --output backend/models/distillation/local-baseline-20260524-080604-balanced-readiness.json
```

Result:

- Local readiness passed: 4774 rows, all four decision kinds, MLP validation top-1 `0.830`, completed 6 gameplay games.
- Production readiness on the same trace failed as intended: `deepseek_source_ratio=0.0%`, no token usage, no production trace audit.
- A follow-up smoke run with `MONOPOLY_TRAIN_FOREST=false`, 1 epoch, and 1 gameplay evaluation passed end-to-end, proving the train script now writes trace audit before readiness even when the Java evaluation hits the snapshot limit. It also fixed Bash 3.2 empty-array handling in the training and DeepSeek collection wrappers.

Interpretation:

Use readiness as the last check before saying a dataset/model package is deliverable. Local readiness proves the Mac pipeline works; production readiness proves the requested DeepSeek-quality training dataset is actually present.

## 2026-05-24 Guarded DeepSeek Production Wrapper

Purpose: reduce the chance of accidentally spending on a large paid collection before inspecting the probe.

Changes:

- Added `training/scripts/run_deepseek_production_pipeline.sh`.
- The wrapper requires a DeepSeek key and starts with `training/scripts/run_paid_probe.sh`.
- Default mode stops after the probe and prints the exact resume command.
- Large collection requires `MONOPOLY_PAID_CONFIRM=run-paid-overnight`.
- Confirmed mode collects the main trace, merges probe and main DeepSeek rows, audits the merged trace, trains students, runs production readiness, and writes artifact plus Windows handoff archives.
- Paid preflight and Windows handoff packages now include the wrapper.

Validation:

- Missing-key guard exits before collection.
- Shell syntax is covered by `bash -n training/scripts/*.sh`.

Interpretation:

This is the command to use when the API key is available and the goal is a production-ready package. It is intentionally conservative: a run is not deliverable unless `<output-prefix>-readiness.json` has `"mode": "production"` and `"ready": true`.

## 2026-05-24 Training Run Summary

Purpose: make a completed run easy to inspect without opening every JSON artifact by hand.

Changes:

- Added `training/scripts/summarize_training_run.py`.
- `training/scripts/train_distilled_rankers.sh` now writes `<output-prefix>-run_summary.md` and `<output-prefix>-run_summary.json` after readiness passes.
- Artifact packages and Windows/5090 handoff packages include the run summary.
- Paid preflight compiles the summary script.

Summary contents:

- readiness status and failed checks
- trace audit status and failed checks
- row/session/source/kind/player-count coverage
- validation top-1, MRR, first-candidate baseline, and random baseline
- model type, device, input dimension, epochs, split, and kind balancing
- gameplay requested/evaluated/natural games, board-rank metrics, and end reasons
- token usage and artifact paths

Interpretation:

Read `<output-prefix>-run_summary.md` first after any overnight or production run. For local heuristic data it can be `local-ready`, but production claims still require `production-ready`.

## 2026-05-24 Seed Replicate Utilities

Purpose: make paper-style claims less dependent on one random train/validation split or one neural initialization.

Changes:

- `training/scripts/train_distilled_rankers.sh` now accepts `MONOPOLY_TRAIN_SEED` and passes it through to MLP, linear, KNN, and forest training.
- Added `training/scripts/run_seed_replicates.sh` to run the standard training pipeline across comma-separated `MONOPOLY_SEEDS`.
- Added `training/scripts/summarize_seed_runs.py` to aggregate run summaries into `<output-prefix>-seed_summary.md` and `.json`.
- Paid preflight and Windows/5090 handoff packages include the seed scripts.

Example:

```bash
MONOPOLY_SEEDS=11,42,73 \
MONOPOLY_TRAIN_SOURCES=deepseek \
MONOPOLY_MIN_TRAIN_ROWS=5000 \
training/scripts/run_seed_replicates.sh \
  training/data/distillation/deepseek-run1-merged.jsonl \
  backend/models/distillation/deepseek-run1
```

Interpretation:

Use the seed summary for paper/report tables when the trace is production-ready. If time only allows one seed, mark the result as a single-seed checkpoint rather than a robust comparison.

## 2026-05-24 Local Multi-Seed Baseline On Mac

Purpose: produce a real Mac-side training/evaluation baseline while no DeepSeek API key is configured.

Command:

```bash
MONOPOLY_SEEDS=11,42,73 \
MONOPOLY_TRAIN_SOURCES=local_heuristic \
MONOPOLY_MIN_TRAIN_ROWS=1000 \
MONOPOLY_TRAIN_EPOCHS=6 \
MONOPOLY_TRAIN_BATCH_SIZE=256 \
MONOPOLY_TRAIN_FOREST=false \
MONOPOLY_EVAL_GAMES=4 \
MONOPOLY_EVAL_PLAYERS=3 \
MONOPOLY_EVAL_SNAPSHOTS=260 \
training/scripts/run_seed_replicates.sh \
  training/data/distillation/local-baseline-20260524-080604.jsonl \
  backend/models/distillation/local-baseline-20260524-080604-multiseed
```

Dataset:

- Trace: `training/data/distillation/local-baseline-20260524-080604.jsonl`
- Rows: 4774
- Sessions: 60
- Teacher source: `local_heuristic`
- Decision kinds: `PLAY_CARD` 3690, `PAYMENT` 840, `JUST_SAY_NO` 178, `OVERFLOW_DISCARD` 66
- Player counts: 2/3/4/5 all present

Artifacts:

- `backend/models/distillation/local-baseline-20260524-080604-multiseed-seed_summary.md`
- `backend/models/distillation/local-baseline-20260524-080604-multiseed-seed_summary.json`
- `backend/models/distillation/local-baseline-20260524-080604-multiseed-gameplay_matrix.md`
- `backend/models/distillation/local-baseline-20260524-080604-multiseed-gameplay_matrix.json`
- Per-seed prefixes: `backend/models/distillation/local-baseline-20260524-080604-multiseed-seed11`, `seed42`, `seed73`

Result:

- All 3 seed runs passed local readiness.
- Production-ready runs: 0, because the trace is not DeepSeek-labeled and has no token usage metadata.
- MLP validation top-1 mean/std: 0.810 / 0.016.
- MLP validation MRR mean/std: 0.892 / 0.009.
- First-candidate baseline mean: 0.332.
- Random expected baseline mean: 0.218.
- Gameplay vs hard, 3-player, ranker seat 1: 12 games requested, 9 natural completions, ranker win rate 0.250, average ranker board rank 1.83, board lead rate 0.333.

Interpretation:

The Mac can train the current 119-feature MLP and export Java-loadable models. The local teacher is learnable in imitation metrics, but gameplay strength remains mixed and should not be presented as a DeepSeek-quality or robust strategic result. The next production step is still to provide a DeepSeek key, run the guarded production wrapper, then repeat the multi-seed summary on the merged DeepSeek trace.

## 2026-05-24 Offline Trace Relabel Tool

Purpose: reuse stored backend-legal candidate states when a DeepSeek key becomes available, so a paid probe can start without rerunning simulations.

Changes:

- Added `com.monopoly.tools.TraceRelabeler`.
- Added `training/scripts/relabel_distillation_trace.sh`.
- Added `training/scripts/run_relabel_paid_probe.sh` for the guarded paid-probe path from an existing trace.
- Paid preflight and Windows/5090 handoff packages include the relabel scripts.
- The DeepSeek mode refuses to start without a key and requires every output row to have `result.metadata.source=deepseek`; local fallback rows are treated as a failed relabel run.

Example:

```bash
DEEPSEEK_API_KEY=... \
training/scripts/run_relabel_paid_probe.sh \
  training/data/distillation/local-enhanced-20260524.jsonl \
  training/data/distillation/deepseek-relabel-probe.jsonl \
  backend/models/distillation/deepseek-relabel-probe
```

Lower-level equivalent:

```bash
DEEPSEEK_API_KEY=... \
MONOPOLY_RELABEL_SELECT=true \
MONOPOLY_RELABEL_MAX_BY_KIND=PLAY_CARD:250,PAYMENT:120,JUST_SAY_NO:80,OVERFLOW_DISCARD:50 \
training/scripts/relabel_distillation_trace.sh \
  training/data/distillation/local-enhanced-20260524.jsonl \
  training/data/distillation/deepseek-relabel-probe.jsonl \
  backend/models/distillation/deepseek-relabel-probe
```

No-cost smoke:

```bash
MONOPOLY_RELABEL_TEACHER=heuristic \
MONOPOLY_RELABEL_SELECT=true \
MONOPOLY_RELABEL_MAX_BY_KIND=PLAY_CARD:5,PAYMENT:5,JUST_SAY_NO:5,OVERFLOW_DISCARD:5 \
training/scripts/relabel_distillation_trace.sh \
  training/data/distillation/local-enhanced-20260524.jsonl \
  /tmp/monopoly-relabel-smoke.jsonl \
  /tmp/monopoly-relabel-smoke
```

Interpretation:

This is a collection accelerator, not a shortcut around the production gate. Use the enhanced local trace when it is available because it has better row count and rare-kind coverage than the older first baseline. The relabeled trace must still pass DeepSeek source ratio, token-usage, row-count, rare-kind coverage, training readiness, and gameplay evaluation before it can be called production data.

Selector smoke evidence:

- Command: `MONOPOLY_RELABEL_SELECT=true MONOPOLY_RELABEL_TEACHER=heuristic MONOPOLY_RELABEL_MAX_BY_KIND=PLAY_CARD:5,PAYMENT:5,JUST_SAY_NO:5,OVERFLOW_DISCARD:5 training/scripts/relabel_distillation_trace.sh training/data/distillation/local-baseline-20260524-080604.jsonl /tmp/monopoly-selected-relabel-smoke.jsonl /tmp/monopoly-selected-relabel-smoke`
- Selection report: `/tmp/monopoly-selected-relabel-smoke-selection_report.json`
- Rows selected: 20
- Decision mix: `PLAY_CARD` 5, `PAYMENT` 5, `JUST_SAY_NO` 5, `OVERFLOW_DISCARD` 5
- Player counts selected: 2-player 4, 3-player 5, 4-player 6, 5-player 5
- Sessions selected: 18
- Average legal candidates: 23.15

## 2026-05-24 Enhanced Local Baseline On Mac

Purpose: improve the no-cost local handoff while no DeepSeek API key is configured, especially rare `OVERFLOW_DISCARD` coverage.

Supplement collection command:

```bash
mvn -q compile exec:java \
  -Dexec.mainClass=com.monopoly.tools.SimulationBatchRunner \
  -Dmonopoly.deepseek.enabled=false \
  -Dmonopoly.ai.decisionDelayMs=0 \
  -Dmonopoly.simulation.teacher=heuristic \
  -Dmonopoly.simulation.games=80 \
  -Dmonopoly.simulation.parallel=8 \
  -Dmonopoly.simulation.playerCounts=2,3,4,5 \
  -Dmonopoly.simulation.maxSnapshots=180 \
  -Dmonopoly.simulation.batchSize=32 \
  -Dmonopoly.simulation.batchWaitMs=40 \
  -Dmonopoly.simulation.runtimeSeconds=25 \
  -Dmonopoly.simulation.maxByKind=PLAY_CARD:800,PAYMENT:500,JUST_SAY_NO:200,OVERFLOW_DISCARD:120 \
  -Dmonopoly.simulation.traceMode=fail_if_exists \
  -Dmonopoly.simulation.tracePath=training/data/distillation/local-supplement-20260524-overflow.jsonl
```

Merge and training commands:

```bash
python3 training/scripts/merge_distillation_traces.py \
  training/data/distillation/local-baseline-20260524-080604.jsonl \
  training/data/distillation/local-supplement-20260524-overflow.jsonl \
  --include-sources local_heuristic \
  --output training/data/distillation/local-enhanced-20260524.jsonl \
  --report backend/models/distillation/local-enhanced-20260524-trace_merge.json

MONOPOLY_TRAIN_SOURCES=local_heuristic \
MONOPOLY_MIN_TRAIN_ROWS=5000 \
MONOPOLY_TRAIN_EPOCHS=6 \
MONOPOLY_TRAIN_BATCH_SIZE=256 \
MONOPOLY_TRAIN_FOREST=false \
MONOPOLY_EVAL_GAMES=4 \
MONOPOLY_EVAL_PLAYERS=3 \
MONOPOLY_EVAL_SNAPSHOTS=260 \
MONOPOLY_EVAL_OPPONENT_STRATEGY=hard \
training/scripts/train_distilled_rankers.sh \
  training/data/distillation/local-enhanced-20260524.jsonl \
  backend/models/distillation/local-enhanced-20260524
```

Dataset:

- Trace: `training/data/distillation/local-enhanced-20260524.jsonl`
- Rows: 6372
- Sessions: 134
- Teacher source: `local_heuristic`
- Decision kinds: `PLAY_CARD` 4490, `PAYMENT` 1340, `JUST_SAY_NO` 378, `OVERFLOW_DISCARD` 164
- Player counts: 2-player 1417, 3-player 1670, 4-player 1628, 5-player 1657
- Merge report: `backend/models/distillation/local-enhanced-20260524-trace_merge.json`
- Trace audit: `backend/models/distillation/local-enhanced-20260524-trace_audit.json`

Result:

- Status: `local-ready`
- Production-ready: no, because every row is local heuristic and no token usage metadata is present.
- Single-run checkpoint: MLP validation top-1 0.815, MRR 0.896.
- Single-run per-kind validation top-1: `PLAY_CARD=0.804`, `PAYMENT=0.927`, `JUST_SAY_NO=0.609`, `OVERFLOW_DISCARD=0.792`.
- Single-run gameplay vs hard: 4 games requested/evaluated, 4 natural completions, ranker win rate 0.500, average ranker board rank 1.75, board lead rate 0.500.
- Artifact archive: `backend/models/distillation/local-enhanced-20260524-artifacts.tar.gz`
- Windows handoff archive: `backend/models/distillation/local-enhanced-20260524-training-handoff.tar.gz`

Interpretation:

This is now the preferred local baseline and the best current relabel source. It is good enough to prove collection, merge, audit, training, Java-loadable MLP export, gameplay evaluation, and handoff packaging on the Mac. It is still not DeepSeek-quality data and should not be used for production or paper strength claims.

## 2026-05-24 Enhanced Local Multi-Seed Baseline On Mac

Purpose: turn the enhanced local checkpoint into a more defensible Mac-side baseline by training three seeds and evaluating each Java-loadable MLP against the hard heuristic opponent.

Command:

```bash
MONOPOLY_SEEDS=11,42,73 \
MONOPOLY_TRAIN_SOURCES=local_heuristic \
MONOPOLY_MIN_TRAIN_ROWS=5000 \
MONOPOLY_TRAIN_EPOCHS=6 \
MONOPOLY_TRAIN_BATCH_SIZE=256 \
MONOPOLY_TRAIN_FOREST=false \
MONOPOLY_EVAL_GAMES=6 \
MONOPOLY_EVAL_PLAYERS=3 \
MONOPOLY_EVAL_SNAPSHOTS=300 \
MONOPOLY_EVAL_OPPONENT_STRATEGY=hard \
training/scripts/run_seed_replicates.sh \
  training/data/distillation/local-enhanced-20260524.jsonl \
  backend/models/distillation/local-enhanced-20260524-multiseed
```

Artifacts:

- `backend/models/distillation/local-enhanced-20260524-multiseed-seed_summary.md`
- `backend/models/distillation/local-enhanced-20260524-multiseed-seed_summary.json`
- Per-seed prefixes: `backend/models/distillation/local-enhanced-20260524-multiseed-seed11`, `seed42`, `seed73`
- Representative Java-loadable MLP: `backend/models/distillation/local-enhanced-20260524-multiseed-seed73-mlp/candidate_ranker_mlp.json`

Result:

- Runs: 3
- Ready runs: 3
- Production-ready runs: 0
- Rows per run: 6372
- MLP validation top-1 mean/std: 0.814 / 0.005
- MLP validation MRR mean/std: 0.894 / 0.004
- First-candidate baseline mean/std: 0.378 / 0.018
- Random expected baseline mean/std: 0.220 / 0.009
- Gameplay vs hard: 18 games requested/evaluated, 16 natural completions, 2 snapshot-limit games.
- Ranker win rate mean/std: 0.167 / 0.000
- Average ranker board rank mean/std: 1.889 / 0.079
- Board lead rate mean/std: 0.222 / 0.079

Interpretation:

The enhanced local trace is stable for imitation learning, and Mac MPS training is reproducible enough for iteration. The hard-opponent gameplay result is still weak, so this should be presented as a pipeline and local-teacher baseline rather than a strong AI. The next quality jump requires DeepSeek labels or a stronger teacher/self-play loop, then the same multi-seed summary should be rerun on the production trace.

## 2026-05-24 Local Gameplay Matrix Pilot

Purpose: verify the gameplay-matrix evaluator across player counts and opponent strengths, and expose where the current local-teacher student is weak.

Command:

```bash
MONOPOLY_MATRIX_GAMES=2 \
MONOPOLY_MATRIX_SNAPSHOTS=260 \
MONOPOLY_MATRIX_PLAYERS=2,3,4 \
MONOPOLY_MATRIX_OPPONENTS=easy,normal,hard \
MONOPOLY_MATRIX_SEATS=1 \
training/scripts/evaluate_gameplay_matrix.sh \
  backend/models/distillation/local-enhanced-20260524-multiseed-seed73-mlp/candidate_ranker_mlp.json \
  backend/models/distillation/local-enhanced-20260524-multiseed-gameplay-matrix-smoke
```

Artifacts:

- `backend/models/distillation/local-enhanced-20260524-multiseed-gameplay-matrix-smoke/summary.md`
- `backend/models/distillation/local-enhanced-20260524-multiseed-gameplay-matrix-smoke/summary.json`
- Per-condition JSON files under `backend/models/distillation/local-enhanced-20260524-multiseed-gameplay-matrix-smoke/`

Result:

- Conditions: 9
- Games requested: 18
- Natural completions: 14
- End reasons: `NATURAL_OR_LIMIT=14`, `LOCAL_RANKER_EVAL_SNAPSHOT_LIMIT=4`
- Ranker win rate: 0.278
- Average ranker board rank: 1.89
- Board lead rate: 0.389
- By opponent: easy win rate 0.333, normal 0.333, hard 0.167
- By player count: 2-player win rate 0.667, 3-player 0.000, 4-player 0.167

Interpretation:

The evaluator now produces clean JSON with `MONOPOLY_EVAL_QUIET=true`, and the matrix summary is usable for future reports. The result is not reportable strength evidence because it uses only 2 games per cell and the local heuristic teacher is not strategic enough. It does show that 3/4-player behavior is the current weak point to target with DeepSeek labels, stronger teacher data, or player-count-specific ablations.

## 2026-05-24 Consolidated Next-Day Handoff

Purpose: make the current Mac deliverable easy to inspect tomorrow without hunting through separate manifest, seed, matrix, readiness, and archive files.

Command:

```bash
python3 training/scripts/summarize_ai_handoff.py

training/scripts/package_training_handoff.sh \
  training/data/distillation/local-enhanced-20260524.jsonl \
  backend/models/distillation/local-enhanced-20260524-multiseed \
  backend/models/distillation/local-enhanced-20260524-multiseed-training-handoff.tar.gz

MONOPOLY_PACKAGE_AGG_PREFIX=backend/models/distillation/local-enhanced-20260524-multiseed \
MONOPOLY_PACKAGE_GAMEPLAY=backend/models/distillation/local-enhanced-20260524-multiseed-seed73-mlp/gameplay_vs_hard.json \
training/scripts/package_distillation_artifacts.sh \
  backend/models/distillation/local-enhanced-20260524-multiseed-seed73 \
  backend/models/distillation/local-enhanced-20260524-multiseed-artifacts.tar.gz
```

Artifacts:

- `backend/models/distillation/local-enhanced-20260524-multiseed-quality_gate.md`
- `backend/models/distillation/local-enhanced-20260524-multiseed-quality_gate.json`
- `backend/models/distillation/local-enhanced-20260524-multiseed-handoff_report.md`
- `backend/models/distillation/local-enhanced-20260524-multiseed-handoff_report.json`
- `backend/models/distillation/local-enhanced-20260524-multiseed-artifacts.tar.gz`
- `backend/models/distillation/local-enhanced-20260524-multiseed-training-handoff.tar.gz`

Result:

- Quality gate verdict: `local-training-ready`.
- Report verdict: `local-ready-only`.
- DeepSeek key present: false.
- Local rows: 6372.
- Local sessions: 134.
- Local-ready runs: 3.
- Production-ready runs: 0.
- Production data ready: false, because the trace has `local_heuristic` rows only and no token usage metadata.
- Paper evidence ready: false, because the gameplay matrix is a 2-game-per-cell smoke and lacks 5-player cells.
- Gameplay matrix smoke: 9 conditions, 18 games requested, 14 natural completions, ranker win rate 0.278.
- The regenerated archives include the handoff report, gameplay matrix summary, seed summaries, representative Java-loadable MLP model, scripts, and research/training docs.

Interpretation:

This is the clean handoff state. The team can train and evaluate locally from the package, and can move to the Windows 5090 only after a DeepSeek trace exists. The report deliberately keeps the main blocker visible: no production DeepSeek-labeled dataset exists in this checkout yet.

## 2026-05-24 Strategic Local Teacher And Probe Baseline

Purpose: provide one better no-key teacher path before freezing the local handoff, without changing the meaning of the existing `local_heuristic` baseline.

Changes:

- Added `StrategicHeuristicDecisionTeacher` with source `strategic_heuristic`.
- `SimulationBatchRunner` accepts `-Dmonopoly.simulation.teacher=strategic_heuristic`.
- `TraceRelabeler` and `training/scripts/relabel_distillation_trace.sh` accept `MONOPOLY_RELABEL_TEACHER=strategic_heuristic`.
- The strategic teacher still selects only Java-generated legal candidates.
- It uses board context for opponent complete-set pressure, self complete sets, bank state, high-charge Just Say No, and payment/discard preservation.

Smoke command for no-key relabeling:

```bash
MONOPOLY_RELABEL_TEACHER=strategic_heuristic \
MONOPOLY_RELABEL_SELECT=true \
MONOPOLY_RELABEL_MAX_BY_KIND=PLAY_CARD:500,PAYMENT:200,JUST_SAY_NO:100,OVERFLOW_DISCARD:100 \
training/scripts/relabel_distillation_trace.sh \
  training/data/distillation/local-enhanced-20260524.jsonl \
  training/data/distillation/local-enhanced-20260524-strategic-probe.jsonl \
  backend/models/distillation/local-enhanced-20260524-strategic-probe
```

Interpretation:

This is a stronger local baseline and useful fallback if the DeepSeek key is unavailable. It is not a substitute for DeepSeek production data, and quality gates should still report production readiness as false until DeepSeek rows and token usage exist.

Probe command actually run for this handoff:

```bash
MONOPOLY_RELABEL_TEACHER=strategic_heuristic \
MONOPOLY_RELABEL_SELECT=true \
MONOPOLY_RELABEL_MAX_BY_KIND=PLAY_CARD:1200,PAYMENT:500,JUST_SAY_NO:250,OVERFLOW_DISCARD:120 \
MONOPOLY_RELABEL_MIN_SELECTED_ROWS=1500 \
MONOPOLY_TRACE_MODE=overwrite \
MONOPOLY_SELECTION_TRACE_MODE=overwrite \
training/scripts/relabel_distillation_trace.sh \
  training/data/distillation/local-enhanced-20260524.jsonl \
  training/data/distillation/local-enhanced-20260524-strategic-probe.jsonl \
  backend/models/distillation/local-enhanced-20260524-strategic-probe
```

Training command:

```bash
MONOPOLY_TRAIN_SOURCES=strategic_heuristic \
MONOPOLY_MIN_TRAIN_ROWS=1500 \
MONOPOLY_TRAIN_EPOCHS=4 \
MONOPOLY_TRAIN_BATCH_SIZE=256 \
MONOPOLY_TRAIN_FOREST=false \
MONOPOLY_EVAL_GAMES=3 \
MONOPOLY_EVAL_PLAYERS=3 \
MONOPOLY_EVAL_SNAPSHOTS=220 \
MONOPOLY_EVAL_OPPONENT_STRATEGY=hard \
training/scripts/train_distilled_rankers.sh \
  training/data/distillation/local-enhanced-20260524-strategic-probe.jsonl \
  backend/models/distillation/local-enhanced-20260524-strategic-probe
```

Artifacts:

- `training/data/distillation/local-enhanced-20260524-strategic-probe.jsonl`
- `backend/models/distillation/local-enhanced-20260524-strategic-probe-run_summary.md`
- `backend/models/distillation/local-enhanced-20260524-strategic-probe-readiness.json`
- `backend/models/distillation/local-enhanced-20260524-strategic-probe-mlp/candidate_ranker_mlp.json`

Result:

- Rows: 2070
- Sessions: 134
- Teacher source: `strategic_heuristic`
- Decision mix: `PLAY_CARD=1200`, `PAYMENT=500`, `JUST_SAY_NO=250`, `OVERFLOW_DISCARD=120`
- Player-count mix: 2-player 515, 3-player 518, 4-player 518, 5-player 519
- Status: `local-ready`
- MLP validation top-1: 0.688
- MLP validation MRR: 0.810
- First-candidate baseline: 0.409
- Random expected baseline: 0.130
- Per-kind validation top-1: `PLAY_CARD=0.620`, `PAYMENT=0.939`, `JUST_SAY_NO=0.569`, `OVERFLOW_DISCARD=0.667`
- Gameplay vs hard: 3 games requested/evaluated, 1 natural completion, ranker win rate 0.333, average ranker board rank 1.67, board lead rate 0.667

Interpretation:

The strategic probe is a real trainable no-key dataset and model, not just a code path. It gives a second local baseline with more balanced rare-decision coverage and a Java-loadable MLP. The validation score is lower than the larger `local_heuristic` dataset because this probe has fewer rows and a more diverse candidate distribution; it still beats first-candidate and random baselines and passes local readiness. It remains non-production because the labels are local and have no token usage metadata.

## 2026-05-24 DeepSeek Production Relabel

Purpose: replace the local-teacher-only handoff with a real DeepSeek-labeled production trace while keeping all DeepSeek outputs in a separate directory from the local heuristic baselines.

Primary paths:

- Trace: `training/data/distillation/deepseek-production-20260524/full-relabel-6372.jsonl`
- Prefix: `backend/models/distillation/deepseek-production-20260524/full-relabel-6372`
- Problem row quarantine: `backend/models/distillation/deepseek-production-20260524/full-relabel-6372-problem-ids.jsonl`
- Artifacts archive: `backend/models/distillation/deepseek-production-20260524/full-relabel-6372-artifacts.tar.gz`
- Handoff archive: `backend/models/distillation/deepseek-production-20260524/full-relabel-6372-training-handoff.tar.gz`

Run shape:

- Used DeepSeek as the teacher through `TraceRelabeler`.
- Used strict DeepSeek mode so fallback rows are rejected instead of mixed into production data.
- Reused the existing backend-legal local candidate envelope only as unlabeled input.
- Skipped one row where DeepSeek did not return a valid `decisionId`; it was quarantined and not added to the production trace.

Result:

- Rows: 6371
- Sessions: 134
- Teacher source: `deepseek`
- Decision mix: `PLAY_CARD=4489`, `PAYMENT=1340`, `JUST_SAY_NO=378`, `OVERFLOW_DISCARD=164`
- Player-count mix: 2-player 1417, 3-player 1670, 4-player 1627, 5-player 1657
- Token usage rows: 6371 / 6371
- Trace audit: passed
- Production readiness: passed
- Estimated cost with recorded price inputs: 1.124942

Best student:

- Model: `backend/models/distillation/deepseek-production-20260524/full-relabel-6372-mlp/candidate_ranker_mlp.json`
- Validation top-1: 0.722
- Validation MRR: 0.830
- First-candidate baseline: 0.387
- Random expected baseline: 0.223
- Per-kind validation top-1: `PLAY_CARD=0.676`, `PAYMENT=0.947`, `JUST_SAY_NO=0.586`, `OVERFLOW_DISCARD=0.667`
- Gameplay vs hard: 20 games requested/evaluated, 20 natural completions, ranker win rate 0.300, average ranker board rank 2.05, board lead rate 0.300

Interpretation:

This is the first production-ready DeepSeek-only dataset and model in this checkout. It is appropriate to describe as DeepSeek policy distillation over Java-generated legal candidate actions. It is still not paper-grade gameplay evidence: the fixed-seat gameplay run is a 20-game smoke against hard opponents, not a full multi-player/opponent matrix with large cell counts.

## 2026-05-24 DeepSeek Direct-Sim Supplement And Merged Production Model

Purpose: add fresh DeepSeek decisions from direct simulated game states, keep them separate from local heuristic data, then merge them with the full DeepSeek relabel trace for the final current production student.

Primary paths:

- Relabel trace: `training/data/distillation/deepseek-production-20260524/full-relabel-6372.jsonl`
- Direct-sim supplement: `training/data/distillation/deepseek-production-20260524/direct-sim-5000.jsonl`
- Merged trace: `training/data/distillation/deepseek-production-20260524/merged-full-plus-direct-8940.jsonl`
- Prefix: `backend/models/distillation/deepseek-production-20260524/merged-full-plus-direct-8940`
- Model: `backend/models/distillation/deepseek-production-20260524/merged-full-plus-direct-8940-mlp/candidate_ranker_mlp.json`
- Handoff report: `backend/models/distillation/deepseek-production-20260524/merged-full-plus-direct-8940-handoff_report.md`

Run shape:

- The supplement used `DeepSeekBatchDecisionTeacher` in strict mode.
- The long direct simulation was stopped after enough supplemental rows had landed, because each batch waited on game runtime limits.
- No local fallback rows were accepted into the supplement or merged production trace.
- The merge used `training/scripts/merge_distillation_traces.py --include-sources deepseek`.
- The final student used `MONOPOLY_TRAIN_SOURCES=deepseek`, 40 epochs, batch size 512, seed 42.

Result:

- Rows: 8940
- Sessions: 214
- Teacher source: `deepseek`
- Decision mix: `PLAY_CARD=6519`, `PAYMENT=1747`, `JUST_SAY_NO=501`, `OVERFLOW_DISCARD=173`
- Player-count mix: 2-player 2049, 3-player 2320, 4-player 2273, 5-player 2298
- Token usage rows: 8940 / 8940
- Trace audit: passed
- Production readiness: passed
- Quality gate: `production-training-ready`
- Estimated cost with recorded price inputs: 1.498805

Best student:

- Model: `backend/models/distillation/deepseek-production-20260524/merged-full-plus-direct-8940-mlp/candidate_ranker_mlp.json`
- Validation top-1: 0.733
- Validation MRR: 0.841
- First-candidate baseline: 0.398
- Random expected baseline: 0.212
- Per-kind validation top-1: `PLAY_CARD=0.685`, `PAYMENT=0.931`, `JUST_SAY_NO=0.625`, `OVERFLOW_DISCARD=0.725`
- Gameplay vs hard: 20 games requested/evaluated, 20 natural completions, ranker win rate 0.300, average ranker board rank 2.10, board lead rate 0.300

Interpretation:

This merged model is the current DeepSeek production handoff. It improves imitation metrics over the relabel-only model while preserving the no-fallback production boundary. It is still not paper-grade gameplay evidence: the next step is a 2/3/4/5-player by easy/normal/hard gameplay matrix with at least 50 games per cell, followed by multi-seed and scaling-curve evidence.
