package com.diet.service.recipe;

import com.diet.mapper.MealMapper;
import com.diet.model.MealItemRow;
import com.diet.service.meal.knowledge.MealKnowledge;
import com.diet.service.meal.knowledge.MealKnowledgeRepository;
import com.diet.service.recipe.rag.RecipeRagProperties;
import com.diet.service.recipe.rag.answer.RecipeAnswerGenerator;
import com.diet.service.recipe.rag.model.RecipeAnswer;
import com.diet.service.recipe.rag.model.RecipeCitation;
import com.diet.service.recipe.rag.model.RecipeQuery;
import com.diet.service.recipe.rag.model.RecipeRetrievalResult;
import com.diet.service.recipe.rag.retrieval.HybridRecipeRetriever;
import com.diet.service.recipe.rag.retrieval.RecipeQueryRewriter;
import com.diet.service.recipe.rag.retrieval.RecipeReranker;
import com.diet.service.recipe.rag.retrieval.RecipeRetrievalMode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class RecipeQueryService {
    private static final String NO_EVIDENCE = "当前菜谱库中没有足够证据回答这个问题。你可以换一道菜名，或补充想用的食材。";
    private final RecipeQueryRouter router;
    private final MealKnowledgeRepository knowledgeRepository;
    private final MealMapper mealMapper;
    private final RecipeQueryRewriter queryRewriter;
    private final HybridRecipeRetriever retriever;
    private final RecipeReranker reranker;
    private final RecipeAnswerGenerator answerGenerator;
    private final RecipeRagProperties properties;

    public RecipeQueryService(RecipeQueryRouter router,
                              MealKnowledgeRepository knowledgeRepository,
                              MealMapper mealMapper,
                              RecipeQueryRewriter queryRewriter,
                              HybridRecipeRetriever retriever,
                              RecipeReranker reranker,
                              RecipeAnswerGenerator answerGenerator,
                              RecipeRagProperties properties) {
        this.router = router;
        this.knowledgeRepository = knowledgeRepository;
        this.mealMapper = mealMapper;
        this.queryRewriter = queryRewriter;
        this.retriever = retriever;
        this.reranker = reranker;
        this.answerGenerator = answerGenerator;
        this.properties = properties;
    }

    public boolean matches(String message, Map<String, Object> context) {
        return router.matches(message, context);
    }

    public RecipeAnswer answer(String sessionId, Long userId, String message, Map<String, Object> context) {
        RecipeQueryRouter.RoutedRecipeQuery routed = router.route(message, context)
                .orElseThrow(() -> new IllegalArgumentException("不是菜谱查询"));
        RecipeQuery query = routed.query();
        if (routed.mealId() != null) {
            var personal = knowledgeRepository.findPersonal(routed.mealId(), userId);
            if (personal.isPresent()) return personalAnswer(routed.mealId(), personal.get(), query);
            MealItemRow meal = mealMapper.findPersonalById(routed.mealId(), userId);
            if (meal == null) return RecipeAnswer.refused("没有找到这道个人餐食，无法确定要查询的菜谱。",
                    Map.of("route", "meal_id_not_found"));
            query = new RecipeQuery(meal.getName() + " 怎么做", query.requiredIngredients(),
                    query.excludedIngredients(), query.requiredTools(), query.maxMinutes());
        }

        try {
            RecipeQuery rewritten = queryRewriter.rewrite(query).block();
            if (rewritten == null) rewritten = query;
            RecipeRetrievalResult retrieval = retriever.retrieve(rewritten, configuredMode()).block();
            if (retrieval == null || retrieval.recipes().isEmpty()) {
                return RecipeAnswer.refused(NO_EVIDENCE, retrievalDiagnostics(retrieval, "no_evidence"));
            }
            List<com.diet.service.recipe.rag.model.RetrievedRecipe> reranked =
                    reranker.rerank(rewritten, retrieval.recipes()).block();
            if (reranked == null) reranked = retrieval.recipes();
            RecipeRetrievalResult finalResult = new RecipeRetrievalResult(reranked, retrieval.diagnostics());
            return answerGenerator.generate(sessionId, rewritten, finalResult);
        } catch (RuntimeException error) {
            return RecipeAnswer.refused("菜谱检索暂时不可用，请稍后再试。",
                    Map.of("route", "retrieval_failed", "error", error.getClass().getSimpleName()));
        }
    }

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
