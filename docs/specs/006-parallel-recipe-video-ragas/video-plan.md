# Parallel Recipe RAG and Video MCP Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make every non-video-only recipe query execute public recipe RAG and Bilibili video search concurrently, then return the best available combination without letting one branch failure cancel the other.

**Architecture:** Keep intent routing and session persistence in `DietOrchestratorService`, move concurrency and branch isolation into a focused `RecipeVideoCoordinator`, and reuse the existing `ChatResponse.videoBlocks` contract. `CookingVideoSearchService` owns video-only classification and recipe-to-video query normalization; the coordinator returns typed branch outcomes that the orchestrator records and composes.

**Tech Stack:** Java 21, Spring Boot 3.3.13, Reactor `Mono`, AgentScope Java 1.0.11, JUnit 5, Mockito, AssertJ.

**Spec:** `docs/specs/006-parallel-recipe-video-ragas/spec.md`

## Global Constraints

- Meal recommendation remains MySQL-only and must not call recipe RAG, embeddings, Qdrant, Lucene, or video MCP.
- Public recipe answers remain grounded only in the fixed HowToCook commit `a0b14bd388ee996943173679c3a7c9db7da04a6c`.
- Video metadata is display-only and must never become recipe evidence.
- Video MCP stays disabled unless the `video-mcp` profile enables it.
- A timeout, empty result, or exception in one branch must not cancel the other branch.
- Explicit “只要视频/只看视频/不用文字菜谱” requests remain video-only.
- Default validation is `mvn test`; final validation is `mvn -DskipTests package`.

## File Map

- Modify `src/main/java/com/diet/service/video/CookingVideoSearchService.java`: distinguish video-only requests and build video requests for ordinary recipe queries.
- Modify `src/test/java/com/diet/service/video/CookingVideoSearchServiceTest.java`: lock routing and normalization behavior.
- Create `src/main/java/com/diet/service/recipe/RecipeVideoCoordinator.java`: run recipe and video branches concurrently and convert terminal signals to typed outcomes.
- Create `src/test/java/com/diet/service/recipe/RecipeVideoCoordinatorTest.java`: prove true concurrency and branch isolation.
- Modify `src/main/java/com/diet/service/recipe/RecipeQueryConfiguration.java`: register the coordinator when recipe RAG is enabled.
- Modify `src/main/java/com/diet/model/ChatResponse.java`: create a response containing recipe text and video cards together.
- Modify `src/test/java/com/diet/model/ChatResponseVideoTest.java`: verify the composite JSON contract.
- Modify `src/main/java/com/diet/service/orchestrator/DietOrchestratorService.java`: replace mutually exclusive routing with video-only routing plus parallel recipe orchestration.
- Modify `src/test/java/com/diet/service/recipe/RecipeOrchestratorTest.java`: cover the complete response matrix and Trace states.
- Modify `src/main/resources/application-video-mcp.yml`: set the product video timeout to the specified three seconds.
- Create `src/test/java/com/diet/service/video/VideoMcpProfileConfigurationTest.java`: bind the profile YAML and lock timeout/search defaults.
- Modify `docs/specs/005-bilibili-video-mcp/spec.md`: mark its old mutually exclusive constraint as superseded by 006.
- Modify `docs/specs/005-bilibili-video-mcp/runbook.md`: document parallel recipe behavior and degradation checks.

---

### Task 1: Classify video-only requests and normalize recipe video searches

**Files:**
- Modify: `src/main/java/com/diet/service/video/CookingVideoSearchService.java`
- Modify: `src/test/java/com/diet/service/video/CookingVideoSearchServiceTest.java`

**Interfaces:**
- Produces: `boolean isVideoOnly(String message, Map<String,Object> context)`
- Produces: `VideoSearchRequest forRecipe(String recipeText)`
- Preserves: `boolean matches(String, Map<String,Object>)`, `VideoSearchRequest route(String, Map<String,Object>)`, `Mono<List<CookingVideo>> search(String, VideoPlatform, int)`, and `Mono<List<CookingVideo>> search(VideoSearchRequest)`.

- [ ] **Step 1: Add failing routing tests**

Add focused assertions to `CookingVideoSearchServiceTest`:

