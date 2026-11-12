package com.diet.model;

import java.util.List;

import com.diet.service.video.CookingVideo;
import com.diet.service.meal.intake.MealDraft;
import com.fasterxml.jackson.annotation.JsonAutoDetect;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

@Data
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
@Accessors(fluent = true)
@AllArgsConstructor
@NoArgsConstructor
public class ChatResponse {
    private String sessionId;
    private String traceId;
    private String responseType;
    private String speechText;
    private List<MealResponse> displayBlocks;
    private List<MealPlanBlock> planBlocks;
    private List<VideoResponse> videoBlocks;
    private String nextAction;
    private String clarifyQuestion;
    private List<String> missingSlots;
    private MealDraft mealDraft;
    private List<String> memoryNotices;
    private com.diet.service.meal.planning.PlanningTypes.Result mealPlan;

    public ChatResponse(String sessionId,String traceId,String responseType,String speechText,
                        List<MealResponse> displayBlocks,List<MealPlanBlock> planBlocks,List<VideoResponse> videoBlocks,
                        String nextAction,String clarifyQuestion,List<String> missingSlots,MealDraft mealDraft,List<String> memoryNotices) {
        this(sessionId,traceId,responseType,speechText,displayBlocks,planBlocks,videoBlocks,nextAction,clarifyQuestion,missingSlots,mealDraft,memoryNotices,null);
    }

    public ChatResponse(String sessionId, String traceId, String responseType, String speechText,
                        List<MealResponse> displayBlocks, List<MealPlanBlock> planBlocks,
                        List<VideoResponse> videoBlocks, String nextAction, String clarifyQuestion,
                        List<String> missingSlots, MealDraft mealDraft) {
        this(sessionId, traceId, responseType, speechText, displayBlocks, planBlocks, videoBlocks,
                nextAction, clarifyQuestion, missingSlots, mealDraft, List.of());
    }

    public ChatResponse(String sessionId, String traceId, String responseType, String speechText,
                        List<MealResponse> displayBlocks, List<MealPlanBlock> planBlocks,
                        List<VideoResponse> videoBlocks, String nextAction, String clarifyQuestion,
                        List<String> missingSlots) {
        this(sessionId, traceId, responseType, speechText, displayBlocks, planBlocks, videoBlocks,
                nextAction, clarifyQuestion, missingSlots, null);
    }

    public static ChatResponse mealDraft(String sessionId, String traceId, String speechText, MealDraft draft) {
        String next = draft == null ? "WAIT_USER" : switch (draft.status()) {
            case NEEDS_INPUT -> "ASK_CLARIFY";
            case READY, AWAITING_MERGE -> "WAIT_USER";
            case SAVED, CANCELLED -> "WAIT_USER";
        };
        return new ChatResponse(sessionId, traceId, "MEAL_DRAFT", speechText,
                List.of(), List.of(), List.of(), next, null,
                draft == null ? List.of() : draft.missingFields(), draft);
    }

    public static ChatResponse answer(String sessionId, String speechText, List<MealResponse> displayBlocks, String nextAction) {
        return answer(sessionId, null, speechText, displayBlocks, nextAction);
    }

    public static ChatResponse answer(String sessionId, String traceId, String speechText, List<MealResponse> displayBlocks, String nextAction) {
        return new ChatResponse(sessionId, traceId, "ANSWER", speechText,
                displayBlocks == null ? List.of() : displayBlocks,
                List.of(), List.of(), nextAction, null, List.of());
    }

    public static ChatResponse plan(
            String sessionId,
            String traceId,
            String speechText,
            List<MealResponse> displayBlocks,
            List<MealPlanBlock> planBlocks,
            String nextAction) {
        return new ChatResponse(sessionId, traceId, "PLAN", speechText,
                displayBlocks == null ? List.of() : displayBlocks,
                planBlocks == null ? List.of() : planBlocks,
                List.of(), nextAction, null, List.of());
    }

    public static ChatResponse recipe(String sessionId,
                                      String traceId,
                                      String speechText,
                                      List<CookingVideo> videos) {
        List<VideoResponse> blocks = videos == null ? List.of() : videos.stream()
                .map(VideoResponse::from)
                .toList();
        return new ChatResponse(sessionId, traceId, "ANSWER", speechText,
                List.of(), List.of(), blocks, "WAIT_USER", null, List.of());
    }

    public static ChatResponse videos(String sessionId,
                                      String traceId,
                                      String speechText,
                                      List<CookingVideo> videos) {
        List<VideoResponse> blocks = videos == null ? List.of() : videos.stream()
                .map(VideoResponse::from)
                .toList();
        return new ChatResponse(sessionId, traceId, "VIDEOS", speechText,
                List.of(), List.of(), blocks, "WAIT_USER", null, List.of());
    }

    public static ChatResponse clarify(String sessionId, String question, List<String> missingSlots) {
        return clarify(sessionId, null, question, missingSlots);
    }

    public static ChatResponse clarify(String sessionId, String traceId, String question, List<String> missingSlots) {
        return new ChatResponse(sessionId, traceId, "CLARIFY", question, List.of(), List.of(), List.of(),
                "ASK_CLARIFY", question, missingSlots == null ? List.of() : List.copyOf(missingSlots));
    }
}




