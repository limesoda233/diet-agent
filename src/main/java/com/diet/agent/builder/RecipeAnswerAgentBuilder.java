package com.diet.agent.builder;

import com.diet.agent.loader.PromptLoader;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.model.Model;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class RecipeAnswerAgentBuilder {
    private final Model mainModel;
    private final PromptLoader promptLoader;

    public RecipeAnswerAgentBuilder(@Qualifier("DietMainChatModel") Model mainModel, PromptLoader promptLoader) {
        this.mainModel = mainModel;
        this.promptLoader = promptLoader;
    }

    public ReActAgent build() {
        return ReActAgent.builder()
                .name("diet_recipe_answer_agent")
                .model(mainModel)
                .sysPrompt(promptLoader.load("diet/prompts/recipe-answer.txt"))
                .memory(new InMemoryMemory())
                .build();
    }
}
