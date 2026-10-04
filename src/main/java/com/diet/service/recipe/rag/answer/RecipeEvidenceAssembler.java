package com.diet.service.recipe.rag.answer;

import com.diet.service.recipe.rag.model.RecipeCitation;
import com.diet.service.recipe.rag.model.RecipeDocument;
import com.diet.service.recipe.rag.model.RecipeEvidenceContext;
import com.diet.service.recipe.rag.model.RecipeRetrievalResult;

import java.util.ArrayList;
import java.util.List;

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
