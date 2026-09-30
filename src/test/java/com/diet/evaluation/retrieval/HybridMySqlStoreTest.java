package com.diet.evaluation.retrieval;

import com.diet.service.meal.constraint.IngredientCatalog;

import com.diet.service.meal.constraint.MealConstraints;

import com.diet.service.meal.knowledge.MealKnowledge;

import com.diet.enums.SourceMode;
import com.diet.model.SlotBundle;
import com.diet.service.retrieval.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.core.io.ClassPathResource;
import java.sql.DriverManager;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfSystemProperty(named="retrieval.eval.mysql", matches="true")
class HybridMySqlStoreTest {
    @Test void filtersBeforeLimitingAndRetainsChangesThatArriveDuringIndexing() throws Exception {
        String url = System.getenv("RETRIEVAL_EVAL_DB_URL"); MySqlRetrievalAdapter.validateUrl(url);
        try (var connection = DriverManager.getConnection(url, System.getenv("RETRIEVAL_EVAL_DB_USERNAME"), System.getenv().getOrDefault("RETRIEVAL_EVAL_DB_PASSWORD", ""))) {
            var jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            jdbc.execute("CREATE TEMPORARY TABLE meal_item(id BIGINT PRIMARY KEY,source_type VARCHAR(20),owner_user_id BIGINT,name VARCHAR(100),meal_time JSON,mood JSON,scene JSON,health_goal JSON,cuisine JSON,taste JSON,convenience JSON)");
            // Temporary table shadowing keeps the production migration isolated on this connection.
            String migration = new String(new ClassPathResource("db/migrations/002_meal_retrieval.sql").getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            for (int repeat = 0; repeat < 2; repeat++) for (String sql : migration.replace("CREATE TABLE", "CREATE TEMPORARY TABLE").split(";")) if (!sql.isBlank()) jdbc.execute(sql);
            for (long id = 1; id <= 56; id++) jdbc.update("INSERT INTO meal_item VALUES(?,'PUBLIC',NULL,?, '[\"晚餐\"]','[]','[]','[]','[]','[]','[]')", id, "餐食" + id);
            var store = new JdbcMealRetrievalStore(jdbc, new ObjectMapper(), new IngredientCatalog());
            store.saveKnowledge(56L, new MealKnowledge(List.of("鸡蛋"), "", "", "SYNTHETIC_EVALUATION", "fixture"));
            var request = new MealRetrievalRequest("同时用鸡蛋", SourceMode.PUBLIC, null, SlotBundle.empty(), List.of(), new MealConstraints(List.of("鸡蛋"), null, null, null));
            assertThat(store.structured(request, 50)).extracting(x -> x.meal().id()).containsExactly(56L);
            store.enqueue(56L); var task = store.pending(10).get(0); store.enqueue(56L); store.complete(task);
            assertThat(store.pending(10)).hasSize(1);
            assertThat(store.pending(10).get(0).revision()).isGreaterThan(task.revision());
            var current=store.pending(10).get(0);
            for(int attempt=0;attempt<5;attempt++) { store.fail(current,"qdrant_timeout"); store.enqueueRepair(56L); }
            assertThat(store.pending(10)).isEmpty();
            assertThat(((Number)store.status().get(0).get("attempts")).intValue()).isEqualTo(5);
            var otherProperties=new RetrievalProperties(); otherProperties.getQdrant().setCollection("diet_other_model");
            var other=new JdbcMealRetrievalStore(jdbc,new ObjectMapper(),new IngredientCatalog(),otherProperties);
            assertThat(other.pending(10)).hasSize(1);
            other.complete(other.pending(10).get(0));
            assertThat(other.status()).isEmpty();
            assertThat(store.status()).hasSize(1);
            store.enqueue(56L);
            assertThat(store.pending(10)).hasSize(1);
            assertThat(store.pending(10).get(0).attempts()).isZero();
            assertThat(other.pending(10)).hasSize(1);
            try(var independent=DriverManager.getConnection(url,System.getenv("RETRIEVAL_EVAL_DB_USERNAME"),System.getenv().getOrDefault("RETRIEVAL_EVAL_DB_PASSWORD",""))) {
                var competitor=new JdbcMealRetrievalStore(new JdbcTemplate(new SingleConnectionDataSource(independent,true)),new ObjectMapper(),new IngredientCatalog());
                assertThat(store.withIndexLock(()-> {
                    assertThatThrownBy(()->competitor.withIndexLock(()->1)).isInstanceOf(RetrievalDependencyException.class).hasMessage("index_worker_busy");
                    return 7;
                })).isEqualTo(7);
                assertThat(competitor.withIndexLock(()->9)).isEqualTo(9);
            }
            store.deleteKnowledge(56L);
            assertThat(store.structured(request, 50)).isEmpty();
            var embedding=mock(EmbeddingClient.class); var vector=mock(VectorStore.class);
            var changed=new java.util.concurrent.atomic.AtomicBoolean();
            when(embedding.embedDocuments(anyList())).thenAnswer(call -> {
                List<String> texts=call.getArgument(0);
                if(texts.get(0).contains("餐食56") && changed.compareAndSet(false,true)) {
                    store.saveKnowledge(56L,new MealKnowledge(List.of("鸡蛋"),"索引期间新修改","","SYNTHETIC_EVALUATION","fixture"));
                    store.enqueue(56L);
                }
                return List.of(new float[]{1,0});
            });
            new MealIndexService(store,embedding,vector,new RetrievalProperties()).rebuild();
            assertThat(changed).isTrue(); assertThat(store.status()).isEmpty();
            verify(vector,times(2)).upsert(argThat(d->d.meal().id()==56L),any());
            verify(vector).markReady();
        }
    }
}
