package com.diet.service.meal;
import com.diet.service.meal.planning.runtime.MealPlanningConfiguration;
import com.diet.service.meal.planning.runtime.MealPlanningChatService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.junit.jupiter.api.Assertions.*;
class PlanningConfigurationTest {
    @Test void disabledByDefaultDoesNotRequireModelsOrDatabase() {
        new ApplicationContextRunner().withUserConfiguration(MealPlanningConfiguration.class).run(context->{
            assertNull(context.getStartupFailure());assertEquals(0,context.getBeansOfType(MealPlanningChatService.class).size());
        });
    }
}
