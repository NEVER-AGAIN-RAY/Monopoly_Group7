import contextlib
import io
import importlib.util
import json
import pathlib
import sys
import tempfile
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location(
    "distill_dataset", ROOT / "scripts" / "distill_dataset.py"
)
distill_dataset = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = distill_dataset
SPEC.loader.exec_module(distill_dataset)

REPORT_SPEC = importlib.util.spec_from_file_location(
    "report_distillation_quality", ROOT / "scripts" / "report_distillation_quality.py"
)
report_distillation_quality = importlib.util.module_from_spec(REPORT_SPEC)
sys.modules[REPORT_SPEC.name] = report_distillation_quality
REPORT_SPEC.loader.exec_module(report_distillation_quality)

SUBSET_SPEC = importlib.util.spec_from_file_location(
    "make_scaling_subsets", ROOT / "scripts" / "make_scaling_subsets.py"
)
make_scaling_subsets = importlib.util.module_from_spec(SUBSET_SPEC)
sys.modules[SUBSET_SPEC.name] = make_scaling_subsets
SUBSET_SPEC.loader.exec_module(make_scaling_subsets)

MATRIX_SPEC = importlib.util.spec_from_file_location(
    "summarize_gameplay_matrix", ROOT / "scripts" / "summarize_gameplay_matrix.py"
)
summarize_gameplay_matrix = importlib.util.module_from_spec(MATRIX_SPEC)
sys.modules[MATRIX_SPEC.name] = summarize_gameplay_matrix
MATRIX_SPEC.loader.exec_module(summarize_gameplay_matrix)

AUDIT_SPEC = importlib.util.spec_from_file_location(
    "audit_distillation_trace", ROOT / "scripts" / "audit_distillation_trace.py"
)
audit_distillation_trace = importlib.util.module_from_spec(AUDIT_SPEC)
sys.modules[AUDIT_SPEC.name] = audit_distillation_trace
AUDIT_SPEC.loader.exec_module(audit_distillation_trace)

READINESS_SPEC = importlib.util.spec_from_file_location(
    "check_training_readiness", ROOT / "scripts" / "check_training_readiness.py"
)
check_training_readiness = importlib.util.module_from_spec(READINESS_SPEC)
sys.modules[READINESS_SPEC.name] = check_training_readiness
READINESS_SPEC.loader.exec_module(check_training_readiness)

MERGE_SPEC = importlib.util.spec_from_file_location(
    "merge_distillation_traces", ROOT / "scripts" / "merge_distillation_traces.py"
)
merge_distillation_traces = importlib.util.module_from_spec(MERGE_SPEC)
sys.modules[MERGE_SPEC.name] = merge_distillation_traces
MERGE_SPEC.loader.exec_module(merge_distillation_traces)

SUMMARY_SPEC = importlib.util.spec_from_file_location(
    "summarize_training_run", ROOT / "scripts" / "summarize_training_run.py"
)
summarize_training_run = importlib.util.module_from_spec(SUMMARY_SPEC)
sys.modules[SUMMARY_SPEC.name] = summarize_training_run
SUMMARY_SPEC.loader.exec_module(summarize_training_run)

SEED_SUMMARY_SPEC = importlib.util.spec_from_file_location(
    "summarize_seed_runs", ROOT / "scripts" / "summarize_seed_runs.py"
)
summarize_seed_runs = importlib.util.module_from_spec(SEED_SUMMARY_SPEC)
sys.modules[SEED_SUMMARY_SPEC.name] = summarize_seed_runs
SEED_SUMMARY_SPEC.loader.exec_module(summarize_seed_runs)

SELECT_RELABEL_SPEC = importlib.util.spec_from_file_location(
    "select_relabel_trace", ROOT / "scripts" / "select_relabel_trace.py"
)
select_relabel_trace = importlib.util.module_from_spec(SELECT_RELABEL_SPEC)
sys.modules[SELECT_RELABEL_SPEC.name] = select_relabel_trace
SELECT_RELABEL_SPEC.loader.exec_module(select_relabel_trace)


