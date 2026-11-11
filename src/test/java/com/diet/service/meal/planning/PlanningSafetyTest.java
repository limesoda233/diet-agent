package com.diet.service.meal.planning;

import org.junit.jupiter.api.Test;
import java.util.*;
import static com.diet.service.meal.planning.PlanningTypes.*;
import static org.assertj.core.api.Assertions.*;

class PlanningSafetyTest {
    static final FrozenContext CONTEXT=new FrozenContext("epoch",Map.of(),com.diet.service.meal.constraint.MealConstraints.empty(),List.of(),List.of(),List.of());
    static final Task A=new Task("d0-dinner",0,"晚餐",List.of());
    static final Task B=new Task("d1-dinner",1,"晚餐",List.of());
    static Candidate meal(long id) {return new Candidate(id,com.diet.enums.SourceMode.PERSONAL,7L,"餐"+id,List.of("晚餐"),List.of("鸡蛋"),true,Map.of("cuisine",List.of("家常菜")),Map.of("meal:"+id+":cuisine:家常菜","家常菜"),"hash"+id);}
    @Test void maximumMatchingPreservesScarceCandidateForItsOnlyTask() {
        Snapshot snapshot=new Snapshot(Map.of(A.taskId(),new Pool(List.of(meal(1),meal(2)),false,false),B.taskId(),new Pool(List.of(meal(1)),false,false)),false,false);
        assertThat(new PlanValidator().maximumMatching(new Spec(List.of(A,B),List.of()),snapshot)).containsEntry(A.taskId(),2L).containsEntry(B.taskId(),1L);
    }
    @Test void rejectsCrossTaskMembershipOwnershipAndInventedFacts() {
        var validator=new PlanValidator();var input=PlanningRequestParserTest.input("未来两天晚餐不要重复",Map.of());
        var range=new Range(java.time.LocalDate.of(2026,10,21),2,List.of("晚餐"),RepeatPolicy.HARD,List.of());
        var spec=new Spec(List.of(A,B),List.of());
        var snapshot=new Snapshot(Map.of(A.taskId(),new Pool(List.of(meal(1)),false,false),B.taskId(),new Pool(List.of(meal(2)),false,false)),false,false);
        var invalid=new Draft(List.of(new Assignment(A.taskId(),2L,List.of()),new Assignment(B.taskId(),2L,List.of("meal:1:cuisine:家常菜"))),List.of());
        var result=validator.validate(input,CONTEXT,range,spec,snapshot,invalid);
        assertThat(result.assignments()).allMatch(a->a.mealId()==null);
        assertThat(result.errors()).anyMatch(e->e.contains("CANDIDATE_MEMBERSHIP")).anyMatch(e->e.contains("FACT_REFERENCE"));
        assertThat(result.gaps()).hasSize(2);
        var foreign=new Candidate(1,com.diet.enums.SourceMode.PERSONAL,99L,"别人的餐",List.of("晚餐"),List.of("鸡蛋"),true,Map.of(),Map.of(),"foreign");
        assertThat(validator.eligible(input,CONTEXT,A,foreign)).isFalse();
    }
    @Test void unknownIngredientsCannotSatisfyIngredientHardConstraints() {
        var constraints=new com.diet.service.meal.constraint.MealConstraints(List.of(),List.of("花生"),Map.of(),Map.of());
        var context=new FrozenContext("e",Map.of(),constraints,List.of(),List.of(),List.of());
        var meal=new Candidate(1,com.diet.enums.SourceMode.PERSONAL,7L,"未知餐",List.of("晚餐"),List.of(),false,Map.of(),Map.of(),"h");
        assertThat(new PlanValidator().eligible(PlanningRequestParserTest.input("今天晚餐",Map.of()),context,A,meal)).isFalse();
    }
    @Test void roleJsonRejectsExtraFieldsTrailingTextUnknownEnumsAndOversizedLists() {
        var codec=new PlanningJsonCodec();
        assertThatThrownBy(()->codec.spec("{\"tasks\":[],\"clarifications\":[],\"userId\":99}")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->codec.spec("{\"tasks\":[],\"clarifications\":[]} done")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->codec.review("{\"decision\":\"MAYBE\",\"issues\":[]}")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->codec.draft("{\"assignments\":[],\"gaps\":[] ,\"gaps\":[]}")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->codec.spec("{\"tasks\":["+String.join(",",Collections.nCopies(10,"{\"taskId\":\"d0-dinner\",\"dayOffset\":0,\"mealTime\":\"晚餐\",\"preferenceRefs\":[]}"))+"],\"clarifications\":[]}")).isInstanceOf(IllegalArgumentException.class);
        assertThat(codec.draft("{\"assignments\":[{\"taskId\":\"d0-dinner\",\"mealId\":null,\"reasonFactRefs\":[]}],\"gaps\":[{\"taskId\":\"d0-dinner\",\"code\":\"NO_MATCH\"}]}" ).assignments().get(0).mealId()).isNull();
    }
}

