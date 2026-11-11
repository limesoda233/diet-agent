package com.diet.service.meal.planning.runtime;

import com.diet.model.*;
import com.diet.service.meal.planning.PlanningTypes.*;
import reactor.core.publisher.Mono;
import java.time.Duration;
import java.util.*;
import java.util.function.Function;

/** Chat adapter retains date groups and exposes only actual selected IDs to memory. */
public class MealPlanningChatService {
    private final Function<Input,Mono<Result>> planning;
    public MealPlanningChatService(Function<Input,Mono<Result>> planning){this.planning=planning;}
    public boolean matches(ChatRequest request) {
        return request.context()!=null&&request.context().containsKey("mealPlan") || request.message()!=null&&request.message().matches("(?s).*(规划|安排.{0,15}(餐|饭)|计划.{0,15}(餐|饭)|三餐|未来.{0,6}天|第[一二三123]天.*(换掉|替换|改掉|修改)).*");
    }
    public ChatResponse plan(Long user,ChatRequest request,SessionState state,String trace) {
        Map<String,Object> structured=Map.of();
        if(request.context()!=null&&request.context().containsKey("mealPlan")) {
            if(!(request.context().get("mealPlan") instanceof Map<?,?> map))return ChatResponse.clarify(state.sessionId(),trace,"mealPlan 必须是日期、天数和餐次组成的对象",List.of("mealPlan"));
            structured=new LinkedHashMap<>();for(var e:map.entrySet())structured.put(String.valueOf(e.getKey()),e.getValue());
        }
        Result result=planning.apply(new Input(user,state.sessionId(),trace,state.sourceMode(),request.message(),state.slots(),state.mealConstraintContext().constraints(),structured)).block(Duration.ofSeconds(45));
        if(result==null)throw new IllegalStateException("规划没有返回结果");
        var cards=result.days().stream().flatMap(d->d.blocks().stream()).map(Block::selectedMeal).filter(Objects::nonNull).map(this::card).toList();
        String text=switch(result.status()) {
            case COMPLETE->"已按日期和餐次生成计划，并完成需求复核。";
            case PARTIAL->"计划存在未满足的餐次或需求，请查看缺口说明。";
            case DEGRADED->"已完成可核验的选菜，但语义复核暂不可用，请查看提示。";
            case NEEDS_INPUT->"需要补充或调整规划条件。";
            case UNAVAILABLE->"规划暂时不可用，请稍后重试。";
        };
        var response=ChatResponse.plan(state.sessionId(),trace,text,cards,List.of(),result.status()==Status.NEEDS_INPUT?"ASK_CLARIFY":"WAIT_USER");
        return response.mealPlan(result);
    }
    private MealResponse card(Candidate c) {
        return new MealResponse(c.mealId(),c.sourceMode(),c.name(),c.mealTimes(),c.tags().getOrDefault("mood",List.of()),c.tags().getOrDefault("scene",List.of()),c.tags().getOrDefault("healthGoal",List.of()),c.tags().getOrDefault("cuisine",List.of()),c.tags().getOrDefault("taste",List.of()),c.tags().getOrDefault("convenience",List.of()),0);
    }
}
