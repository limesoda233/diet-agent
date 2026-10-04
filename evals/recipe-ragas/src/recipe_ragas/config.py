from dataclasses import dataclass, field
from pathlib import Path
from typing import Mapping
from urllib.parse import urlparse


@dataclass(frozen=True)
class RagasConfig:
    llm_base_url: str = field(repr=False)
    llm_api_key: str = field(repr=False)
    llm_model: str
    embedding_base_url: str = field(repr=False)
    embedding_api_key: str = field(repr=False)
    embedding_model: str
    concurrency: int = 2
    llm_timeout_seconds: int = 60
    embedding_timeout_seconds: int = 60

    @classmethod
    def from_env(cls, environ: Mapping[str, str]) -> "RagasConfig":
        names = (
            "RAGAS_LLM_BASE_URL", "RAGAS_LLM_API_KEY", "RAGAS_LLM_MODEL",
            "RAGAS_EMBEDDING_BASE_URL", "RAGAS_EMBEDDING_API_KEY",
            "RAGAS_EMBEDDING_MODEL",
        )
        missing = [name for name in names if not environ.get(name, "").strip()]
        if missing:
            raise ValueError("missing Ragas configuration: " + ", ".join(missing))

        values = {name: environ[name].strip() for name in names}
        for name in ("RAGAS_LLM_BASE_URL", "RAGAS_EMBEDDING_BASE_URL"):
            parsed = urlparse(values[name])
            if parsed.scheme not in {"http", "https"} or not parsed.hostname:
                raise ValueError(f"{name} must be an absolute HTTP(S) URL")

        concurrency = _positive_integer(environ, "RAGAS_CONCURRENCY", 2, maximum=8)
        llm_timeout = _positive_integer(environ, "RAGAS_LLM_TIMEOUT_SECONDS", 60, maximum=600)
        embedding_timeout = _positive_integer(
            environ, "RAGAS_EMBEDDING_TIMEOUT_SECONDS", 60, maximum=600
        )
        return cls(
            values["RAGAS_LLM_BASE_URL"],
            values["RAGAS_LLM_API_KEY"],
            values["RAGAS_LLM_MODEL"],
            values["RAGAS_EMBEDDING_BASE_URL"],
            values["RAGAS_EMBEDDING_API_KEY"],
            values["RAGAS_EMBEDDING_MODEL"],
            concurrency,
            llm_timeout,
            embedding_timeout,
        )

    def metadata(self) -> dict[str, object]:
        return {
            "llm_host": urlparse(self.llm_base_url).hostname or "",
            "llm_model": self.llm_model,
            "embedding_host": urlparse(self.embedding_base_url).hostname or "",
            "embedding_model": self.embedding_model,
            "concurrency": self.concurrency,
        }


def _positive_integer(environ: Mapping[str, str], name: str, default: int, *, maximum: int) -> int:
    try:
        value = int(environ.get(name, str(default)))
    except (TypeError, ValueError) as error:
        raise ValueError(f"{name} must be an integer between 1 and {maximum}") from error
    if value < 1 or value > maximum:
        raise ValueError(f"{name} must be between 1 and {maximum}")
    return value


def validate_paths(
    input_path: str | Path, java_report_path: str | Path, output_dir: str | Path
) -> tuple[Path, Path, Path]:
    """Validate CLI file inputs and return normalized paths without creating output."""
    input_file = Path(input_path).expanduser().resolve()
    report_file = Path(java_report_path).expanduser().resolve()
    output_path = Path(output_dir).expanduser().resolve()
    if not input_file.is_file():
        raise ValueError(f"input JSONL file does not exist: {input_file}")
    if not report_file.is_file():
        raise ValueError(f"Java report file does not exist: {report_file}")
    if output_path.exists() and not output_path.is_dir():
        raise ValueError(f"output directory path is a file: {output_path}")
    if input_file == report_file:
        raise ValueError("input JSONL and Java report paths must be different")
    return input_file, report_file, output_path
