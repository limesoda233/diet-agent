package com.diet.service.recipe.rag.answer;

import com.diet.agent.factory.AgentFactory;
import com.diet.service.recipe.rag.model.RecipeAnswer;
import com.diet.service.recipe.rag.model.RecipeAnswerPurpose;
import com.diet.service.recipe.rag.model.RecipeCitation;
import com.diet.service.recipe.rag.model.RecipeDocument;
import com.diet.service.recipe.rag.model.RecipeEvidenceContext;
import com.diet.service.recipe.rag.model.RecipeQuery;
import com.diet.service.recipe.rag.model.RecipeRetrievalResult;
import com.diet.service.trace.AgentTraceService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.ReActAgent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 使用本轮检索证据生成菜谱回答。先组装证据，模型返回 JSON，再核对引用是否合法。
 * 没证据直接拒答；模型异常或输出解析失败时，用首条父菜谱原文构造确定性回答。
 * 合法引用不等于每个事实都被语义验证，Faithfulness 仍需离线 Judge 与人工抽查。
 * 回退同样依赖检索排序：首条证据不相关时，不能声称模板回退自动解决相关性问题。
 */
public class AgentScopeRecipeAnswerGenerator implements RecipeAnswerGenerator {
    private static final String REFUSAL = "当前菜谱库中没有足够证据回答这个问题。你可以换一道菜名，或补充想用的食材。";
    private static final Pattern CITATION_REFERENCE = Pattern.compile("\\[([A-Z][A-Za-z0-9_-]*)\\]");
    private final RecipeEvidenceAssembler assembler;
    private final RawAnswerModel model;
    private final AgentFactory agents;
    private final AgentTraceService trace;
    private final ObjectMapper json;

    public AgentScopeRecipeAnswerGenerator(RecipeEvidenceAssembler assembler,
                                           RawAnswerModel model,
                                           ObjectMapper json) {
        this.assembler = assembler;
        this.model = model;
        this.agents = null;
        this.trace = null;
        this.json = json;
    }

    public AgentScopeRecipeAnswerGenerator(RecipeEvidenceAssembler assembler,
                                           AgentFactory agents,
                                           AgentTraceService trace,
                                           ObjectMapper json) {
        this.assembler = assembler;
        this.model = null;
        this.agents = agents;
        this.trace = trace;
        this.json = json;
    }

    @Override
    public RecipeAnswer generate(String sessionId, RecipeQuery query, RecipeRetrievalResult retrieval) {
        RecipeEvidenceAssembler.EvidenceBundle evidence = assembler.assemble(retrieval);
        RecipeAnswerPurpose purpose = RecipeAnswerPurpose.fromText(query.text());
        Map<String, Object> diagnostics = diagnostics(retrieval);
        diagnostics.put("answerPurpose", purpose.name());
        if (evidence.citations().isEmpty()) return RecipeAnswer.refused(REFUSAL, diagnostics);
        try {
            String prompt = prompt(query, evidence.promptEvidence(), purpose);
            String raw;
            if (model != null) {
                raw = model.generate(prompt);
            } else {
                ReActAgent agent = agents.get(sessionId).recipeAnswer();
                if (agent == null) throw new IllegalStateException("recipe answer agent is unavailable");
                raw = trace.callAgent(sessionId, "RecipeAnswerAgent", "main", agent, prompt).getTextContent();
            }
            RecipeAnswer parsed = parse(raw, evidence.citations(), diagnostics, evidence.contexts(), purpose);
            return parsed == null ? deterministic(evidence, diagnostics, purpose) : parsed;
        } catch (RuntimeException error) {
            return deterministic(evidence, diagnostics, purpose);
        }
    }

