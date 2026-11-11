package com.diet.service.recipe.rag;

import com.diet.service.recipe.rag.index.RecipeEmbeddingModel;
import com.diet.service.recipe.rag.index.RecipeVectorIndex;
import com.diet.service.recipe.rag.model.RecipeQuery;
import com.diet.service.recipe.rag.retrieval.RecipeQueryRewriter;
import com.diet.service.recipe.rag.retrieval.RecipeReranker;
import com.diet.service.recipe.rag.retrieval.HttpRecipeReranker;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

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

    @Test
    void enabledRerankRequiresCredentialsAndExplicitFilterThreshold() {
        contextRunner.withPropertyValues("diet.recipe-rag.enabled=true", "diet.recipe-rag.rerank.enabled=true")
                .run(context -> assertThat(context).hasFailed());
        contextRunner.withPropertyValues("diet.recipe-rag.enabled=true", "diet.recipe-rag.rerank.enabled=true",
                        "diet.recipe-rag.rerank.api-key=fixture-secret")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void enabledRerankBuildsLazyScorerAndRerankOnlyNeedsNoThreshold() {
        contextRunner.withPropertyValues("diet.recipe-rag.enabled=true", "diet.recipe-rag.rerank.enabled=true",
                        "diet.recipe-rag.rerank.api-key=fixture-secret", "diet.recipe-rag.rerank.min-score=0.5")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(RecipeReranker.class)).isInstanceOf(HttpRecipeReranker.class);
                });
        contextRunner.withPropertyValues("diet.recipe-rag.enabled=true", "diet.recipe-rag.rerank.enabled=true",
                        "diet.recipe-rag.rerank.api-key=fixture-secret", "diet.recipe-rag.rerank.filter-enabled=false")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void queryRewriteFlagSwitchesFromIdentityToLightModel() {
        RecordingModel model = new RecordingModel();

        contextRunner.withBean("DietLightChatModel", Model.class, () -> model)
                .withPropertyValues(
                        "diet.recipe-rag.enabled=true",
                        "diet.recipe-rag.query-rewrite.enabled=true")
                .run(context -> {
                    RecipeQuery original = new RecipeQuery(
                            "西红市炒鸡蛋咋做", List.of(), List.of(), List.of(), null);
                    RecipeQuery actual = context.getBean(RecipeQueryRewriter.class).rewrite(original).block();

                    assertThat(actual.text()).isEqualTo("西红柿炒鸡蛋 做法");
                    assertThat(model.calls).hasValue(1);
                });
    }

    private static final class RecordingModel implements Model {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            calls.incrementAndGet();
            return Flux.just(ChatResponse.builder().content(List.of(
                    TextBlock.builder().text("{\"query\":\"西红柿炒鸡蛋 做法\"}").build())).build());
        }

        @Override
        public String getModelName() {
            return "test-light-model";
        }
    }
}
