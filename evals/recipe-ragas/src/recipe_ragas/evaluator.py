"""Evaluate eligible answers with bounded case concurrency and partial results."""

import asyncio
import math
from dataclasses import dataclass
from typing import Literal, Mapping

from recipe_ragas.metrics import MetricScorer, finite_score
from recipe_ragas.models import RagasInput


@dataclass(frozen=True)
class CaseScore:
    case: RagasInput
    metrics: dict[str, float]
    errors: dict[str, str]
    status: Literal["complete", "partial", "failed", "skipped"]

    @property
    def query_id(self) -> str:
        return self.case.query_id

    @classmethod
    def from_results(
        cls, case: RagasInput, results: dict[str, float], errors: dict[str, str],
    ) -> "CaseScore":
        if not case.eligible_for_semantic_metrics:
            return cls(case, {}, {}, "skipped")
        status = "failed" if not results else "partial" if errors else "complete"
        return cls(case, dict(results), dict(errors), status)


async def evaluate_cases(
    cases: list[RagasInput], metrics: Mapping[str, MetricScorer], concurrency: int,
    *, timeout_seconds: float = 60,
) -> list[CaseScore]:
    """Score in input order; each eligible case gets its own total time budget.

    The budget starts after semaphore admission, excludes queue time, and cancels
    the active scorer on expiry. Completed values survive and remaining metrics
    receive TimeoutError. External cancellation propagates to the caller.
    """
    if type(concurrency) is not int or not 1 <= concurrency <= 8:
        raise ValueError("concurrency must be an integer between 1 and 8")
    if (isinstance(timeout_seconds, bool) or not isinstance(timeout_seconds, (int, float))
            or not math.isfinite(timeout_seconds) or timeout_seconds <= 0):
        raise ValueError("sample timeout must be positive and finite")

    # 限制同时评分的样本数，而不是给每个指标都开一个不受控请求。
    # 样本拿到 semaphore 后才开始预算，排队等待不计入该样本的 Judge 超时。
    semaphore = asyncio.Semaphore(concurrency)

    async def score_one(case: RagasInput) -> CaseScore:
        # OOD/拒答等样本由 Java 规则另行评估，不混进语义指标均值。
        if not case.eligible_for_semantic_metrics:
            return CaseScore.from_results(case, {}, {})
        async with semaphore:
            results: dict[str, float] = {}
            errors: dict[str, str] = {}
            try:
                async with asyncio.timeout(timeout_seconds):
                    for name, metric in metrics.items():
                        try:
                            results[name] = finite_score(await metric.score(case))
                        except Exception as error:
                            # Never store provider response text, URLs, or credentials.
                            errors[name] = type(error).__name__
            except TimeoutError:
                for name in metrics:
                    if name not in results and name not in errors:
                        errors[name] = "TimeoutError"
            # 已完成的指标保留；超时或单项失败只记录错误类型，不把缺失值当作 0 分。
            return CaseScore.from_results(case, results, errors)

    return list(await asyncio.gather(*(score_one(case) for case in cases)))
