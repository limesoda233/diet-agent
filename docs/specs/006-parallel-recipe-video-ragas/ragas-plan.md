# Recipe Ragas Evaluation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Export real recipe answers and their final prompt evidence from Java, then score eligible locked-test answers with a pinned, independently runnable Python Ragas CLI.

**Architecture:** Extend the internal `RecipeAnswer` value with the exact evidence contexts assembled for the production prompt. An opt-in Spring Boot test runs the production RAG and answer path and atomically exports a versioned JSONL contract. A separate `uv` Python project reads that JSONL, invokes three Ragas 0.4.3 metrics through configurable OpenAI-compatible clients, and writes per-case plus aggregate reports without affecting application runtime or default Maven tests.

**Tech Stack:** Java 21, Spring Boot 3.3.13, AgentScope Java 1.0.11, Jackson, JUnit 5, Python 3.11+, uv, Ragas 0.4.3, Pydantic, OpenAI-compatible LLM and embedding APIs.

**Spec:** `docs/specs/006-parallel-recipe-video-ragas/spec.md`

## Global Constraints

- Ragas runs offline only; no production request may start Python or call a Judge.
- Pin `ragas==0.4.3` and commit `uv.lock`.
- Export the final evidence actually passed to `RecipeAnswerGenerator`, in the original order.
- First-version semantic metrics are Faithfulness, Answer Relevancy, and ContextUtilization.
- Only successful `answer_expectation=ANSWER` samples enter semantic metric averages.
- OOD and unsupported-answer cases remain in Java refusal metrics and are excluded from Ragas averages.
- No complete reference answers are added in this phase; do not enable Factual Correctness or LLM Context Recall.
- No secret appears in source, command output, JSON, CSV, exception text, or Git history.
- Do not change locked test qrels based on any generated report.
- Default `mvn test` and Python unit tests make no network calls.

## File Map

- Modify `src/main/java/com/diet/service/recipe/rag/answer/RecipeEvidenceAssembler.java`: retain exact per-context prompt blocks.
- Create `src/main/java/com/diet/service/recipe/rag/model/RecipeEvidenceContext.java`: keep evidence ID, recipe ID, and exact prompt text together.
- Modify `src/main/java/com/diet/service/recipe/rag/model/RecipeAnswer.java`: expose immutable structured evidence while preserving the four-argument constructor.
- Modify `src/main/java/com/diet/service/recipe/rag/answer/AgentScopeRecipeAnswerGenerator.java`: attach evidence to agent, deterministic, and model-refusal answers.
- Modify `src/test/java/com/diet/service/recipe/rag/answer/RecipeAnswerGeneratorTest.java`: verify evidence identity and ordering.
- Create `src/test/java/com/diet/evaluation/recipe/RecipeRagasSample.java`: define the JSONL boundary.
- Create `src/test/java/com/diet/evaluation/recipe/RecipeRagasExporter.java`: validate and atomically write JSONL.
- Create `src/test/java/com/diet/evaluation/recipe/RecipeRagasExporterTest.java`: test schema, filtering inputs, hashes, and atomic replacement.
- Create `src/test/java/com/diet/evaluation/recipe/RecipeAnswerEvaluationExportTest.java`: opt-in live export using production Spring beans.
- Create `evals/recipe-ragas/pyproject.toml` and `evals/recipe-ragas/uv.lock`: isolated pinned Python environment.
- Create `evals/recipe-ragas/src/recipe_ragas/config.py`: environment validation and sanitized metadata.
- Create `evals/recipe-ragas/src/recipe_ragas/models.py`: strict input and report models.
- Create `evals/recipe-ragas/src/recipe_ragas/metrics.py`: construct Ragas scorers behind a small adapter.
- Create `evals/recipe-ragas/src/recipe_ragas/evaluator.py`: select eligible cases, score with bounded concurrency, and preserve failures.
- Create `evals/recipe-ragas/src/recipe_ragas/report.py`: aggregate by metric and slice, then atomically write JSON/CSV.
- Create `evals/recipe-ragas/src/recipe_ragas/__main__.py`: CLI entry point.
- Create focused tests under `evals/recipe-ragas/tests/`.
- Create `scripts/evaluate-recipe-ragas.ps1`: optional two-stage orchestration.
- Modify `docs/specs/004-recipe-rag/runbook.md`: document export, Judge configuration, reuse, and report interpretation.
- Modify `docs/specs/006-parallel-recipe-video-ragas/spec.md` only for factual implementation corrections, never to weaken accepted behavior.

---

### Task 1: Preserve final prompt evidence on every generated recipe answer

