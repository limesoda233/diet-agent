package com.diet.service.retrieval;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.slf4j.LoggerFactory;
import java.util.List;

/** Database lock serializes writers; collection-specific progress preserves concurrent CRUD. */
@Service
public class MealIndexService {
    private final JdbcMealRetrievalStore store;
    private final EmbeddingClient embedding;
    private final VectorStore vector;
    private final RetrievalProperties properties;
    public MealIndexService(JdbcMealRetrievalStore store,EmbeddingClient embedding,VectorStore vector,RetrievalProperties properties) {
        this.store=store; this.embedding=embedding; this.vector=vector; this.properties=properties;
    }
    @Scheduled(fixedDelay=30000, initialDelay=30000)
    public void scheduledDrain() {
        if(!properties.isEnabled() || !properties.isIndexWorkerEnabled() || !embedding.configured()) return;
        try { drain(); } catch(RuntimeException failure) {
            LoggerFactory.getLogger(getClass()).warn("Meal index worker failed: {}",failure instanceof RetrievalDependencyException?failure.getMessage():"database_or_index_error");
        }
    }
    public synchronized int drain() {
        return store.withIndexLock(this::drainLocked);
    }
    private int drainLocked() {
        var tasks=store.pending(32);
        if(tasks.isEmpty()) return 0;
        try { vector.ensureCollection(); }
        catch(RetrievalDependencyException failure) {
            tasks.forEach(task -> store.fail(task,failure.getMessage()));
            return 0;
        }
        int done=0;
        for(var task:tasks) {
            try {
                var document=store.find(List.of(task.mealId())).get(task.mealId());
                if(document==null) vector.delete(task.mealId());
                else vector.upsert(document,embedding.embedDocuments(List.of(document.text())).get(0));
                store.complete(task); done++;
            } catch(RetrievalDependencyException failure) { store.fail(task,failure.getMessage()); }
        }
        return done;
    }
    public synchronized void rebuild() {
        store.withIndexLock(() -> { rebuildLocked(); return 0; });
    }
    public synchronized void retry() {
        store.withIndexLock(() -> { store.retryFailed(); return drainLocked(); });
    }
    private void rebuildLocked() {
        vector.ensureCollection();
        long after=0;
        while(true) {
            var ids=store.idsAfter(after,200); if(ids.isEmpty()) break;
            ids.forEach(store::enqueue); after=ids.get(ids.size()-1);
        }
        store.retryFailed();
        int rounds=0;
        while(!store.pending(1).isEmpty()) {
            drainLocked();
            if(++rounds>10000) throw new RetrievalDependencyException("index_rebuild_not_caught_up");
        }
        if(!store.status().isEmpty()) throw new RetrievalDependencyException("index_rebuild_has_failed_tasks");
        vector.markReady();
    }
}
