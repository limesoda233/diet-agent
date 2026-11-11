package com.diet.service.recipe;

import com.diet.mapper.MealMapper;
import com.diet.model.MealItemRow;
import com.diet.service.meal.knowledge.MealKnowledge;
import com.diet.service.meal.knowledge.MealKnowledgeRepository;
import com.diet.service.recipe.rag.RecipeRagProperties;
import com.diet.service.recipe.rag.answer.RecipeAnswerGenerator;
import com.diet.service.recipe.rag.model.RecipeAnswer;
import com.diet.service.recipe.rag.model.RecipeAnswerPurpose;
import com.diet.service.recipe.rag.model.RecipeCitation;
import com.diet.service.recipe.rag.model.RecipeQuery;
import com.diet.service.recipe.rag.model.RecipeRetrievalResult;
import com.diet.service.recipe.rag.retrieval.HybridRecipeRetriever;
import com.diet.service.recipe.rag.retrieval.RecipeQueryRewriter;
import com.diet.service.recipe.rag.retrieval.RecipeReranker;
import com.diet.service.recipe.rag.retrieval.RecipeRerankResult;
import com.diet.service.trace.AgentTraceService;
import com.diet.service.recipe.rag.retrieval.RecipeRetrievalMode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 菜谱问答业务入口。餐食推荐是另一数据域，不能经此服务写入或查询 meal 向量。
 * <p>准备阶段解析请求与个人 mealId，执行用户归属校验。已有个人做法优先返回；
 * 公共路径执行可选改写、混合检索、可选重排与证据回答。模型改写只影响检索文本，
 * 回答仍使用准备阶段的完整问题，避免丢失用户的事实要求或否定条件。</p>
 * 此服务提供同步 answer，因此外层并行编排将其放在 boundedElastic 上执行。
 */
public class RecipeQueryService {
    private com.diet.service.memory.MemoryPolicy memoryPolicy;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    public void setMemoryPolicy(com.diet.service.memory.MemoryPolicy policy) {this.memoryPolicy=policy;}
    private static final String NO_EVIDENCE = "当前菜谱库中没有足够证据回答这个问题。你可以换一道菜名，或补充想用的食材。";
    private final RecipeQueryRouter router;
    private final MealKnowledgeRepository knowledgeRepository;
    private final MealMapper mealMapper;
    private final RecipeQueryRewriter queryRewriter;
    private final HybridRecipeRetriever retriever;
    private final RecipeReranker reranker;
    private final RecipeAnswerGenerator answerGenerator;
    private final RecipeRagProperties properties;
    private final AgentTraceService trace;

    public RecipeQueryService(RecipeQueryRouter router,
                              MealKnowledgeRepository knowledgeRepository,
                              MealMapper mealMapper,
                              RecipeQueryRewriter queryRewriter,
                              HybridRecipeRetriever retriever,
                              RecipeReranker reranker,
                              RecipeAnswerGenerator answerGenerator,
                              RecipeRagProperties properties) {
        this(router, knowledgeRepository, mealMapper, queryRewriter, retriever, reranker, answerGenerator, properties, null);
    }

    public RecipeQueryService(RecipeQueryRouter router, MealKnowledgeRepository knowledgeRepository,
                              MealMapper mealMapper, RecipeQueryRewriter queryRewriter,
                              HybridRecipeRetriever retriever, RecipeReranker reranker,
                              RecipeAnswerGenerator answerGenerator, RecipeRagProperties properties,
                              AgentTraceService trace) {
        this.router = router;
        this.knowledgeRepository = knowledgeRepository;
        this.mealMapper = mealMapper;
        this.queryRewriter = queryRewriter;
        this.retriever = retriever;
        this.reranker = reranker;
        this.answerGenerator = answerGenerator;
        this.properties = properties;
        this.trace = trace;
    }

    public boolean matches(String message, Map<String, Object> context) {
        return router.matches(message, context);
    }

    public RecipeAnswer answer(String sessionId, Long userId, String message, Map<String, Object> context) {
        return answer(sessionId, prepareQuery(userId, message, context, false));
    }

    /** Resolve and validate once on the request thread, before launching either branch. */
    public PreparedRecipeQuery prepareQuery(Long userId, String message, Map<String, Object> context) {
        return prepareQuery(userId, message, context, true);
    }

