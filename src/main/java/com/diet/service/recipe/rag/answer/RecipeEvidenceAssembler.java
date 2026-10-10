package com.diet.service.recipe.rag.answer;

import com.diet.service.recipe.rag.model.RecipeCitation;
import com.diet.service.recipe.rag.model.RecipeDocument;
import com.diet.service.recipe.rag.model.RecipeEvidenceContext;
import com.diet.service.recipe.rag.model.RecipeRetrievalResult;

import java.util.ArrayList;
import java.util.List;

/**
 * 将最终父菜谱列表裁剪成 Answer Agent 实际看到的证据，并生成本轮 S1/S2 等引用编号。
 * characterBudget 是字符预算，不是模型 token 预算；超过剩余预算时当前实现按字符截断。
 * contexts 保存实际裁剪后的块供 Ragas 导出，不能用未裁剪的完整文档代替评测上下文。
 */
public class RecipeEvidenceAssembler {
    private final int characterBudget;
    private final int maxRecipes;

    public RecipeEvidenceAssembler(int characterBudget, int maxRecipes) {
        if (characterBudget < 500) throw new IllegalArgumentException("evidence character budget must be at least 500");
        if (maxRecipes < 1) throw new IllegalArgumentException("max recipes must be positive");
        this.characterBudget = characterBudget;
        this.maxRecipes = maxRecipes;
    }

    public EvidenceBundle assemble(RecipeRetrievalResult retrieval) {
        if (retrieval == null || retrieval.recipes().isEmpty()) return new EvidenceBundle("", List.of(), List.of(), List.of());
        StringBuilder evidence = new StringBuilder();
        List<RecipeEvidenceContext> contexts = new ArrayList<>();
        List<RecipeCitation> citations = new ArrayList<>();
        List<RecipeDocument> documents = new ArrayList<>();
        for (int i = 0; i < Math.min(maxRecipes, retrieval.recipes().size()); i++) {
            RecipeDocument recipe = retrieval.recipes().get(i).recipe();
            String citationId = "S" + (i + 1);
            String block = render(citationId, recipe);
            int remaining = characterBudget - evidence.length();
            if (remaining <= 0) break;
            if (block.length() > remaining) block = block.substring(0, remaining);
            // 先记录裁剪后 block，保证 Judge 的 retrieved_contexts 与本轮 prompt 一致。
            contexts.add(new RecipeEvidenceContext(citationId, recipe.recipeId(), block));
            evidence.append(block);
            citations.add(new RecipeCitation(citationId, recipe.recipeId(), recipe.title(), recipe.relativePath(),
                    recipe.sourceCommit(), recipe.contentHash()));
            documents.add(recipe);
        }
        return new EvidenceBundle(evidence.toString(), contexts, citations, documents);
    }

    private String render(String id, RecipeDocument recipe) {
        return "[" + id + "] 菜名：" + recipe.title()
                + "\n食材：" + String.join("；", recipe.ingredients())
                + "\n步骤：" + String.join("；", recipe.steps())
                + "\n来源：" + recipe.relativePath() + "@" + recipe.sourceCommit() + "\n\n";
    }

    public record EvidenceBundle(String promptEvidence, List<RecipeEvidenceContext> contexts,
                                 List<RecipeCitation> citations,
                                 List<RecipeDocument> documents) {
        public EvidenceBundle {
            contexts = contexts == null ? List.of() : List.copyOf(contexts);
            citations = citations == null ? List.of() : List.copyOf(citations);
            documents = documents == null ? List.of() : List.copyOf(documents);
        }
    }
}
