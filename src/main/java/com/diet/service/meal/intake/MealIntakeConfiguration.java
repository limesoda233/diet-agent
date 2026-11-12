package com.diet.service.meal.intake;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.ObjectProvider;
import com.diet.service.slot.SlotOptionService;
import com.diet.service.trace.AgentTraceService;
import com.diet.service.profile.DietaryProfileService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.model.Model;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MealIntakeProperties.class)
public class MealIntakeConfiguration {
    @Bean
    MealDraftModel mealDraftModel(@Qualifier("DietLightChatModel") Model model,MealIntakeProperties properties) {
        return new AgentScopeMealDraftModel(model,properties.getTimeout());
    }
    @Bean
    MealDraftService mealDraftService(MealDraftModel model,SlotOptionService slots,ObjectMapper json,
            MealIntakeProperties properties,ObjectProvider<AgentTraceService> trace,
            ObjectProvider<DietaryProfileService> profiles) {
        return new MealDraftService(model,slots,json,properties,trace.getIfAvailable(),profiles.getIfAvailable());
    }
}
