package com.diet.service.retrieval;

import java.util.List;
import java.util.Map;

public interface MealRetrievalStore {
    List<MealDocument> structured(MealRetrievalRequest request, int limit);
    Map<Long,MealDocument> find(List<Long> ids);
    List<Long> idsAfter(long afterId, int limit);
    void enqueue(long mealId);
    void enqueueRepair(long mealId);
    List<IndexTask> pending(int limit);
    void complete(IndexTask task);
    void fail(IndexTask task, String category);
    record IndexTask(long mealId, long revision, int attempts) {}
}