    private RecipeAnswer parse(String raw, List<RecipeCitation> available, Map<String, Object> diagnostics,
                               List<RecipeEvidenceContext> evidenceContexts, RecipeAnswerPurpose purpose) {
        try {
            JsonNode root = json.readTree(stripFence(raw));
            // 模型明确拒答时尊重它，不用确定性模板强行补出一个答案。
            if (root.path("refused").asBoolean(false)) return RecipeAnswer.refused(REFUSAL, diagnostics, evidenceContexts);
            String text = root.path("text").asText("").trim();
            if (text.isBlank() || !root.path("citationIds").isArray()) return null;
            Map<String, RecipeCitation> byId = new LinkedHashMap<>();
            available.forEach(citation -> byId.put(citation.id(), citation));
            Set<String> ids = new LinkedHashSet<>();
            root.path("citationIds").forEach(node -> ids.add(node.asText()));
            // 引用必须属于本轮证据，且在正文中实际出现；引用其它轮次的 S 编号无效。
            if (ids.isEmpty() || !byId.keySet().containsAll(ids)) return null;
            List<RecipeCitation> used = ids.stream().map(byId::get).toList();
            String recommendation = "";
            if (purpose == RecipeAnswerPurpose.INGREDIENT_SUGGESTION) {
                if (!validReferences(text, ids)) return null;
                JsonNode recommendationNode = root.path("recommendation");
                if (!recommendationNode.isMissingNode() && !recommendationNode.isNull() && !recommendationNode.isTextual()) return null;
                recommendation = recommendationNode.asText("").trim();
                if (!recommendation.isBlank() && !validReferences(recommendation, ids)) {
                    return null;
                }
            }
            String citedText = recommendation + "\n\n" + text;
            if (ids.stream().anyMatch(id -> !citedText.contains("[" + id + "]"))) return null;
            if (purpose == RecipeAnswerPurpose.INGREDIENT_SUGGESTION && recommendation.isBlank()) {
                var firstReference = CITATION_REFERENCE.matcher(text);
                firstReference.find(); // validReferences 已确保正文有合法的本轮引用。
                recommendation = conservativeRecommendation(byId.get(firstReference.group(1)));
            }
            String answerText = recommendation.isBlank() ? text : recommendation + "\n\n" + text;
            return new RecipeAnswer(answerText + sourceFooter(used), used, false, withGeneration(diagnostics, "agent"), evidenceContexts);
        } catch (Exception ignored) {
            return null;
        }
    }

    private RecipeAnswer deterministic(RecipeEvidenceAssembler.EvidenceBundle evidence,
                                       Map<String, Object> diagnostics, RecipeAnswerPurpose purpose) {
        RecipeDocument recipe = evidence.documents().get(0);
        RecipeCitation citation = evidence.citations().get(0);
        String id = "[" + citation.id() + "]";
        List<String> ingredients = new ArrayList<>();
        if (recipe.ingredients().isEmpty()) {
            ingredients.add("- 原文未列出 " + id);
        } else {
            recipe.ingredients().forEach(value -> ingredients.add("- " + value + " " + id));
        }
        List<String> numbered = new ArrayList<>();
        if (recipe.steps().isEmpty()) {
            numbered.add("1. 原文没有足够步骤 " + id);
        } else {
            for (int i = 0; i < recipe.steps().size(); i++) {
                numbered.add((i + 1) + ". " + recipe.steps().get(i) + " " + id);
            }
        }
        String text = "## " + recipe.title() + " " + id
                + "\n\n### 食材\n" + String.join("\n", ingredients)
                + "\n\n### 步骤\n" + String.join("\n", numbered)
                + "\n\n### 来源\n- " + id + " `" + recipe.relativePath() + "`";
        if (purpose == RecipeAnswerPurpose.INGREDIENT_SUGGESTION) {
            text = conservativeRecommendation(citation) + "\n\n" + text;
        }
        return new RecipeAnswer(text, List.of(citation), false,
                withGeneration(diagnostics, "deterministic_fallback"), evidence.contexts());
    }

    private boolean validReferences(String text, Set<String> declaredIds) {
        var matcher = CITATION_REFERENCE.matcher(text);
        boolean cited = false;
        while (matcher.find()) {
            if (!declaredIds.contains(matcher.group(1))) return false;
            cited = true;
        }
        return cited;
    }

