from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator


class RagasInput(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)

    query_id: str
    dataset_version: str
    dataset_hash: str
    generator_model: str
    split: str
    slice: str
    user_input: str
    response: str
    retrieved_contexts: list[str]
    retrieved_context_ids: list[str]
    reference_context_ids: list[str]
    answer_expectation: Literal["ANSWER", "REFUSE"]
    refused: bool
    citations: list[str]
    retrieval_latency_ms: int = Field(ge=0)
    generation_latency_ms: int = Field(ge=0)

    @field_validator(
        "query_id", "dataset_version", "dataset_hash", "generator_model", "split", "slice",
        "user_input"
    )
    @classmethod
    def require_nonblank_text(cls, value: str) -> str:
        if not value.strip():
            raise ValueError("must not be blank")
        return value

    @field_validator("retrieved_contexts", "retrieved_context_ids", "reference_context_ids", "citations")
    @classmethod
    def require_unique_nonblank_values(cls, values: list[str]) -> list[str]:
        if any(not value.strip() for value in values):
            raise ValueError("values must not be blank")
        if len(set(values)) != len(values):
            raise ValueError("values must be unique")
        return values

    @model_validator(mode="after")
    def successful_answers_have_evidence(self) -> "RagasInput":
        if len(self.retrieved_contexts) != len(self.retrieved_context_ids):
            raise ValueError("retrieved_contexts and retrieved_context_ids must have equal length")
        if self.answer_expectation == "ANSWER" and not self.refused:
            if not self.response.strip() or not self.retrieved_contexts:
                raise ValueError("successful non-refused answers require response and evidence")
        return self

    @property
    def eligible_for_semantic_metrics(self) -> bool:
        return (
            self.answer_expectation == "ANSWER"
            and not self.refused
            and bool(self.response.strip())
            and bool(self.retrieved_contexts)
        )
