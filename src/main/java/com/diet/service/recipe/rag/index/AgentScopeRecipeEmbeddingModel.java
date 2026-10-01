package com.diet.service.recipe.rag.index;

import com.diet.service.recipe.rag.RecipeRagDependencyException;
import io.agentscope.core.embedding.EmbeddingModel;
import io.agentscope.core.message.TextBlock;
import reactor.core.publisher.Mono;

import java.util.Objects;

public class AgentScopeRecipeEmbeddingModel implements RecipeEmbeddingModel {
    private final EmbeddingModel model;

    public AgentScopeRecipeEmbeddingModel(EmbeddingModel model) {
        this.model = Objects.requireNonNull(model, "model");
    }

    @Override
    public Mono<double[]> embed(String text) {
        if (text == null || text.isBlank()) return Mono.error(new IllegalArgumentException("embedding text must not be blank"));
        return model.embed(TextBlock.builder().text(text).build())
                .switchIfEmpty(Mono.error(new RecipeRagDependencyException("embedding_empty_response")))
                .flatMap(vector -> vector.length == dimensions()
                        ? Mono.just(vector)
                        : Mono.error(new RecipeRagDependencyException("embedding_dimension_mismatch: expected "
                                + dimensions() + " but was " + vector.length)));
    }

    @Override public String modelName() { return model.getModelName(); }
    @Override public int dimensions() { return model.getDimensions(); }
}