class DistillDatasetSplitTest(unittest.TestCase):
    def test_session_split_keeps_whole_sessions_together(self):
        examples = []
        for session_id in ["s1", "s2", "s3", "s4"]:
            for decision_index in range(3):
                examples.append(
                    distill_dataset.Example(
                        features=[],
                        label=decision_index == 0,
                        weight=1.0,
                        decision_kind="PLAY_CARD",
                        session_id=session_id,
                        decision_id=f"{session_id}-d{decision_index}",
                        player_count=3,
                    )
                )

        train, val = distill_dataset.split_examples(
            examples,
            validation_ratio=0.5,
            seed=7,
            split_by="session",
        )

        train_sessions = {ex.session_id for ex in train}
        val_sessions = {ex.session_id for ex in val}
        self.assertTrue(train_sessions)
        self.assertTrue(val_sessions)
        self.assertTrue(train_sessions.isdisjoint(val_sessions))

    def test_decision_split_can_split_same_session(self):
        examples = [
            distill_dataset.Example(
                features=[],
                label=True,
                weight=1.0,
                decision_kind="PLAY_CARD",
                session_id="same-session",
                decision_id=f"d{i}",
                player_count=3,
            )
            for i in range(6)
        ]

        train, val = distill_dataset.split_examples(
            examples,
            validation_ratio=0.5,
            seed=7,
            split_by="decision",
        )

        self.assertEqual({"same-session"}, {ex.session_id for ex in train})
        self.assertEqual({"same-session"}, {ex.session_id for ex in val})

    def test_player_count_split_holds_out_configured_counts(self):
        examples = []
        for player_count in [2, 3, 4, 5]:
            examples.append(
                distill_dataset.Example(
                    features=[],
                    label=True,
                    weight=1.0,
                    decision_kind="PLAY_CARD",
                    session_id=f"s{player_count}",
                    decision_id=f"d{player_count}",
                    player_count=player_count,
                )
            )

        train, val = distill_dataset.split_examples(
            examples,
            validation_ratio=0.2,
            seed=7,
            split_by="player-count",
            validation_player_counts={4, 5},
        )

        self.assertEqual({2, 3}, {ex.player_count for ex in train})
        self.assertEqual({4, 5}, {ex.player_count for ex in val})


class DistillDatasetValidationTest(unittest.TestCase):
    def test_valid_context_passes(self):
        self.assertEqual([], distill_dataset.validate_row(sample_row()))

    def test_duplicate_visible_card_id_fails(self):
        row = sample_row()
        row["request"]["context"]["self"]["handCards"].append(
            {"id": "PROP_1", "kind": "PROPERTY", "name": "copy", "valueM": 1, "color": "BROWN"}
        )

        errors = distill_dataset.validate_row(row)

        self.assertTrue(any("duplicate visible card ids" in err for err in errors))

    def test_play_candidate_card_must_be_in_self_hand(self):
        row = sample_row()
        row["request"]["candidates"][0]["payload"]["cardId"] = "MISSING_CARD"

        errors = distill_dataset.validate_row(row)

        self.assertTrue(any("not in self hand" in err for err in errors))

    def test_legal_candidate_ids_must_match_request_candidates(self):
        row = sample_row()
        row["request"]["context"]["decision"]["legalCandidates"][0]["id"] = "other"

        errors = distill_dataset.validate_row(row)

        self.assertTrue(any("legalCandidates must match" in err for err in errors))

    def test_kind_balance_upweights_rare_decision_kinds(self):
        rows = []
        for i in range(4):
            row = sample_row()
            row["request"]["decisionId"] = f"play-{i}"
            rows.append(row)
        rare = sample_row()
        rare["request"]["decisionId"] = "payment-1"
        rare["request"]["decisionKind"] = "PAYMENT"
        rare["request"]["context"]["gameMeta"]["decisionKind"] = "PAYMENT"
        rare["request"]["context"]["decision"]["kind"] = "PAYMENT"
        rows.append(rare)

        weights = distill_dataset.kind_balance_multipliers(rows, enabled=True, max_multiplier=4.0)

        self.assertEqual(1.0, weights["PLAY_CARD"])
        self.assertGreater(weights["PAYMENT"], 1.0)