```java
@Test
void distinguishesVideoOnlyFromRecipeQuestionsThatAlsoMentionVideo() {
    var properties = new VideoMcpProperties();
    properties.setEnabled(true);
    var service = new CookingVideoSearchService(new FakeCookingVideoSearchProvider(), properties);

    assertThat(service.isVideoOnly("只要宫保鸡丁教学视频", Map.of())).isTrue();
    assertThat(service.isVideoOnly("找一个宫保鸡丁教学视频", Map.of())).isTrue();
    assertThat(service.isVideoOnly("宫保鸡丁怎么做，有视频吗", Map.of())).isFalse();
    assertThat(service.isVideoOnly("宫保鸡丁咋做啊", Map.of())).isFalse();
    assertThat(service.isVideoOnly("ignored", Map.of("videoSearch", Map.of("query", "宫保鸡丁"))))
            .isTrue();
}

@Test
void buildsCompactVideoRequestForOrdinaryRecipeQuestion() {
    var properties = new VideoMcpProperties();
    properties.setEnabled(true);
    properties.setDefaultLimit(3);
    var service = new CookingVideoSearchService(new FakeCookingVideoSearchProvider(), properties);

    var request = service.forRecipe("宫保鸡丁咋做啊");

    assertThat(request.query()).isEqualTo("宫保鸡丁");
    assertThat(request.platform()).isEqualTo(VideoPlatform.ANY);
    assertThat(request.limit()).isEqualTo(3);
}
```

- [ ] **Step 2: Run the new tests and verify red state**

Run:

```powershell
mvn "-Dtest=CookingVideoSearchServiceTest" test
```

Expected: compilation fails because `isVideoOnly` and `forRecipe` do not exist.

- [ ] **Step 3: Implement the two explicit APIs**

Add named patterns and methods instead of expanding the meaning of `matches()`:

```java
private static final Pattern RECIPE_ACTION = Pattern.compile(
        "(怎么做|咋做|如何做|怎么弄|咋弄|做法|菜谱|食谱|烹饪步骤|制作步骤|有视频吗|视频教程)",
        Pattern.CASE_INSENSITIVE);
private static final Pattern VIDEO_ONLY = Pattern.compile(
        "(只要|只看|仅看).{0,8}(视频|教程)|(不用|不要).{0,8}(文字|菜谱|做法)",
        Pattern.CASE_INSENSITIVE);

public boolean isVideoOnly(String message, Map<String, Object> context) {
    Map<String, Object> safe = context == null ? Map.of() : context;
    if (safe.containsKey("videoSearch")) return true;
    if (message == null || !VIDEO_REQUEST.matcher(message).find()) return false;
    if (VIDEO_ONLY.matcher(message).find()) return true;
    return !RECIPE_ACTION.matcher(message).find();
}

public VideoSearchRequest forRecipe(String recipeText) {
    if (recipeText == null || recipeText.isBlank()) {
        throw new IllegalArgumentException("菜谱视频 query 不能为空");
    }
    String query = RECIPE_ACTION.matcher(recipeText).replaceAll(" ")
            .replaceAll("(?i)哔哩哔哩|bilibili|B站|抖音|视频|教程|链接|帮我|请|啊|呀", " ")
            .replaceAll("[？?！!，,。\s]+", " ")
            .trim();
    if (query.isBlank()) query = recipeText.trim();
    return new VideoSearchRequest(query, VideoPlatform.ANY,
            validatedLimit(properties.getDefaultLimit()));
}
```

Do not append the configured suffix here; `BilibiliMcpCookingVideoSearchProvider` remains responsible for adding `querySuffix` exactly once.

- [ ] **Step 4: Run the video service tests**

Run:

```powershell
mvn "-Dtest=CookingVideoSearchServiceTest,BilibiliMcpCookingVideoSearchProviderTest" test
```

Expected: all tests pass and existing explicit video routing remains unchanged.

- [ ] **Step 5: Commit the routing unit**

```powershell
git add src/main/java/com/diet/service/video/CookingVideoSearchService.java src/test/java/com/diet/service/video/CookingVideoSearchServiceTest.java
git commit -m "feat: normalize video searches for recipe queries"
```

---

### Task 2: Add typed concurrent branch coordination

