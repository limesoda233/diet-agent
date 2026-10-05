import json
from pathlib import Path

import pytest
from pydantic import ValidationError

from recipe_ragas.models import RagasInput


def valid_case() -> dict[str, object]:
    return {
        "query_id": "rq-001",
        "dataset_version": "v2",
        "dataset_hash": "sha256:abc123",
        "generator_model": "generator-model",
        "split": "test",
        "slice": "exact_name",
        "user_input": "如何做宫保鸡丁？",
        "response": "按证据中的步骤烹饪。",
        "retrieved_contexts": ["菜谱步骤一", "菜谱步骤二"],
        "retrieved_context_ids": ["recipe-a", "recipe-b"],
        "reference_context_ids": ["recipe-a"],
        "answer_expectation": "ANSWER",
        "refused": False,
        "citations": ["recipe-a"],
        "retrieval_latency_ms": 12,
        "generation_latency_ms": 34,
    }


def test_parses_java_jsonl_contract_and_preserves_generator_model() -> None:
    case = RagasInput.model_validate_json(json.dumps(valid_case(), ensure_ascii=False))

    assert case.generator_model == "generator-model"
    assert case.eligible_for_semantic_metrics


@pytest.mark.parametrize("missing", ["dataset_hash", "generator_model", "retrieved_contexts"])
def test_rejects_missing_required_contract_fields(missing: str) -> None:
    payload = valid_case()
    del payload[missing]
    with pytest.raises(ValidationError):
        RagasInput.model_validate_json(json.dumps(payload))


def test_rejects_unknown_fields() -> None:
    payload = valid_case() | {"unexpected": "value"}
    with pytest.raises(ValidationError, match="unexpected"):
        RagasInput.model_validate_json(json.dumps(payload))


def test_rejects_blank_hash_and_contexts() -> None:
    with pytest.raises(ValidationError, match="dataset_hash"):
        RagasInput.model_validate_json(json.dumps(valid_case() | {"dataset_hash": "  "}))
    with pytest.raises(ValidationError, match="retrieved_contexts"):
        RagasInput.model_validate_json(json.dumps(valid_case() | {"retrieved_contexts": ["  "]}))


def test_rejects_duplicate_contexts_and_ids() -> None:
    with pytest.raises(ValidationError, match="unique"):
        RagasInput.model_validate_json(
            json.dumps(valid_case() | {"retrieved_contexts": ["same", "same"]})
        )
    with pytest.raises(ValidationError, match="unique"):
        RagasInput.model_validate_json(
            json.dumps(valid_case() | {"retrieved_context_ids": ["same", "same"]})
        )


@pytest.mark.parametrize("field", ["retrieval_latency_ms", "generation_latency_ms"])
def test_rejects_negative_latency(field: str) -> None:
    with pytest.raises(ValidationError, match=field):
        RagasInput.model_validate_json(json.dumps(valid_case() | {field: -1}))


def test_rejects_unsupported_answer_expectation() -> None:
    with pytest.raises(ValidationError, match="answer_expectation"):
        RagasInput.model_validate_json(
            json.dumps(valid_case() | {"answer_expectation": "MAYBE"})
        )


def test_requires_evidence_for_successful_non_refused_answer() -> None:
    with pytest.raises(ValidationError, match="evidence"):
        RagasInput.model_validate_json(
            json.dumps(valid_case() | {"retrieved_contexts": [], "retrieved_context_ids": []})
        )


def test_refusal_and_non_answer_are_not_semantic_metric_eligible() -> None:
    refused = RagasInput.model_validate_json(json.dumps(valid_case() | {"refused": True}))
    non_answer = RagasInput.model_validate_json(
        json.dumps(valid_case() | {"answer_expectation": "REFUSE_OUT_OF_CORPUS", "refused": True,
                                  "response": "无法回答", "retrieved_contexts": [],
                                  "retrieved_context_ids": []})
    )
    assert not refused.eligible_for_semantic_metrics
    assert not non_answer.eligible_for_semantic_metrics


@pytest.mark.parametrize("expectation", ["ANSWER", "REFUSE_OUT_OF_CORPUS", "REFUSE_UNSUPPORTED"])
def test_java_answer_expectation_contract_fixture(expectation: str) -> None:
    fixture = Path(__file__).parent / "fixtures" / "java-answer-expectations.jsonl"
    rows = [RagasInput.model_validate_json(line) for line in fixture.read_text(encoding="utf-8").splitlines()]
    assert {row.answer_expectation for row in rows} == {
        "ANSWER", "REFUSE_OUT_OF_CORPUS", "REFUSE_UNSUPPORTED"
    }
    row = next(row for row in rows if row.answer_expectation == expectation)
    assert row.eligible_for_semantic_metrics is (expectation == "ANSWER")


def test_rejects_wrong_json_types_instead_of_coercing() -> None:
    payload = valid_case() | {"refused": "false", "retrieval_latency_ms": "12"}
    with pytest.raises(ValidationError):
        RagasInput.model_validate_json(json.dumps(payload))
