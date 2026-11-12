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
import org.mockito.ArgumentCaptor;

class PlanningMealSearchTest {
    @Test void lightTasteDoesNotRequireASeparateLightHealthGoalTag() {
        var mapper=mock(MealMapper.class);
        var service=new MealService(mapper,mock(SlotOptionService.class),
                new JsonService(new com.fasterxml.jackson.databind.ObjectMapper()));
        var slots=new SlotBundle(List.of("晚餐"),List.of(),List.of(),List.of("清淡"),
                List.of(),List.of("清淡"),List.of());

        service.searchFrozen(SourceMode.PUBLIC,1L,slots,MealConstraints.empty());

        var health=ArgumentCaptor.forClass(String.class);
        var taste=ArgumentCaptor.forClass(String.class);
        verify(mapper).search(eq(SourceMode.PUBLIC),eq(1L),any(),any(),any(),health.capture(),any(),
                taste.capture(),any(),any(),any(),anyInt());
        assertEquals("[]",health.getValue());
        assertEquals("[\"清淡\"]",taste.getValue());
    }

    @Test void anotherHealthGoalRemainsRequiredAlongsideLightTaste() {
        var mapper=mock(MealMapper.class);
        var service=new MealService(mapper,mock(SlotOptionService.class),
                new JsonService(new com.fasterxml.jackson.databind.ObjectMapper()));
        var slots=new SlotBundle(List.of("晚餐"),List.of(),List.of(),List.of("减脂","清淡"),
                List.of(),List.of("清淡"),List.of());

        service.searchFrozen(SourceMode.PUBLIC,1L,slots,MealConstraints.empty());

        var health=ArgumentCaptor.forClass(String.class);
        var taste=ArgumentCaptor.forClass(String.class);
        verify(mapper).search(eq(SourceMode.PUBLIC),eq(1L),any(),any(),any(),health.capture(),any(),
                taste.capture(),any(),any(),any(),anyInt());
        assertEquals("[\"减脂\"]",health.getValue());
        assertEquals("[\"清淡\"]",taste.getValue());
    }

    @Test void lightHealthGoalAlsoSearchesMealsWithOnlyLightTaste() {
        var mapper=mock(MealMapper.class);
        var service=new MealService(mapper,mock(SlotOptionService.class),
                new JsonService(new com.fasterxml.jackson.databind.ObjectMapper()));
        var slots=new SlotBundle(List.of("晚餐"),List.of(),List.of(),List.of("清淡"),
                List.of(),List.of(),List.of());

        service.searchFrozen(SourceMode.PERSONAL,1L,slots,MealConstraints.empty());

        var health=ArgumentCaptor.forClass(String.class);
        var taste=ArgumentCaptor.forClass(String.class);
        verify(mapper).search(eq(SourceMode.PERSONAL),eq(1L),any(),any(),any(),health.capture(),any(),
                taste.capture(),any(),any(),any(),anyInt());
        assertEquals("[]",health.getValue());
        assertEquals("[\"清淡\"]",taste.getValue());
    }

    @Test void lightTasteSearchAlsoIncludesMealsTaggedOnlyWithLightHealthGoal() throws Exception {
        var configuration=new org.apache.ibatis.session.Configuration();
        try(var xml=PlanningMealSearchTest.class.getResourceAsStream("/mapper/MealMapper.xml")) {
            assertNotNull(xml);
            new org.apache.ibatis.builder.xml.XMLMapperBuilder(xml,configuration,
                    "mapper/MealMapper.xml",configuration.getSqlFragments()).parse();
        }
        var params=new HashMap<String,Object>();
        params.put("sourceMode",SourceMode.PUBLIC);
        var sql=configuration.getMappedStatement("com.diet.mapper.MealMapper.search")
                .getBoundSql(params).getSql();
        assertTrue(sql.contains("JSON_OVERLAPS(m.health_goal, JSON_ARRAY('清淡'))"));
    }

    @Test void broadSpicyRequestRecallsMildButNotNonSpicyMeals() {
        var mapper=mock(MealMapper.class);
        var service=new MealService(mapper,mock(SlotOptionService.class),
                new JsonService(new com.fasterxml.jackson.databind.ObjectMapper()));
        var slots=new SlotBundle(List.of("晚餐"),List.of(),List.of(),List.of(),
                List.of(),List.of("辣"),List.of());
        service.searchFrozen(SourceMode.PERSONAL,1L,slots,MealConstraints.empty());
        var taste=ArgumentCaptor.forClass(String.class);
        verify(mapper).search(eq(SourceMode.PERSONAL),eq(1L),any(),any(),any(),any(),any(),
                taste.capture(),any(),any(),any(),anyInt());
        assertTrue(taste.getValue().contains("微辣"));
        assertFalse(taste.getValue().contains("不辣"));
    }
    @Test void specificMildRequestDoesNotExpandToStrongerHeat() {
        var mapper=mock(MealMapper.class);
        var service=new MealService(mapper,mock(SlotOptionService.class),
                new JsonService(new com.fasterxml.jackson.databind.ObjectMapper()));
        var slots=new SlotBundle(List.of("晚餐"),List.of(),List.of(),List.of(),
                List.of(),List.of("微辣"),List.of());
        service.searchFrozen(SourceMode.PERSONAL,1L,slots,MealConstraints.empty());
        var taste=ArgumentCaptor.forClass(String.class);
        verify(mapper).search(any(),any(),any(),any(),any(),any(),any(),taste.capture(),any(),any(),any(),anyInt());
        assertEquals("[\"微辣\"]",taste.getValue());
    }
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
