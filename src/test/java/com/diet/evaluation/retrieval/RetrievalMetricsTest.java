package com.diet.evaluation.retrieval;

import com.diet.enums.SourceMode;
import com.diet.model.SlotBundle;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class RetrievalMetricsTest {
    static List<RetrievalDataset.Meal> meals() {
        return List.of(meal(1, SourceMode.PUBLIC, null, List.of("鸡蛋")),
                meal(2, SourceMode.PUBLIC, null, List.of("豆腐")),
                meal(3, SourceMode.PUBLIC, null, List.of("牛肉")),
                meal(4, SourceMode.PERSONAL, 2L, List.of("鸡蛋")));
    }
    static RetrievalDataset.Meal meal(long id, SourceMode mode, Long owner, List<String> ingredients) {
        return new RetrievalDataset.Meal(id,mode,owner,"餐食"+id,Map.of(),"2026-09-01T00:00:00",ingredients,"描述","做法",Map.of("recipeStatus","synthetic_recipe_assumption"));
    }
    static RetrievalDataset.Query query(Map<Long,Integer> grades,List<Long> excludes,List<String> banned,List<String> required) {
        return new RetrievalDataset.Query("q1","g1","dev","fixture","查询",List.of(),SourceMode.PUBLIC,1L,Map.of(),excludes,banned,required,grades,"手算样例","draft");
    }
    @Test
    void computesGradedRankingAgainstHandCalculatedExample() {
        var q=query(Map.of(1L,2,2L,1,3L,0),List.of(),List.of(),List.of());
        var score=RetrievalMetrics.evaluate(q,meals(),List.of(2L,3L,1L),List.of(2L,3L,1L),2.0);
        assertThat(score.candidateRecall50()).isEqualTo(1.0);
        assertThat(score.precision3()).isCloseTo(2.0/3,within(1e-9));
        assertThat(score.recall3()).isEqualTo(1.0);
        assertThat(score.mrr3()).isEqualTo(1.0);
        assertThat(score.ndcg3()).isCloseTo(0.68852888,within(1e-7));
        var shortResult=RetrievalMetrics.evaluate(q,meals(),List.of(1L),List.of(1L),1);
        assertThat(shortResult.precision3()).isCloseTo(1.0/3,within(1e-9));
        assertThat(shortResult.recall3()).isEqualTo(0.5);
    }
    @Test
    void emptyGoldIsNotPerfectRecallAndEmptyReturnIsNotANan() {
        var q=query(Map.of(1L,0,2L,0,3L,0),List.of(),List.of(),List.of());
        var score=RetrievalMetrics.evaluate(q,meals(),List.of(),List.of(),0);
        assertThat(score.recall3()).isNull();
        assertThat(score.ndcg3()).isNull();
        assertThat(score.expectedEmpty()).isTrue();
        assertThat(score.returnedEmpty()).isTrue();
        var miss=RetrievalMetrics.evaluate(query(Map.of(1L,2,2L,0,3L,0),List.of(),List.of(),List.of()),meals(),List.of(),List.of(),0);
        assertThat(miss.recall3()).isZero();
        assertThat(miss.ndcg3()).isZero();
        assertThat(miss.mrr3()).isZero();
    }
    @Test
    void violationsAreCountedBeforeFilteringBadResults() {
        var q=query(Map.of(1L,2,2L,0,3L,0),List.of(2L),List.of("牛肉"),List.of("鸡蛋"));
        var s=RetrievalMetrics.evaluate(q,meals(),List.of(2L,3L,4L),List.of(2L,3L,4L),1);
        assertThat(s.violations().get("sourceScope")).containsExactly(4L);
        assertThat(s.violations().get("excludedMealId")).containsExactly(2L);
        assertThat(s.violations().get("excludedIngredient")).containsExactly(3L);
        assertThat(s.violations().get("missingRequiredIngredient")).containsExactly(2L,3L);
        assertThat(s.precision3()).isZero();
        assertThat(s.returnedCount()).isEqualTo(3);
    }
    @Test
    void rejectsDuplicateUnknownAndInventedFinalCandidates() {
        var q=query(Map.of(1L,2,2L,1,3L,0),List.of(),List.of(),List.of());
        assertThatThrownBy(()->RetrievalMetrics.evaluate(q,meals(),List.of(1L,1L),List.of(1L),1)).hasMessageContaining("duplicate");
        assertThatThrownBy(()->RetrievalMetrics.evaluate(q,meals(),List.of(99L),List.of(99L),1)).hasMessageContaining("unknown");
        assertThatThrownBy(()->RetrievalMetrics.evaluate(q,meals(),List.of(1L),List.of(2L),1)).hasMessageContaining("not recalled");
    }
}
