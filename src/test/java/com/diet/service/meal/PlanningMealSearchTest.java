package com.diet.service.meal;
import com.diet.mapper.MealMapper;
import com.diet.enums.SourceMode;
import com.diet.model.*;
import com.diet.service.meal.constraint.MealConstraints;
import com.diet.service.meal.knowledge.*;
import com.diet.service.memory.MemoryPolicy;
import com.diet.service.slot.SlotOptionService;
import com.diet.util.JsonService;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class PlanningMealSearchTest {
    @Test void frozenSearchAttachesIngredientEvidenceWithoutReadingLiveMemory() {
        var mapper=mock(MealMapper.class);
        var row=new MealItemRow();row.setId(3L);row.setName("鸡蛋面");row.setSourceType("PUBLIC");
        row.setMealTime("[\"晚餐\"]");
        when(mapper.search(any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),anyInt())).thenReturn(List.of(row));
        var knowledge=mock(MealKnowledgeRepository.class);
        var facts=new MealKnowledge(List.of("鸡蛋"),"","","IMPORTED","fixture");
        when(knowledge.findByMealIds(List.of(3L))).thenReturn(Map.of(3L,facts));
        var service=new MealService(mapper,mock(SlotOptionService.class),new JsonService(new com.fasterxml.jackson.databind.ObjectMapper()),knowledge);
        var policy=mock(MemoryPolicy.class);service.setMemoryPolicy(policy);
        var result=service.searchFrozen(SourceMode.PUBLIC,1L,SlotBundle.empty(),MealConstraints.empty());
        assertEquals(List.of("鸡蛋"),result.get(0).knowledge().ingredients());
        assertEquals(3L,result.get(0).id());
        verifyNoInteractions(policy);
    }
}
