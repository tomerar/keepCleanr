#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Offline aggregate scoring for a consented, event-split gallery evaluation corpus.

Inputs contain opaque IDs, labels and processing states only; this program never reads images.
It does not calibrate thresholds or decide that a release gate passed.
"""

import argparse
import csv
import json
import random
import re
import sys
from collections import Counter, defaultdict
from itertools import combinations
from pathlib import Path

TOKEN = re.compile(r"[A-Za-z0-9_-]{1,128}\Z")
SPLITS = {"development", "validation", "holdout"}
STATES = {"analyzed", "unsupported", "skipped", "not_processed"}
LABELS = {"match", "different", "ambiguous"}
MAX_ROWS = 1_000_000


class InputError(ValueError):
    pass


def token(value, context):
    if not TOKEN.fullmatch(value or ""):
        raise InputError(f"{context}: expected a nonempty opaque ID or simple category")
    return value


def rows(path, required):
    with Path(path).open("r", encoding="utf-8-sig", newline="") as stream:
        reader = csv.DictReader(stream)
        if reader.fieldnames is None or len(set(reader.fieldnames)) != len(reader.fieldnames):
            raise InputError("CSV has no header or contains duplicate columns")
        if not required.issubset(reader.fieldnames):
            raise InputError("CSV is missing required columns: " + ", ".join(sorted(required - set(reader.fieldnames))))
        for index, row in enumerate(reader, start=2):
            if index > MAX_ROWS + 1:
                raise InputError("CSV exceeds the one-million-row limit")
            if None in row or any(value is None for value in row.values()):
                raise InputError(f"CSV row {index}: wrong number of columns")
            yield index, row


def pair(a, b, context):
    a, b = token(a, context), token(b, context)
    if a == b:
        raise InputError(f"{context}: pair members must differ")
    return tuple(sorted((a, b)))


def inventory(path):
    items, event_split = {}, {}
    for index, row in rows(path, {"image_id", "event_id", "split", "stratum", "status"}):
        context = f"manifest row {index}"
        image = token(row["image_id"], context)
        event = token(row["event_id"], context)
        stratum = token(row["stratum"], context)
        split, status = row["split"], row["status"]
        if image in items or split not in SPLITS or status not in STATES:
            raise InputError(f"{context}: duplicate image or unknown split/status")
        if event in event_split and event_split[event] != split:
            raise InputError(f"{context}: event leakage across dataset splits")
        event_split[event] = split
        items[image] = {"event": event, "split": split, "stratum": stratum, "status": status}
    if not items:
        raise InputError("manifest is empty")
    return items


def labels(path, items):
    truth, clusters, event_clusters = {}, {}, {}
    for index, row in rows(path, {"image_a", "image_b", "label", "cluster_id", "pair_stratum"}):
        context = f"labels row {index}"
        key = pair(row["image_a"], row["image_b"], context)
        if key in truth or any(image not in items for image in key):
            raise InputError(f"{context}: duplicate pair or unknown image")
        split = items[key[0]]["split"]
        if items[key[1]]["split"] != split:
            raise InputError(f"{context}: pair crosses dataset splits")
        label = row["label"]
        cluster = token(row["cluster_id"], context)
        stratum = token(row["pair_stratum"], context)
        if label not in LABELS:
            raise InputError(f"{context}: unknown label")
        if cluster in clusters and clusters[cluster] != split:
            raise InputError(f"{context}: label cluster crosses dataset splits")
        clusters[cluster] = split
        for image in key:
            event = items[image]["event"]
            if event in event_clusters and event_clusters[event] != cluster:
                raise InputError(f"{context}: an event appears in multiple bootstrap clusters")
            event_clusters[event] = cluster
        truth[key] = {"label": label, "cluster": cluster, "stratum": stratum, "split": split}
    return truth


def groups(path, items, split):
    groups_by_id, members = defaultdict(list), set()
    for index, row in rows(path, {"group_id", "image_id"}):
        context = f"groups row {index}"
        group = token(row["group_id"], context)
        image = token(row["image_id"], context)
        if image not in items or image in members:
            raise InputError(f"{context}: unknown or repeated group member")
        if items[image]["split"] != split or items[image]["status"] != "analyzed":
            raise InputError(f"{context}: group contains an out-of-scope or unanalyzed source")
        members.add(image)
        groups_by_id[group].append(image)
        if len(groups_by_id[group]) > 20:
            raise InputError(f"{context}: group exceeds 20 images")
    if any(len(group) < 2 for group in groups_by_id.values()):
        raise InputError("comparison groups must contain at least two images")
    pair_groups = {tuple(sorted(p)): name for name, group in groups_by_id.items() for p in combinations(group, 2)}
    return set(pair_groups), len(groups_by_id), len(members), pair_groups


def candidates(path, items, split):
    if path is None:
        return None
    found = set()
    for index, row in rows(path, {"image_a", "image_b"}):
        context = f"candidates row {index}"
        key = pair(row["image_a"], row["image_b"], context)
        if key in found or any(image not in items for image in key):
            raise InputError(f"{context}: duplicate pair or unknown image")
        if any(items[image]["split"] != split for image in key):
            raise InputError(f"{context}: pair crosses the evaluation scope")
        found.add(key)
    return found


def fraction(numerator, denominator):
    return numerator / denominator if denominator else None


def confidence_interval(clusters, repetitions, seed):
    """Resample independently defined pair-label clusters, not correlated individual pairs."""
    if len(clusters) < 2 or repetitions < 1 or len(clusters) * repetitions > 10_000_000:
        return None
    samples = list(clusters.values())
    rng = random.Random(seed)
    values = []
    for _ in range(repetitions):
        selected = rng.choices(samples, k=len(samples))
        true = sum(value[0] for value in selected)
        false = sum(value[1] for value in selected)
        if true + false:
            values.append(true / (true + false))
    if len(values) < repetitions * 0.95:
        return None
    values.sort()
    return {
        "lower_95": values[int((len(values) - 1) * 0.025)],
        "upper_95": values[int((len(values) - 1) * 0.975)],
        "replicates": len(values),
        "independent_clusters": len(samples),
        "seed": seed,
    }


def evaluate(items, truth, suggested, candidates_found, split, repetitions, seed, group_count, group_members, pair_groups):
    subset = {key: row for key, row in truth.items() if row["split"] == split}
    counts = Counter()
    by_stratum = defaultdict(Counter)
    false_groups, uncertain_groups = set(), set()
    bootstrap = defaultdict(lambda: [0, 0])
    for key, row in subset.items():
        label, stratum, cluster = row["label"], row["stratum"], row["cluster"]
        bootstrap[cluster]  # Include clusters with no suggested labelled pairs.
        by_stratum[stratum]["labelled_pairs"] += 1
        by_stratum[stratum][f"label_{label}"] += 1
        chosen = key in suggested
        if label == "match":
            counts["reference_matches"] += 1
            by_stratum[stratum]["reference_matches"] += 1
            if candidates_found is not None and key in candidates_found:
                counts["retrieved_reference_matches"] += 1
            if chosen:
                counts["true_matches"] += 1
                by_stratum[stratum]["true_matches"] += 1
                bootstrap[cluster][0] += 1
        elif label == "different" and chosen:
            counts["false_matches"] += 1
            false_groups.add(pair_groups[key])
            by_stratum[stratum]["false_matches"] += 1
            bootstrap[cluster][1] += 1
        elif label == "ambiguous" and chosen:
            counts["ambiguous_suggestions"] += 1
            uncertain_groups.add(pair_groups[key])
    unlabelled = suggested - set(subset)
    uncertain_groups.update(pair_groups[key] for key in unlabelled)
    unknown = len(unlabelled)
    eligible = [item for item in items.values() if item["split"] == split]
    if not eligible:
        raise InputError("evaluation split has no images")
    precision_denominator = counts["true_matches"] + counts["false_matches"]
    strata = {}
    for name, totals in sorted(by_stratum.items()):
        strata[name] = {
            "labelled_pairs": totals["labelled_pairs"],
            "labelled_different_pairs": totals["label_different"],
            "labelled_ambiguous_pairs": totals["label_ambiguous"],
            "true_matches": totals["true_matches"],
            "false_matches": totals["false_matches"],
            "reference_matches": totals["reference_matches"],
            "known_pair_precision": fraction(totals["true_matches"], totals["true_matches"] + totals["false_matches"]),
            "reference_recall": fraction(totals["true_matches"], totals["reference_matches"]),
        }
    return {
        "schema_version": 1,
        "split": split,
        "eligible_images": len(eligible),
        "distinct_events": len({item["event"] for item in eligible}),
        "image_strata": dict(sorted(Counter(item["stratum"] for item in eligible).items())),
        "split_image_counts": {name: sum(item["split"] == name for item in items.values()) for name in sorted(SPLITS)},
        "split_event_counts": {name: len({item["event"] for item in items.values() if item["split"] == name})
                               for name in sorted(SPLITS)},
        "labelled_reference_pairs": len(subset),
        "processing_status_counts": {state: sum(item["status"] == state for item in eligible) for state in sorted(STATES)},
        "groups": group_count,
        "false_merge_groups": len(false_groups),
        "groups_with_unlabelled_or_ambiguous_pairs": len(uncertain_groups),
        "grouped_images": group_members,
        "grouped_image_fraction_of_eligible": fraction(group_members, len(eligible)),
        "suggested_pairs": len(suggested),
        "unlabelled_suggested_pairs": unknown,
        "ambiguous_suggested_pairs": counts["ambiguous_suggestions"],
        "true_matches": counts["true_matches"],
        "false_matches": counts["false_matches"],
        "reference_matches_including_unprocessed": counts["reference_matches"],
        "known_pair_precision": fraction(counts["true_matches"], precision_denominator),
        "reference_recall": fraction(counts["true_matches"], counts["reference_matches"]),
        "candidate_recall_on_labelled_reference": None if candidates_found is None else fraction(
            counts["retrieved_reference_matches"], counts["reference_matches"]
        ),
        "cluster_bootstrap_known_precision_95": confidence_interval(bootstrap, repetitions, seed),
        "pair_strata": strata,
        "suggested_pair_labels_complete": unknown == 0 and counts["ambiguous_suggestions"] == 0 and bool(subset),
        "analysis_scope_complete": all(item["status"] == "analyzed" for item in eligible),
        "bootstrap_work_limited": len(bootstrap) * repetitions > 10_000_000,
        "release_gate_decision": "not_evaluated",
    }


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", required=True)
    parser.add_argument("--labels", required=True)
    parser.add_argument("--groups", required=True)
    parser.add_argument("--candidates", help="Optional pre-reranking candidate-pair CSV")
    parser.add_argument("--split", choices=sorted(SPLITS), required=True)
    parser.add_argument("--bootstrap", type=int, default=1000)
    parser.add_argument("--seed", type=int, default=20261108)
    args = parser.parse_args(argv)
    if not 0 <= args.bootstrap <= 10_000:
        parser.error("--bootstrap must be between 0 and 10000")
    try:
        items = inventory(args.manifest)
        truth = labels(args.labels, items)
        suggested, group_count, group_members, pair_groups = groups(args.groups, items, args.split)
        candidate_pairs = candidates(args.candidates, items, args.split)
        report = evaluate(items, truth, suggested, candidate_pairs, args.split, args.bootstrap, args.seed, group_count, group_members, pair_groups)
    except InputError as error:
        print(f"Invalid benchmark input: {error}", file=sys.stderr)
        return 2
    except (OSError, UnicodeError, csv.Error):
        print("Could not read benchmark input as valid UTF-8 CSV", file=sys.stderr)
        return 2
    print(json.dumps(report, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