**Files:**
- Create: `src/main/java/com/diet/service/recipe/RecipeVideoCoordinator.java`
- Create: `src/test/java/com/diet/service/recipe/RecipeVideoCoordinatorTest.java`
- Modify: `src/main/java/com/diet/service/recipe/RecipeQueryConfiguration.java`

**Interfaces:**
- Consumes: `CookingVideoSearchService.forRecipe(String)` from Task 1.
- Produces: `Mono<RecipeVideoCoordinator.Result> execute(Supplier<RecipeAnswer> recipeCall, String recipeText)`.
- Produces: `BranchStatus { SUCCESS, REFUSED, EMPTY, DISABLED, TIMEOUT, FAILED }`.
- Produces: immutable `BranchOutcome<T>` and `Result` records.

- [ ] **Step 1: Write failing concurrency and degradation tests**

Create `RecipeVideoCoordinatorTest` with a latch-based concurrency proof so the test does not depend on fragile wall-clock thresholds:

```java
@Test
void startsRecipeAndVideoBranchesBeforeEitherCompletes() {
    CountDownLatch bothStarted = new CountDownLatch(2);
    var properties = enabledProperties(Duration.ofSeconds(1));
    CookingVideoSearchProvider provider = (query, platform, limit) -> Mono.fromCallable(() -> {
        bothStarted.countDown();
        assertThat(bothStarted.await(500, TimeUnit.MILLISECONDS)).isTrue();
        return List.of(video(query));
    }).subscribeOn(Schedulers.boundedElastic());
    var coordinator = new RecipeVideoCoordinator(
            new CookingVideoSearchService(provider, properties));

    var result = coordinator.execute(() -> {
        bothStarted.countDown();
        assertThat(bothStarted.await(500, TimeUnit.MILLISECONDS)).isTrue();
        return groundedAnswer();
    }, "宫保鸡丁咋做啊").block(Duration.ofSeconds(2));

    assertThat(result.recipe().status()).isEqualTo(BranchStatus.SUCCESS);
    assertThat(result.video().status()).isEqualTo(BranchStatus.SUCCESS);
}

private VideoMcpProperties enabledProperties(Duration timeout) {
    var properties = new VideoMcpProperties();
    properties.setEnabled(true);
    properties.setTimeout(timeout);
    return properties;
}

private CookingVideo video(String query) {
    return new CookingVideo(query, "宫保鸡丁教程", "https://example.invalid/BV1",
            VideoPlatform.BILIBILI, "测试UP", 120, "fake");
}

private RecipeAnswer groundedAnswer() {
    return new RecipeAnswer("宫保鸡丁做法 [S1]", List.of(
            new RecipeCitation("S1", "r1", "宫保鸡丁", "path.md", "commit", "hash")),
            false, Map.of());
}
```

Add separate tests for:

```java
assertThat(result.recipe().status()).isEqualTo(BranchStatus.REFUSED);
assertThat(result.video().status()).isEqualTo(BranchStatus.EMPTY);
assertThat(result.video().status()).isEqualTo(BranchStatus.TIMEOUT);
assertThat(result.recipe().status()).isEqualTo(BranchStatus.FAILED);
```

Every test must also assert that the unaffected branch retains its value.

- [ ] **Step 2: Run the coordinator test and verify red state**

Run:

```powershell
mvn "-Dtest=RecipeVideoCoordinatorTest" test
```

Expected: compilation fails because the coordinator and result types do not exist.

- [ ] **Step 3: Implement typed terminal outcomes**

Create the coordinator with this public surface. Use record constructors directly so every exit records elapsed time:

