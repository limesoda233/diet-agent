package com.diet.service.retrieval;

import com.diet.service.meal.constraint.MealConstraintContext;

import com.diet.service.meal.constraint.MealConstraints;

import com.diet.enums.SourceMode;
import com.diet.model.*;
import com.diet.service.meal.MealPlanService;
import com.diet.service.recommend.RecommendResponseAgentService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class HybridMealPlanTest {
    @Test void usesCoordinatorOrderAndCarriesConstraintsAndPreviouslySelectedIds() {
        var retrieval=mock(MealRetrievalService.class); var generation=mock(RecommendResponseAgentService.class);
        var service=new MealPlanService(retrieval,generation);
        var constraints=new MealConstraints(null,List.of("牛肉"),null,null);
        when(retrieval.retrieve(any())).thenAnswer(call->{
            MealRetrievalRequest request=call.getArgument(0);
            long id=switch(request.slots().mealTime().get(0)) {case "早餐"->1;case "午餐"->2;default->3;};
            var meal=new MealItem(id,SourceMode.PUBLIC,null,"菜"+id,request.slots(),.02);
            return new MealRetrievalService.Result(List.of(meal),List.of(meal),List.of(),null);
        });
        when(generation.recommendAndRespond(any(),any(),any(),any(),any())).thenAnswer(call->{
            MealItem meal=((List<MealItem>)call.getArgument(4)).get(0);
            var recommendation=new RecommendedMealOption(meal.id(),meal.sourceType(),meal.name(),"匹配",meal.matchScore(),meal.slots());
            return new RecommendResponseAgentService.Result(new RecommendResult(List.of(recommendation),false),new ResponseResult("餐食",List.of(MealResponse.from(meal)),"WAIT_USER"));
        });
        service.buildPlan("s",1L,"三餐",SourceMode.PUBLIC,SlotBundle.empty(),new MealConstraintContext("热食",constraints,"rule"));
        var capture=ArgumentCaptor.forClass(MealRetrievalRequest.class); verify(retrieval,times(3)).retrieve(capture.capture());
        assertThat(capture.getAllValues()).allSatisfy(r->assertThat(r.constraints()).isEqualTo(constraints));
        assertThat(capture.getAllValues().get(2).excludeMealIds()).containsExactly(1L,2L);
        assertThat(capture.getAllValues().get(0).queryText()).isEqualTo("热食");
    }
}
