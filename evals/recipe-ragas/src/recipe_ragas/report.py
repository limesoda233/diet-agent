"""Reproducible summaries of frozen recipe answer evaluations."""

import csv
import hashlib
import json
import math
import os
import re
from collections import Counter, defaultdict
from datetime import datetime, timezone
from importlib.metadata import version
from pathlib import Path
from statistics import fmean
from typing import Any, Sequence

from recipe_ragas.config import RagasConfig
from recipe_ragas.evaluator import CaseScore


METRIC_NAMES = ("faithfulness", "answer_relevancy", "context_utilization")
CSV_COLUMNS = ("query_id", "split", "slice", "status", *METRIC_NAMES, "error_metrics")
_ERROR_NAME = re.compile(r"[A-Za-z_][A-Za-z_0-9]*\Z")


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _safe_error(value: str) -> str:
    return value if _ERROR_NAME.fullmatch(value) else "MetricError"


def build_report(scores: Sequence[CaseScore], config: RagasConfig,
                 input_file: str | Path, java_report_file: str | Path) -> dict[str, Any]:
    """Aggregate finite values only; keep skipped and failed cases visible."""
    if not scores:
        raise ValueError("Ragas report requires at least one input case")
    dataset_versions = {score.case.dataset_version for score in scores}
    dataset_hashes = {score.case.dataset_hash for score in scores}
    generator_models = {score.case.generator_model for score in scores}
    if len(dataset_versions) != 1 or len(dataset_hashes) != 1:
        raise ValueError("Ragas cases must share a dataset version and hash")
    if len(generator_models) != 1:
        raise ValueError("Ragas cases must share one generator_model")
    generator_model = next(iter(generator_models))

    values: dict[str, list[float]] = defaultdict(list)
    slice_values: dict[str, dict[str, list[float]]] = defaultdict(lambda: defaultdict(list))
    failures: Counter[str] = Counter()
    error_counts: Counter[str] = Counter()
    status_counts: Counter[str] = Counter()
    case_rows: list[dict[str, Any]] = []
    same_model_count = 0
    eligible_count = 0

    for score in scores:
        case = score.case
        eligible = case.eligible_for_semantic_metrics
        same_model = case.generator_model == config.llm_model
        if eligible:
            eligible_count += 1
            same_model_count += int(same_model)
            slice_values[case.slice]
        clean_metrics: dict[str, float] = {}
        clean_errors: dict[str, str] = {}
        if eligible:
            for name in METRIC_NAMES:
                raw = score.metrics.get(name)
                if raw is not None and isinstance(raw, (int, float)) and math.isfinite(raw):
                    clean_metrics[name] = float(raw)
                    values[name].append(float(raw))
                    slice_values[case.slice][name].append(float(raw))
                else:
                    error = ("NonFiniteScore" if raw is not None else
                             _safe_error(score.errors[name]) if name in score.errors else
                             "MissingScore")
                    clean_errors[name] = error
                    failures[name] += 1
                    error_counts[error] += 1
        status = ("skipped" if not eligible else "failed" if not clean_metrics else
                  "partial" if clean_errors else "complete")
        status_counts[status] += 1
        case_rows.append({
            "query_id": case.query_id, "split": case.split, "slice": case.slice,
            "status": status, "generator_model": case.generator_model,
            "same_model_judge": same_model, "metrics": clean_metrics, "errors": clean_errors,
        })

    successful = sum(len(values[name]) for name in METRIC_NAMES)
    failed = sum(failures.values())
    status = "failed" if successful == 0 else "partial" if failed else "complete"
    java_path = Path(java_report_file)
    metadata = validate_java_report(java_path, next(iter(dataset_hashes)),
                                    next(iter(dataset_versions)))
    return {
        "status": status,
        "generated_at_utc": datetime.now(timezone.utc).isoformat(),
        "ragas_version": version("ragas"),
        "judge": config.metadata(),
        "same_model_judge": generator_model == config.llm_model,
        "same_model_judge_count": same_model_count,
        "dataset_version": next(iter(dataset_versions)),
        "dataset_hash": next(iter(dataset_hashes)),
        "corpus_commit": metadata.get("corpusCommit"),
        "input_sha256": _sha256(Path(input_file)),
        "java_report_path": str(java_path),
        "java_report_sha256": _sha256(java_path),
        "total_input_count": len(scores),
        "eligible_count": eligible_count,
        "excluded_refusal_count": len(scores) - eligible_count,
        "case_status_counts": {name: status_counts[name]
                               for name in ("complete", "partial", "failed", "skipped")},
        "metric_error_counts": dict(sorted(error_counts.items())),
        "metrics": {name: {"mean": fmean(values[name]) if values[name] else None,
                           "valid_count": len(values[name]), "failure_count": failures[name]}
                    for name in METRIC_NAMES},
        "slices": {slice_name: {name: fmean(by_metric[name]) if by_metric[name] else None
                                for name in METRIC_NAMES}
                   for slice_name, by_metric in sorted(slice_values.items())},
        "cases": case_rows,
    }


def validate_java_report(path: Path, dataset_hash: str, dataset_version: str) -> dict[str, Any]:
    """Require the deterministic report to identify the same frozen dataset."""
    data = json.loads(path.read_text(encoding="utf-8"))
    metadata = data.get("metadata") if isinstance(data, dict) else None
    if not isinstance(metadata, dict):
        raise ValueError("Java report metadata must be an object")
    for name, expected in (("datasetHash", dataset_hash), ("datasetVersion", dataset_version)):
        actual = metadata.get(name)
        if not isinstance(actual, str) or not actual.strip():
            raise ValueError(f"Java report metadata.{name} is required")
        if actual != expected:
            raise ValueError(f"Java report metadata.{name} differs from Ragas input")
    return metadata


def write_report(report: dict[str, Any], output_dir: str | Path) -> tuple[Path, Path]:
    """Stage both files alongside their destinations, then atomically replace each."""
    directory = Path(output_dir)
    directory.mkdir(parents=True, exist_ok=True)
    json_path = directory / "ragas-report.json"
    csv_path = directory / "ragas-cases.csv"
    json_temp = json_path.with_name(json_path.name + ".tmp")
    csv_temp = csv_path.with_name(csv_path.name + ".tmp")
    try:
        with json_temp.open("w", encoding="utf-8", newline="\n") as output:
            json.dump(report, output, ensure_ascii=False, indent=2, allow_nan=False)
            output.write("\n")
            output.flush()
        with csv_temp.open("w", encoding="utf-8", newline="") as output:
            writer = csv.DictWriter(output, fieldnames=CSV_COLUMNS)
            writer.writeheader()
            for case in report["cases"]:
                writer.writerow({
                    "query_id": case["query_id"], "split": case["split"],
                    "slice": case["slice"], "status": case["status"],
                    **{name: case["metrics"].get(name, "") for name in METRIC_NAMES},
                    "error_metrics": ";".join(
                        f"{name}:{reason}" for name, reason in case["errors"].items()),
                })
            output.flush()
        os.replace(csv_temp, csv_path)
        os.replace(json_temp, json_path)
    finally:
        json_temp.unlink(missing_ok=True)
        csv_temp.unlink(missing_ok=True)
    return json_path, csv_path
