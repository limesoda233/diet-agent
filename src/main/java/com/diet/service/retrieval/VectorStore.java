package com.diet.service.retrieval;

import java.util.List;

public interface VectorStore {
    void ensureCollection();
    boolean isReady();
    void markReady();
    void upsert(MealDocument document, float[] vector);
    void delete(long mealId);
    List<Hit> search(MealRetrievalRequest request, float[] vector, int limit);
    record Hit(long mealId, double score, String contentHash) {}
}