**Files:**
- Modify: `src/main/java/com/diet/service/recipe/rag/answer/RecipeEvidenceAssembler.java`
- Create: `src/main/java/com/diet/service/recipe/rag/model/RecipeEvidenceContext.java`
- Modify: `src/main/java/com/diet/service/recipe/rag/model/RecipeAnswer.java`
- Modify: `src/main/java/com/diet/service/recipe/rag/answer/AgentScopeRecipeAnswerGenerator.java`
- Modify: `src/test/java/com/diet/service/recipe/rag/answer/RecipeAnswerGeneratorTest.java`

**Interfaces:**
- Produces: `RecipeEvidenceAssembler.EvidenceBundle.contexts(): List<RecipeEvidenceContext>`.
- Produces: `RecipeAnswer.evidenceContexts(): List<RecipeEvidenceContext>`.
- Preserves: `new RecipeAnswer(text, citations, refused, diagnostics)` for existing callers.
- Produces: `RecipeAnswer.refused(text, diagnostics, evidenceContexts)` for evidence-backed refusals.

- [ ] **Step 1: Write failing evidence-preservation tests**

Extend `RecipeAnswerGeneratorTest` with an answer generated from two retrieved recipes:

```java
@Test
void exposesExactlyTheEvidenceBlocksSentToTheAnswerModel() {
    AtomicReference<String> prompt = new AtomicReference<>();
    var generator = new AgentScopeRecipeAnswerGenerator(
            new RecipeEvidenceAssembler(6000, 3),
            value -> {
                prompt.set(value);
                return "{\"text\":\"做法 [S1]\",\"citationIds\":[\"S1\"],\"refused\":false}";
            },
            new ObjectMapper());

    RecipeAnswer answer = generator.generate("s", query(), retrieval(recipeOne(), recipeTwo()));

    assertThat(answer.evidenceContexts()).hasSize(2);
    assertThat(answer.evidenceContexts().get(0).recipeId()).isEqualTo(recipeOne().recipeId());
    assertThat(answer.evidenceContexts().get(0).text()).startsWith("[S1] 菜名：");
    assertThat(answer.evidenceContexts().get(1).text()).startsWith("[S2] 菜名：");
    assertThat(prompt.get()).containsSubsequence(
            answer.evidenceContexts().get(0).text(), answer.evidenceContexts().get(1).text());
}
```

Add a second test proving `answer.evidenceContexts()` is immutable and the legacy four-argument constructor returns an empty list.

- [ ] **Step 2: Run the generator test and verify red state**

Run:

```powershell
mvn "-Dtest=RecipeAnswerGeneratorTest" test
```

Expected: compilation fails because `evidenceContexts()` does not exist.

- [ ] **Step 3: Split assembler output into exact ordered contexts**

Create the structured context first:

```java
public record RecipeEvidenceContext(String id, String recipeId, String text) {
    public RecipeEvidenceContext {
        id = id == null ? "" : id.trim();
        recipeId = recipeId == null ? "" : recipeId.trim();
        text = text == null ? "" : text;
        if (id.isBlank() || recipeId.isBlank() || text.isBlank()) {
            throw new IllegalArgumentException("recipe evidence context fields must be nonblank");
        }
    }
}
```

In `RecipeEvidenceAssembler`, collect each rendered block with its identity before appending it:

```java
List<RecipeEvidenceContext> contexts = new ArrayList<>();
// after character-budget truncation:
contexts.add(new RecipeEvidenceContext(citationId, recipe.recipeId(), block));
evidence.append(block);
```

Change the bundle to:

```java
public record EvidenceBundle(String promptEvidence,
                             List<RecipeEvidenceContext> contexts,
                             List<RecipeCitation> citations,
                             List<RecipeDocument> documents) {
    public EvidenceBundle {
        contexts = contexts == null ? List.of() : List.copyOf(contexts);
        citations = citations == null ? List.of() : List.copyOf(citations);
        documents = documents == null ? List.of() : List.copyOf(documents);
    }
}
```

The empty case returns `new EvidenceBundle("", List.of(), List.of(), List.of())`.

- [ ] **Step 4: Extend `RecipeAnswer` compatibly**

Use a five-field record plus a compatibility constructor:

```java
public record RecipeAnswer(String text,
                           List<RecipeCitation> citations,
                           boolean refused,
                           Map<String, Object> diagnostics,
                           List<RecipeEvidenceContext> evidenceContexts) {
    public RecipeAnswer(String text, List<RecipeCitation> citations,
                        boolean refused, Map<String, Object> diagnostics) {
        this(text, citations, refused, diagnostics, List.of());
    }

    public RecipeAnswer {
        text = text == null ? "" : text.trim();
        citations = citations == null ? List.of() : List.copyOf(citations);
        diagnostics = diagnostics == null ? Map.of() : Map.copyOf(diagnostics);
        evidenceContexts = evidenceContexts == null ? List.of() : List.copyOf(evidenceContexts);
    }

    public static RecipeAnswer refused(String text, Map<String, Object> diagnostics,
                                       List<RecipeEvidenceContext> evidenceContexts) {
        return new RecipeAnswer(text, List.of(), true, diagnostics, evidenceContexts);
    }
}
```

