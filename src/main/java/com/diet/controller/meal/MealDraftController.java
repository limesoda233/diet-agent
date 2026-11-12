package com.diet.controller.meal;

import com.diet.auth.AuthPrincipal;
import com.diet.exception.DietException;
import com.diet.service.meal.intake.MealDraft;
import com.diet.service.meal.intake.MealDraftService;
import com.diet.service.trace.AgentTraceService;
import com.diet.service.trace.MealDiagnosticLog;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/diet/meals/personal/draft")
public class MealDraftController {
    private static final Logger log=LoggerFactory.getLogger(MealDraftController.class);
    private final MealDraftService service;
    private final AgentTraceService trace;
    public MealDraftController(MealDraftService service, AgentTraceService trace) { this.service=service; this.trace=trace; }
    @PostMapping
    public MealDraft generate(@AuthenticationPrincipal AuthPrincipal principal,
            @RequestBody JsonNode request) {
        String traceId = UUID.randomUUID().toString();
        try (var scope = trace.openTrace(traceId,"meal_draft_"+UUID.randomUUID(),principal.userId())) {
            long started=System.nanoTime();
            log.info("DIET_DIAG v=1 event=MEAL_DRAFT_START traceId={} entry=MANUAL",traceId);
            trace.recordEvent("MEAL_DRAFT_REQUESTED","HTTP",Map.of("entry","MANAGEMENT_PAGE"),null);
            try {
                if (request == null || !request.isObject() || request.size() != 1
                        || !request.has("text") || !request.get("text").isTextual())
                    throw new DietException("草稿请求只允许提供文本字段text");
                MealDraft draft=service.generate(principal.userId(),request.get("text").textValue(),null);
                log.info("DIET_DIAG v=1 event=MEAL_DRAFT_DONE traceId={} entry=MANUAL status={} slots={} missing={} durationMs={}",
                        traceId,draft.status(),MealDiagnosticLog.slotCounts(draft.data().toMealRequest().toSlots()),
                        draft.missingFields().size(),(System.nanoTime()-started)/1_000_000);
                return draft;
            } catch (RuntimeException error) {
                log.warn("DIET_DIAG v=1 event=MEAL_DRAFT_FAILED traceId={} entry=MANUAL errorType={} reason={} durationMs={}",
                        traceId,error.getClass().getSimpleName(),MealDiagnosticLog.draftFailure(error),
                        (System.nanoTime()-started)/1_000_000);
                if(error instanceof DietException diet)
                    trace.recordError("MEAL_DRAFT_FAILED","HTTP",Map.of("entry","MANAGEMENT_PAGE"),diet);
                throw error;
            }
        }
    }
    @ExceptionHandler(DietException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String,String> invalid(DietException error) { return Map.of("message",error.getMessage()); }
}
