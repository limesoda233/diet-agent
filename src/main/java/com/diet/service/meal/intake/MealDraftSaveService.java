package com.diet.service.meal.intake;

import com.diet.model.SessionState;
import com.diet.service.meal.MealService;
import com.diet.service.session.SessionStateService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.diet.exception.DietException;
import java.util.Objects;
import java.util.Map;
import java.util.List;
import java.util.LinkedHashMap;
import com.diet.service.slot.SlotOptionService;
import com.diet.service.meal.nutrition.MealNutritionVariantService;

@Service
public class MealDraftSaveService {
    private final MealService meals;
    private final SessionStateService sessions;
    public MealDraftSaveService(MealService meals, SessionStateService sessions) {
        this.meals=meals; this.sessions=sessions;
    }
    @Transactional
    public SessionState save(Long userId,String draftId,SessionState state) {
        return save(userId,draftId,state,null);
    }
    @Transactional
    public SessionState save(Long userId,String draftId,SessionState state,Map<?,?> reviewedTags) {
        return save(userId,draftId,state,reviewedTags,false);
    }
    @Transactional
    public SessionState save(Long userId,String draftId,SessionState state,Map<?,?> reviewedTags,boolean merge) {
        return save(userId,draftId,state,reviewedTags,merge,null);
    }
    @Transactional
    public SessionState save(Long userId,String draftId,SessionState state,Map<?,?> reviewedTags,boolean merge,
                             MealNutritionVariantService.Input nutritionVariant) {
        if (state == null || userId == null || !Objects.equals(userId,state.userId())
                || draftId == null || draftId.isBlank()) throw new DietException("餐食草稿不属于当前用户，请重新录入。");
        MealDraft requested = state.mealDraft();
        if (requested == null || !draftId.equals(requested.draftId())) throw new DietException("这份草稿已过期，请重新录入。");
        if (requested.status() == MealDraftStatus.CANCELLED) throw new DietException("这份草稿已取消，请重新录入。");
        if ((requested.status() == MealDraftStatus.READY || requested.status() == MealDraftStatus.AWAITING_MERGE)
                && !complete(requested)) throw new DietException("请先补齐菜名和餐次，再确认保存。");
        // The orchestrator holds the per-session JVM lock. Read the receipt again inside the
        // transaction so callers cannot turn an old READY snapshot into a second insert.
        SessionState current = sessions.loadExisting(state.sessionId(),userId,state.sourceMode());
        if (current == null) throw new DietException("草稿会话不存在或不属于当前用户，请重新录入。");
        MealDraft draft = current.mealDraft();
        if (draft == null || !draftId.equals(draft.draftId())) throw new DietException("这份草稿已过期，请重新录入。");
        if (draft.status() == MealDraftStatus.SAVED) {
            if (draft.savedMealId() == null) throw new DietException("餐食保存回执无效，请重新录入。");
            return current;
        }
        if ((draft.status() != MealDraftStatus.READY && draft.status() != MealDraftStatus.AWAITING_MERGE)
                || !complete(draft) || (merge && draft.status()!=MealDraftStatus.AWAITING_MERGE))
            throw new DietException("请先补齐菜名和餐次，再确认保存。");
        MealDraft reviewed = reviewedTags == null ? draft : withReviewedTags(draft,reviewedTags);
        var request=reviewed.data().toMealRequest();
        request.nutritionVariant(nutritionVariant);
        Long mealId = (merge ? meals.mergePersonalMeal(userId,request)
                : meals.createPersonalMeal(userId,request)).id();
        SessionState saved = current.withMealDraft(reviewed.saved(mealId));
        sessions.save(saved);
        return saved;
    }
    private MealDraft withReviewedTags(MealDraft draft,Map<?,?> raw) {
        if (raw.size()!=SlotOptionService.SLOT_NAMES.size() ||
                !raw.keySet().containsAll(SlotOptionService.SLOT_NAMES)) throw new DietException("草稿标签不完整，请重新核对");
        var tags=new LinkedHashMap<String,List<String>>();
        for (String field:SlotOptionService.SLOT_NAMES) {
            Object value=raw.get(field);
            if (!(value instanceof List<?> values) || values.size()>32 ||
                    values.stream().anyMatch(item -> !(item instanceof String text) || text.isBlank()))
                throw new DietException("草稿标签无效，请重新核对");
            tags.put(field,values.stream().map(item -> (String)item).distinct().toList());
        }
        var old=draft.data();
        var data=new MealDraftData(old.name(),tags.get("mealTime"),tags.get("mood"),tags.get("scene"),
                tags.get("healthGoal"),tags.get("cuisine"),tags.get("taste"),tags.get("convenience"),old.knowledge());
        if (data.mealTime().isEmpty()) throw new DietException("请至少选一个餐次");
        return new MealDraft(draft.draftId(),draft.originalText(),data,List.of(),draft.warnings(),
                draft.evidence(),draft.status(),draft.savedMealId());
    }
    private boolean complete(MealDraft draft) {
        return draft.data() != null && draft.data().name() != null && !draft.data().name().isBlank()
                && draft.data().mealTime() != null && !draft.data().mealTime().isEmpty();
    }
}
