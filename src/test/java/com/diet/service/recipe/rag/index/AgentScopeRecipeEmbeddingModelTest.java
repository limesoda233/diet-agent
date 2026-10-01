package com.diet.service.recipe.rag.index;

import com.diet.service.recipe.rag.RecipeRagDependencyException;
import io.agentscope.core.embedding.EmbeddingModel;
import io.agentscope.core.embedding.ollama.OllamaTextEmbedding;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.model.ExecutionConfig;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentScopeRecipeEmbeddingModelTest {

    @Test
    void exposesRealAgentScopeOllamaMetadataWithoutCallingTheServer() {
        EmbeddingModel model = OllamaTextEmbedding.builder()
                .baseUrl("http://localhost:11434")
                .modelName("bge-m3:567m")
                .dimensions(1024)
                .executionConfig(ExecutionConfig.builder()
                        .timeout(Duration.ofSeconds(3)).maxAttempts(1).build())
                .build();

        var adapter = new AgentScopeRecipeEmbeddingModel(model);

        assertThat(adapter.modelName()).isEqualTo("bge-m3:567m");
        assertThat(adapter.dimensions()).isEqualTo(1024);
    }

    @Test
    void rejectsVectorsWhoseRuntimeDimensionDoesNotMatchConfiguration() {
        EmbeddingModel model = mock(EmbeddingModel.class);
        when(model.getModelName()).thenReturn("fixture");
        when(model.getDimensions()).thenReturn(3);
        when(model.embed(any(ContentBlock.class))).thenReturn(Mono.just(new double[]{1, 2}));

        assertThat(org.assertj.core.api.Assertions.catchThrowable(
                () -> new AgentScopeRecipeEmbeddingModel(model).embed("番茄炒蛋").block()))
                .isInstanceOf(RecipeRagDependencyException.class)
                .hasMessageContaining("embedding_dimension_mismatch");
    }
}
