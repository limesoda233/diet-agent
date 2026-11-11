package com.diet.service.meal;
import com.diet.service.meal.planning.runtime.PlanningDataAdapter;
import com.diet.service.meal.planning.PlanningTypes.*;
import com.diet.service.meal.constraint.*;
import com.diet.model.*;
import com.diet.enums.SourceMode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import reactor.core.scheduler.Schedulers;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class PlanningDataAdapterTest {
    @Test void lastTwoDaysAreRelativeToTheRequestedRange() {
        var adapter=new PlanningDataAdapter(mock(MealService.class),null,null,new IngredientCatalog(),Schedulers.immediate(),null,new ObjectMapper(),Clock.systemUTC());
        var context=adapter.freeze(new Input(1L,"s1","t1",SourceMode.PUBLIC,"未来两天晚餐，后两天清淡",SlotBundle.empty(),MealConstraints.empty(),Map.of()),Duration.ofSeconds(1)).block();
        assertTrue(context.preferences().stream().filter(p->p.value().equals("清淡")).allMatch(p->p.taskIds().contains("d0-dinner")&&p.taskIds().contains("d1-dinner")&&!p.taskIds().contains("d2-dinner")));
    }
    @Test void sessionPreferencesRemainSoftAndCurrentPreferenceTakesPriority() {
        var adapter=new PlanningDataAdapter(mock(MealService.class),null,null,new IngredientCatalog(),Schedulers.immediate(),null,new ObjectMapper(),Clock.systemUTC());
        var slots=new SlotBundle(null,null,null,null,List.of("川菜"),null,null);
        var context=adapter.freeze(new Input(1L,"s1","t1",SourceMode.PUBLIC,"安排今天晚餐",slots,MealConstraints.empty(),Map.of()),Duration.ofSeconds(1)).block();
        assertTrue(context.preferences().stream().anyMatch(p->p.value().equals("川菜")&&p.sourceRef().equals("SESSION")));
        assertTrue(context.hardConstraints().requiredSlots().isEmpty());
        var changed=adapter.freeze(new Input(1L,"s1","t1",SourceMode.PUBLIC,"安排今天晚餐，想吃粤菜",slots,MealConstraints.empty(),Map.of()),Duration.ofSeconds(1)).block();
        assertFalse(changed.preferences().stream().anyMatch(p->p.value().equals("川菜")));
    }
    @Test void unverifiableNumbersEquipmentAndCurrentAllergyNeverBecomeSatisfiedRequirements() {
        var adapter=new PlanningDataAdapter(mock(MealService.class),null,null,new IngredientCatalog(),Schedulers.immediate(),null,new ObjectMapper(),Clock.systemUTC());
        for(String text:List.of("安排今天晚餐，控制在500卡以内","安排今天晚餐，只用电饭煲","安排今天晚餐，蛋白质30g以上","安排今天晚餐，低于20块钱","安排今天晚餐，总耗时不到一刻钟")) {
            var context=adapter.freeze(new Input(1L,"s1","t1",SourceMode.PUBLIC,text,SlotBundle.empty(),MealConstraints.empty(),Map.of()),Duration.ofSeconds(1)).block();
            assertFalse(context.unsupportedConstraints().isEmpty(),text);
        }
        var allergic=adapter.freeze(new Input(1L,"s1","t1",SourceMode.PUBLIC,"安排今天晚餐，我对花生过敏",SlotBundle.empty(),MealConstraints.empty(),Map.of()),Duration.ofSeconds(1)).block();
        assertFalse(allergic.blockers().isEmpty());
    }
    @Test void candidatesKeepOnlyTrustedSourceAndDetectFactEditsBeforeCommit() {
        var meals=mock(MealService.class);
        var slots=new SlotBundle(List.of("晚餐"),null,null,List.of("清淡"),null,null,null);
        var good=new MealItem(3L,SourceMode.PUBLIC,null,"鸡蛋面",slots,0);
        var foreign=new MealItem(4L,SourceMode.PERSONAL,2L,"私有菜",slots,0);
        when(meals.searchFrozen(any(),any(),any(),any())).thenReturn(List.of(good,foreign));
        when(meals.findPlanningMeals(any(),any(),any())).thenReturn(List.of(good));
        var adapter=new PlanningDataAdapter(meals,null,null,new IngredientCatalog(),Schedulers.immediate(),null,new ObjectMapper(),Clock.fixed(Instant.parse("2026-10-21T00:00:00Z"),ZoneOffset.UTC));
        var input=new Input(1L,"s1","t1",SourceMode.PUBLIC,"未来三天只安排晚餐，第一天清淡，后两天川菜",SlotBundle.empty(),MealConstraints.empty(),Map.of());
        var context=adapter.freeze(input,Duration.ofSeconds(1)).block();
        assertTrue(context.preferences().stream().anyMatch(p->p.value().equals("清淡")&&p.taskIds().equals(List.of("d0-breakfast","d0-lunch","d0-dinner"))));
        assertTrue(context.preferences().stream().anyMatch(p->p.value().equals("川菜")&&p.taskIds().contains("d2-dinner")));
        var pool=adapter.fetch(input,context,new Task("d0-dinner",0,"晚餐",List.of()),5,Duration.ofSeconds(1)).block();
        assertEquals(List.of(3L),pool.candidates().stream().map(Candidate::mealId).toList());
        var snapshot=new Snapshot(Map.of("d0-dinner",pool),false,false);
        assertEquals(Boolean.TRUE,adapter.unchanged(input,context,snapshot,Duration.ofSeconds(1)).block());
        good.name("已修改");
        assertEquals(Boolean.FALSE,adapter.unchanged(input,context,snapshot,Duration.ofSeconds(1)).block());
    }
}