    private String conservativeRecommendation(RecipeCitation citation) {
        return "根据本轮检索，可以优先考虑" + citation.title() + " [" + citation.id() + "]。"
                + "是否适合你现有的食材，仍需核对下列原文原料、调味料及处理状态；未说明持有的原料还需确认／准备。";
    }

    private String prompt(RecipeQuery query, String evidence, RecipeAnswerPurpose purpose) {
        String suggestion = purpose == RecipeAnswerPurpose.INGREDIENT_SUGGESTION
                ? "回答目的：INGREDIENT_SUGGESTION。先在 recommendation 字段输出带本轮引用的推荐说明，直接说明可优先考虑的菜，"
                    + "再由 text 给出首选菜谱。根据本轮证据解释用户提供的主要食材与原文的对应关系，"
                    + "不要求每道菜使用全部可用食材，可用食材不等于必须食材。不能把生／熟或鲜／干原料自动等同。"
                    + "原文要求但用户未说明持有的原料及调味料，应表述为还需确认／准备，不能断言缺少或库存齐全。"
                    + "完整问题中的只有、不要等限制必须保留；明确缺少原料且证据没有替换方案时，不得编造替换。"
                    + "其他候选仅在本轮证据支持时简述；证据不足或约束冲突仍应 refused=true。"
                : "";
        return "用户问题：" + query.text() + "\n必须食材：" + String.join("、", query.requiredIngredients())
                + "\n排除食材：" + String.join("、", query.excludedIngredients())
                + "\n\n你只能根据以下证据回答。食材和步骤必须使用 [S1] 形式引用。"
                + "不得补写热量、营养或证据中不存在的替换方案。证据不足或约束冲突时 refused=true。"
                + suggestion
                + "text 必须使用 Markdown 排版，并依次包含菜名标题、### 食材、### 步骤；食材使用无序列表，步骤使用有序列表。"
                + "只输出 JSON：{\"text\":\"...\",\"citationIds\":[\"S1\"],\"refused\":false"
                + (purpose == RecipeAnswerPurpose.INGREDIENT_SUGGESTION ? ",\"recommendation\":\"推荐说明 [S1]\"" : "")
                + "}\n\n"
                + evidence;
    }

    private String sourceFooter(List<RecipeCitation> citations) {
        StringBuilder footer = new StringBuilder("\n\n### 来源");
        for (RecipeCitation citation : citations) {
            footer.append("\n- [").append(citation.id()).append("] ")
                    .append(citation.title()).append(" · ").append(citation.sourcePath());
            if (citation.sourceCommit() != null && !citation.sourceCommit().isBlank()) {
                footer.append('@').append(citation.sourceCommit());
            }
        }
        return footer.toString();
    }

    private String stripFence(String raw) {
        if (raw == null) return "";
        String value = raw.strip().replace("```json", "").replace("```", "").strip();
        int start = value.indexOf('{');
        int end = value.lastIndexOf('}');
        return start >= 0 && end >= start ? value.substring(start, end + 1) : value;
    }

    private Map<String, Object> diagnostics(RecipeRetrievalResult retrieval) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (retrieval != null && retrieval.diagnostics() != null) {
            result.put("requestedMode", retrieval.diagnostics().requestedMode().name());
            result.put("actualMode", retrieval.diagnostics().actualMode().name());
            result.put("retrievalDurationMs", retrieval.diagnostics().totalDurationMs());
            result.put("degradationReasons", retrieval.diagnostics().degradationReasons());
        }
        return result;
    }

    private Map<String, Object> withGeneration(Map<String, Object> base, String generation) {
        Map<String, Object> result = new LinkedHashMap<>(base);
        result.put("generation", generation);
        return result;
    }

    @FunctionalInterface
    public interface RawAnswerModel {
        String generate(String prompt);
    }
}
