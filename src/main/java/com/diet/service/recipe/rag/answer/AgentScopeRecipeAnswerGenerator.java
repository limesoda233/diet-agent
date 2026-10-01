package com.diet.service.recipe.rag.answer;

import com.diet.agent.factory.AgentFactory;
import com.diet.service.recipe.rag.model.RecipeAnswer;
import com.diet.service.recipe.rag.model.RecipeCitation;
import com.diet.service.recipe.rag.model.RecipeDocument;
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

public class AgentScopeRecipeAnswerGenerator implements RecipeAnswerGenerator {
    private static final String REFUSAL = "当前菜谱库中没有足够证据回答这个问题。你可以换一道菜名，或补充想用的食材。";
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
        Map<String, Object> diagnostics = diagnostics(retrieval);
        if (evidence.citations().isEmpty()) return RecipeAnswer.refused(REFUSAL, diagnostics);
        try {
            String prompt = prompt(query, evidence.promptEvidence());
            String raw;
            if (model != null) {
                raw = model.generate(prompt);
            } else {
                ReActAgent agent = agents.get(sessionId).recipeAnswer();
                if (agent == null) throw new IllegalStateException("recipe answer agent is unavailable");
                raw = trace.callAgent(sessionId, "RecipeAnswerAgent", "main", agent, prompt).getTextContent();
            }
            RecipeAnswer parsed = parse(raw, evidence.citations(), diagnostics);
            return parsed == null ? deterministic(evidence, diagnostics) : parsed;
        } catch (RuntimeException error) {
            return deterministic(evidence, diagnostics);
        }
    }

    private RecipeAnswer parse(String raw, List<RecipeCitation> available, Map<String, Object> diagnostics) {
        try {
            JsonNode root = json.readTree(stripFence(raw));
            if (root.path("refused").asBoolean(false)) return RecipeAnswer.refused(REFUSAL, diagnostics);
            String text = root.path("text").asText("").trim();
            if (text.isBlank() || !root.path("citationIds").isArray()) return null;
            Map<String, RecipeCitation> byId = new LinkedHashMap<>();
            available.forEach(citation -> byId.put(citation.id(), citation));
            Set<String> ids = new LinkedHashSet<>();
            root.path("citationIds").forEach(node -> ids.add(node.asText()));
            if (ids.isEmpty() || !byId.keySet().containsAll(ids)) return null;
            if (ids.stream().anyMatch(id -> !text.contains("[" + id + "]"))) return null;
            List<RecipeCitation> used = ids.stream().map(byId::get).toList();
            return new RecipeAnswer(text + sourceFooter(used), used, false, withGeneration(diagnostics, "agent"));
        } catch (Exception ignored) {
            return null;
        }
    }

    private RecipeAnswer deterministic(RecipeEvidenceAssembler.EvidenceBundle evidence,
                                       Map<String, Object> diagnostics) {
        RecipeDocument recipe = evidence.documents().get(0);
        RecipeCitation citation = evidence.citations().get(0);
        String id = "[" + citation.id() + "]";
        String ingredients = recipe.ingredients().isEmpty() ? "原文未列出" : String.join("、", recipe.ingredients());
        List<String> numbered = new ArrayList<>();
        for (int i = 0; i < recipe.steps().size(); i++) numbered.add((i + 1) + ". " + recipe.steps().get(i));
        String steps = numbered.isEmpty() ? "原文没有足够步骤" : String.join("\n", numbered);
        String text = "**" + recipe.title() + "** " + id + "\n\n食材：" + ingredients + " " + id
                + "\n\n步骤：\n" + steps + " " + id + "\n\n来源：" + recipe.relativePath() + " " + id;
        return new RecipeAnswer(text, List.of(citation), false,
                withGeneration(diagnostics, "deterministic_fallback"));
    }

    private String prompt(RecipeQuery query, String evidence) {
        return "用户问题：" + query.text() + "\n必须食材：" + String.join("、", query.requiredIngredients())
                + "\n排除食材：" + String.join("、", query.excludedIngredients())
                + "\n\n你只能根据以下证据回答。食材和步骤必须使用 [S1] 形式引用。"
                + "不得补写热量、营养或证据中不存在的替换方案。证据不足或约束冲突时 refused=true。"
                + "只输出 JSON：{\"text\":\"...\",\"citationIds\":[\"S1\"],\"refused\":false}\n\n"
                + evidence;
    }

    private String sourceFooter(List<RecipeCitation> citations) {
        StringBuilder footer = new StringBuilder("\n\n来源：");
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
