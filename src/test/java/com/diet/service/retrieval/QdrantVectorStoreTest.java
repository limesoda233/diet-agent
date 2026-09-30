package com.diet.service.retrieval;

import com.diet.service.meal.constraint.MealConstraints;

import com.diet.enums.SourceMode;
import com.diet.model.SlotBundle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.net.http.HttpClient;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

@EnabledIfSystemProperty(named="retrieval.eval.qdrant", matches="true")
class QdrantVectorStoreTest {
    @Test void filtersPrivateMealsAndIngredientsThenDeletesWithoutSyntheticQualityClaims() {
        var config = new RetrievalProperties.Qdrant();
        config.setEndpoint(System.getenv().getOrDefault("RETRIEVAL_EVAL_QDRANT_URL", "http://127.0.0.1:6333"));
        config.setCollection("diet_eval_" + java.util.UUID.randomUUID().toString().replace("-", ""));
        var store = new QdrantVectorStore(config, 2, "fixture", HttpClient.newHttpClient());
        store.ensureCollection();
        for (long id = 1; id <= 3; id++) store.upsert(MealDocumentTest.document(id, SourceMode.PERSONAL, id == 2 ? 2L : 1L, List.of(id == 3 ? "牛肉" : "鸡蛋")), new float[]{1, 0});
        var request = new MealRetrievalRequest("鸡蛋", SourceMode.PERSONAL, 1L, SlotBundle.empty(), List.of(), new MealConstraints(List.of("鸡蛋"), List.of("牛肉"), null, null));
        assertThat(store.search(request, new float[]{1, 0}, 50)).extracting(VectorStore.Hit::mealId).containsExactly(1L);
        store.delete(1L);
        assertThat(store.search(request, new float[]{1, 0}, 50)).isEmpty();
        assertThatThrownBy(() -> new QdrantVectorStore(config, 3, "fixture", HttpClient.newHttpClient()).ensureCollection()).isInstanceOf(RetrievalDependencyException.class);
        // Collection is unique and contains synthetic test data; no other collection is changed.
    }
}