Keep the existing two-argument `refused(text, diagnostics)` and delegate it with an empty evidence list.

- [ ] **Step 5: Attach evidence on every evidence-backed generator exit**

Pass `evidence.contexts()` through `parse(String, List<RecipeCitation>, Map<String,Object>, List<RecipeEvidenceContext>)` and `deterministic(EvidenceBundle, Map<String,Object>)`. A model-produced refusal after evidence assembly uses the new refusal overload. Retrieval failures and no-evidence refusals in `RecipeQueryService` keep an empty list.

Example successful return:

```java
return new RecipeAnswer(text + sourceFooter(used), used, false,
        withGeneration(diagnostics, "agent"), evidence.contexts());
```

- [ ] **Step 6: Run focused and compatibility tests**

Run:

```powershell
mvn "-Dtest=RecipeAnswerGeneratorTest,RecipeQueryServiceTest,RecipeOrchestratorTest" test
```

Expected: all tests pass and existing four-argument test fixtures still compile.

- [ ] **Step 7: Commit evidence capture**

```powershell
git add src/main/java/com/diet/service/recipe/rag/model/RecipeEvidenceContext.java src/main/java/com/diet/service/recipe/rag/answer/RecipeEvidenceAssembler.java src/main/java/com/diet/service/recipe/rag/model/RecipeAnswer.java src/main/java/com/diet/service/recipe/rag/answer/AgentScopeRecipeAnswerGenerator.java src/test/java/com/diet/service/recipe/rag/answer/RecipeAnswerGeneratorTest.java
git commit -m "feat: retain recipe answer evidence for evaluation"
```

---

### Task 2: Define and atomically write the Java-to-Ragas JSONL contract

**Files:**
- Create: `src/test/java/com/diet/evaluation/recipe/RecipeRagasSample.java`
- Create: `src/test/java/com/diet/evaluation/recipe/RecipeRagasExporter.java`
- Create: `src/test/java/com/diet/evaluation/recipe/RecipeRagasExporterTest.java`

**Interfaces:**
- Consumes: `RecipeEvaluationDataset.Example` and `RecipeAnswer`.
- Produces: `RecipeRagasSample.from(RecipeEvaluationDataset, RecipeEvaluationDataset.Example, RecipeAnswer, String, long, long)`.
- Produces: `void RecipeRagasExporter.write(Path output, List<RecipeRagasSample> samples)`.

- [ ] **Step 1: Write failing schema and atomic-write tests**

Create `RecipeRagasExporterTest`:

```java
@TempDir Path temp;

@Test
void writesSnakeCaseJsonlWithDatasetAndEvidenceMetadata() throws Exception {
    Path output = temp.resolve("ragas-input.jsonl");
    var sample = sample("rq-001", List.of("[S1] 菜名：宫保鸡丁"));

    new RecipeRagasExporter(new ObjectMapper()).write(output, List.of(sample));

    JsonNode row = new ObjectMapper().readTree(Files.readString(output).trim());
    assertThat(row.path("query_id").asText()).isEqualTo("rq-001");
    assertThat(row.path("dataset_version").asText()).isEqualTo("v2");
    assertThat(row.path("retrieved_contexts").get(0).asText()).startsWith("[S1]");
    assertThat(row.path("reference_context_ids")).isNotEmpty();
    assertThat(row.path("answer_expectation").asText()).isEqualTo("ANSWER");
}
```

Add tests that duplicate `query_id`, blank dataset hash, mismatched context/context-ID counts, and an empty sample list fail before the destination is replaced. A response may cite only a subset of evidence, so citation count must not be required to equal context count. Pre-create the destination with `old` and assert it remains `old` after validation failure.

- [ ] **Step 2: Run the exporter test and verify red state**

Run:

```powershell
mvn "-Dtest=RecipeRagasExporterTest" test
```

Expected: compilation fails because the sample and exporter do not exist.

- [ ] **Step 3: Define the immutable sample record**

Use Jackson snake-case annotations or explicit `@JsonProperty` fields:

