package com.diet.service.retrieval;

import com.diet.enums.SourceMode;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.mockito.Mockito.*;

class MealIndexServiceTest {
    @Test void countsCollectionFailuresAgainstEachPendingTaskRetryBudget() {
        var store=mock(JdbcMealRetrievalStore.class); var embedding=mock(EmbeddingClient.class); var vector=mock(VectorStore.class);
        var task=new MealRetrievalStore.IndexTask(1,1,4);
        when(store.withIndexLock(any())).thenAnswer(call -> ((java.util.function.IntSupplier)call.getArgument(0)).getAsInt());
        when(store.pending(32)).thenReturn(List.of(task));
        doThrow(new RetrievalDependencyException("qdrant_timeout")).when(vector).ensureCollection();
        new MealIndexService(store,embedding,vector,new RetrievalProperties()).drain();
        verify(store).fail(task,"qdrant_timeout");
        verify(store,never()).complete(any());
    }
    @Test void acknowledgesOnlySuccessfulWritesAndRecordsSafeFailures() {
        var store=mock(JdbcMealRetrievalStore.class); var embedding=mock(EmbeddingClient.class); var vector=mock(VectorStore.class);
        var task=new MealRetrievalStore.IndexTask(1,2,0);
        when(store.withIndexLock(any())).thenAnswer(call -> ((java.util.function.IntSupplier)call.getArgument(0)).getAsInt());
        var document=MealDocumentTest.document(1,SourceMode.PUBLIC,null,List.of("鸡蛋"));
        when(store.pending(32)).thenReturn(List.of(task)); when(store.find(List.of(1L))).thenReturn(Map.of(1L,document));
        when(embedding.embedDocuments(anyList())).thenReturn(List.of(new float[]{1,0}));
        var service=new MealIndexService(store,embedding,vector,new RetrievalProperties());
        service.drain(); verify(vector).upsert(eq(document),any()); verify(store).complete(task);
        reset(vector); doThrow(new RetrievalDependencyException("qdrant_timeout")).when(vector).upsert(any(),any());
        service.drain(); verify(store).fail(task,"qdrant_timeout"); verify(store,times(1)).complete(task);
        when(store.find(List.of(1L))).thenReturn(Map.of()); service.drain(); verify(vector).delete(1L); verify(store,times(2)).complete(task);
    }
}
