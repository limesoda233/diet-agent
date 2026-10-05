import asyncio
import json
from types import SimpleNamespace

import httpx
import pytest
from openai import AsyncOpenAI

from recipe_ragas.config import RagasConfig
from recipe_ragas.evaluator import evaluate_cases
from recipe_ragas.metrics import CollectionMetricScorer, build_metrics
from recipe_ragas.models import RagasInput


def case(query_id="rq-001", **overrides):
    return RagasInput.model_validate({
        "query_id": query_id, "dataset_version": "v2", "dataset_hash": "sha256:fixture",
        "generator_model": "generator", "split": "test", "slice": "exact_name",
        "user_input": "How to cook rice?", "response": "Boil rice in water.",
        "retrieved_contexts": ["Boil rice in water."], "retrieved_context_ids": ["rice"],
        "reference_context_ids": ["rice"], "answer_expectation": "ANSWER", "refused": False,
        "citations": ["rice"], "retrieval_latency_ms": 10, "generation_latency_ms": 20,
    } | overrides)


class FakeMetric:
    def __init__(self, value):
        self.value = value
        self.case_ids = []

    async def score(self, item):
        self.case_ids.append(item.query_id)
        if isinstance(self.value, Exception):
            raise self.value
        return self.value


async def test_scores_three_metrics_and_keeps_input_order_and_provenance():
    metrics = {"faithfulness": FakeMetric(0.8), "answer_relevancy": FakeMetric(0.7),
               "context_utilization": FakeMetric(0.6)}
    scores = await evaluate_cases([case("b"), case("a")], metrics, 2)
    assert [score.query_id for score in scores] == ["b", "a"]
    assert scores[0].metrics == {"faithfulness": 0.8, "answer_relevancy": 0.7,
                                 "context_utilization": 0.6}
    assert scores[0].status == "complete"
    assert scores[0].errors == {}
    assert scores[0].case.generator_model == "generator"


async def test_refusals_and_non_answers_are_skipped_without_fake_scores():
    metric = FakeMetric(0.8)
    scores = await evaluate_cases([
        case("answer"), case("refused", refused=True),
        case("non-answer", answer_expectation="REFUSE_UNSUPPORTED", refused=False),
    ], {"faithfulness": metric}, 2)
    assert metric.case_ids == ["answer"]
    assert [score.status for score in scores] == ["complete", "skipped", "skipped"]
    assert all(score.metrics == {} and score.errors == {} for score in scores[1:])


async def test_metric_failure_is_partial_sanitized_and_not_retried():
    failed = FakeMetric(RuntimeError("secret provider response"))
    scores = await evaluate_cases([case()], {"faithfulness": failed,
                                             "answer_relevancy": FakeMetric(0.7)}, 1)
    assert scores[0].status == "partial"
    assert scores[0].metrics == {"answer_relevancy": 0.7}
    assert scores[0].errors == {"faithfulness": "RuntimeError"}
    assert failed.case_ids == ["rq-001"]
    assert "secret" not in repr(scores[0])


async def test_total_failure_is_failed_and_does_not_abort_other_cases():
    class SelectiveMetric:
        async def score(self, item):
            if item.query_id == "bad":
                raise ValueError("private payload")
            return 0.5

    scores = await evaluate_cases([case("bad"), case("good")],
                                 {"faithfulness": SelectiveMetric()}, 2)
    assert [score.status for score in scores] == ["failed", "complete"]
    assert scores[0].metrics == {}
    assert scores[0].errors == {"faithfulness": "ValueError"}
    assert scores[1].metrics == {"faithfulness": 0.5}


async def test_concurrency_cap_allows_two_cases_but_never_three():
    active = 0
    peak = 0
    release = asyncio.Event()

    class BlockingMetric:
        async def score(self, item):
            nonlocal active, peak
            active += 1
            peak = max(peak, active)
            if active == 2:
                release.set()
            try:
                await asyncio.wait_for(release.wait(), 1)
                await asyncio.sleep(0)
                return 0.8
            finally:
                active -= 1

    scores = await evaluate_cases([case(str(i)) for i in range(6)],
                                 {"faithfulness": BlockingMetric()}, 2)
    assert peak == 2
    assert active == 0
    assert all(score.status == "complete" for score in scores)


@pytest.mark.parametrize("concurrency", [0, -1, 9, True, 1.5])
async def test_rejects_concurrency_outside_integer_one_to_eight(concurrency):
    with pytest.raises(ValueError, match="concurrency"):
        await evaluate_cases([case()], {"faithfulness": FakeMetric(0.5)}, concurrency)


@pytest.mark.parametrize("timeout", [0, -1, float("nan"), float("inf"), True])
async def test_rejects_invalid_sample_timeout(timeout):
    with pytest.raises(ValueError, match="timeout"):
        await evaluate_cases([case()], {"faithfulness": FakeMetric(0.5)}, 1,
                             timeout_seconds=timeout)


async def test_case_timeout_preserves_partial_scores_cancels_work_and_continues_batch():
    active = 0
    cancelled = []

    class HangingMetric:
        async def score(self, item):
            nonlocal active
            if item.query_id != "hang":
                return 0.6
            active += 1
            try:
                await asyncio.Event().wait()
            finally:
                active -= 1
                cancelled.append(item.query_id)

    not_started = FakeMetric(0.7)
    scores = await evaluate_cases([case("hang"), case("good")], {
        "faithfulness": FakeMetric(0.8), "answer_relevancy": HangingMetric(),
        "context_utilization": not_started,
    }, 1, timeout_seconds=0.03)
    assert scores[0].status == "partial"
    assert scores[0].metrics == {"faithfulness": 0.8}
    assert scores[0].errors == {"answer_relevancy": "TimeoutError",
                               "context_utilization": "TimeoutError"}
    assert scores[1].status == "complete"
    assert not_started.case_ids == ["good"]
    assert cancelled == ["hang"]
    assert active == 0


