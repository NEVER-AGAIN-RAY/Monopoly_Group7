# Research Experiment Plan

## Thesis

Monopoly Deal can be treated as a complex rule-based imperfect-information card game where a symbolic engine guarantees legal states and legal actions, an expensive teacher labels decisions, and a small local ranker imitates the teacher under strict action constraints.

The practical question is not whether a small model can generate valid game text. It never generates actions. The question is whether a small model can rank backend-legal candidates well enough to become a cheap offline opponent.

## Research Questions

1. How many teacher-labeled decisions are needed before imitation metrics become stable?
2. Does batch LLM labeling reduce cost enough to make distillation practical?
3. Which decision types matter most for gameplay strength: play-card decisions, Just Say No, payments, or overflow discards?
4. Does a student trained on 2/3-player games generalize to 4/5-player games?
5. Does offline imitation accuracy correlate with gameplay win rate?

## Experimental Ladder

### Stage 0: Pipeline Validity

- Teacher: `local_heuristic`
- Rows: 1k-5k
- Goal: verify legal-state generation, JSONL validation, feature extraction, MPS training, Java inference, and JSON gameplay evaluation.
- Current evidence:
  - `data/distillation/local-enhanced-20260524.jsonl`
  - `models/distillation/local-enhanced-20260524-multiseed-quality_gate.md`
  - `models/distillation/local-enhanced-20260524-multiseed-seed_summary.md`
  - `models/distillation/local-enhanced-20260524-multiseed-gameplay-matrix-smoke/summary.md`

The local teacher is a deterministic rule scorer. It rewards property completion, Deal Breaker, Forced Deal, steal actions, rent/debt expected payment, low-overpay payment choices, and low-opportunity-cost discards. This makes it useful for repeatable smoke tests and cheap pretraining, but it is not a high-quality strategic oracle. A local-student win-rate gap against the hard heuristic is expected and should be reported as a limitation, not hidden.

### Stage 1: First DeepSeek Run

- Teacher: `deepseek`
- Rows: at least 5k
- Player counts: 2,3,4,5 mixed
- Required report: `models/distillation/deepseek-run1-quality_report.md`
- Gate: at least 95% `deepseek` rows, all four decision kinds present, validation top-1 above first/random baseline.

### Stage 2: Scaling Curve

Train the same model family at these row counts:

- 1k
- 5k
- 20k
- 100k

Use `scripts/make_scaling_subsets.py` to create deterministic cumulative subsets from one paid trace. This avoids paying DeepSeek again for each curve point and makes the curve reproducible.

For each checkpoint, record:

- top-1 imitation accuracy
- MRR
- per-kind top-1
- natural win rate in fixed snapshot budgets
- average snapshots to natural win
- force-end reasons

### Stage 2b: Player-Count Generalization

Use `MONOPOLY_TRAIN_SPLIT_BY=player-count` with `MONOPOLY_VALIDATION_PLAYER_COUNTS=4,5` to train on 2/3-player decisions and validate on 4/5-player decisions. This is stronger than a random session split because the model must generalize to a different table shape.

Record:

- train/validation player-count coverage from the dataset manifest
- validation top-1/MRR by decision kind
- whether `PAYMENT` and `JUST_SAY_NO` degrade more than `PLAY_CARD`
- gameplay results in 4/5-player evaluation rooms

### Stage 3: Model Family Comparison

Compare:

- linear JSON ranker
- MLP JSON candidate ranker
- forest-style bagged linear offline baseline
- KNN nearest-neighbor offline baseline
- rule heuristic teacher baseline

The linear model is easiest to inspect. The MLP JSON ranker is now also loadable by Java and is the likely stronger student. KNN and forest-style baselines are not runtime opponents; they are traditional-model comparators for paper-style analysis.

Recommended gameplay protocol:

- Fix `rankerSeat=1`.
- Run 2-player, 3-player, and 4-player evaluations separately.
- For each player count, evaluate against `easy`, `normal`, and `hard` opponent strategies.
- Use at least 50 games for a cheap checkpoint and 200+ games for a reportable result.
- Record both natural wins and force-end reasons, because snapshot-limit games should not be counted as real wins.
- Use `scripts/evaluate_gameplay_matrix.sh` to run the matrix and `summary.md` for report tables.

