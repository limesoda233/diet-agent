package com.diet.service.recipe.rag.retrieval;

import com.diet.service.recipe.rag.model.RecipeDocument;
import com.diet.service.recipe.rag.model.RecipeHit;
import com.diet.service.recipe.rag.model.RecipeQuery;
import com.diet.service.recipe.rag.model.RetrievedRecipe;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class RecipeParentResolver {
    private final Map<String, RecipeDocument> documents;

    public RecipeParentResolver(List<RecipeDocument> documents) {
        Map<String, RecipeDocument> byId = new LinkedHashMap<>();
        if (documents != null) documents.forEach(document -> byId.put(document.recipeId(), document));
        this.documents = Map.copyOf(byId);
    }

    public List<RetrievedRecipe> resolve(RecipeQuery query, List<RecipeHit> fused,
                                         List<RecipeHit> evidence, int topK) {
        Map<String, List<RecipeHit>> evidenceByParent = new LinkedHashMap<>();
        evidence.stream().sorted(Comparator.comparing(RecipeHit::channel)
                        .thenComparingInt(RecipeHit::rank).thenComparing(RecipeHit::chunkId))
                .forEach(hit -> evidenceByParent.computeIfAbsent(hit.parentRecipeId(), ignored -> new ArrayList<>()).add(hit));
        List<RetrievedRecipe> recipes = new ArrayList<>();
        for (RecipeHit hit : fused) {
            RecipeDocument document = documents.get(hit.parentRecipeId());
            if (document != null && matches(query, document)) {
                recipes.add(new RetrievedRecipe(document, hit.score(),
                        evidenceByParent.getOrDefault(document.recipeId(), List.of())));
                if (recipes.size() == topK) break;
            }
        }
        return List.copyOf(recipes);
    }

    private boolean matches(RecipeQuery query, RecipeDocument document) {
        if (query == null) return true;
        String ingredients = String.join(" ", document.ingredients()).toLowerCase(Locale.ROOT);
        boolean containsRequired = query.requiredIngredients().stream()
                .allMatch(value -> ingredients.contains(value.toLowerCase(Locale.ROOT)));
        boolean containsExcluded = query.excludedIngredients().stream()
                .anyMatch(value -> ingredients.contains(value.toLowerCase(Locale.ROOT)));
        String searchable = (ingredients + " " + String.join(" ", document.sections().values())).toLowerCase(Locale.ROOT);
        boolean hasTools = query.requiredTools().stream()
                .allMatch(value -> searchable.contains(value.toLowerCase(Locale.ROOT)));
        return containsRequired && !containsExcluded && hasTools;
    }
}
