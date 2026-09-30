package com.diet.service.meal;

import com.diet.enums.SourceMode;
import com.diet.model.MealItem;
import com.diet.model.MealRankRequest;
import com.diet.model.MealResponse;
import com.diet.model.MealSearchRequest;
import com.diet.model.RecommendResult;
import com.diet.model.RecommendedMealOption;
import com.diet.model.ResponseResult;
import com.diet.model.SlotBundle;
import com.diet.service.meal.constraint.MealConstraintContext;
import com.diet.service.meal.constraint.MealConstraints;
import com.diet.service.orchestrator.DietOrchestratorService;
import com.diet.service.recommend.RecommendResponseAgentService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MySqlOnlyRecommendationTest {

    @Mock MealSearchService searchService;
    @Mock MealRankService rankService;
    @Mock RecommendResponseAgentService responseService;

    @Test
    void recommendationServicesDoNotDependOnTheRemovedMealRag() {
        assertThat(fieldTypeNames(MealPlanService.class)).doesNotContain("MealRetrievalService");
        assertThat(fieldTypeNames(DietOrchestratorService.class)).doesNotContain("MealRetrievalService");
    }

    @Test
    void planPassesExplicitIngredientConstraintsToEveryMysqlSearch() {
        MealPlanService service = new MealPlanService(searchService, rankService, responseService);
        MealConstraints constraints = new MealConstraints(
                List.of("番茄"), List.of("牛肉"), java.util.Map.of(), java.util.Map.of());
        MealConstraintContext context = new MealConstraintContext("清淡，不要牛肉", constraints, "USER_INPUT");

        when(searchService.search(any())).thenAnswer(invocation -> {
            MealSearchRequest request = invocation.getArgument(0);
            return List.of(new MealItem(1L, SourceMode.PUBLIC, null, "番茄炒蛋", request.slots(), 1.0));
        });
        when(rankService.rank(any(MealRankRequest.class))).thenAnswer(invocation ->
                invocation.<MealRankRequest>getArgument(0).candidates());
        when(responseService.recommendAndRespond(any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            MealItem item = invocation.<List<MealItem>>getArgument(4).get(0);
            RecommendedMealOption option = new RecommendedMealOption(
                    item.id(), item.sourceType(), item.name(), "匹配约束", item.matchScore(), item.slots());
            return new RecommendResponseAgentService.Result(
                    new RecommendResult(List.of(option), false),
                    new ResponseResult("推荐番茄炒蛋", List.of(MealResponse.from(item)), "WAIT_USER"));
        });

        service.buildPlan("s1", 7L, context.queryText(), SourceMode.PUBLIC, SlotBundle.empty(), context);

        ArgumentCaptor<MealSearchRequest> requests = ArgumentCaptor.forClass(MealSearchRequest.class);
        verify(searchService, times(3)).search(requests.capture());
        assertThat(requests.getAllValues()).allSatisfy(request ->
                assertThat(request.constraints()).isEqualTo(constraints));
    }

    private List<String> fieldTypeNames(Class<?> type) {
        return Arrays.stream(type.getDeclaredFields()).map(Field::getType).map(Class::getSimpleName).toList();
    }
}