```java
record RecipeRagasSample(
        @JsonProperty("query_id") String queryId,
        @JsonProperty("dataset_version") String datasetVersion,
        @JsonProperty("dataset_hash") String datasetHash,
        @JsonProperty("generator_model") String generatorModel,
        String split,
        String slice,
        @JsonProperty("user_input") String userInput,
        String response,
        @JsonProperty("retrieved_contexts") List<String> retrievedContexts,
        @JsonProperty("retrieved_context_ids") List<String> retrievedContextIds,
        @JsonProperty("reference_context_ids") List<String> referenceContextIds,
        @JsonProperty("answer_expectation") String answerExpectation,
        boolean refused,
        List<String> citations,
        @JsonProperty("retrieval_latency_ms") long retrievalLatencyMs,
        @JsonProperty("generation_latency_ms") long generationLatencyMs) { }
```

`retrievedContexts` and `retrievedContextIds` are projected from the same ordered `RecipeEvidenceContext` list, so they cannot drift when the generated answer cites only a subset of available evidence. `referenceContextIds` comes from locked qrels. `generatorModel` records the configured production answer model. Latencies are clamped to zero or greater.

Implement the factory explicitly:

```java
static RecipeRagasSample from(RecipeEvaluationDataset dataset,
                              RecipeEvaluationDataset.Example example,
                              RecipeAnswer answer,
                              String generatorModel,
                              long retrievalLatencyMs,
                              long generationLatencyMs) {
    return new RecipeRagasSample(example.queryId(), dataset.version(), dataset.fingerprint(),
            generatorModel, example.split(), example.slice(), example.query(), answer.text(),
            answer.evidenceContexts().stream().map(RecipeEvidenceContext::text).toList(),
            answer.evidenceContexts().stream().map(RecipeEvidenceContext::recipeId).toList(),
            example.relevantRecipeIds(), example.answerExpectation().name(), answer.refused(),
            answer.citations().stream().map(RecipeCitation::id).toList(),
            Math.max(0, retrievalLatencyMs), Math.max(0, generationLatencyMs));
}
```

- [ ] **Step 4: Implement validated atomic JSONL output**

Write one compact JSON object per UTF-8 line to a sibling temporary file, then replace the destination:

```java
Path temporary = output.resolveSibling(output.getFileName() + ".tmp");
Files.createDirectories(output.getParent());
try (BufferedWriter writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
    for (RecipeRagasSample sample : validated(samples)) {
        writer.write(json.writeValueAsString(sample));
        writer.newLine();
    }
}
try {
    Files.move(temporary, output, StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING);
} catch (AtomicMoveNotSupportedException unsupported) {
    Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING);
}
```

Delete the temporary file in a `finally` block. Do not catch validation failures and continue.

- [ ] **Step 5: Run exporter tests**

Run:

```powershell
mvn "-Dtest=RecipeRagasExporterTest" test
```

Expected: all schema and atomicity tests pass.

- [ ] **Step 6: Commit the contract**

```powershell
git add src/test/java/com/diet/evaluation/recipe/RecipeRagasSample.java src/test/java/com/diet/evaluation/recipe/RecipeRagasExporter.java src/test/java/com/diet/evaluation/recipe/RecipeRagasExporterTest.java
git commit -m "test: define recipe Ragas export contract"
```

---

### Task 3: Export locked-test answers through the production Java RAG path

**Files:**
- Create: `src/test/java/com/diet/evaluation/recipe/RecipeAnswerEvaluationExportTest.java`
- Modify: `src/test/java/com/diet/evaluation/recipe/RecipeEvaluationDatasetTest.java` only if a helper needs direct coverage.

**Interfaces:**
- Consumes: `RecipeQueryService.answer(String, Long, String, Map<String,Object>)`, `RecipeIndexCoordinator.rebuild(Path)`, and `RecipeRagasExporter.write(Path, List<RecipeRagasSample>)`.
- Produces: `target/evaluation/recipe/ragas-input.jsonl`.
- Opt-in flag: `-Ddiet.recipe-ragas.export.live=true`.

- [ ] **Step 1: Create the opt-in Spring test shell**

Use the established live-evaluation pattern:

```java
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "diet.recipe-rag.enabled=true",
                "spring.datasource.hikari.initialization-fail-timeout=-1",
                "spring.datasource.hikari.minimum-idle=0"
        })
@EnabledIfSystemProperty(named = "diet.recipe-ragas.export.live", matches = "true")
class RecipeAnswerEvaluationExportTest {
    @Autowired RecipeQueryService recipes;
    @Autowired RecipeIndexCoordinator index;
    @Autowired ObjectMapper json;
    @MockBean MealKnowledgeRepository knowledgeRepository;
    @MockBean MealMapper mealMapper;
    @Value("${diet.llm.main-model:deepseek-chat}") String generatorModel;
}
```