async def test_timeout_before_any_score_is_failed():
    class HangingMetric:
        async def score(self, item):
            await asyncio.Event().wait()

    scores = await evaluate_cases([case()], {"faithfulness": HangingMetric()}, 1,
                                 timeout_seconds=0.01)
    assert scores[0].status == "failed"
    assert scores[0].errors == {"faithfulness": "TimeoutError"}


@pytest.mark.parametrize("value", [float("nan"), float("inf"), -float("inf")])
async def test_nonfinite_scores_are_metric_failures(value):
    scores = await evaluate_cases([case()], {"faithfulness": FakeMetric(value)}, 1)
    assert scores[0].status == "failed"
    assert scores[0].errors == {"faithfulness": "ValueError"}


@pytest.mark.parametrize("uses_contexts", [True, False])
async def test_collection_adapter_uses_metric_specific_arguments_and_converts_result(uses_contexts):
    class FakeCollection:
        async def ascore(self, **kwargs):
            expected = {"user_input": "How to cook rice?", "response": "Boil rice in water."}
            if uses_contexts:
                expected["retrieved_contexts"] = ["Boil rice in water."]
            assert kwargs == expected
            return SimpleNamespace(value="0.8")

    value = await CollectionMetricScorer(FakeCollection(), uses_contexts=uses_contexts).score(case())
    assert value == 0.8
    assert isinstance(value, float)


@pytest.mark.parametrize("value", [float("nan"), float("inf")])
async def test_collection_adapter_rejects_nonfinite_metric_results(value):
    class FakeCollection:
        async def ascore(self, **kwargs):
            return SimpleNamespace(value=value)

    with pytest.raises(ValueError):
        await CollectionMetricScorer(FakeCollection(), uses_contexts=True).score(case())


def configured_metrics(monkeypatch, handler):
    # Keep real Ragas, Instructor and SDK; replace only external HTTP transport.
    monkeypatch.setenv("RAGAS_DO_NOT_TRACK", "true")
    clients = []

    class TestAsyncOpenAI(AsyncOpenAI):
        def __init__(self, **kwargs):
            super().__init__(**kwargs, http_client=httpx.AsyncClient(
                transport=httpx.MockTransport(handler)))
            clients.append(self)

    monkeypatch.setattr("openai.AsyncOpenAI", TestAsyncOpenAI)
    config = RagasConfig("https://judge.invalid/v1", "fake-test-token", "judge-model",
                         "https://embedding.invalid/v1", "fake-test-token", "embedding-model",
                         llm_timeout_seconds=7, embedding_timeout_seconds=9)
    return build_metrics(config), clients


async def test_real_ragas_043_collections_score_with_fake_http_provider(monkeypatch):
    requests = []
    answers = iter([
        {"statements": ["Boil rice in water."]},
        {"statements": [{"statement": "Boil rice in water.", "reason": "supported", "verdict": 1}]},
        *[{"question": "How to cook rice?", "noncommittal": 0}] * 3,
        {"reason": "useful", "verdict": 1},
    ])

    def handler(request):
        payload = json.loads(request.content)
        requests.append((request.url.host, payload["model"]))
        if request.url.path.endswith("/embeddings"):
            count = 1 if isinstance(payload["input"], str) else len(payload["input"])
            return httpx.Response(200, json={"object": "list", "model": "embedding-model",
                "data": [{"object": "embedding", "index": i, "embedding": [1.0, 0.0]}
                         for i in range(count)], "usage": {"prompt_tokens": 1, "total_tokens": 1}})
        return httpx.Response(200, json={"id": "fake", "object": "chat.completion", "created": 0,
            "model": "judge-model", "choices": [{"index": 0, "finish_reason": "stop",
            "message": {"role": "assistant", "content": json.dumps(next(answers))}}],
            "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2}})

    metrics, clients = configured_metrics(monkeypatch, handler)
    try:
        scores = await evaluate_cases([case()], metrics, 1)
        assert scores[0].status == "complete"
        assert scores[0].metrics == pytest.approx({"faithfulness": 1.0, "answer_relevancy": 1.0,
                                                  "context_utilization": 1.0})
        assert requests.count(("judge.invalid", "judge-model")) == 6
        assert requests.count(("embedding.invalid", "embedding-model")) == 2
        assert [client.max_retries for client in clients] == [0, 0]
        assert [client.timeout for client in clients] == [7, 9]
    finally:
        for client in clients:
            await client.close()


@pytest.mark.parametrize("failure", ["server", "malformed"])
async def test_real_judge_failure_never_retries_or_changes_model(monkeypatch, failure):
    requested_models = []

    def handler(request):
        requested_models.append(json.loads(request.content)["model"])
        if failure == "server":
            return httpx.Response(500, json={"error": {"message": "private provider payload"}})
        return httpx.Response(200, json={"id": "fake", "object": "chat.completion", "created": 0,
            "model": "judge-model", "choices": [{"index": 0, "finish_reason": "stop",
            "message": {"role": "assistant", "content": "not valid json"}}],
            "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2}})

    metrics, clients = configured_metrics(monkeypatch, handler)
    try:
        scores = await evaluate_cases([case()], {"faithfulness": metrics["faithfulness"]}, 1)
        assert scores[0].status == "failed"
        assert requested_models == ["judge-model"]
        assert "private" not in repr(scores[0].errors)
    finally:
        for client in clients:
            await client.close()