class QualityReportTest(unittest.TestCase):
    def test_gameplay_evaluation_renders_in_report(self):
        report = {
            "rows": 10,
            "sessions": 2,
            "actors": 2,
            "preferredSource": "deepseek",
            "preferredSourceRatio": 1.0,
            "readyForTraining": True,
            "readyForProductionTraining": True,
            "labelQualityTier": "deepseek_teacher",
            "qualityGates": [],
            "byKind": {"PLAY_CARD": 10},
            "byTeacherSource": {"deepseek": 10},
            "candidateCounts": {"avg": 2.0, "p50": 2, "p90": 3, "min": 1, "max": 4},
            "roundRange": [1, 2],
            "playerCounts": {"3": 10},
            "actionsUsedThisTurn": {"0": 5, "1": 5},
            "tokenUsage": {
                "rowsWithUsage": 2,
                "promptTokensShare": 1200,
                "completionTokensShare": 80,
                "totalTokensShare": 1280,
                "avgTotalTokensPerDecision": 640,
            },
            "metrics": {},
            "modelComparisons": [],
            "gameplayEvaluations": [
                {
                    "opponentStrategy": "hard",
                    "gamesRequested": 5,
                    "naturalWinRate": 0.2,
                    "rankerWinRate": 0.1,
                    "averageSnapshots": 157.4,
                    "averageRankerBoardRank": 1.8,
                    "rankerBoardLeadRate": 0.4,
                    "endReasons": {"LOCAL_RANKER_EVAL_SNAPSHOT_LIMIT": 4},
                }
            ],
        }

        markdown = report_distillation_quality.render_markdown(report)

        self.assertIn("## Gameplay Evaluation", markdown)
        self.assertIn("| hard | 5 | 0.200 | 0.100 | 157.4 | 1.80 | 0.400 |", markdown)
        self.assertIn("Avg total tokens per decision: 640.0", markdown)

    def test_token_usage_summary_uses_per_case_share(self):
        row = sample_row()
        row["result"]["metadata"]["promptTokensShare"] = 100.5
        row["result"]["metadata"]["completionTokensShare"] = 9.5
        row["result"]["metadata"]["totalTokensShare"] = 110

        usage = report_distillation_quality.token_usage_summary([row])

        self.assertEqual(1, usage["rowsWithUsage"])
        self.assertEqual(110, usage["totalTokensShare"])
        self.assertEqual(110, usage["avgTotalTokensPerDecision"])


class ScalingSubsetsTest(unittest.TestCase):
    def test_parse_sizes_sorts_and_deduplicates(self):
        self.assertEqual([2, 5, 10], make_scaling_subsets.parse_sizes("10,2,5,2"))

    def test_stratified_order_preserves_all_rows(self):
        rows = []
        for i, (kind, player_count) in enumerate([
            ("PLAY_CARD", 2),
            ("PLAY_CARD", 3),
            ("PAYMENT", 2),
            ("PAYMENT", 3),
        ]):
            row = sample_row()
            row["request"]["decisionId"] = f"d{i}"
            row["request"]["decisionKind"] = kind
            row["request"]["context"]["gameMeta"]["decisionKind"] = kind
            row["request"]["context"]["gameMeta"]["playerCount"] = player_count
            row["request"]["context"]["decision"]["kind"] = kind
            rows.append(row)

        ordered = make_scaling_subsets.stratified_order(rows, ["decisionKind", "playerCount"], seed=7)

        self.assertEqual({f"d{i}" for i in range(4)}, {r["request"]["decisionId"] for r in ordered})
        self.assertEqual(4, len(ordered))


class GameplayMatrixSummaryTest(unittest.TestCase):
    def test_summary_uses_game_weighted_averages(self):
        rows = [
            {
                "players": 2,
                "opponentStrategy": "easy",
                "rankerSeat": 1,
                "gamesRequested": 2,
                "completedGames": 1,
                "naturalWinRate": 0.5,
                "rankerWinRate": 0.5,
                "averageRankerBoardRank": 1.0,
                "rankerBoardLeadRate": 0.5,
                "endReasons": {"NATURAL_OR_LIMIT": 2},
            },
            {
                "players": 3,
                "opponentStrategy": "hard",
                "rankerSeat": 1,
                "gamesRequested": 4,
                "completedGames": 2,
                "naturalWinRate": 0.25,
                "rankerWinRate": 0.0,
                "averageRankerBoardRank": 2.5,
                "rankerBoardLeadRate": 0.0,
                "endReasons": {"LOCAL_RANKER_EVAL_SNAPSHOT_LIMIT": 4},
            },
        ]

        summary = summarize_gameplay_matrix.build_summary(rows)
        markdown = summarize_gameplay_matrix.render_markdown(summary)

        self.assertEqual(2, summary["runs"])
        self.assertEqual(6, summary["gamesRequested"])
        self.assertAlmostEqual((0.5 * 2 + 0.25 * 4) / 6, summary["naturalWinRate"])
        self.assertAlmostEqual((0.5 * 2 + 0.0 * 4) / 6, summary["rankerWinRate"])
        self.assertIn("## By Opponent", markdown)
        self.assertIn("| hard | 1 | 4 | 0.250 | 0.000 | 2.50 | 0.000 |", markdown)


