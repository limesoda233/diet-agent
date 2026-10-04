from dataclasses import FrozenInstanceError
from pathlib import Path

import pytest

from recipe_ragas.config import RagasConfig, validate_paths


def valid_env() -> dict[str, str]:
    return {
        "RAGAS_LLM_BASE_URL": "https://judge.example/v1",
        "RAGAS_LLM_API_KEY": "never-print-this-key",
        "RAGAS_LLM_MODEL": "judge-model",
        "RAGAS_EMBEDDING_BASE_URL": "http://embed.example/v1",
        "RAGAS_EMBEDDING_API_KEY": "embedding-key",
        "RAGAS_EMBEDDING_MODEL": "embed-model",
    }


@pytest.mark.parametrize(
    "missing",
    ["RAGAS_LLM_BASE_URL", "RAGAS_LLM_API_KEY", "RAGAS_LLM_MODEL"],
)
def test_requires_llm_settings(missing: str) -> None:
    env = valid_env()
    del env[missing]
    with pytest.raises(ValueError, match=missing):
        RagasConfig.from_env(env)


@pytest.mark.parametrize(
    "missing",
    ["RAGAS_EMBEDDING_BASE_URL", "RAGAS_EMBEDDING_API_KEY", "RAGAS_EMBEDDING_MODEL"],
)
def test_requires_embedding_settings(missing: str) -> None:
    env = valid_env()
    del env[missing]
    with pytest.raises(ValueError, match=missing):
        RagasConfig.from_env(env)


def test_defaults_and_sanitizes_judge_metadata_and_repr() -> None:
    config = RagasConfig.from_env(valid_env())

    assert config.concurrency == 2
    assert config.llm_timeout_seconds == 60
    assert config.embedding_timeout_seconds == 60
    assert config.metadata() == {
        "llm_host": "judge.example",
        "llm_model": "judge-model",
        "embedding_host": "embed.example",
        "embedding_model": "embed-model",
        "concurrency": 2,
    }
    assert "never-print-this-key" not in repr(config)
    assert "embedding-key" not in repr(config)
    assert "judge.example" not in repr(config)


@pytest.mark.parametrize("value", ["0", "9", "many"])
def test_rejects_invalid_concurrency(value: str) -> None:
    env = valid_env() | {"RAGAS_CONCURRENCY": value}
    with pytest.raises(ValueError, match="RAGAS_CONCURRENCY"):
        RagasConfig.from_env(env)


@pytest.mark.parametrize("name", ["RAGAS_LLM_TIMEOUT_SECONDS", "RAGAS_EMBEDDING_TIMEOUT_SECONDS"])
@pytest.mark.parametrize("value", ["0", "-1", "bad"])
def test_rejects_invalid_timeout(name: str, value: str) -> None:
    with pytest.raises(ValueError, match=name):
        RagasConfig.from_env(valid_env() | {name: value})


def test_rejects_invalid_service_urls() -> None:
    for name in ("RAGAS_LLM_BASE_URL", "RAGAS_EMBEDDING_BASE_URL"):
        with pytest.raises(ValueError, match=name):
            RagasConfig.from_env(valid_env() | {name: "file:///secret"})


def test_config_is_immutable() -> None:
    config = RagasConfig.from_env(valid_env())
    with pytest.raises(FrozenInstanceError):
        config.concurrency = 3  # type: ignore[misc]


def test_validates_cli_input_report_and_output_paths(tmp_path: Path) -> None:
    input_path = tmp_path / "input.jsonl"
    report_path = tmp_path / "report.json"
    output_dir = tmp_path / "output"
    input_path.write_text("{}\n", encoding="utf-8")
    report_path.write_text("{}", encoding="utf-8")

    assert validate_paths(input_path, report_path, output_dir) == (
        input_path.resolve(), report_path.resolve(), output_dir.resolve()
    )


def test_rejects_missing_input_or_java_report(tmp_path: Path) -> None:
    with pytest.raises(ValueError, match="input"):
        validate_paths(tmp_path / "missing.jsonl", tmp_path / "report.json", tmp_path / "out")

    input_path = tmp_path / "input.jsonl"
    input_path.write_text("{}\n", encoding="utf-8")
    with pytest.raises(ValueError, match="Java report"):
        validate_paths(input_path, tmp_path / "missing.json", tmp_path / "out")


def test_rejects_output_path_that_is_a_file(tmp_path: Path) -> None:
    input_path = tmp_path / "input.jsonl"
    report_path = tmp_path / "report.json"
    output_path = tmp_path / "output"
    input_path.write_text("{}\n", encoding="utf-8")
    report_path.write_text("{}", encoding="utf-8")
    output_path.write_text("file", encoding="utf-8")

    with pytest.raises(ValueError, match="output directory"):
        validate_paths(input_path, report_path, output_path)