```java
public final class RecipeVideoCoordinator {
    private final CookingVideoSearchService videos;

    public RecipeVideoCoordinator(CookingVideoSearchService videos) {
        this.videos = videos;
    }

    public Mono<Result> execute(Supplier<RecipeAnswer> recipeCall, String recipeText) {
        Mono<BranchOutcome<RecipeAnswer>> recipe = Mono.defer(() -> {
            long started = System.nanoTime();
            return Mono.fromCallable(recipeCall::get)
                    .subscribeOn(Schedulers.boundedElastic())
                    .map(answer -> new BranchOutcome<>(
                            answer.refused() ? BranchStatus.REFUSED : BranchStatus.SUCCESS,
                            answer, "", elapsedMs(started)))
                    .onErrorResume(error -> Mono.just(new BranchOutcome<>(
                            BranchStatus.FAILED, null, error.getClass().getSimpleName(),
                            elapsedMs(started))));
        });

        Mono<BranchOutcome<List<CookingVideo>>> video = Mono.defer(() -> {
            long started = System.nanoTime();
            return videos.search(videos.forRecipe(recipeText))
                    .map(found -> new BranchOutcome<>(
                            found == null || found.isEmpty()
                                    ? BranchStatus.EMPTY : BranchStatus.SUCCESS,
                            found == null ? List.of() : List.copyOf(found), "", elapsedMs(started)))
                    .onErrorResume(error -> Mono.just(new BranchOutcome<>(
                            videoStatus(error), List.of(), error.getClass().getSimpleName(),
                            elapsedMs(started))));
        });

        return Mono.zip(recipe, video, Result::new);
    }

    public enum BranchStatus { SUCCESS, REFUSED, EMPTY, DISABLED, TIMEOUT, FAILED }

    public record BranchOutcome<T>(BranchStatus status, T value,
                                   String errorType, long durationMs) { }

    public record Result(BranchOutcome<RecipeAnswer> recipe,
                         BranchOutcome<List<CookingVideo>> video) { }

    private BranchStatus videoStatus(Throwable error) {
        if (error instanceof java.util.concurrent.TimeoutException) return BranchStatus.TIMEOUT;
        if ("video_search_disabled".equals(error.getMessage())) return BranchStatus.DISABLED;
        return BranchStatus.FAILED;
    }

    private long elapsedMs(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }
}
```

Never expose exception messages in the result because they may contain external payloads. The code stores only the exception class; the disabled sentinel is inspected only to select `DISABLED`.

- [ ] **Step 4: Register the coordinator bean**

Add to `RecipeQueryConfiguration`:

```java
@Bean
RecipeVideoCoordinator recipeVideoCoordinator(CookingVideoSearchService videos) {
    return new RecipeVideoCoordinator(videos);
}
```

The configuration remains conditional on `diet.recipe-rag.enabled=true`, while `CookingVideoSearchService` can wrap the disabled provider when the video profile is absent.

- [ ] **Step 5: Run coordinator and configuration tests**

Run:

```powershell
mvn "-Dtest=RecipeVideoCoordinatorTest,CookingVideoSearchServiceTest" test
```

Expected: all tests pass; the latch test demonstrates both branches start concurrently.

- [ ] **Step 6: Commit the coordination unit**

```powershell
git add src/main/java/com/diet/service/recipe/RecipeVideoCoordinator.java src/test/java/com/diet/service/recipe/RecipeVideoCoordinatorTest.java src/main/java/com/diet/service/recipe/RecipeQueryConfiguration.java
git commit -m "feat: coordinate recipe and video branches concurrently"
```

---

### Task 3: Compose recipe text and video cards in the orchestrator

**Files:**
- Modify: `src/main/java/com/diet/model/ChatResponse.java`
- Modify: `src/test/java/com/diet/model/ChatResponseVideoTest.java`
- Modify: `src/main/java/com/diet/service/orchestrator/DietOrchestratorService.java`
- Modify: `src/test/java/com/diet/service/recipe/RecipeOrchestratorTest.java`

**Interfaces:**
- Consumes: `RecipeVideoCoordinator.Result` from Task 2.
- Produces: `ChatResponse.recipe(String sessionId, String traceId, String text, List<CookingVideo> videos)`.
- Preserves: the existing JSON fields and front-end use of `videoBlocks`.

- [ ] **Step 1: Add a failing composite response contract test**

Extend `ChatResponseVideoTest`:

```java
@Test
void recipeResponseCanContainTextAndVideoCards() throws Exception {
    ChatResponse response = ChatResponse.recipe("s", "t", "宫保鸡丁做法 [S1]",
            List.of(video()));

    JsonNode json = objectMapper.valueToTree(response);
    assertThat(json.path("responseType").asText()).isEqualTo("ANSWER");
    assertThat(json.path("speechText").asText()).contains("[S1]");
    assertThat(json.path("videoBlocks")).hasSize(1);
    assertThat(json.path("displayBlocks")).isEmpty();
}
```

