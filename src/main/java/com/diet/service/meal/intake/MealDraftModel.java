package com.diet.service.meal.intake;

import reactor.core.publisher.Mono;

public interface MealDraftModel {
    Mono<String> extract(String systemPrompt, String userPrompt);
}
