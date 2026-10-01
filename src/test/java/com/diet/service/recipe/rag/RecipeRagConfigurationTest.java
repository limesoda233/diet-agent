package com.diet.service.recipe.rag;

import com.diet.service.recipe.rag.index.RecipeEmbeddingModel;
import com.diet.service.recipe.rag.index.RecipeVectorIndex;
import com.diet.service.recipe.rag.retrieval.RecipeQueryRewriter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class RecipeRagConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(RecipeRagConfiguration.class)
            .withBean(ObjectMapper.class, ObjectMapper::new);

    @Test
    void recipeRagIsDisabledByDefaultAndCreatesNoExternalClients() {
        assertThat(new RecipeRagProperties().isEnabled()).isFalse();

        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(RecipeEmbeddingModel.class);
            assertThat(context).doesNotHaveBean(RecipeVectorIndex.class);
        });
    }

    @Test
    void enabledConfigurationBuildsLazyClientsWithoutConnecting() {
        contextRunner.withPropertyValues(
                        "diet.recipe-rag.enabled=true",
                        "diet.recipe-rag.embedding.dimensions=1024",
                        "diet.recipe-rag.embedding.model-name=bge-m3:567m")
                .run(context -> {
                    assertThat(context).hasSingleBean(RecipeEmbeddingModel.class);
                    assertThat(context).hasSingleBean(RecipeVectorIndex.class);
                    assertThat(context).hasSingleBean(RecipeQueryRewriter.class);
                });
    }
}