    private PreparedRecipeQuery prepareQuery(Long userId, String message, Map<String, Object> context,
                                             boolean resolvePersonalName) {
        RecipeQueryRouter.RoutedRecipeQuery routed = router.route(message, context)
                .orElseThrow(() -> new IllegalArgumentException("不是菜谱查询"));
        RecipeQuery query = memoryPolicy==null?routed.query():memoryPolicy.recipeQuery(userId,routed.query());
        MealKnowledge personal = null;
        boolean missingMeal = false;
        String source = context != null && context.get("recipeQuery") instanceof Map<?, ?> structured
                && structured.get("query") instanceof String text && !text.isBlank()
                ? "structured_recipe_query" : "recipe_rule";
        if (routed.mealId() != null) {
            personal = knowledgeRepository.findPersonal(routed.mealId(), userId).orElse(null);
            if (personal == null || resolvePersonalName) {
                MealItemRow meal = mealMapper.findPersonalById(routed.mealId(), userId);
                missingMeal = meal == null;
                if (!missingMeal) {
                    query = new RecipeQuery(meal.getName() + " 怎么做", query.requiredIngredients(),
                            query.excludedIngredients(), query.requiredTools(), query.maxMinutes());
                    source = "personal_meal";
                } else if (resolvePersonalName) {
                    throw new IllegalArgumentException("没有找到这道个人餐食，无法确定要查询的菜谱。");
                }
            }
        }
        return new PreparedRecipeQuery(query, routed.mealId(), personal, missingMeal, source);
    }

    public RecipeAnswer answer(String sessionId, PreparedRecipeQuery prepared) {
        RecipeQuery query = prepared.query();
        if (prepared.personalKnowledge() != null) {
            return personalAnswer(prepared.mealId(), prepared.personalKnowledge(), query);
        }
        if (prepared.missingMeal()) return RecipeAnswer.refused(
                "没有找到这道个人餐食，无法确定要查询的菜谱。", Map.of("route", "meal_id_not_found"));

        try {
            // 改写器自带回退；identity 是默认配置，不产生额外模型请求。
            RecipeQuery rewritten = queryRewriter.rewrite(query).block();
            if (rewritten == null) rewritten = query;
            RecipeRetrievalResult retrieval = retriever.retrieve(rewritten, configuredMode()).block();
            // 没有本轮证据时不调用 Answer Agent，即使模型“知道这道菜”也不补写。
            if (retrieval == null || retrieval.recipes().isEmpty()) {
                Map<String, Object> diagnostics = retrievalDiagnostics(retrieval, "no_evidence");
                diagnostics.put("answerPurpose", RecipeAnswerPurpose.fromText(query.text()).name());
                diagnostics.put("generation", "not_called");
                return RecipeAnswer.refused(NO_EVIDENCE, diagnostics);
            }
            // Relevance must see the complete resolved question, not a rewrite that may shorten its intent.
            // A scoring failure must not use the legacy rerank fallback to admit unfiltered evidence.
            RecipeRerankResult selection;
            try {
                selection = reranker.rerankDetailed(query, retrieval.recipes()).block();
                if (selection == null) throw new IllegalStateException("rerank_empty_signal");
            } catch (RuntimeException unavailable) {
                selection = new RecipeRerankResult(RecipeRerankResult.Status.UNAVAILABLE, List.of(), List.of(),
                        properties.getRerank().getModel(), properties.getRerank().getMinScore(),
                        retrieval.recipes().size(), 0, "scorer_error", "", List.of());
            }
            recordRelevance(selection);
            if (selection.status() == RecipeRerankResult.Status.UNAVAILABLE || selection.recipes().isEmpty()) {
                Map<String, Object> diagnostics = retrievalDiagnostics(retrieval,
                        selection.status() == RecipeRerankResult.Status.UNAVAILABLE ? "relevance_failed" : "no_relevant_evidence");
                diagnostics.put("relevance", selection.diagnostics());
                diagnostics.put("generation", "not_called");
                diagnostics.put("answerPurpose", RecipeAnswerPurpose.fromText(query.text()).name());
                if (selection.status() == RecipeRerankResult.Status.UNAVAILABLE) {
                    diagnostics.put("error", "RecipeRagDependencyException");
                    return RecipeAnswer.refused("菜谱相关性筛选暂时不可用，请稍后再试。", diagnostics);
                }
                return RecipeAnswer.refused(NO_EVIDENCE, diagnostics);
            }
            RecipeRetrievalResult finalResult = new RecipeRetrievalResult(selection.recipes(), retrieval.diagnostics());
            // 关键分工：检索看 rewritten，回答看 query。原始问题可能包含“原文未写就拒答”。
            RecipeAnswer answer = answerGenerator.generate(sessionId, query, finalResult);
            if (selection.status() == RecipeRerankResult.Status.BYPASSED) return answer;
            Map<String, Object> diagnostics = new LinkedHashMap<>(answer.diagnostics());
            diagnostics.put("relevance", selection.diagnostics());
            return new RecipeAnswer(answer.text(), answer.citations(), answer.refused(), diagnostics, answer.evidenceContexts());
        } catch (RuntimeException error) {
            return RecipeAnswer.refused("菜谱检索暂时不可用，请稍后再试。",
                    Map.of("route", "retrieval_failed", "error", error.getClass().getSimpleName()));
        }
    }