Assert `agentscope.deepseek.api-key` is present before rebuilding or generating. The test may require HowToCook, Ollama, Qdrant, and the configured main chat model; document all four dependencies in its assertion message.

- [ ] **Step 2: Build structured recipe contexts from each locked example**

Create a helper that preserves evaluation constraints:

```java
private Map<String, Object> context(RecipeEvaluationDataset.Example example) {
    Map<String, Object> query = new LinkedHashMap<>();
    query.put("query", example.query());
    query.put("requiredIngredients", example.requiredIngredients());
    query.put("excludedIngredients", example.excludedIngredients());
    query.put("requiredTools", example.requiredTools());
    if (example.maxMinutes() != null) query.put("maxMinutes", example.maxMinutes());
    return Map.of("recipeQuery", query);
}
```

This forces near-OOD and unsupported-answer cases through the same public recipe service without changing the user query text.

- [ ] **Step 3: Generate and export every locked-test sample**

Rebuild the fixed corpus, load v2, filter `split=test`, and call the production service with a unique session ID per case:

```java
index.rebuild(Path.of(requiredProperty("diet.recipe-rag.corpus-path")));
RecipeEvaluationDataset dataset = RecipeEvaluationDataset.load("v2");
List<RecipeRagasSample> samples = new ArrayList<>();
for (var example : dataset.examples().stream()
        .filter(value -> "test".equals(value.split())).toList()) {
    long started = System.nanoTime();
    RecipeAnswer answer = recipes.answer("ragas-" + example.queryId(), 1L,
            example.query(), context(example));
    long totalMs = elapsedMs(started);
    long retrievalMs = number(answer.diagnostics().get("retrievalDurationMs"));
    samples.add(RecipeRagasSample.from(dataset, example, answer, generatorModel,
            retrievalMs, Math.max(0, totalMs - retrievalMs)));
}
new RecipeRagasExporter(json).write(
        Path.of("target/evaluation/recipe/ragas-input.jsonl"), samples);
```

Assert the exported count equals the locked test count and the file hash is printed. Do not assert quality thresholds in this export test.

- [ ] **Step 4: Add a skipped-by-default verification**

Run without the flag:

```powershell
mvn "-Dtest=RecipeAnswerEvaluationExportTest" test
```

Expected: one test skipped and no external dependency is called.

- [ ] **Step 5: Run the live exporter when dependencies are available**

Run:

```powershell
mvn "-Dtest=RecipeAnswerEvaluationExportTest" "-Ddiet.recipe-ragas.export.live=true" "-Ddiet.recipe-rag.corpus-path=E:/Mycode/HowToCook" test
```

Expected: `target/evaluation/recipe/ragas-input.jsonl` contains every locked test case, each positive generated answer carries non-empty final evidence, and no API key appears in the file.

- [ ] **Step 6: Commit the live exporter**

```powershell
git add src/test/java/com/diet/evaluation/recipe/RecipeAnswerEvaluationExportTest.java
git commit -m "test: export production recipe answers for Ragas"
```

---

### Task 4: Create the pinned Python Ragas project and strict input models

**Files:**
- Create: `evals/recipe-ragas/pyproject.toml`
- Create: `evals/recipe-ragas/uv.lock`
- Create: `evals/recipe-ragas/src/recipe_ragas/__init__.py`
- Create: `evals/recipe-ragas/src/recipe_ragas/config.py`
- Create: `evals/recipe-ragas/src/recipe_ragas/models.py`
- Create: `evals/recipe-ragas/tests/test_config.py`
- Create: `evals/recipe-ragas/tests/test_models.py`

**Interfaces:**
- Produces: `RagasConfig.from_env(environ: Mapping[str,str])`.
- Produces: `RagasInput.model_validate_json(line)`.
- Produces: sanitized Judge metadata without credentials.

- [ ] **Step 1: Create project metadata and tests before installing dependencies**

Use this `pyproject.toml` base:

```toml
[project]
name = "diet-recipe-ragas"
version = "0.1.0"
requires-python = ">=3.11,<3.14"
dependencies = [
  "ragas==0.4.3",
  "openai>=1.0,<3",
  "pydantic>=2.10,<3",
]

[project.scripts]
diet-recipe-ragas = "recipe_ragas.__main__:main"

[dependency-groups]
dev = ["pytest>=8,<9", "pytest-asyncio>=0.24,<1"]

[build-system]
requires = ["hatchling"]
build-backend = "hatchling.build"

[tool.pytest.ini_options]
asyncio_mode = "auto"
pythonpath = ["src"]
```

Write tests that reject missing LLM or embedding settings, reject blank hashes and contexts, and verify `repr(config)` never contains the API key.