Current pilot matrix:

```bash
MONOPOLY_MATRIX_GAMES=2 \
MONOPOLY_MATRIX_SNAPSHOTS=260 \
MONOPOLY_MATRIX_PLAYERS=2,3,4 \
MONOPOLY_MATRIX_OPPONENTS=easy,normal,hard \
MONOPOLY_MATRIX_SEATS=1 \
scripts/evaluate_gameplay_matrix.sh \
  models/distillation/local-enhanced-20260524-multiseed-seed73-mlp/candidate_ranker_mlp.json \
  models/distillation/local-enhanced-20260524-multiseed-gameplay-matrix-smoke
```

Result: 9 conditions, 18 games requested, 14 natural completions, ranker win rate 0.278, average ranker board rank 1.89. The 2-player cells look much stronger than the 3-player and 4-player cells, so player-count generalization is a real risk to test after DeepSeek labels.

Quality gate:

```bash
python3 scripts/check_ai_quality_gate.py --require local
```

Current gate status is `local-training-ready`: local training and handoff are valid, but production and paper evidence gates are intentionally false. A production dataset must pass:

```bash
python3 scripts/check_ai_quality_gate.py \
  --production-prefix models/distillation/deepseek-run1 \
  --require production
```

Paper-strength evidence must pass:

```bash
python3 scripts/check_ai_quality_gate.py \
  --production-prefix models/distillation/deepseek-run1 \
  --require paper
```

That paper gate requires production data plus a 2/3/4/5-player by easy/normal/hard gameplay matrix with at least 50 games per cell.

### Stage 4: Ablations

Train variants:

- only `PLAY_CARD`
- `PLAY_CARD` + `PAYMENT`
- all decision kinds
- 2/3-player train, 4/5-player test
- with and without strategy text in teacher prompt

## Metrics

Offline metrics:

- dataset validation pass/fail
- rows and sessions
- teacher source ratio
- decision-kind coverage
- average/P50/P90 candidates
- top-1 imitation
- MRR
- first-candidate baseline
- random expected baseline
- per-kind top-1

Gameplay metrics:

- natural win rate
- ranker-seat win rate against fixed Easy/Normal/Hard opponents
- winner distribution
- average snapshots per game
- force-end reason distribution
- invalid action fallback count
- payment overpay amount
- Just Say No usage rate
- complete-set tempo
- model latency

## Paper Framing

Potential title:

> Legal-Action Distillation for a Complex Rule-Based Card Game

Contribution framing:

- Use a symbolic game engine to guarantee legal state/action generation.
- Batch-label decisions with a strong general LLM teacher.
- Distill to a small local ranker that never emits illegal actions.
- Evaluate both imitation quality and actual gameplay behavior.

Relevant prior work:

- DouZero: deep reinforcement learning for DouDizhu, a multiplayer imperfect-information card game.
- Policy Distillation: compressing expensive policies into smaller students.
- DeepStack: expert-level imperfect-information card-game AI.
- AlphaZero/MuZero family: useful contrast for legal-action search/self-play, but less direct because Monopoly Deal has hidden information and stochastic card flow.

Detailed paper framing, source links, experiment tables, and claim boundaries now live in `docs/ai-paper-outline.md`.

## Current Limitations

- No large DeepSeek-labeled dataset has been collected in this checkout yet because no API key is available in the current environment.
- Java runtime can load both linear JSON and MLP JSON rankers.
- Local ranker runtime now covers the same four decision kinds as the simulator: play-card, payment, Just Say No, and overflow discard.
- Local baseline labels are not a substitute for DeepSeek labels.
- Gameplay evaluation needs longer runs and stronger opponent comparisons before any claim about playing strength.
- The current local model has stable imitation metrics but weak hard-opponent gameplay, so the next production-quality step is teacher quality rather than simply adding more local heuristic rows.
