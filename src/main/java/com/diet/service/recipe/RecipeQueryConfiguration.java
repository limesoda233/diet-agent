package com.diet.service.recipe;

import com.diet.agent.factory.AgentFactory;
import com.diet.mapper.MealMapper;
import com.diet.service.meal.knowledge.MealKnowledgeRepository;
import com.diet.service.recipe.rag.RecipeRagProperties;
import com.diet.service.recipe.rag.answer.AgentScopeRecipeAnswerGenerator;
import com.diet.service.recipe.rag.answer.RecipeAnswerGenerator;
import com.diet.service.recipe.rag.answer.RecipeEvidenceAssembler;
import com.diet.service.recipe.rag.corpus.HowToCookMarkdownParser;
import com.diet.service.recipe.rag.corpus.RecipeCorpusImporter;
import com.diet.service.recipe.rag.index.RecipeEmbeddingModel;
import com.diet.service.recipe.rag.index.RecipeIndexCoordinator;
import com.diet.service.recipe.rag.index.RecipeSparseIndex;
import com.diet.service.recipe.rag.index.RecipeVectorIndex;
import com.diet.service.recipe.rag.model.RecipeDocument;
import com.diet.service.recipe.rag.retrieval.HybridRecipeRetriever;
import com.diet.service.recipe.rag.retrieval.RecipeParentResolver;
import com.diet.service.recipe.rag.retrieval.RecipeQueryRewriter;
import com.diet.service.recipe.rag.retrieval.RecipeReranker;
import com.diet.service.trace.AgentTraceService;
import com.diet.service.video.CookingVideoSearchService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "diet.recipe-rag", name = "enabled", havingValue = "true")
public class RecipeQueryConfiguration {

    @Bean
    RecipeVideoCoordinator recipeVideoCoordinator(CookingVideoSearchService videos) {
        return new RecipeVideoCoordinator(videos);
    }

    @Bean
    RecipeQueryRouter recipeQueryRouter() {
        return new RecipeQueryRouter();
    }

    @Bean
    RecipeEvidenceAssembler recipeEvidenceAssembler() {
        return new RecipeEvidenceAssembler(6000, 3);
    }

    @Bean
    RecipeAnswerGenerator recipeAnswerGenerator(RecipeEvidenceAssembler assembler,
                                                 AgentFactory agents,
                                                 AgentTraceService trace,
                                                 ObjectMapper json) {
        return new AgentScopeRecipeAnswerGenerator(assembler, agents, trace, json);
    }

    @Bean
    RecipeParentResolver recipeParentResolver(RecipeRagProperties properties) {
        Path corpusRoot = Path.of(properties.getCorpusPath());
        List<RecipeDocument> documents = Files.isDirectory(corpusRoot)
                ? new RecipeCorpusImporter(new HowToCookMarkdownParser())
                    .importFrom(corpusRoot, properties.getSourceCommit()).documents()
                : List.of();
        return new RecipeParentResolver(documents);
    }

    @Bean
    RecipeCorpusImporter recipeCorpusImporter() {
        return new RecipeCorpusImporter(new HowToCookMarkdownParser());
    }

    @Bean
    RecipeIndexCoordinator recipeIndexCoordinator(RecipeCorpusImporter importer,
                                                   RecipeSparseIndex sparseIndex,
                                                   RecipeEmbeddingModel embeddingModel,
                                                   RecipeVectorIndex vectorIndex,
                                                   RecipeRagProperties properties) {
        return new RecipeIndexCoordinator(importer, sparseIndex, embeddingModel, vectorIndex,
                properties, "howtocook-v1");
    }

    @Bean
    HybridRecipeRetriever hybridRecipeRetriever(RecipeSparseIndex sparseIndex,
                                                 RecipeEmbeddingModel embeddingModel,
                                                 RecipeVectorIndex vectorIndex,
                                                 RecipeParentResolver parentResolver,
                                                 RecipeRagProperties properties) {
        String activeVersion = sparseIndex.activeVersion();
        if (activeVersion != null) vectorIndex.activateVersion(activeVersion);
        var retrieval = properties.getRetrieval();
        return new HybridRecipeRetriever(sparseIndex, embeddingModel, vectorIndex, parentResolver,
                retrieval.getBranchTimeout(), retrieval.getSparseTopK(), retrieval.getDenseTopK(),
                retrieval.getFinalTopK(), retrieval.getRrfRankConstant());
    }

    @Bean
    RecipeQueryService recipeQueryService(RecipeQueryRouter router,
                                          MealKnowledgeRepository knowledgeRepository,
                                          MealMapper mealMapper,
                                          RecipeQueryRewriter queryRewriter,
                                          HybridRecipeRetriever retriever,
                                          RecipeReranker reranker,
                                          RecipeAnswerGenerator answerGenerator,
                                          RecipeRagProperties properties) {
        return new RecipeQueryService(router, knowledgeRepository, mealMapper, queryRewriter,
                retriever, reranker, answerGenerator, properties);
    }
}