- [ ] **Step 2: Implement environment validation**

`RagasConfig` contains:

```python
@dataclass(frozen=True)
class RagasConfig:
    llm_base_url: str
    llm_api_key: str
    llm_model: str
    embedding_base_url: str
    embedding_api_key: str
    embedding_model: str
    concurrency: int = 2

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
        concurrency = int(environ.get("RAGAS_CONCURRENCY", "2"))
        if concurrency < 1 or concurrency > 8:
            raise ValueError("RAGAS_CONCURRENCY must be between 1 and 8")
        return cls(
            environ["RAGAS_LLM_BASE_URL"].strip(),
            environ["RAGAS_LLM_API_KEY"].strip(),
            environ["RAGAS_LLM_MODEL"].strip(),
            environ["RAGAS_EMBEDDING_BASE_URL"].strip(),
            environ["RAGAS_EMBEDDING_API_KEY"].strip(),
            environ["RAGAS_EMBEDDING_MODEL"].strip(),
            concurrency,
        )

    def metadata(self) -> dict[str, object]:
        return {
            "llm_host": urlparse(self.llm_base_url).hostname or "",
            "llm_model": self.llm_model,
            "embedding_host": urlparse(self.embedding_base_url).hostname or "",
            "embedding_model": self.embedding_model,
            "concurrency": self.concurrency,
        }
```

`metadata()` stores only parsed hostname, model names, and concurrency; it never returns either key or a URL containing credentials. `report.py` computes `same_model_judge` by comparing the single `generator_model` value in the input file with `config.llm_model` and rejects mixed generator models in one run.

- [ ] **Step 3: Implement strict Pydantic input models**

Define `RagasInput` with the exact snake-case JSONL fields from Task 2. Validators require unique nonblank context values, nonnegative latencies, supported `answer_expectation`, and evidence for successful non-refused answers.

Add:

```python
@property
def eligible_for_semantic_metrics(self) -> bool:
    return (
        self.answer_expectation == "ANSWER"
        and not self.refused
        and bool(self.response.strip())
        and bool(self.retrieved_contexts)
    )
```

- [ ] **Step 4: Lock dependencies**

Run:

```powershell
uv lock --project evals/recipe-ragas
uv sync --frozen --project evals/recipe-ragas
```

Expected: `uv.lock` is created and resolves `ragas==0.4.3` exactly.

- [ ] **Step 5: Run Python model tests**

Run:

```powershell
uv run --project evals/recipe-ragas pytest evals/recipe-ragas/tests/test_config.py evals/recipe-ragas/tests/test_models.py -q
```

Expected: all tests pass without network access.

- [ ] **Step 6: Commit the Python boundary**

```powershell
git add evals/recipe-ragas
git commit -m "build: add pinned recipe Ragas project"
```

---

### Task 5: Score eligible answers and preserve partial failures

**Files:**
- Create: `evals/recipe-ragas/src/recipe_ragas/metrics.py`
- Create: `evals/recipe-ragas/src/recipe_ragas/evaluator.py`
- Create: `evals/recipe-ragas/tests/test_evaluator.py`

**Interfaces:**
- Consumes: `RagasConfig` and `list[RagasInput]` from Task 4.
- Produces: `dict[str, MetricScorer] build_metrics(RagasConfig)`.
- Produces: `async evaluate_cases(cases, metrics, concurrency) -> list[CaseScore]`.

- [ ] **Step 1: Write fake-scorer tests for selection and failures**

Do not mock Ragas internals. Test the adapter using a small protocol:

```python
class FakeMetric:
    def __init__(self, value: float | Exception):
        self.value = value

    async def score(self, case: RagasInput) -> float:
        if isinstance(self.value, Exception):
            raise self.value
        return self.value
```

Tests must prove:

```python
assert scores[0].metrics == {
    "faithfulness": 0.8,
    "answer_relevancy": 0.7,
    "context_utilization": 0.6,
}
assert refused_case not in semantic_case_ids
assert one_metric_failure.status == "partial"
assert all_metric_failure.status == "failed"
```

- [ ] **Step 2: Run evaluator tests and verify red state**

Run:

```powershell
uv run --project evals/recipe-ragas pytest evals/recipe-ragas/tests/test_evaluator.py -q
```

Expected: import failures because metric adapters and evaluator do not exist.

- [ ] **Step 3: Build Ragas 0.4.3 collection scorers**

Use modern collection APIs:

