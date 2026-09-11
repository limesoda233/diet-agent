package com.diet.service.retrieval;

import com.diet.mapper.MealMapper;
import com.diet.model.MealRequest;
import com.diet.service.meal.MealService;
import com.diet.service.slot.SlotOptionService;
import com.diet.util.JsonService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MealKnowledgeCrudTest {
    @Test void unauthorizedMutationDoesNotWriteKnowledgeOrIndexTasks() {
        var mapper=mock(MealMapper.class); var store=mock(JdbcMealRetrievalStore.class);
        var properties=new RetrievalProperties(); properties.setEnabled(true);
        var service=new MealService(mapper,mock(SlotOptionService.class),new JsonService(new ObjectMapper()),store,properties);
        var request=new MealRequest("菜",List.of("晚餐"),null,null,null,null,null,null);
        request.knowledge(new MealKnowledge(List.of("鸡蛋"),"","","USER_PROVIDED",""));
        when(mapper.updatePersonal(any())).thenReturn(0);
        assertThatThrownBy(()->service.updatePersonalMeal(2L,1L,request));
        when(mapper.deletePersonal(1L,2L)).thenReturn(0);
        assertThatThrownBy(()->service.deletePersonalMeal(2L,1L));
        verifyNoInteractions(store);
    }
}
