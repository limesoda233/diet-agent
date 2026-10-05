"""Small scoring boundary around Ragas 0.4.3 modern collection metrics."""

import math
from typing import Any, Protocol

from recipe_ragas.config import RagasConfig
from recipe_ragas.models import RagasInput


class MetricScorer(Protocol):
    async def score(self, case: RagasInput) -> float: ...


class CollectionMetricScorer:
    def __init__(self, metric: Any, *, uses_contexts: bool):
        self.metric = metric
        self.uses_contexts = uses_contexts

    async def score(self, case: RagasInput) -> float:
        arguments = {"user_input": case.user_input, "response": case.response}
        if self.uses_contexts:
            arguments["retrieved_contexts"] = case.retrieved_contexts
        result = await self.metric.ascore(**arguments)
        return finite_score(result.value)


def finite_score(value: Any) -> float:
    score = float(value)
    if not math.isfinite(score):
        raise ValueError("metric score must be finite")
    return score


def build_metrics(config: RagasConfig) -> dict[str, MetricScorer]:
    # Lazy imports keep fake-scorer evaluation independent of provider initialization.
    from openai import AsyncOpenAI
    from ragas.embeddings.base import embedding_factory
    from ragas.llms import llm_factory
    from ragas.metrics.collections import AnswerRelevancy, ContextUtilization, Faithfulness

    llm_client = AsyncOpenAI(
        api_key=config.llm_api_key, base_url=config.llm_base_url,
        timeout=config.llm_timeout_seconds, max_retries=0,
    )
    embedding_client = AsyncOpenAI(
        api_key=config.embedding_api_key, base_url=config.embedding_base_url,
        timeout=config.embedding_timeout_seconds, max_retries=0,
    )
    # The locked Instructor version counts retries after the initial attempt.
    # Disable structured-output repair retries as well as SDK HTTP retries.
    llm = llm_factory(config.llm_model, provider="openai", client=llm_client, max_retries=0)
    embeddings = embedding_factory(
        "openai", model=config.embedding_model, client=embedding_client,
    )
    return {
        "faithfulness": CollectionMetricScorer(Faithfulness(llm=llm), uses_contexts=True),
        "answer_relevancy": CollectionMetricScorer(
            AnswerRelevancy(llm=llm, embeddings=embeddings), uses_contexts=False,
        ),
        "context_utilization": CollectionMetricScorer(
            ContextUtilization(llm=llm), uses_contexts=True,
        ),
    }
