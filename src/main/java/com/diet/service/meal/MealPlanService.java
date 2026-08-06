package com.diet.service.meal;

import com.diet.enums.SourceMode;
import com.diet.model.MealItem;
import com.diet.model.MealPlanBlock;
import com.diet.model.MealRankRequest;
import com.diet.model.MealResponse;
import com.diet.model.MealSearchRequest;
import com.diet.model.RecommendResult;
import com.diet.model.RecommendedMealOption;
import com.diet.model.ResponseResult;
import com.diet.model.SlotBundle;
import com.diet.service.recommend.RecommendResponseAgentService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 早餐、午餐、晚餐的独立检索与推荐服务。
 */
@Service
public class MealPlanService {

    private static final List<String> PLAN_MEAL_TIMES = List.of("早餐", "午餐", "晚餐");

    private final MealSearchService mealSearchService;
    private final MealRankService mealRankService;
    private final RecommendResponseAgentService recommendResponseAgentService;

    public MealPlanService(
            MealSearchService mealSearchService,
            MealRankService mealRankService,
            RecommendResponseAgentService recommendResponseAgentService) {
        this.mealSearchService = mealSearchService;
        this.mealRankService = mealRankService;
        this.recommendResponseAgentService = recommendResponseAgentService;
    }

    /**
     * 将“三餐”展开为早餐、午餐、晚餐，并为每个餐次分别检索和生成推荐。
     */
    public PlanResult buildPlan(
            String sessionId,
            Long userId,
            String userInput,
            SourceMode sourceMode,
            SlotBundle baseSlots) {
        SlotBundle safeBaseSlots = baseSlots == null ? SlotBundle.empty() : baseSlots;
        List<MealPlanBlock> blocks = new ArrayList<>();
        List<RecommendedMealOption> recommendations = new ArrayList<>();
        List<MealResponse> displayBlocks = new ArrayList<>();
        Set<Long> selectedIds = new LinkedHashSet<>();
        boolean needDisclaimer = false;

        for (String mealTime : PLAN_MEAL_TIMES) {
            SlotBundle mealSlots = withMealTime(safeBaseSlots, mealTime);
            List<Long> excludeIds = List.copyOf(selectedIds);

            List<MealItem> candidates = mealSearchService.search(
                    new MealSearchRequest(sourceMode, userId, mealSlots, excludeIds));
            List<MealItem> ranked = mealRankService.rank(
                    new MealRankRequest(candidates, mealSlots, excludeIds));

            RecommendResponseAgentService.Result generated = recommendResponseAgentService.recommendAndRespond(
                    sessionId, userInput, sourceMode, mealSlots, ranked);

            RecommendResult mealRecommend = generated.recommend();
            ResponseResult mealResponse = generated.response();
            List<RecommendedMealOption> mealOptions = mealRecommend == null || mealRecommend.recommendations() == null
                    ? List.of()
                    : mealRecommend.recommendations();
            List<MealResponse> mealCards = mealResponse == null || mealResponse.displayBlocks() == null
                    ? List.of()
                    : mealResponse.displayBlocks();

            mealOptions.stream().map(RecommendedMealOption::itemId).forEach(selectedIds::add);
            recommendations.addAll(mealOptions);
            displayBlocks.addAll(mealCards);
            needDisclaimer = needDisclaimer || (mealRecommend != null && mealRecommend.needDisclaimer());

            String summary = mealResponse == null ? "" : mealResponse.speechText();
            blocks.add(new MealPlanBlock(mealTime, summary, List.copyOf(mealCards)));
        }

        RecommendResult recommendResult = new RecommendResult(List.copyOf(recommendations), needDisclaimer);
        ResponseResult responseResult = new ResponseResult(
                buildSpeech(blocks, needDisclaimer),
                List.copyOf(displayBlocks),
                "WAIT_USER");
        return new PlanResult(List.copyOf(blocks), recommendResult, responseResult);
    }

    private SlotBundle withMealTime(SlotBundle slots, String mealTime) {
        return new SlotBundle(
                List.of(mealTime),
                slots.mood(),
                slots.scene(),
                slots.healthGoal(),
                slots.cuisine(),
                slots.taste(),
                slots.convenience());
    }

    private String buildSpeech(List<MealPlanBlock> blocks, boolean needDisclaimer) {
        StringBuilder speech = new StringBuilder("我按早餐、午餐和晚餐分别整理了候选：");
        for (MealPlanBlock block : blocks) {
            speech.append("\n").append(block.mealTime()).append("：");
            if (block.meals() == null || block.meals().isEmpty()) {
                speech.append("暂时没有匹配的餐食");
            } else {
                speech.append(String.join("、", block.meals().stream().map(MealResponse::name).toList()));
            }
        }
        speech.append("\n你可以从每个餐次中选一款，也可以继续告诉我想调整哪一餐。");
        if (needDisclaimer) {
            speech.append("这些建议只做日常饮食参考，如有明确疾病或特殊身体情况，建议咨询医生或营养师。");
        }
        return speech.toString();
    }

    public record PlanResult(
            List<MealPlanBlock> planBlocks,
            RecommendResult recommend,
            ResponseResult response) {
    }
}
