package com.diet.controller.meal;

import com.diet.auth.AuthPrincipal;
import com.diet.exception.DietException;
import com.diet.service.meal.intake.MealDraft;
import com.diet.service.meal.intake.MealDraftService;
import com.diet.service.trace.AgentTraceService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/diet/meals/personal/draft")
public class MealDraftController {
    private final MealDraftService service;
    private final AgentTraceService trace;
    public MealDraftController(MealDraftService service, AgentTraceService trace) { this.service=service; this.trace=trace; }
    @PostMapping
    public MealDraft generate(@AuthenticationPrincipal AuthPrincipal principal,
            @RequestBody JsonNode request) {
        String traceId = UUID.randomUUID().toString();
        try (var scope = trace.openTrace(traceId,"meal_draft_"+UUID.randomUUID(),principal.userId())) {
            trace.recordEvent("MEAL_DRAFT_REQUESTED","HTTP",Map.of("entry","MANAGEMENT_PAGE"),null);
            try {
                if (request == null || !request.isObject() || request.size() != 1
                        || !request.has("text") || !request.get("text").isTextual())
                    throw new DietException("草稿请求只允许提供文本字段text");
            } catch (DietException error) {
                trace.recordError("MEAL_DRAFT_FAILED","HTTP",Map.of("entry","MANAGEMENT_PAGE"),error);
                throw error;
            }
            return service.generate(request.get("text").textValue());
        }
    }
    @ExceptionHandler(DietException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String,String> invalid(DietException error) { return Map.of("message",error.getMessage()); }
}
