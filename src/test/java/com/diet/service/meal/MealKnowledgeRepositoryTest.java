package com.diet.service.meal;

import com.diet.service.meal.knowledge.MealKnowledge;
import com.diet.service.meal.knowledge.MealKnowledgeRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class MealKnowledgeRepositoryTest {

    @Test
    void normalizesIngredientsBeforeSavingBusinessKnowledge() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        MealKnowledgeRepository repository = new MealKnowledgeRepository(jdbc, new ObjectMapper());

        repository.save(41L, new MealKnowledge(
                List.of("西红柿", "番茄", "鸡蛋"), "家常菜", "先炒蛋", "USER_PROVIDED", "用户录入"));

        ArgumentCaptor<Object[]> arguments = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(anyString(), arguments.capture());
        assertThat(arguments.getValue()[0]).isEqualTo(41L);
        assertThat(arguments.getValue()[1].toString()).isEqualTo("[\"番茄\",\"鸡蛋\"]");
    }
}
