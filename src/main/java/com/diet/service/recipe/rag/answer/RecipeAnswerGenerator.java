package com.diet.service.recipe.rag.answer;

import com.diet.service.recipe.rag.model.RecipeAnswer;
import com.diet.service.recipe.rag.model.RecipeQuery;
import com.diet.service.recipe.rag.model.RecipeRetrievalResult;

@FunctionalInterface
public interface RecipeAnswerGenerator {
    RecipeAnswer generate(String sessionId, RecipeQuery query, RecipeRetrievalResult retrieval);
}
