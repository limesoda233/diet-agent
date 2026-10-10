package com.diet.service.recipe.rag;

import com.diet.service.recipe.rag.index.AgentScopeRecipeEmbeddingModel;
import com.diet.service.recipe.rag.index.LuceneRecipeSparseIndex;
import com.diet.service.recipe.rag.index.QdrantRecipeVectorIndex;
import com.diet.service.recipe.rag.index.RecipeEmbeddingModel;
import com.diet.service.recipe.rag.index.RecipeSparseIndex;
import com.diet.service.recipe.rag.index.RecipeVectorIndex;
import com.diet.service.recipe.rag.retrieval.IdentityRecipeQueryRewriter;
import com.diet.service.recipe.rag.retrieval.IdentityRecipeReranker;
import com.diet.service.recipe.rag.retrieval.AgentScopeRecipeQueryRewriter;
import com.diet.service.recipe.rag.retrieval.RecipeQueryRewriter;
import com.diet.service.recipe.rag.retrieval.RecipeReranker;
import com.diet.service.trace.AgentTraceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.embedding.EmbeddingModel;
import io.agentscope.core.embedding.ollama.OllamaTextEmbedding;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.Model;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;
import java.nio.file.Path;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RecipeRagProperties.class)
@ConditionalOnProperty(prefix = "diet.recipe-rag", name = "enabled", havingValue = "true")
public class RecipeRagConfiguration {

    @Bean
    EmbeddingModel recipeAgentScopeEmbeddingModel(RecipeRagProperties properties) {
        RecipeRagProperties.Embedding config = properties.getEmbedding();
        if (config.getDimensions() <= 0) throw new IllegalArgumentException("recipe embedding dimensions must be positive");
        return OllamaTextEmbedding.builder()
                .baseUrl(config.getBaseUrl())
                .modelName(config.getModelName())
                .dimensions(config.getDimensions())
                .executionConfig(ExecutionConfig.builder()
                        .timeout(config.getTimeout()).maxAttempts(config.getMaxAttempts()).build())
                .build();
    }

    @Bean
    RecipeEmbeddingModel recipeEmbeddingModel(EmbeddingModel recipeAgentScopeEmbeddingModel) {
        return new AgentScopeRecipeEmbeddingModel(recipeAgentScopeEmbeddingModel);
    }

    @Bean
    HttpClient recipeRagHttpClient(RecipeRagProperties properties) {
        return HttpClient.newBuilder().connectTimeout(properties.getQdrant().getTimeout()).build();
    }

    @Bean
    RecipeVectorIndex recipeVectorIndex(RecipeRagProperties properties,
                                        HttpClient recipeRagHttpClient,
                                        ObjectMapper objectMapper) {
        return new QdrantRecipeVectorIndex(properties, recipeRagHttpClient, objectMapper);
    }

    @Bean
    RecipeSparseIndex recipeSparseIndex(RecipeRagProperties properties) {
        return new LuceneRecipeSparseIndex(Path.of(properties.getLuceneDirectory()));
    }

    @Bean
    RecipeQueryRewriter recipeQueryRewriter(
            RecipeRagProperties properties,
            ObjectMapper objectMapper,
            @Qualifier("DietLightChatModel") ObjectProvider<Model> lightModelProvider,
            ObjectProvider<AgentTraceService> traceServiceProvider) {
        if (properties.getQueryRewrite().isEnabled()) {
            Model lightModel = lightModelProvider.getIfAvailable();
            if (lightModel == null) {
                throw new IllegalStateException(
                        "diet.recipe-rag.query-rewrite.enabled=true requires DietLightChatModel");
            }
            return new AgentScopeRecipeQueryRewriter(
                    lightModel,
                    objectMapper,
                    properties.getQueryRewrite().getTimeout(),
                    traceServiceProvider.getIfAvailable());
        }
        return RecipeQueryRewriter.withFallback(
                new IdentityRecipeQueryRewriter(), properties.getQueryRewrite().getTimeout());
    }

    @Bean
    RecipeReranker recipeReranker(RecipeRagProperties properties) {
        return RecipeReranker.withFallback(new IdentityRecipeReranker(), properties.getRerank().getTimeout());
    }
}
