package com.diet.service.recipe.rag.corpus;

import com.diet.service.recipe.rag.index.RecipeIndexCoordinator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;

@Component
public class RecipeIndexCommand implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(RecipeIndexCommand.class);
    private static final String OPTION = "recipe-rag-index";
    private final ObjectProvider<RecipeIndexCoordinator> coordinatorProvider;

    public RecipeIndexCommand(ObjectProvider<RecipeIndexCoordinator> coordinatorProvider) {
        this.coordinatorProvider = coordinatorProvider;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!args.containsOption(OPTION)) return;
        List<String> values = args.getOptionValues(OPTION);
        if (values == null || values.size() != 1 || values.get(0).isBlank()) {
            throw new IllegalArgumentException("--recipe-rag-index 必须提供一个 HowToCook 绝对路径");
        }
        Path root = Path.of(values.get(0)).toAbsolutePath().normalize();
        RecipeIndexCoordinator coordinator = coordinatorProvider.getIfAvailable();
        if (coordinator == null) {
            throw new IllegalStateException("索引命令需要启用 diet.recipe-rag.enabled=true");
        }
        var result = coordinator.rebuild(root);
        log.info("Recipe index activated: version={}, documents={}, chunks={}, embedding={}({})",
                result.indexVersion(), result.documentCount(), result.chunkCount(),
                result.embeddingModel(), result.dimensions());
    }
}
