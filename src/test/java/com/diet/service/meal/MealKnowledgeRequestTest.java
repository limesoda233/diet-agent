package com.diet.service.meal;

import com.diet.model.MealRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class MealKnowledgeRequestTest {
    @Test void distinguishesOmittedKnowledgeFromExplicitClear() throws Exception {
        var mapper = new ObjectMapper();
        assertThat(mapper.readValue("{\"name\":\"菜\"}", MealRequest.class).knowledgeProvided()).isFalse();
        assertThat(mapper.readValue("{\"name\":\"菜\",\"knowledge\":null}", MealRequest.class).knowledgeProvided()).isTrue();
        var supplied = mapper.readValue("{\"name\":\"菜\",\"knowledge\":{\"ingredients\":[\"鸡蛋\"],\"sourceType\":\"USER_PROVIDED\"}}", MealRequest.class);
        assertThat(supplied.knowledgeProvided()).isTrue();
        assertThat(supplied.knowledge().ingredients()).containsExactly("鸡蛋");
    }
}
