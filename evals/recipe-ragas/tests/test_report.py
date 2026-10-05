import csv
import hashlib
import json
from pathlib import Path

import pytest

from recipe_ragas.config import RagasConfig
from recipe_ragas.evaluator import CaseScore
from recipe_ragas.models import RagasInput
from recipe_ragas.report import build_report, write_report


def sample(query_id: str, slice_name: str, *, generator_model: str = "judge-model",
           refused: bool = False) -> RagasInput:
    return RagasInput.model_validate({
        "query_id": query_id, "dataset_version": "v2", "dataset_hash": "sha256:frozen",
        "generator_model": generator_model, "split": "test", "slice": slice_name,
        "user_input": "How to cook rice?", "response": "Boil rice.",
        "retrieved_contexts": ["Boil rice."], "retrieved_context_ids": ["rice"],
        "reference_context_ids": ["rice"], "answer_expectation": "ANSWER",
        "refused": refused, "citations": ["S1"], "retrieval_latency_ms": 1,
        "generation_latency_ms": 2,
    })


def config() -> RagasConfig:
    return RagasConfig("https://judge.example/private/v1", "secret-key", "judge-model",
                       "https://embed.example/private/v1", "embedding-key", "embed-model")


def sources(tmp_path: Path) -> tuple[Path, Path]:
    input_file = tmp_path / "input.jsonl"
    java_report = tmp_path / "java.json"
    input_file.write_text('{"frozen":true}\n', encoding="utf-8")
    java_report.write_text('{"metadata":{"corpusCommit":"abc","datasetHash":"sha256:frozen",'
                           '"datasetVersion":"v2"}}', encoding="utf-8")
    return input_file, java_report


def test_aggregates_finite_values_failures_slices_and_model_provenance(tmp_path: Path):
    input_file, java_report = sources(tmp_path)
    scores = [
        CaseScore(sample("a", "exact_name"), {"faithfulness": 0.8,
                  "answer_relevancy": 0.7, "context_utilization": 0.6}, {}, "complete"),
        CaseScore(sample("b", "constraint"),
                  {"faithfulness": 0.7, "context_utilization": float("nan")},
                  {"answer_relevancy": "RuntimeError"}, "partial"),
        CaseScore(sample("c", "constraint", refused=True), {}, {}, "skipped"),
    ]

    report = build_report(scores, config(), input_file, java_report)

    assert report["status"] == "partial"
    assert report["metrics"]["faithfulness"] == {"mean": pytest.approx(0.75),
                                                "valid_count": 2, "failure_count": 0}
    assert report["metrics"]["answer_relevancy"]["failure_count"] == 1
    assert report["metrics"]["context_utilization"]["failure_count"] == 1
    assert report["slices"]["exact_name"]["faithfulness"] == pytest.approx(0.8)
    assert report["slices"]["constraint"]["faithfulness"] == pytest.approx(0.7)
    assert report["input_sha256"] == hashlib.sha256(input_file.read_bytes()).hexdigest()
    assert report["java_report_sha256"] == hashlib.sha256(java_report.read_bytes()).hexdigest()
    assert report["total_input_count"] == 3
    assert report["eligible_count"] == 2
    assert report["excluded_refusal_count"] == 1
    assert report["case_status_counts"] == {"complete": 1, "partial": 1,
                                             "failed": 0, "skipped": 1}
    assert report["same_model_judge"] is True
    assert report["same_model_judge_count"] == 2
    assert report["cases"][0]["generator_model"] == "judge-model"
    assert report["cases"][0]["same_model_judge"] is True
    assert report["cases"][1]["same_model_judge"] is True
    assert report["cases"][1]["metrics"] == {"faithfulness": 0.7}
    assert report["cases"][1]["errors"] == {"answer_relevancy": "RuntimeError",
                                             "context_utilization": "NonFiniteScore"}
    serialized = json.dumps(report)
    assert "secret-key" not in serialized
    assert "embedding-key" not in serialized
    assert "/private/v1" not in serialized


def test_report_rejects_mixed_generator_models(tmp_path: Path):
    input_file, java_report = sources(tmp_path)
    scores = [CaseScore(sample("a", "exact_name"), {"faithfulness": 0.8}, {}, "partial"),
              CaseScore(sample("b", "constraint", generator_model="other-model"),
                        {"faithfulness": 0.7}, {}, "partial")]
    with pytest.raises(ValueError, match="generator_model"):
        build_report(scores, config(), input_file, java_report)


def test_report_marks_distinct_single_generator_as_independent(tmp_path: Path):
    input_file, java_report = sources(tmp_path)
    score = CaseScore(sample("a", "exact_name", generator_model="other-model"),
                      {"faithfulness": 0.8}, {}, "partial")
    report = build_report([score], config(), input_file, java_report)
    assert report["same_model_judge"] is False
    assert report["same_model_judge_count"] == 0
    assert report["cases"][0]["same_model_judge"] is False


def test_zero_successful_metric_values_is_failed(tmp_path: Path):
    input_file, java_report = sources(tmp_path)
    scores = [CaseScore(sample("a", "exact_name"), {}, {"faithfulness": "TimeoutError"},
                        "failed")]
    report = build_report(scores, config(), input_file, java_report)
    assert report["status"] == "failed"
    assert report["metrics"]["faithfulness"] == {"mean": None, "valid_count": 0,
                                                "failure_count": 1}
    assert report["slices"]["exact_name"]["faithfulness"] is None


def test_writes_stable_json_csv_and_keeps_previous_files_on_replace_error(
        tmp_path: Path, monkeypatch):
    input_file, java_report = sources(tmp_path)
    report = build_report([CaseScore(sample("a", "exact_name"),
                                     {"faithfulness": 0.8}, {}, "complete")],
                          config(), input_file, java_report)
    output = tmp_path / "out"
    write_report(report, output)
    rows = list(csv.DictReader((output / "ragas-cases.csv").open(encoding="utf-8", newline="")))
    assert list(rows[0]) == ["query_id", "split", "slice", "status", "faithfulness",
                             "answer_relevancy", "context_utilization", "error_metrics"]
    assert rows[0]["faithfulness"] == "0.8"
    assert json.loads((output / "ragas-report.json").read_text(encoding="utf-8")) == report
    old_json = (output / "ragas-report.json").read_bytes()
    old_csv = (output / "ragas-cases.csv").read_bytes()

    def unsupported(*_args):
        raise OSError("atomic replacement unsupported")

    monkeypatch.setattr("recipe_ragas.report.os.replace", unsupported)
    with pytest.raises(OSError, match="atomic replacement unsupported"):
        write_report(report, output)
    assert (output / "ragas-report.json").read_bytes() == old_json
    assert (output / "ragas-cases.csv").read_bytes() == old_csv
    assert not list(output.glob("*.tmp"))


def test_csv_records_sanitized_metric_failure_reasons(tmp_path: Path):
    input_file, java_report = sources(tmp_path)
    report = build_report([CaseScore(sample("a", "exact_name"), {},
                                     {"faithfulness": "RuntimeError(private response)"},
                                     "failed")], config(), input_file, java_report)
    write_report(report, tmp_path / "out")
    row = next(csv.DictReader((tmp_path / "out" / "ragas-cases.csv").open(
        encoding="utf-8", newline="")))
    assert "faithfulness:MetricError" in row["error_metrics"]
    assert "private response" not in row["error_metrics"]
