package com.diet.service.retrieval;

import com.diet.model.*;
import com.diet.service.meal.*;
import com.diet.service.trace.AgentTraceService;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class MealRetrievalService {
    private final MealRetrievalStore store;
    private final EmbeddingClient embedding;
    private final VectorStore vector;
    private final RetrievalProperties properties;
    private final MealSearchService legacySearch;
    private final MealRankService legacyRank;
    private final AgentTraceService trace;
    public MealRetrievalService(MealRetrievalStore store,EmbeddingClient embedding,VectorStore vector,RetrievalProperties properties,
                                MealSearchService legacySearch,MealRankService legacyRank,AgentTraceService trace) {
        this.store=store; this.embedding=embedding; this.vector=vector; this.properties=properties; this.legacySearch=legacySearch; this.legacyRank=legacyRank; this.trace=trace;
    }
    public record Diagnostics(String strategy,boolean degraded,String reason,List<Long> structuredIds,List<VectorStore.Hit> vectorHits,
                              Map<Long,List<String>> rejected,Map<String,Double> timingsMs,String embeddingModel) {}
    public record Result(List<MealItem> candidates,List<MealItem> ranked,List<MealDocument> evidence,Diagnostics diagnostics) {}

    public Result retrieve(MealRetrievalRequest request) {
        if(!properties.isEnabled()) {
            if(!request.constraints().isEmpty()) throw new IllegalArgumentException("明确食材/标签限制需要启用检索知识库，请先完成数据库迁移和配置");
            return legacy(request,false);
        }
        if(properties.getStrategy()==RetrievalProperties.Strategy.LEGACY) return legacy(request,true);
        var times=new LinkedHashMap<String,Double>();
        long started=System.nanoTime();
        var structured=store.structured(request,50);
        times.put("sql",elapsed(started));
        var ids=structured.stream().map(d->d.meal().id()).toList();
        List<VectorStore.Hit> hits=List.of(); boolean degraded=false; String reason=""; int retryCount=0;
        if(properties.getStrategy()==RetrievalProperties.Strategy.HYBRID) {
            try {
                if(!embedding.configured()) throw new RetrievalDependencyException("embedding_not_configured");
                if(!timed("qdrantReadiness",times,vector::isReady)) throw new RetrievalDependencyException("qdrant_index_not_ready");
                float[] queryVector;
                try { queryVector=timed("embedding",times,()->embedding.embedQuery(request.embeddingText())); }
                finally { retryCount=embedding.lastRetryCount(); }
                hits=timed("qdrantSearch",times,()->vector.search(request,queryVector,50));
            } catch(RetrievalDependencyException failure) { degraded=true; reason=failure.getMessage(); }
        }
        var allIds=new LinkedHashSet<>(ids); hits.forEach(hit->allIds.add(hit.mealId()));
        started=System.nanoTime(); var current=store.find(List.copyOf(allIds)); times.put("sqlRecheck",elapsed(started));
        var rejected=new LinkedHashMap<Long,List<String>>();
        var validIds=new ArrayList<Long>();
        for(long id:allIds) {
            var document=current.get(id);
            var violations=document==null?List.of("deleted"):document.violations(request);
            if(!violations.isEmpty()) rejected.put(id,violations); else validIds.add(id);
        }
        var validVectorIds=new ArrayList<Long>();
        for(var hit:hits) {
            var document=current.get(hit.mealId());
            if(document==null || !document.contentHash().equals(hit.contentHash())) {
                store.enqueueRepair(hit.mealId());
                rejected.putIfAbsent(hit.mealId(),List.of("staleVector"));
            } else if(validIds.contains(hit.mealId())) validVectorIds.add(hit.mealId());
        }
        var validStructured=ids.stream().filter(validIds::contains).toList();
        var scores=ReciprocalRankFusion.fuse(validStructured,validVectorIds);
        Comparator<Long> order=Comparator.<Long>comparingDouble(scores::get).reversed()
                .thenComparing(Comparator.<Long>comparingDouble(id->RetrievalSlots.softScore(request.slots(),current.get(id).meal().slots())).reversed()).thenComparingLong(Long::longValue);
        var selected=scores.keySet().stream().sorted(order).limit(50).toList();
        var candidates=selected.stream().map(id->withScore(current.get(id),scores.get(id))).toList();
        var evidence=selected.stream().limit(3).map(current::get).toList();
        var diagnostics=new Diagnostics(properties.getStrategy().name(),degraded,reason,ids,hits,rejected,times,properties.getEmbedding().getModel());
        var result=new Result(candidates,candidates.stream().limit(10).toList(),evidence,diagnostics);
        trace.recordEvent("MEAL_RETRIEVED","RETRIEVAL",request,diagnostics);
        trace.recordEvent("MEAL_RETRIEVAL_RANKED","RETRIEVAL",Map.of("strategy",properties.getStrategy().name()),
                Map.of("ranked",candidates.stream().map(m->Map.of("mealId",m.id(),"rrfScore",m.matchScore())).toList(),
                       "evidence",evidence.stream().map(d->Map.of("mealId",d.meal().id(),"contentHash",d.contentHash(),"template",MealDocument.TEMPLATE_VERSION)).toList(),
                       "embeddingRetries",retryCount));
        return result;
    }
    private Result legacy(MealRetrievalRequest request,boolean recheck) {
        var candidates=legacySearch.search(new MealSearchRequest(request.sourceMode(),request.userId(),request.slots(),request.excludeMealIds()));
        if(recheck) {
            var documents=store.find(candidates.stream().map(MealItem::id).toList());
            candidates=candidates.stream().filter(m->documents.containsKey(m.id()) && documents.get(m.id()).violations(request).isEmpty())
                    .map(m->withScore(documents.get(m.id()),m.matchScore())).toList();
        }
        var ranked=legacyRank.rank(new MealRankRequest(candidates,request.slots(),request.excludeMealIds()));
        var evidence=ranked.stream().limit(3).map(m->new MealDocument(m,m.knowledge())).toList();
        var diagnostics=new Diagnostics("LEGACY",false,"",candidates.stream().map(MealItem::id).toList(),List.of(),Map.of(),Map.of(),"");
        trace.recordEvent("MEAL_RETRIEVED","RETRIEVAL",request,diagnostics);
        return new Result(candidates,ranked,evidence,diagnostics);
    }
    private MealItem withScore(MealDocument document,double score) {
        var item=document.meal(); return new MealItem(item.id(),item.sourceType(),item.ownerUserId(),item.name(),item.slots(),score,document.knowledge());
    }
    private static double elapsed(long started) { return (System.nanoTime()-started)/1_000_000.0; }
    private static <T> T timed(String stage,Map<String,Double> times,java.util.function.Supplier<T> action) {
        long started=System.nanoTime();
        try { return action.get(); } finally { times.put(stage,elapsed(started)); }
    }
}
