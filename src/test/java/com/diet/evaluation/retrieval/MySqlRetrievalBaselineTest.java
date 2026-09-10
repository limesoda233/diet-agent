package com.diet.evaluation.retrieval;

import com.diet.enums.SourceMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@EnabledIfSystemProperty(named="retrieval.eval.mysql",matches="true")
class MySqlRetrievalBaselineTest {
    @Test
    void realMapperEnforcesAndScopeAndExcludesPreviousRecommendations() throws Exception {
        var data=RetrievalDataset.load();
        try(var adapter=MySqlRetrievalAdapter.fromEnvironment(data.meals())) {
            var spicy=adapter.retrieve(find(data,"tag-spicy-1"));
            assertThat(spicy.top()).extracting(m->m.id()).containsExactlyInAnyOrder(4L,23L);
            assertThat(adapter.retrieve(find(data,"isolate-empty-owner-1")).candidates()).isEmpty();
            assertThat(adapter.retrieve(find(data,"adjust-spicy-1")).top()).extracting(m->m.id()).containsExactly(23L);
            assertThat(adapter.retrieve(find(data,"isolate-personal-1")).candidates()).allSatisfy(m->{
                assertThat(m.sourceType()).isEqualTo(SourceMode.PERSONAL);
                assertThat(m.ownerUserId()).isEqualTo(1L);
            });
            // Positive culinary matches lack the exact 加班 tag: all dimensions AND suppresses them.
            assertThat(adapter.retrieve(find(data,"warm-solo-1")).candidates()).extracting(m->m.id()).doesNotContain(1L,2L,8L,18L);
        }
    }
    @Test
    void newerRowsCanHideAnOlderRelevantMealBeforeRanking() throws Exception {
        var data=RetrievalDataset.load();
        var source=data.meals().get(0);
        List<RetrievalDataset.Meal> expanded=new ArrayList<>();
        expanded.add(source);
        for(int i=0;i<55;i++) expanded.add(new RetrievalDataset.Meal(1000+i,source.sourceMode(),null,"新菜"+i,source.slots(),"2026-09-02T00:00:00",source.ingredients(),source.description(),source.preparation(),source.provenance()));
        var q=new RetrievalDataset.Query("limit","limit","dev","diagnostic","晚餐",List.of(),SourceMode.PUBLIC,1L,
                Map.of("mealTime",List.of("晚餐")),List.of(),List.of(),List.of(),Map.of(1L,2),"截断诊断，不参与质量汇总","draft");
        try(var adapter=MySqlRetrievalAdapter.fromEnvironment(expanded)) {
            var result=adapter.retrieve(RetrievalStrategy.Input.from(q));
            assertThat(result.candidates()).hasSize(50).extracting(m->m.id()).doesNotContain(1L);
        }
    }
    static RetrievalStrategy.Input find(RetrievalDataset data,String id) {
        return RetrievalStrategy.Input.from(data.queries().stream().filter(q->q.id().equals(id)).findFirst().orElseThrow());
    }
}
