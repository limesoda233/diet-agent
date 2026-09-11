package com.diet.service.retrieval;

import com.diet.enums.SourceMode;
import com.diet.model.SlotBundle;
import java.util.List;

public record MealRetrievalRequest(String queryText, SourceMode sourceMode, Long userId, SlotBundle slots,
                                   List<Long> excludeMealIds, MealConstraints constraints) {
    public MealRetrievalRequest {
        if (sourceMode == null || sourceMode == SourceMode.PERSONAL && (userId == null || userId <= 0))
            throw new IllegalArgumentException("检索数据域或用户无效");
        queryText = queryText == null ? "" : queryText.trim();
        if (queryText.length() > 8000) throw new IllegalArgumentException("检索需求过长");
        slots = slots == null ? SlotBundle.empty() : slots;
        excludeMealIds = excludeMealIds == null ? List.of() : List.copyOf(excludeMealIds);
        if (excludeMealIds.stream().anyMatch(id -> id == null || id <= 0) || excludeMealIds.size() > 1000)
            throw new IllegalArgumentException("排除餐食 ID 无效或过多");
        constraints = constraints == null ? MealConstraints.empty() : constraints;
    }
    public String embeddingText() { return queryText + "\n偏好：" + RetrievalSlots.from(slots) + "\n明确限制：" + constraints; }
}
