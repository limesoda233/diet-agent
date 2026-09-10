package com.diet.evaluation.retrieval;

import com.diet.model.MealItem;
import com.diet.enums.SourceMode;
import java.util.List;
import java.util.Map;

/** Input deliberately excludes judgments, split, and annotation rationale. */
interface RetrievalStrategy extends AutoCloseable {
    Retrieved retrieve(Input query);
    String name();
    record Input(String utterance, List<String> context, SourceMode sourceMode, Long userId,
                 Map<String,List<String>> slots, List<Long> excludeMealIds,
                 List<String> excludedIngredients, List<String> requiredIngredients) {
        static Input from(RetrievalDataset.Query q) {
            return new Input(q.utterance(),q.context(),q.sourceMode(),q.userId(),q.slots(),q.excludeMealIds(),q.excludedIngredients(),q.requiredIngredients());
        }
    }
    record Retrieved(List<MealItem> candidates, List<MealItem> top) {}
    @Override void close() throws Exception;
}