```python
from openai import AsyncOpenAI
from ragas.embeddings.base import embedding_factory
from ragas.llms import llm_factory
from ragas.metrics.collections import AnswerRelevancy, ContextUtilization, Faithfulness

llm_client = AsyncOpenAI(api_key=config.llm_api_key, base_url=config.llm_base_url)
embedding_client = AsyncOpenAI(
    api_key=config.embedding_api_key,
    base_url=config.embedding_base_url,
)
llm = llm_factory(config.llm_model, provider="openai", client=llm_client)
embeddings = embedding_factory(
    "openai", model=config.embedding_model, client=embedding_client)

metrics = {
    "faithfulness": Faithfulness(llm=llm),
    "answer_relevancy": AnswerRelevancy(llm=llm, embeddings=embeddings),
    "context_utilization": ContextUtilization(llm=llm),
}
```

Wrap each collection metric in a `MetricScorer` adapter. Faithfulness calls `ascore(user_input=case.user_input, response=case.response, retrieved_contexts=case.retrieved_contexts)`, Answer Relevancy calls `ascore(user_input=case.user_input, response=case.response)`, and ContextUtilization calls the same three arguments as Faithfulness. Convert `MetricResult.value` to a finite `float` and reject NaN or infinity as a metric failure.

- [ ] **Step 4: Implement bounded concurrent evaluation**

Use one semaphore shared across cases:

```python
semaphore = asyncio.Semaphore(concurrency)

async def score_one(case: RagasInput) -> CaseScore:
    async with semaphore:
        results: dict[str, float] = {}
        errors: dict[str, str] = {}
        for name, metric in metrics.items():
            try:
                results[name] = await metric.score(case)
            except Exception as error:
                errors[name] = type(error).__name__
        return CaseScore.from_results(case, results, errors)
```

Run cases concurrently with `asyncio.gather`. Store only sanitized exception class names, never raw provider response bodies.

- [ ] **Step 5: Run unit tests**

Run:

```powershell
uv run --project evals/recipe-ragas pytest evals/recipe-ragas/tests/test_evaluator.py -q
```

Expected: selection, concurrency cap, partial status, and total failure tests pass without network access.

- [ ] **Step 6: Commit the metric engine**

```powershell
git add evals/recipe-ragas/src/recipe_ragas/metrics.py evals/recipe-ragas/src/recipe_ragas/evaluator.py evals/recipe-ragas/tests/test_evaluator.py
git commit -m "feat: evaluate recipe answers with Ragas metrics"
```

---

### Task 6: Write reproducible JSON/CSV reports and expose the CLI

**Files:**
- Create: `evals/recipe-ragas/src/recipe_ragas/report.py`
- Create: `evals/recipe-ragas/src/recipe_ragas/__main__.py`
- Create: `evals/recipe-ragas/tests/test_report.py`
- Create: `evals/recipe-ragas/tests/test_cli.py`

**Interfaces:**
- Consumes: input JSONL path, Java report path, output directory, config, and case scores.
- Produces: `ragas-report.json` and `ragas-cases.csv` through atomic replacement.
- CLI: `diet-recipe-ragas --input target/evaluation/recipe/ragas-input.jsonl --java-report target/evaluation/recipe/report.json --output-dir target/evaluation/recipe`.

- [ ] **Step 1: Write failing aggregation and CLI tests**

Create deterministic scores across two slices and assert:

```python
assert report["status"] == "partial"
assert report["metrics"]["faithfulness"]["valid_count"] == 2
assert report["metrics"]["faithfulness"]["mean"] == pytest.approx(0.75)
assert report["slices"]["exact_name"]["faithfulness"] == pytest.approx(0.8)
assert report["input_sha256"] == sha256(input_file)
assert report["java_report_sha256"] == sha256(java_report)
```

The CLI test injects fake metric construction and verifies missing configuration exits before creating output files.

- [ ] **Step 2: Implement report aggregation**

For each metric report `mean`, `valid_count`, and `failure_count`. Per-slice aggregates only include valid values. Top-level status rules are exact:

```python
if successful_metric_values == 0:
    status = "failed"
elif metric_failures > 0:
    status = "partial"
else:
    status = "complete"
```

Include Ragas version, sanitized Judge metadata, input and Java report hashes, dataset version/hash, generated UTC timestamp, total input count, eligible count, excluded refusal count, and per-case statuses.

- [ ] **Step 3: Implement atomic JSON and CSV writes**

Write sibling `.tmp` files, flush and close them, then use `os.replace(temp, destination)`. CSV columns are stable:

```text
query_id,split,slice,status,faithfulness,answer_relevancy,context_utilization,error_metrics
```

Delete temporary files in `finally` blocks.

- [ ] **Step 4: Implement the CLI**

`main()` parses:

