package com.diet.service.meal;

import com.diet.enums.SourceMode;
import com.diet.mapper.MealMapper;
import com.diet.model.MealRequest;
import com.diet.service.meal.knowledge.MealKnowledge;
import com.diet.service.meal.knowledge.MealKnowledgeRepository;
import com.diet.service.slot.SlotOptionService;
import com.diet.util.JsonService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MealServiceKnowledgeTest {

    @Test
    void savesPersonalKnowledgeWithoutRetrievalConfiguration() {
        MealMapper mapper = mock(MealMapper.class);
        MealKnowledgeRepository knowledgeRepository = mock(MealKnowledgeRepository.class);
        MealService service = new MealService(
                mapper,
                mock(SlotOptionService.class),
                new JsonService(new ObjectMapper()),
                knowledgeRepository);
        MealKnowledge knowledge = new MealKnowledge(
                List.of("番茄", "鸡蛋"), "家常菜", "先炒蛋", "USER_PROVIDED", "用户录入");
        MealRequest request = new MealRequest("番茄炒蛋", List.of("午餐"), null, null, null, null, null, null)
                .knowledge(knowledge);

        doAnswer(invocation -> {
            invocation.<com.diet.model.MealItemRow>getArgument(0).setId(41L);
            return 1;
        }).when(mapper).insert(any());
        when(knowledgeRepository.findByMealIds(List.of(41L))).thenReturn(Map.of(41L, knowledge));

        var created = service.createPersonalMeal(7L, request);

        verify(knowledgeRepository).save(41L, knowledge);
        assertThat(created.knowledge()).isEqualTo(knowledge);
        assertThat(created.sourceType()).isEqualTo(SourceMode.PERSONAL);
    }
}
