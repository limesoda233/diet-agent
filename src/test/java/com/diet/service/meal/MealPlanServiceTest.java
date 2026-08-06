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
import com.diet.service.recommend.RecommendResponseAgentService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MealPlanServiceTest {

    @Mock
    private MealSearchService mealSearchService;
    @Mock
    private MealRankService mealRankService;
    @Mock
    private RecommendResponseAgentService recommendResponseAgentService;

    @Test
    void expandsThreeMealsAndSearchesEachMealTimeSeparately() {
        MealPlanService service = new MealPlanService(
                mealSearchService, mealRankService, recommendResponseAgentService);

        when(mealSearchService.search(any(MealSearchRequest.class))).thenAnswer(invocation -> {
            MealSearchRequest request = invocation.getArgument(0);
            String mealTime = request.slots().mealTime().get(0);
            long id = switch (mealTime) {
                case "早餐" -> 101L;
                case "午餐" -> 102L;
                case "晚餐" -> 103L;
                default -> throw new AssertionError("unexpected meal time: " + mealTime);
            };
            return List.of(new MealItem(id, SourceMode.PERSONAL, 1L, mealTime + "候选", request.slots(), 1.0));
        });
        when(mealRankService.rank(any(MealRankRequest.class))).thenAnswer(invocation -> {
            MealRankRequest request = invocation.getArgument(0);
            return request.candidates();
        });
        when(recommendResponseAgentService.recommendAndRespond(
                any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            SlotBundle slots = invocation.getArgument(3);
            List<MealItem> ranked = invocation.getArgument(4);
            MealItem meal = ranked.get(0);
            RecommendedMealOption option = new RecommendedMealOption(
                    meal.id(), meal.sourceType(), meal.name(), "匹配" + slots.mealTime().get(0),
                    meal.matchScore(), meal.slots());
            return new RecommendResponseAgentService.Result(
                    new RecommendResult(List.of(option), true),
                    new ResponseResult("推荐" + meal.name(), List.of(MealResponse.from(meal)), "WAIT_USER"));
        });

        SlotBundle baseSlots = new SlotBundle(
                List.of("三餐"), List.of(), List.of(),
                List.of("减脂", "高蛋白", "控碳水"),
                List.of(), List.of(), List.of());

        MealPlanService.PlanResult result = service.buildPlan(
                "session-1", 1L, "安排三餐", SourceMode.PERSONAL, baseSlots);

        assertThat(result.planBlocks()).extracting(block -> block.mealTime())
                .containsExactly("早餐", "午餐", "晚餐");
        assertThat(result.planBlocks()).allSatisfy(block -> assertThat(block.meals()).hasSize(1));
        assertThat(result.recommend().recommendations()).hasSize(3);
        assertThat(result.response().displayBlocks()).hasSize(3);

        ArgumentCaptor<MealSearchRequest> searchCaptor = ArgumentCaptor.forClass(MealSearchRequest.class);
        verify(mealSearchService, times(3)).search(searchCaptor.capture());
        assertThat(searchCaptor.getAllValues())
                .extracting(request -> request.slots().mealTime())
                .containsExactly(List.of("早餐"), List.of("午餐"), List.of("晚餐"));
        assertThat(searchCaptor.getAllValues()).allSatisfy(request ->
                assertThat(request.slots().healthGoal())
                        .containsExactly("减脂", "高蛋白", "控碳水"));
    }
}