class TraceAuditTest(unittest.TestCase):
    def test_audit_passes_balanced_deepseek_rows_with_usage(self):
        rows = []
        for i, kind in enumerate(["PLAY_CARD", "PAYMENT", "JUST_SAY_NO", "OVERFLOW_DISCARD"]):
            row = sample_row()
            row["request"]["decisionId"] = f"d{i}"
            row["request"]["decisionKind"] = kind
            row["request"]["context"]["gameMeta"]["decisionKind"] = kind
            row["request"]["context"]["decision"]["kind"] = kind
            row["request"]["candidates"].append(
                {"id": "c2", "summary": "Other", "payload": {"cardId": "PROP_1", "actionType": "DISCARD"}}
            )
            row["result"]["choiceId"] = "c2" if i % 2 else "c1"
            row["result"]["metadata"]["source"] = "deepseek"
            row["result"]["metadata"]["totalTokensShare"] = 10
            rows.append(row)

        audit = audit_distillation_trace.build_audit(
            rows,
            preferred_source="deepseek",
            min_rows=4,
            min_source_ratio=0.95,
            max_first_choice_ratio=0.75,
            min_rare_kind_rows=1,
            require_token_usage=True,
        )

        self.assertTrue(audit["ok"])
        self.assertEqual(0, audit["failureCount"])

    def test_audit_fails_first_choice_bias(self):
        rows = []
        for i in range(4):
            row = sample_row()
            row["request"]["decisionId"] = f"d{i}"
            row["result"]["metadata"]["source"] = "deepseek"
            row["result"]["metadata"]["totalTokensShare"] = 10
            rows.append(row)

        audit = audit_distillation_trace.build_audit(
            rows,
            preferred_source="deepseek",
            min_rows=1,
            min_source_ratio=0.95,
            max_first_choice_ratio=0.5,
            min_rare_kind_rows=0,
            require_token_usage=True,
        )

        self.assertFalse(audit["ok"])
        self.assertTrue(any(c["name"] == "first_choice_bias" and not c["ok"] for c in audit["checks"]))