- [ ] **Step 2: Add failing orchestrator matrix tests**

Add tests to `RecipeOrchestratorTest` for all six specification rows. At minimum, each test asserts response text, `videoBlocks`, saved intent, and the absence of unrelated intent/meal calls. Example grounded case:

```java
var outcome = new RecipeVideoCoordinator.Result(
        new RecipeVideoCoordinator.BranchOutcome<>(BranchStatus.SUCCESS, answer, "", 20),
        new RecipeVideoCoordinator.BranchOutcome<>(BranchStatus.SUCCESS, List.of(video), "", 15));
when(coordinator.execute(any(), eq("宫保鸡丁怎么做")))
        .thenReturn(Mono.just(outcome));

ChatResponse response = orchestrator.dietChat(7L,
        new ChatRequest("s", "宫保鸡丁怎么做", SourceMode.PUBLIC, Map.of()));

assertThat(response.speechText()).contains("[S1]");
assertThat(response.videoBlocks()).hasSize(1);
verify(states).save(argThat(saved -> saved.currentIntent() == Intent.RECIPE_QUERY));
```

Also lock these behaviors:

- recipe refusal plus videos returns the refusal/explanation text and cards;
- recipe exception plus videos returns a stable “暂无可信文字菜谱” explanation and cards;
- grounded recipe plus video timeout returns the grounded answer with no cards;
- both unavailable returns a stable user-facing explanation and no internal exception;
- “只要宫保鸡丁视频” uses the existing pure-video search and never calls recipe RAG;
- “宫保鸡丁怎么做，有视频吗” uses the parallel recipe route.

- [ ] **Step 3: Run response and orchestrator tests to verify red state**

Run:

```powershell
mvn "-Dtest=ChatResponseVideoTest,RecipeOrchestratorTest" test
```

Expected: failures because composite response and coordinator integration are absent.

- [ ] **Step 4: Add the composite response factory**

Implement in `ChatResponse`:

```java
public static ChatResponse recipe(String sessionId,
                                  String traceId,
                                  String speechText,
                                  List<CookingVideo> videos) {
    List<VideoResponse> blocks = videos == null ? List.of() : videos.stream()
            .map(VideoResponse::from)
            .toList();
    return new ChatResponse(sessionId, traceId, "ANSWER", speechText,
            List.of(), List.of(), blocks, "WAIT_USER", null, List.of());
}
```

- [ ] **Step 5: Replace the mutually exclusive early route**

Change the current early video check from `matches(request.message(), request.context())` to `isVideoOnly(request.message(), request.context())`. Pure video responses should save `Intent.RECIPE_QUERY`, because cooking tutorials are part of the recipe domain rather than `OTHER`.

Inject `RecipeVideoCoordinator` with an optional setter matching the existing optional recipe service pattern:

```java
private RecipeVideoCoordinator recipeVideoCoordinator;

@Autowired(required = false)
public void setRecipeVideoCoordinator(RecipeVideoCoordinator coordinator) {
    this.recipeVideoCoordinator = coordinator;
}
```

Inside `handleRecipeQuery`, preserve the existing forced structured context, then execute:

```java
var result = recipeVideoCoordinator.execute(
        () -> recipeQueryService.answer(sessionId, userId, request.message(), finalContext),
        request.message()
).block();
```

Use a final local `finalContext` before capturing it in the supplier. If the coordinator is absent, keep a pure-RAG fallback for unit isolation.

- [ ] **Step 6: Implement response composition and Trace events**

Record `RECIPE_QUERY_NORMALIZED`, both `*_STARTED` events, one terminal event per branch, and `RECIPE_RESPONSE_COMPOSED`. Terminal payloads contain only status, duration, citation count or video count, and sanitized error type.

Create `completeRecipeResponse(String sessionId, String traceId, SessionState state, String text, List<CookingVideo> videos)` so all matrix exits use the same persistence path:

```java
SessionState saved = state.withIntent(Intent.RECIPE_QUERY);
sessionStateService.save(saved);
sessionService.appendMessage(sessionId, "assistant", text, Intent.RECIPE_QUERY.name(), traceId);
ChatResponse response = videos.isEmpty()
        ? ChatResponse.answer(sessionId, traceId, text, List.of(), "WAIT_USER")
        : ChatResponse.recipe(sessionId, traceId, text, videos);
```

