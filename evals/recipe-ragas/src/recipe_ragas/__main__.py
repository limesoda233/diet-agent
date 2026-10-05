"""Run offline Ragas scoring against a frozen Java export."""

import argparse
import asyncio
import os
import sys
from pathlib import Path

from pydantic import ValidationError

from recipe_ragas.config import RagasConfig, validate_paths
from recipe_ragas.evaluator import evaluate_cases
from recipe_ragas.metrics import build_metrics
from recipe_ragas.models import RagasInput
from recipe_ragas.report import build_report, validate_java_report, write_report


def load_cases(path: Path) -> list[RagasInput]:
    cases: list[RagasInput] = []
    seen: set[str] = set()
    for line_number, line in enumerate(path.read_text(encoding="utf-8-sig").splitlines(), 1):
        if not line.strip():
            continue
        try:
            case = RagasInput.model_validate_json(line)
        except ValidationError as error:
            raise ValueError(f"invalid Ragas JSONL at line {line_number}") from error
        if case.query_id in seen:
            raise ValueError(f"duplicate query_id at line {line_number}")
        seen.add(case.query_id)
        cases.append(case)
    if not cases:
        raise ValueError("Ragas JSONL contains no cases")
    if len({case.dataset_hash for case in cases}) != 1:
        raise ValueError("Ragas JSONL contains mixed dataset hashes")
    if len({case.dataset_version for case in cases}) != 1:
        raise ValueError("Ragas JSONL contains mixed dataset versions")
    if len({case.generator_model for case in cases}) != 1:
        raise ValueError("Ragas JSONL contains mixed generator_model values")
    return cases


def validate_output_targets(input_file: Path, java_report: Path, output_dir: Path) -> None:
    destinations = {(output_dir / name).resolve()
                    for name in ("ragas-report.json", "ragas-cases.csv")}
    if input_file in destinations or java_report in destinations:
        raise ValueError("an input path cannot be a Ragas output destination")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Score frozen recipe answers with Ragas")
    parser.add_argument("--input", default="target/evaluation/recipe/ragas-input.jsonl")
    parser.add_argument("--java-report", default="target/evaluation/recipe/report.json")
    parser.add_argument("--output-dir", default="target/evaluation/recipe")
    args = parser.parse_args(argv)

    try:
        input_file, java_report, output_dir = validate_paths(
            args.input, args.java_report, args.output_dir)
        validate_output_targets(input_file, java_report, output_dir)
        cases = load_cases(input_file)
        validate_java_report(java_report, cases[0].dataset_hash, cases[0].dataset_version)
        config = RagasConfig.from_env(os.environ)
    except ValueError as error:
        # These messages are constructed by path, schema and config validation.
        print(f"Ragas input/configuration error: {error}", file=sys.stderr)
        return 2
    except (OSError, UnicodeError) as error:
        print(f"Ragas input/configuration error: {type(error).__name__}", file=sys.stderr)
        return 2

    try:
        metrics = build_metrics(config)
        scores = asyncio.run(evaluate_cases(cases, metrics, config.concurrency,
                                            timeout_seconds=config.llm_timeout_seconds))
        report = build_report(scores, config, input_file, java_report)
        write_report(report, output_dir)
    except Exception as error:
        print(f"Ragas evaluation error: {type(error).__name__}", file=sys.stderr)
        return 1
    return 1 if report["status"] == "failed" else 0


if __name__ == "__main__":
    raise SystemExit(main())