```text
--input target/evaluation/recipe/ragas-input.jsonl
--java-report target/evaluation/recipe/report.json
--output-dir target/evaluation/recipe
```

Load and validate every nonblank line before initializing network clients. Reject duplicate `query_id`, mixed dataset hashes, and missing Java report. Return exit code 2 for configuration/input errors and 1 when all Judge metrics fail.

- [ ] **Step 5: Run the complete Python suite**

Run:

```powershell
uv run --project evals/recipe-ragas pytest evals/recipe-ragas/tests -q
```

Expected: all tests pass without API credentials or network access.

- [ ] **Step 6: Commit reporting and CLI**

```powershell
git add evals/recipe-ragas/src/recipe_ragas/report.py evals/recipe-ragas/src/recipe_ragas/__main__.py evals/recipe-ragas/tests/test_report.py evals/recipe-ragas/tests/test_cli.py
git commit -m "feat: report recipe Ragas evaluation results"
```

---

### Task 7: Add the two-stage runner, runbooks, and final verification

**Files:**
- Create: `scripts/evaluate-recipe-ragas.ps1`
- Modify: `docs/specs/004-recipe-rag/runbook.md`
- Create: `docs/specs/006-parallel-recipe-video-ragas/acceptance.md`

**Interfaces:**
- Consumes: Java export test from Task 3 and Python CLI from Task 6.
- Produces: one repeatable command and an acceptance record.

- [ ] **Step 1: Implement a parameterized PowerShell runner**

The script accepts:

```powershell
param(
    [Parameter(Mandatory=$true)][string]$CorpusPath,
    [switch]$SkipJavaExport,
    [string]$InputPath = "target/evaluation/recipe/ragas-input.jsonl",
    [string]$OutputDir = "target/evaluation/recipe"
)
```

When `SkipJavaExport` is false, run both `RecipeRetrievalEvaluationTest` and `RecipeAnswerEvaluationExportTest` with their opt-in flags so a fresh Java deterministic report and Ragas input are created together. Always run `uv sync --frozen` before the Python CLI. Check `$LASTEXITCODE` after each process and stop on nonzero. Never print environment variable values.

Use this Maven invocation in the script:

```powershell
mvn "-Dtest=RecipeRetrievalEvaluationTest,RecipeAnswerEvaluationExportTest" `
    "-Ddiet.recipe-rag.live=true" `
    "-Ddiet.recipe-ragas.export.live=true" `
    "-Ddiet.recipe-rag.corpus-path=$CorpusPath" test
```

- [ ] **Step 2: Document configuration and cost-safe reuse**

Add commands to the runbook:

```powershell
$env:RAGAS_LLM_BASE_URL = "https://api.deepseek.com/v1"
$env:RAGAS_LLM_API_KEY = "<set locally>"
$env:RAGAS_LLM_MODEL = "deepseek-chat"
$env:RAGAS_EMBEDDING_BASE_URL = "http://localhost:11434/v1"
$env:RAGAS_EMBEDDING_API_KEY = "ollama"
$env:RAGAS_EMBEDDING_MODEL = "bge-m3:567m"
```

Document that `-SkipJavaExport` reuses an existing frozen JSONL and avoids paying generation cost again. Explain `same_model_judge=true`, `partial`, valid counts, and why refusal cases are separate.

- [ ] **Step 3: Run all offline verification**

Run:

```powershell
mvn test
mvn -DskipTests package
uv sync --frozen --project evals/recipe-ragas
uv run --project evals/recipe-ragas pytest evals/recipe-ragas/tests -q
```

Expected: Java tests pass with external integrations skipped; Python tests pass without network access; package succeeds.

- [ ] **Step 4: Run one real baseline when credentials and local dependencies are available**

Run:

```powershell
./scripts/evaluate-recipe-ragas.ps1 -CorpusPath E:/Mycode/HowToCook
```

Verify:

- input dataset hash equals the frozen v2 hash;
- all locked test rows are present;
- eligible count excludes OOD and unsupported-refusal cases;
- report metadata names Judge and embedding models without keys;
- output status is `complete` or `partial`, never silently successful with zero valid values;
- no qrel changes follow from the locked test result.

- [ ] **Step 5: Write the acceptance record**

Record exact commands, dependency versions, hashes, case counts, metric means with valid/failure counts, and limitations. If a real baseline cannot run because credentials are absent, record the offline verification separately and do not invent Ragas scores.

- [ ] **Step 6: Commit runner and documentation**

```powershell
git add scripts/evaluate-recipe-ragas.ps1 docs/specs/004-recipe-rag/runbook.md docs/specs/006-parallel-recipe-video-ragas/acceptance.md
git commit -m "docs: add recipe Ragas evaluation workflow"
```
