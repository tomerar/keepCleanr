# SPDX-License-Identifier: GPL-3.0-or-later
"""Synthetic counterexamples for the offline evaluator; these are not gallery quality results."""

import csv
import importlib.util
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "gallery-benchmark-score.py"
spec = importlib.util.spec_from_file_location("gallery_benchmark", SCRIPT)
benchmark = importlib.util.module_from_spec(spec)
spec.loader.exec_module(benchmark)


class BenchmarkScoreTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.manifest = self.write_csv("manifest", ["image_id", "event_id", "split", "stratum", "status"], [
            ["a", "event_a", "holdout", "camera", "analyzed"],
            ["b", "event_a", "holdout", "camera", "analyzed"],
            ["c", "event_b", "holdout", "screenshot", "analyzed"],
            ["d", "event_b", "holdout", "screenshot", "not_processed"],
            ["e", "event_c", "development", "camera", "analyzed"],
        ])
        self.labels = self.write_csv("labels", ["image_a", "image_b", "label", "cluster_id", "pair_stratum"], [
            ["a", "b", "match", "private_family_1", "camera"],
            ["a", "c", "different", "private_family_1", "hard_negative"],
            ["c", "d", "match", "private_family_1", "screenshot"],
        ])
        self.groups = self.write_csv("groups", ["group_id", "image_id"], [["g", "a"], ["g", "b"], ["g", "c"]])
        self.candidates = self.write_csv("candidates", ["image_a", "image_b"], [["a", "b"]])

    def write_csv(self, name, header, data):
        path = self.root / (name + ".csv")
        with path.open("w", encoding="utf-8", newline="") as stream:
            writer = csv.writer(stream)
            writer.writerow(header)
            writer.writerows(data)
        return path

    def report(self):
        items = benchmark.inventory(self.manifest)
        truth = benchmark.labels(self.labels, items)
        suggested, count, members, pair_groups = benchmark.groups(self.groups, items, "holdout")
        candidates = benchmark.candidates(self.candidates, items, "holdout")
        return benchmark.evaluate(items, truth, suggested, candidates, "holdout", 1000, 42, count, members, pair_groups)

    def test_unprocessed_reference_matches_still_count_in_recall(self):
        result = self.report()
        self.assertEqual(result["reference_matches_including_unprocessed"], 2)
        self.assertEqual(result["true_matches"], 1)
        self.assertEqual(result["false_matches"], 1)
        self.assertEqual(result["reference_recall"], 0.5)
        self.assertEqual(result["candidate_recall_on_labelled_reference"], 0.5)
        self.assertEqual(result["known_pair_precision"], 0.5)
        self.assertEqual(result["unlabelled_suggested_pairs"], 1)
        self.assertFalse(result["suggested_pair_labels_complete"])
        self.assertFalse(result["analysis_scope_complete"])
        self.assertEqual(result["release_gate_decision"], "not_evaluated")

    def test_every_pair_in_group_is_scored_and_raw_ids_do_not_enter_report(self):
        result = self.report()
        self.assertEqual(result["suggested_pairs"], 3)
        self.assertEqual(result["false_merge_groups"], 1)
        self.assertEqual(result["groups_with_unlabelled_or_ambiguous_pairs"], 1)
        self.assertEqual(result["distinct_events"], 2)
        self.assertEqual(result["image_strata"], {"camera": 2, "screenshot": 2})
        self.assertEqual(result["pair_strata"]["hard_negative"]["false_matches"], 1)
        self.assertNotIn("private_family", json.dumps(result))
        self.assertEqual(result["processing_status_counts"]["not_processed"], 1)

    def test_event_leakage_is_rejected_even_without_label_pairs(self):
        self.write_csv("manifest", ["image_id", "event_id", "split", "stratum", "status"], [
            ["a", "same", "holdout", "camera", "analyzed"],
            ["b", "same", "development", "camera", "analyzed"],
        ])
        with self.assertRaisesRegex(benchmark.InputError, "event leakage"):
            benchmark.inventory(self.manifest)

    def test_duplicate_mirrored_pair_rejected(self):
        self.write_csv("labels", ["image_a", "image_b", "label", "cluster_id", "pair_stratum"], [
            ["a", "b", "match", "one", "camera"], ["b", "a", "match", "one", "camera"],
        ])
        with self.assertRaisesRegex(benchmark.InputError, "duplicate pair"):
            benchmark.labels(self.labels, benchmark.inventory(self.manifest))

    def test_bootstrap_cluster_cannot_split_an_event_between_pairs(self):
        self.write_csv("labels", ["image_a", "image_b", "label", "cluster_id", "pair_stratum"], [
            ["a", "b", "match", "one", "camera"], ["a", "c", "different", "two", "camera"],
        ])
        with self.assertRaisesRegex(benchmark.InputError, "multiple bootstrap clusters"):
            benchmark.labels(self.labels, benchmark.inventory(self.manifest))

    def test_ambiguous_or_missing_label_is_not_counted_as_a_true_match(self):
        self.write_csv("labels", ["image_a", "image_b", "label", "cluster_id", "pair_stratum"], [
            ["a", "b", "ambiguous", "one", "camera"], ["a", "c", "different", "one", "camera"],
        ])
        result = self.report()
        self.assertEqual(result["true_matches"], 0)
        self.assertEqual(result["ambiguous_suggested_pairs"], 1)
        self.assertEqual(result["unlabelled_suggested_pairs"], 1)
        self.assertFalse(result["suggested_pair_labels_complete"])
        self.assertIsNone(result["reference_recall"])

    def test_overlapping_or_unprocessed_group_members_are_rejected(self):
        items = benchmark.inventory(self.manifest)
        self.write_csv("groups", ["group_id", "image_id"], [["g", "a"], ["g", "b"], ["h", "a"]])
        with self.assertRaisesRegex(benchmark.InputError, "repeated"):
            benchmark.groups(self.groups, items, "holdout")
        self.write_csv("groups", ["group_id", "image_id"], [["g", "a"], ["g", "d"]])
        with self.assertRaisesRegex(benchmark.InputError, "unanalyzed"):
            benchmark.groups(self.groups, items, "holdout")

    def test_group_limit_rejects_twenty_one_sources(self):
        images = [[f"p{i}", f"event{i}", "holdout", "camera", "analyzed"] for i in range(21)]
        self.write_csv("manifest", ["image_id", "event_id", "split", "stratum", "status"], images)
        self.write_csv("groups", ["group_id", "image_id"], [["group", row[0]] for row in images])
        with self.assertRaisesRegex(benchmark.InputError, "exceeds 20"):
            benchmark.groups(self.groups, benchmark.inventory(self.manifest), "holdout")

    def test_bootstrap_work_cap_never_silently_reduces_samples(self):
        clusters = {f"c{i}": [1, 0] for i in range(1001)}
        self.assertIsNone(benchmark.confidence_interval(clusters, 10_000, 7))

    def test_empty_reference_and_candidates_are_reported_as_unknown(self):
        self.write_csv("labels", ["image_a", "image_b", "label", "cluster_id", "pair_stratum"], [])
        items = benchmark.inventory(self.manifest)
        truth = benchmark.labels(self.labels, items)
        suggested, count, members, pair_groups = benchmark.groups(self.groups, items, "holdout")
        result = benchmark.evaluate(items, truth, suggested, None, "holdout", 1000, 42, count, members, pair_groups)
        self.assertIsNone(result["known_pair_precision"])
        self.assertIsNone(result["reference_recall"])
        self.assertIsNone(result["candidate_recall_on_labelled_reference"])
        self.assertEqual(result["unlabelled_suggested_pairs"], 3)
        self.assertFalse(result["suggested_pair_labels_complete"])

    def test_bootstrap_resamples_clusters_not_individual_pairs(self):
        clusters = {"one": [90, 10], "two": [0, 10], "none_selected": [0, 0]}
        first = benchmark.confidence_interval(clusters, 1000, 7)
        self.assertIsNotNone(first)
        self.assertEqual(first, benchmark.confidence_interval(clusters, 1000, 7))
        self.assertEqual(first["independent_clusters"], 3)
        self.assertEqual(first["lower_95"], 0.0)
        self.assertEqual(first["upper_95"], 0.9)
        self.assertIsNone(benchmark.confidence_interval({"one": [1, 0]}, 1000, 7))

    def test_cli_uses_no_image_files_and_emits_aggregate_json(self):
        run = subprocess.run(
            [sys.executable, "-I", str(SCRIPT), "--manifest", str(self.manifest), "--labels", str(self.labels),
             "--groups", str(self.groups), "--candidates", str(self.candidates), "--split", "holdout"],
            capture_output=True, text=True, check=True,
        )
        self.assertEqual(json.loads(run.stdout)["reference_recall"], 0.5)
        self.assertNotIn("private_family", run.stdout)

    def test_missing_input_does_not_print_its_path(self):
        secret = self.root / "private-filename.csv"
        run = subprocess.run(
            [sys.executable, "-I", str(SCRIPT), "--manifest", str(secret), "--labels", str(self.labels),
             "--groups", str(self.groups), "--split", "holdout"], capture_output=True, text=True,
        )
        self.assertEqual(run.returncode, 2)
        self.assertNotIn(str(secret), run.stderr)


if __name__ == "__main__":
    unittest.main()
