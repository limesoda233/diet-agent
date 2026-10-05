import json
from pathlib import Path

import pytest

from recipe_ragas.__main__ import main


def payload(query_id="a", **changes):
    return {
        "query_id": query_id, "dataset_version": "v2", "dataset_hash": "sha256:frozen",
        "generator_model": "generator", "split": "test", "slice": "exact_name",
        "user_input": "How to cook rice?", "response": "Boil rice.",
        "retrieved_contexts": ["Boil rice."], "retrieved_context_ids": ["rice"],
        "reference_context_ids": ["rice"], "answer_expectation": "ANSWER",
        "refused": False, "citations": ["S1"], "retrieval_latency_ms": 1,
        "generation_latency_ms": 2,
    } | changes


def paths(tmp_path: Path, rows):
    input_file = tmp_path / "input.jsonl"
    java_report = tmp_path / "java.json"
    output = tmp_path / "out"
    input_file.write_text("\n".join(json.dumps(row) for row in rows) + "\n", encoding="utf-8")
    java_report.write_text('{"metadata":{"datasetHash":"sha256:frozen",'
                           '"datasetVersion":"v2"}}',
                           encoding="utf-8")
    return input_file, java_report, output


def args(input_file: Path, java_report: Path, output: Path):
    return ["--input", str(input_file), "--java-report", str(java_report),
            "--output-dir", str(output)]


def configured(monkeypatch):
    for key, value in {
        "RAGAS_LLM_BASE_URL": "https://judge.invalid/v1", "RAGAS_LLM_API_KEY": "fake",
        "RAGAS_LLM_MODEL": "judge", "RAGAS_EMBEDDING_BASE_URL": "https://embed.invalid/v1",
        "RAGAS_EMBEDDING_API_KEY": "fake", "RAGAS_EMBEDDING_MODEL": "embed",
    }.items():
        monkeypatch.setenv(key, value)


def test_missing_config_exits_before_metric_creation_or_output(tmp_path: Path, monkeypatch,
                                                               capsys):
    input_file, java_report, output = paths(tmp_path, [payload()])
    monkeypatch.delenv("RAGAS_LLM_API_KEY", raising=False)
    monkeypatch.setattr("recipe_ragas.__main__.build_metrics", lambda _: pytest.fail("built"))
    assert main(args(input_file, java_report, output)) == 2
    assert not output.exists()
    assert "RAGAS_LLM_API_KEY" in capsys.readouterr().err


@pytest.mark.parametrize("rows", [
    [payload("a"), payload("a")],
    [payload("a"), payload("b", dataset_hash="sha256:other")],
    [payload("a", unknown_field="invalid")],
])
def test_invalid_jsonl_exits_before_metric_creation(tmp_path: Path, monkeypatch, rows):
    input_file, java_report, output = paths(tmp_path, rows)
    monkeypatch.setattr("recipe_ragas.__main__.build_metrics", lambda _: pytest.fail("built"))
    assert main(args(input_file, java_report, output)) == 2
    assert not output.exists()


def test_missing_java_report_is_input_error(tmp_path: Path):
    input_file, java_report, output = paths(tmp_path, [payload()])
    java_report.unlink()
    assert main(args(input_file, java_report, output)) == 2
    assert not output.exists()


def test_mixed_generator_models_exit_before_metric_creation(tmp_path: Path, monkeypatch):
    input_file, java_report, output = paths(tmp_path, [
        payload("a", generator_model="judge"),
        payload("b", generator_model="other-model"),
    ])
    configured(monkeypatch)
    monkeypatch.setattr("recipe_ragas.__main__.build_metrics", lambda _: pytest.fail("built"))
    assert main(args(input_file, java_report, output)) == 2
    assert not output.exists()


@pytest.mark.parametrize("metadata", [
    None,
    {},
    {"datasetHash": "sha256:frozen"},
    {"datasetVersion": "v2"},
    {"datasetHash": "sha256:other", "datasetVersion": "v2"},
    {"datasetHash": "sha256:frozen", "datasetVersion": "v1"},
    {"datasetHash": " ", "datasetVersion": "v2"},
    ["not an object"],
])
def test_missing_or_mismatched_java_dataset_exits_before_scoring(
        tmp_path: Path, monkeypatch, metadata):
    input_file, java_report, output = paths(tmp_path, [payload()])
    java_report.write_text(json.dumps({"metadata": metadata}), encoding="utf-8")
    configured(monkeypatch)
    monkeypatch.setattr("recipe_ragas.__main__.build_metrics", lambda _: pytest.fail("built"))
    assert main(args(input_file, java_report, output)) == 2
    assert not output.exists()