class TraceMergeTest(unittest.TestCase):
    def test_merge_skips_identical_duplicate_decisions(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            first = root / "first.jsonl"
            second = root / "second.jsonl"
            output = root / "merged.jsonl"
            row = sample_row()
            write_jsonl(first, [row])
            write_jsonl(second, [row])

            code = run_merge_cli([
                    str(first),
                    str(second),
                    "--output",
                    str(output),
                    "--report",
                    str(root / "report.json"),
                ])

            self.assertEqual(0, code)
            lines = output.read_text(encoding="utf-8").strip().splitlines()
            self.assertEqual(1, len(lines))
            report = json.loads((root / "report.json").read_text(encoding="utf-8"))
            self.assertEqual(1, report["rowsWritten"])
            self.assertEqual(1, report["duplicateRowsSkipped"])
            self.assertEqual(0, report["conflictCount"])

    def test_merge_fails_on_conflicting_duplicate_decisions_by_default(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            first = root / "first.jsonl"
            second = root / "second.jsonl"
            output = root / "merged.jsonl"
            row = sample_row()
            conflict = clone_row(row)
            conflict["request"]["candidates"].append(
                {"id": "c2", "summary": "Other", "payload": {"cardId": "PROP_1", "actionType": "DISCARD"}}
            )
            conflict["result"]["choiceId"] = "c2"
            write_jsonl(first, [row])
            write_jsonl(second, [conflict])

            code = run_merge_cli([
                    str(first),
                    str(second),
                    "--output",
                    str(output),
                    "--report",
                    str(root / "report.json"),
                ])

            self.assertEqual(2, code)
            self.assertFalse(output.exists())
            report = json.loads((root / "report.json").read_text(encoding="utf-8"))
            self.assertFalse(report["ok"])
            self.assertEqual(1, report["conflictCount"])

    def test_merge_can_filter_teacher_sources(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            first = root / "mixed.jsonl"
            output = root / "deepseek-only.jsonl"
            deepseek = sample_row()
            local = clone_row(deepseek)
            local["request"]["decisionId"] = "local"
            local["result"]["decisionId"] = "local"
            local["result"]["metadata"]["source"] = "local_heuristic"
            write_jsonl(first, [deepseek, local])

            code = run_merge_cli([
                    str(first),
                    "--output",
                    str(output),
                    "--include-sources",
                    "deepseek",
                ])

            self.assertEqual(0, code)
            rows = [json.loads(line) for line in output.read_text(encoding="utf-8").splitlines()]
            self.assertEqual(["d1"], [row["request"]["decisionId"] for row in rows])


class TrainingReadinessTest(unittest.TestCase):
    def test_local_readiness_passes_with_local_heuristic_artifacts(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            trace = root / "trace.jsonl"
            prefix = root / "run"
            rows = readiness_rows("local_heuristic")
            write_jsonl(trace, rows)
            write_readiness_artifacts(prefix, rows, production=False)

            report = check_training_readiness.build_readiness_report(
                trace=trace,
                output_prefix=prefix,
                mode="local",
                min_rows=4,
                min_rare_kind_rows=1,
                min_validation_top1=0.55,
                min_gameplay_games=1,
                model_type="mlp",
                gameplay_path=None,
                trace_audit_path=None,
                quality_report_path=None,
            )

            self.assertTrue(report["ready"])
            self.assertEqual(0, report["failureCount"])

    def test_local_readiness_accepts_snapshot_limited_evaluation(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            trace = root / "trace.jsonl"
            prefix = root / "run"
            rows = readiness_rows("local_heuristic")
            write_jsonl(trace, rows)
            write_readiness_artifacts(prefix, rows, production=False)
            (prefix.parent / f"{prefix.name}-mlp" / "gameplay_vs_hard.json").write_text(
                json.dumps({
                    "gamesRequested": 2,
                    "evaluatedGames": 2,
                    "completedGames": 0,
                    "endReasons": {"LOCAL_RANKER_EVAL_SNAPSHOT_LIMIT": 2},
                    "games": [{}, {}],
                }),
                encoding="utf-8",
            )

            report = check_training_readiness.build_readiness_report(
                trace=trace,
                output_prefix=prefix,
                mode="local",
                min_rows=4,
                min_rare_kind_rows=1,
                min_validation_top1=0.55,
                min_gameplay_games=2,
                model_type="mlp",
                gameplay_path=None,
                trace_audit_path=None,
                quality_report_path=None,
            )

            self.assertTrue(report["ready"])
            self.assertNotIn(
                "gameplay_natural_completed",
                {item["name"] for item in report["checks"]},
            )

    def test_readiness_can_require_natural_gameplay_finishes(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            trace = root / "trace.jsonl"
            prefix = root / "run"
            rows = readiness_rows("local_heuristic")
            write_jsonl(trace, rows)
            write_readiness_artifacts(prefix, rows, production=False)
            (prefix.parent / f"{prefix.name}-mlp" / "gameplay_vs_hard.json").write_text(
                json.dumps({
                    "gamesRequested": 2,
                    "evaluatedGames": 2,
                    "completedGames": 0,
                    "games": [{}, {}],
                }),
                encoding="utf-8",
            )

            report = check_training_readiness.build_readiness_report(
                trace=trace,
                output_prefix=prefix,
                mode="local",
                min_rows=4,
                min_rare_kind_rows=1,
                min_validation_top1=0.55,
                min_gameplay_games=2,
                model_type="mlp",
                gameplay_path=None,
                require_natural_gameplay=True,
                trace_audit_path=None,
                quality_report_path=None,
            )

            self.assertFalse(report["ready"])
            failed_names = {item["name"] for item in report["checks"] if not item["ok"]}
            self.assertIn("gameplay_natural_completed", failed_names)

    def test_production_readiness_rejects_local_heuristic_trace(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            trace = root / "trace.jsonl"
            prefix = root / "run"
            rows = readiness_rows("local_heuristic")
            write_jsonl(trace, rows)
            write_readiness_artifacts(prefix, rows, production=False)

            report = check_training_readiness.build_readiness_report(
                trace=trace,
                output_prefix=prefix,
                mode="production",
                min_rows=4,
                min_rare_kind_rows=1,
                min_validation_top1=0.55,
                min_gameplay_games=1,
                model_type="mlp",
                gameplay_path=None,
                trace_audit_path=None,
                quality_report_path=None,
            )

            self.assertFalse(report["ready"])
            failed_names = {item["name"] for item in report["checks"] if not item["ok"]}
            self.assertIn("deepseek_source_ratio", failed_names)
            self.assertIn("token_usage_present", failed_names)
            self.assertIn("trace_audit_exists", failed_names)


class TrainingRunSummaryTest(unittest.TestCase):
    def test_summary_marks_local_run_ready_but_not_production(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            trace = root / "trace.jsonl"
            prefix = root / "run"
            rows = readiness_rows("local_heuristic")
            write_jsonl(trace, rows)
            write_readiness_artifacts(prefix, rows, production=False)
            write_summary_artifacts(prefix, rows, readiness_mode="local", ready=True)

            summary = summarize_training_run.build_summary(prefix)
            markdown = summarize_training_run.render_markdown(summary)

            self.assertTrue(summary["handoffReady"])
            self.assertFalse(summary["productionReady"])
            self.assertEqual("local", summary["readinessMode"])
            self.assertIn("Status: **local-ready**", markdown)
            self.assertIn("Validation top-1: 0.800", markdown)

    def test_summary_surfaces_readiness_failures(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            trace = root / "trace.jsonl"
            prefix = root / "run"
            rows = readiness_rows("local_heuristic")
            write_jsonl(trace, rows)
            write_readiness_artifacts(prefix, rows, production=False)
            write_summary_artifacts(prefix, rows, readiness_mode="production", ready=False)

            summary = summarize_training_run.build_summary(prefix)
            markdown = summarize_training_run.render_markdown(summary)

            self.assertFalse(summary["handoffReady"])
            self.assertFalse(summary["productionReady"])
            self.assertIn("deepseek_source_ratio", markdown)


class SeedRunSummaryTest(unittest.TestCase):
    def test_seed_summary_aggregates_run_summaries(self):
        rows = [
            {
                "outputPrefix": "run-seed1",
                "handoffReady": True,
                "productionReady": False,
                "readinessMode": "local",
                "rows": 100,
                "validation": {
                    "top1": 0.6,
                    "mrr": 0.7,
                    "firstCandidateTop1": 0.3,
                    "randomExpectedTop1": 0.2,
                },
                "gameplay": {
                    "averageRankerBoardRank": 2.0,
                    "rankerBoardLeadRate": 0.1,
                    "rankerWinRate": 0.0,
                    "naturalWinRate": 0.0,
                    "evaluatedGames": 2,
                    "completedGames": 0,
                },
                "model": {"device": "mps", "splitBy": "session", "epochs": 1},
            },
            {
                "outputPrefix": "run-seed2",
                "handoffReady": True,
                "productionReady": False,
                "readinessMode": "local",
                "rows": 100,
                "validation": {
                    "top1": 0.8,
                    "mrr": 0.9,
                    "firstCandidateTop1": 0.3,
                    "randomExpectedTop1": 0.2,
                },
                "gameplay": {
                    "averageRankerBoardRank": 1.0,
                    "rankerBoardLeadRate": 0.4,
                    "rankerWinRate": 0.5,
                    "naturalWinRate": 0.5,
                    "evaluatedGames": 2,
                    "completedGames": 1,
                },
                "model": {"device": "mps", "splitBy": "session", "epochs": 1},
            },
        ]

        summary = summarize_seed_runs.build_summary([summarize_seed_runs.load_row_from_data(row) for row in rows])
        markdown = summarize_seed_runs.render_markdown(summary)

        self.assertEqual(2, summary["runs"])
        self.assertEqual(2, summary["readyRuns"])
        self.assertAlmostEqual(0.7, summary["aggregates"]["validationTop1"]["mean"])
        self.assertAlmostEqual(0.25, summary["aggregates"]["rankerWinRate"]["mean"])
        self.assertIn("Multi-Seed", markdown)


class RelabelSelectionTest(unittest.TestCase):
    def test_selector_balances_requested_kinds_and_player_counts(self):
        rows = []
        for kind in ["PLAY_CARD", "PAYMENT", "JUST_SAY_NO", "OVERFLOW_DISCARD"]:
            for i, player_count in enumerate([2, 3, 4]):
                row = sample_row()
                row["request"]["decisionId"] = f"{kind}-{i}"
                row["request"]["sessionId"] = f"s-{kind}-{i}"
                row["request"]["decisionKind"] = kind
                row["request"]["context"]["gameMeta"]["decisionKind"] = kind
                row["request"]["context"]["gameMeta"]["playerCount"] = player_count
                rows.append(select_relabel_trace.row_info(
                    row,
                    json.dumps(row, ensure_ascii=False),
                    len(rows) + 1,
                ))

        selected, report = select_relabel_trace.select_rows(
            rows,
            input_path=pathlib.Path("input.jsonl"),
            output_path=pathlib.Path("selected.jsonl"),
            quotas={"PLAY_CARD": 2, "PAYMENT": 2, "JUST_SAY_NO": 2, "OVERFLOW_DISCARD": 2},
            target_rows=0,
            include_sources=set(),
            require_kinds=["PLAY_CARD", "PAYMENT", "JUST_SAY_NO", "OVERFLOW_DISCARD"],
            min_rows=8,
            seed="test",
        )

        self.assertTrue(report["ok"])
        self.assertEqual(8, len(selected))
        self.assertEqual({
            "JUST_SAY_NO": 2,
            "OVERFLOW_DISCARD": 2,
            "PAYMENT": 2,
            "PLAY_CARD": 2,
        }, report["selectedByDecisionKind"])
        self.assertGreaterEqual(len(report["selectedByPlayerCount"]), 2)

    def test_selector_reports_quota_deficit_as_warning(self):
        row = sample_row()
        info = select_relabel_trace.row_info(row, json.dumps(row), 1)

        selected, report = select_relabel_trace.select_rows(
            [info],
            input_path=pathlib.Path("input.jsonl"),
            output_path=pathlib.Path("selected.jsonl"),
            quotas={"PLAY_CARD": 2},
            target_rows=0,
            include_sources=set(),
            require_kinds=["PLAY_CARD"],
            min_rows=1,
            seed="test",
        )

        self.assertTrue(report["ok"])
        self.assertEqual(1, len(selected))
        self.assertEqual(1, report["warningCount"])
        self.assertEqual({"PLAY_CARD": 1}, report["quotaDeficits"])


def clone_row(row):
    return json.loads(json.dumps(row))


def run_merge_cli(args):
    stdout = io.StringIO()
    stderr = io.StringIO()
    with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
        return merge_distillation_traces.main(args)


def write_jsonl(path, rows):
    path.write_text(
        "".join(json.dumps(row, ensure_ascii=False) + "\n" for row in rows),
        encoding="utf-8",
    )


def readiness_rows(source):
    rows = []
    for i, kind in enumerate(["PLAY_CARD", "PAYMENT", "JUST_SAY_NO", "OVERFLOW_DISCARD"]):
        row = sample_row()
        row["request"]["decisionId"] = f"d{i}"
        row["request"]["sessionId"] = f"s{i % 2}"
        row["request"]["decisionKind"] = kind
        row["request"]["context"]["gameMeta"]["decisionKind"] = kind
        row["request"]["context"]["decision"]["kind"] = kind
        row["result"]["decisionId"] = f"d{i}"
        row["result"]["metadata"]["source"] = source
        if source == "deepseek":
            row["result"]["metadata"]["totalTokensShare"] = 10
        rows.append(row)
    return rows


def write_readiness_artifacts(prefix, rows, production):
    (prefix.parent / f"{prefix.name}-mlp").mkdir(parents=True)
    (prefix.parent / f"{prefix.name}-dataset_manifest.json").write_text("{}", encoding="utf-8")
    (prefix.parent / f"{prefix.name}-quality_report.json").write_text(
        json.dumps({
            "readyForTraining": True,
            "readyForProductionTraining": bool(production),
        }),
        encoding="utf-8",
    )
    (prefix.parent / f"{prefix.name}-mlp" / "metrics.json").write_text(
        json.dumps({
            "validation": {
                "top1": 0.8,
                "firstCandidateTop1": 0.3,
                "randomExpectedTop1": 0.2,
            }
        }),
        encoding="utf-8",
    )
    (prefix.parent / f"{prefix.name}-mlp" / "candidate_ranker_mlp.json").write_text("{}", encoding="utf-8")
    (prefix.parent / f"{prefix.name}-mlp" / "gameplay_vs_hard.json").write_text(
        json.dumps({"gamesRequested": 1, "completedGames": 1}),
        encoding="utf-8",
    )
    if production:
        (prefix.parent / f"{prefix.name}-trace_audit.json").write_text(
            json.dumps({"ok": True}),
            encoding="utf-8",
        )


def write_summary_artifacts(prefix, rows, readiness_mode, ready):
    by_kind = {}
    by_source = {}
    for row in rows:
        by_kind[row["request"]["decisionKind"]] = by_kind.get(row["request"]["decisionKind"], 0) + 1
        source = row["result"]["metadata"]["source"]
        by_source[source] = by_source.get(source, 0) + 1
    (prefix.parent / f"{prefix.name}-dataset_manifest.json").write_text(
        json.dumps({
            "rows": len(rows),
            "sessions": 2,
            "byDecisionKind": by_kind,
            "byTeacherSource": by_source,
            "byPlayerCount": {"2": len(rows)},
            "tokenUsageEstimate": {"rowsWithUsage": 0},
        }),
        encoding="utf-8",
    )
    readiness_checks = []
    if not ready:
        readiness_checks.append({"name": "deepseek_source_ratio", "ok": False, "detail": "0.0% / 95.0%"})
    (prefix.parent / f"{prefix.name}-readiness.json").write_text(
        json.dumps({
            "mode": readiness_mode,
            "ready": ready,
            "rows": len(rows),
            "byDecisionKind": by_kind,
            "byTeacherSource": by_source,
            "rowsWithTokenUsage": 0,
            "checks": readiness_checks,
        }),
        encoding="utf-8",
    )
    (prefix.parent / f"{prefix.name}-trace_audit.json").write_text(
        json.dumps({"ok": True, "checks": []}),
        encoding="utf-8",
    )
    (prefix.parent / f"{prefix.name}-quality_report.json").write_text(
        json.dumps({
            "rows": len(rows),
            "sessions": 2,
            "byKind": by_kind,
            "byTeacherSource": by_source,
            "tokenUsage": {"rowsWithUsage": 0},
        }),
        encoding="utf-8",
    )


def sample_row():
    return {
        "schema": distill_dataset.SCHEMA,
        "request": {
            "decisionId": "d1",
            "sessionId": "s1",
            "actorPlayerId": "ai-1",
            "decisionKind": "PLAY_CARD",
            "stateSequence": 1,
            "context": {
                "gameMeta": {
                    "playerCount": 2,
                    "roundNumber": 1,
                    "decisionKind": "PLAY_CARD",
                    "decisionPlayerId": "ai-1",
                    "actionsRemainingThisTurn": 3,
                },
                "self": {
                    "id": "ai-1",
                    "handCount": 1,
                    "handCards": [
                        {
                            "id": "PROP_1",
                            "kind": "PROPERTY",
                            "name": "Baltic",
                            "valueM": 1,
                            "color": "BROWN",
                        }
                    ],
                },
                "players": [
                    {"id": "ai-1", "isSelf": True},
                    {"id": "ai-2", "isSelf": False},
                ],
                "decision": {
                    "kind": "PLAY_CARD",
                    "legalCandidates": [{"id": "c1", "summary": "Deploy BROWN."}],
                },
            },
            "candidates": [
                {
                    "id": "c1",
                    "summary": "Deploy property BROWN.",
                    "payload": {"cardId": "PROP_1", "actionType": "DEPLOY", "targetColorKey": "BROWN"},
                }
            ],
        },
        "result": {
            "decisionId": "d1",
            "choiceId": "c1",
            "metadata": {"source": "deepseek"},
        },
    }


if __name__ == "__main__":
    unittest.main()
