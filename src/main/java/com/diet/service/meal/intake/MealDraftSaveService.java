package com.diet.service.meal.intake;

import com.diet.model.SessionState;
import com.diet.service.meal.MealService;
import com.diet.service.session.SessionStateService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.diet.exception.DietException;
import java.util.Objects;

@Service
public class MealDraftSaveService {
    private final MealService meals;
    private final SessionStateService sessions;
    public MealDraftSaveService(MealService meals, SessionStateService sessions) {
        this.meals=meals; this.sessions=sessions;
    }
    @Transactional
    public SessionState save(Long userId,String draftId,SessionState state) {
        if (state == null || userId == null || !Objects.equals(userId,state.userId())
                || draftId == null || draftId.isBlank()) throw new DietException("餐食草稿不属于当前用户，请重新录入。");
        MealDraft requested = state.mealDraft();
        if (requested == null || !draftId.equals(requested.draftId())) throw new DietException("这份草稿已过期，请重新录入。");
        if (requested.status() == MealDraftStatus.CANCELLED) throw new DietException("这份草稿已取消，请重新录入。");
        if (requested.status() == MealDraftStatus.READY && !complete(requested)) throw new DietException("请先补齐菜名和餐次，再确认保存。");
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
        if (draft.status() != MealDraftStatus.READY || !complete(draft)) throw new DietException("请先补齐菜名和餐次，再确认保存。");
        Long mealId = meals.createPersonalMeal(userId,draft.data().toMealRequest()).id();
        SessionState saved = current.withMealDraft(draft.saved(mealId));
        sessions.save(saved);
        return saved;
    }
    private boolean complete(MealDraft draft) {
        return draft.data() != null && draft.data().name() != null && !draft.data().name().isBlank()
                && draft.data().mealTime() != null && !draft.data().mealTime().isEmpty();
    }
}