@pytest.mark.parametrize("source,destination", [
    ("input", "ragas-report.json"),
    ("input", "ragas-cases.csv"),
    ("java", "ragas-report.json"),
    ("java", "ragas-cases.csv"),
    ("input", "ragas-report.json.tmp"),
    ("input", "ragas-cases.csv.tmp"),
    ("java", "ragas-report.json.tmp"),
    ("java", "ragas-cases.csv.tmp"),
])
def test_output_cannot_replace_an_input_file(tmp_path: Path, monkeypatch,
                                             source: str, destination: str):
    input_file, java_report, output = paths(tmp_path, [payload()])
    output.mkdir()
    collision = output / destination
    original = input_file.read_bytes() if source == "input" else java_report.read_bytes()
    collision.write_bytes(original)
    if source == "input":
        input_file = collision
    else:
        java_report = collision
    configured(monkeypatch)
    monkeypatch.setattr("recipe_ragas.__main__.build_metrics", lambda _: pytest.fail("built"))
    assert main(args(input_file, java_report, output)) == 2
    assert collision.read_bytes() == original


def test_fake_metric_path_writes_reports_and_single_case_failure_is_partial(
        tmp_path: Path, monkeypatch):
    input_file, java_report, output = paths(tmp_path, [payload("good"), payload("bad")])
    for key, value in {
        "RAGAS_LLM_BASE_URL": "https://judge.invalid/v1", "RAGAS_LLM_API_KEY": "fake",
        "RAGAS_LLM_MODEL": "judge", "RAGAS_EMBEDDING_BASE_URL": "https://embed.invalid/v1",
        "RAGAS_EMBEDDING_API_KEY": "fake", "RAGAS_EMBEDDING_MODEL": "embed",
    }.items():
        monkeypatch.setenv(key, value)

    class FakeMetric:
        async def score(self, case):
            if case.query_id == "bad":
                raise RuntimeError("private provider response")
            return 0.8

    monkeypatch.setattr("recipe_ragas.__main__.build_metrics",
                        lambda _: {"faithfulness": FakeMetric()})
    assert main(args(input_file, java_report, output)) == 0
    report = json.loads((output / "ragas-report.json").read_text(encoding="utf-8"))
    assert report["status"] == "partial"
    assert report["case_status_counts"]["failed"] == 1
    assert "private provider response" not in (output / "ragas-report.json").read_text()


def test_all_judge_metrics_fail_exits_one_but_writes_report(tmp_path: Path, monkeypatch):
    input_file, java_report, output = paths(tmp_path, [payload()])
    for key, value in {
        "RAGAS_LLM_BASE_URL": "https://judge.invalid/v1", "RAGAS_LLM_API_KEY": "fake",
        "RAGAS_LLM_MODEL": "judge", "RAGAS_EMBEDDING_BASE_URL": "https://embed.invalid/v1",
        "RAGAS_EMBEDDING_API_KEY": "fake", "RAGAS_EMBEDDING_MODEL": "embed",
    }.items():
        monkeypatch.setenv(key, value)

    class FailedMetric:
        async def score(self, _):
            raise RuntimeError("private provider response")

    monkeypatch.setattr("recipe_ragas.__main__.build_metrics",
                        lambda _: {"faithfulness": FailedMetric()})
    assert main(args(input_file, java_report, output)) == 1
    assert json.loads((output / "ragas-report.json").read_text())["status"] == "failed"


def test_batch_exception_exits_one_without_writing_report(tmp_path: Path, monkeypatch):
    input_file, java_report, output = paths(tmp_path, [payload()])
    for key, value in {
        "RAGAS_LLM_BASE_URL": "https://judge.invalid/v1", "RAGAS_LLM_API_KEY": "fake",
        "RAGAS_LLM_MODEL": "judge", "RAGAS_EMBEDDING_BASE_URL": "https://embed.invalid/v1",
        "RAGAS_EMBEDDING_API_KEY": "fake", "RAGAS_EMBEDDING_MODEL": "embed",
    }.items():
        monkeypatch.setenv(key, value)

    def failed_build(_):
        raise RuntimeError("private provider response")

    monkeypatch.setattr("recipe_ragas.__main__.build_metrics", failed_build)
    assert main(args(input_file, java_report, output)) == 1
    assert not output.exists()