Choose text using the exact matrix in the specification. Never call `CookingVideoSearchService.format()` to replace a grounded recipe answer.

- [ ] **Step 7: Run the focused integration tests**

Run:

```powershell
mvn "-Dtest=RecipeVideoCoordinatorTest,RecipeOrchestratorTest,ChatResponseVideoTest,CookingVideoSearchServiceTest" test
```

Expected: all tests pass; recipe responses contain both Markdown text and structured video blocks.

- [ ] **Step 8: Commit the user-visible behavior**

```powershell
git add src/main/java/com/diet/model/ChatResponse.java src/test/java/com/diet/model/ChatResponseVideoTest.java src/main/java/com/diet/service/orchestrator/DietOrchestratorService.java src/test/java/com/diet/service/recipe/RecipeOrchestratorTest.java
git commit -m "feat: return recipe answers with video cards"
```

---

### Task 4: Align configuration, documentation, and end-to-end validation

**Files:**
- Modify: `src/main/resources/application-video-mcp.yml`
- Create: `src/test/java/com/diet/service/video/VideoMcpProfileConfigurationTest.java`
- Modify: `docs/specs/005-bilibili-video-mcp/spec.md`
- Modify: `docs/specs/005-bilibili-video-mcp/runbook.md`
- Modify: `docs/specs/006-parallel-recipe-video-ragas/spec.md` only if implementation reveals a factual mismatch; do not change accepted behavior.

**Interfaces:**
- Consumes: all behavior from Tasks 1–3.
- Produces: documented startup and degradation commands.

- [ ] **Step 1: Lock the configured timeout with a property test**

Create `VideoMcpProfileConfigurationTest` and bind the actual YAML:

```java
var sources = new YamlPropertySourceLoader().load(
        "video-mcp", new ClassPathResource("application-video-mcp.yml"));
var environment = new StandardEnvironment();
environment.getPropertySources().addFirst(sources.get(0));
VideoMcpProperties properties = Binder.get(environment)
        .bind("diet.video-mcp", Bindable.of(VideoMcpProperties.class))
        .orElseThrow();

assertThat(properties.getTimeout()).isEqualTo(Duration.ofSeconds(3));
assertThat(properties.getDefaultLimit()).isEqualTo(3);
assertThat(properties.getQuerySuffix()).isEqualTo(" 做法 教程");
```

- [ ] **Step 2: Run the property test and verify it fails**

Expected: current profile value is 30 seconds rather than the accepted three seconds.

- [ ] **Step 3: Update profile and superseded documentation**

Set:

```yaml
diet:
  video-mcp:
    timeout: 3s
```

In 005, retain the historical implementation description but add a direct note that 006 supersedes the rule forbidding implicit video calls from recipe RAG. In the runbook document these startup profiles:

```powershell
mvn spring-boot:run "-Dspring-boot.run.arguments=--spring.profiles.active=recipe-rag,video-mcp --diet.recipe-rag.enabled=true --diet.recipe-rag.corpus-path=E:/Mycode/HowToCook"
```

Document four manual checks: combined success, MCP stopped, no RAG evidence with videos, and both unavailable.

- [ ] **Step 4: Run the complete Java verification**

Run:

```powershell
mvn test
mvn -DskipTests package
```

Expected: the existing suite and new tests pass; opt-in live tests remain skipped.

- [ ] **Step 5: Run optional real MCP acceptance when Node/MCP is available**

Run:

```powershell
mvn "-Dtest=BilibiliMcpLiveTest" "-Ddiet.video-mcp.live=true" test
```

Then start the application with both profiles and ask “宫保鸡丁咋做啊”. Verify the single response contains cited Markdown plus clickable Bilibili cards, and the Trace has terminal events for both branches.

- [ ] **Step 6: Commit configuration and documentation**

```powershell
git add src/main/resources/application-video-mcp.yml src/test/java/com/diet/service/video/VideoMcpProfileConfigurationTest.java docs/specs/005-bilibili-video-mcp/spec.md docs/specs/005-bilibili-video-mcp/runbook.md
git commit -m "docs: describe parallel recipe video behavior"
```