    private void recordRelevance(RecipeRerankResult result) {
        if (trace != null && result.status() != RecipeRerankResult.Status.BYPASSED) {
            trace.recordEvent("RECIPE_RELEVANCE_CHECKED", "RECIPE_RAG", null,
                    result.diagnostics(), result.durationMs());
        }
    }

    public record PreparedRecipeQuery(RecipeQuery query, Long mealId, MealKnowledge personalKnowledge,
                                      boolean missingMeal, String source) { }

    private RecipeAnswer personalAnswer(long mealId, MealKnowledge knowledge, RecipeQuery query) {
        if ((!query.requiredIngredients().isEmpty() || !query.excludedIngredients().isEmpty())
                && !knowledge.ingredientDataPresent()) {
            return RecipeAnswer.refused("这道个人餐食没有记录食材，无法核对你的食材限制。",
                    Map.of("route", "personal_knowledge_missing_ingredients"));
        }
        boolean missingRequired = query.requiredIngredients().stream()
                .anyMatch(required -> knowledge.ingredients().stream().noneMatch(value -> value.contains(required)));
        boolean containsExcluded = query.excludedIngredients().stream()
                .anyMatch(excluded -> knowledge.ingredients().stream().anyMatch(value -> value.contains(excluded)));
        if (missingRequired || containsExcluded) {
            return RecipeAnswer.refused("你保存的个人做法与本次食材限制冲突，我不会据此生成做法。",
                    Map.of("route", "personal_knowledge_constraint_conflict"));
        }
        String ingredients = knowledge.ingredients().isEmpty() ? "未记录" : String.join("、", knowledge.ingredients());
        String preparation = knowledge.preparation().isBlank() ? "未记录具体步骤" : knowledge.preparation();
        String source = knowledge.sourceReference().isBlank() ? "用户录入" : knowledge.sourceReference();
        RecipeCitation citation = new RecipeCitation("P1", "meal:" + mealId, "个人餐食做法", source, "", "");
        String text = "这是你保存的个人做法（用户录入）[P1]\n\n食材：" + ingredients
                + " [P1]\n\n做法：" + preparation + " [P1]\n\n来源：" + source + " [P1]";
        return new RecipeAnswer(text, List.of(citation), false, Map.of("route", "personal_meal_knowledge"));
    }

    private RecipeRetrievalMode configuredMode() {
        String configured = properties.getRetrieval().getMode();
        if (configured == null || configured.isBlank()) return RecipeRetrievalMode.PARALLEL_HYBRID;
        return RecipeRetrievalMode.valueOf(configured.trim().toUpperCase(Locale.ROOT));
    }

    private Map<String, Object> retrievalDiagnostics(RecipeRetrievalResult retrieval, String route) {
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("route", route);
        if (retrieval != null && retrieval.diagnostics() != null) {
            diagnostics.put("actualMode", retrieval.diagnostics().actualMode().name());
            diagnostics.put("retrievalDurationMs", retrieval.diagnostics().totalDurationMs());
            diagnostics.put("degradationReasons", retrieval.diagnostics().degradationReasons());
        }
        return diagnostics;
    }
}
