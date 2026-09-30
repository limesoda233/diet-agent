package com.diet.service.retrieval;

import com.diet.service.meal.constraint.MealConstraints;

import com.diet.enums.SourceMode;
import com.diet.model.SlotBundle;
import com.diet.service.meal.*;
import com.diet.service.trace.AgentTraceService;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MealRetrievalServiceTest {
    @Test void fusesRanksAndRechecksUntrustedVectorIdsAndVersions() {
        assertThat(ReciprocalRankFusion.fuse(List.of(1L,2L),List.of(1L,3L)).get(1L)).isCloseTo(2.0/61, within(1e-10));
        var store=mock(MealRetrievalStore.class); var embeddings=mock(EmbeddingClient.class); var vector=mock(VectorStore.class);
        var props=new RetrievalProperties(); props.setEnabled(true); props.setStrategy(RetrievalProperties.Strategy.HYBRID);
        var good=MealDocumentTest.document(1,SourceMode.PERSONAL,1L,List.of("鸡蛋"));
        var foreign=MealDocumentTest.document(2,SourceMode.PERSONAL,2L,List.of("鸡蛋"));
        var stale=MealDocumentTest.document(3,SourceMode.PERSONAL,1L,List.of("鸡蛋"));
        when(store.structured(any(),eq(50))).thenReturn(List.of(good));
        when(store.find(any())).thenReturn(Map.of(1L,good,2L,foreign,3L,stale));
        when(embeddings.configured()).thenReturn(true); when(embeddings.embedQuery(anyString())).thenReturn(new float[]{1,0}); when(vector.isReady()).thenReturn(true);
        when(vector.search(any(),any(),eq(50))).thenReturn(List.of(new VectorStore.Hit(2,1,foreign.contentHash()),new VectorStore.Hit(3,.9,"old"),new VectorStore.Hit(4,.8,"deleted"),new VectorStore.Hit(1,.7,good.contentHash())));
        var service=new MealRetrievalService(store,embeddings,vector,props,mock(MealSearchService.class),mock(MealRankService.class),mock(AgentTraceService.class));
        var request=new MealRetrievalRequest("鸡蛋",SourceMode.PERSONAL,1L,SlotBundle.empty(),List.of(),MealConstraints.empty());
        assertThat(service.retrieve(request).ranked()).extracting(x->x.id()).containsExactly(1L);
        verify(store).enqueueRepair(3L); verify(store).enqueueRepair(4L);
        when(vector.search(any(),any(),eq(50))).thenThrow(new RetrievalDependencyException("qdrant_timeout"));
        var degraded=service.retrieve(request);
        assertThat(degraded.ranked()).extracting(x->x.id()).containsExactly(1L);
        assertThat(degraded.diagnostics().degraded()).isTrue();
        when(store.structured(any(),eq(50))).thenThrow(new IllegalStateException("database offline"));
        assertThatThrownBy(()->service.retrieve(request)).hasMessage("database offline");
    }
}
