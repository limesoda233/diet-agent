package com.diet.evaluation.retrieval;

import java.util.*;
import java.util.stream.Collectors;

public final class RetrievalMetrics {
    private RetrievalMetrics() {}
    public static Score evaluate(RetrievalDataset.Query q, List<RetrievalDataset.Meal> meals,
                                 List<Long> candidates, List<Long> top, double latencyMs) {
        Map<Long,RetrievalDataset.Meal> byId = meals.stream().collect(Collectors.toMap(RetrievalDataset.Meal::id,m->m));
        validateIds(candidates,byId); validateIds(top,byId);
        if (candidates.size()>50 || top.size()>3) throw new IllegalArgumentException("result exceeds evaluation depth");
        if (!candidates.containsAll(top)) throw new IllegalArgumentException("final result not recalled");
        if (!Double.isFinite(latencyMs) || latencyMs < 0) throw new IllegalArgumentException("invalid latency");
        long relevant = q.relevanceGrades().values().stream().filter(g->g>0).count();
        long recalled = candidates.stream().filter(id->q.relevanceGrades().getOrDefault(id,0)>0).count();
        long hits = top.stream().filter(id->q.relevanceGrades().getOrDefault(id,0)>0).count();
        double dcg=0, mrr=0;
        for (int i=0;i<top.size();i++) {
            int grade=q.relevanceGrades().getOrDefault(top.get(i),0);
            dcg += gain(grade,i);
            if (grade>0 && mrr==0) mrr=1.0/(i+1);
        }
        List<Integer> ideal=q.relevanceGrades().values().stream().sorted(Comparator.reverseOrder()).limit(3).toList();
        double idcg=0;
        for (int i=0;i<ideal.size();i++) idcg+=gain(ideal.get(i),i);
        Map<String,List<Long>> violations = new LinkedHashMap<>();
        violations.put("candidateSourceScope", candidates.stream().filter(id->!q.allows(byId.get(id))).toList());
        violations.put("sourceScope", top.stream().filter(id->!q.allows(byId.get(id))).toList());
        violations.put("excludedMealId", top.stream().filter(q.excludeMealIds()::contains).toList());
        violations.put("excludedIngredient", top.stream().filter(id->!Collections.disjoint(byId.get(id).ingredients(),q.excludedIngredients())).toList());
        violations.put("missingRequiredIngredient", top.stream().filter(id->!byId.get(id).ingredients().containsAll(q.requiredIngredients())).toList());
        return new Score(relevant==0?null:recalled/(double)relevant,
                relevant==0?null:hits/(double)relevant,relevant==0?null:hits/3.0,
                idcg==0?null:dcg/idcg,relevant==0?null:mrr,relevant==0,top.isEmpty(),
                relevant>0 && top.size()<Math.min(3,relevant),(int)relevant,top.size(),candidates.size(),violations,latencyMs);
    }
    private static double gain(int grade,int index) { return (Math.pow(2,grade)-1)/(Math.log(index+2)/Math.log(2)); }
    private static void validateIds(List<Long> ids,Map<Long,RetrievalDataset.Meal> byId) {
        if(new HashSet<>(ids).size()!=ids.size()) throw new IllegalArgumentException("duplicate returned ID");
        if(!byId.keySet().containsAll(ids)) throw new IllegalArgumentException("unknown returned ID");
    }
    public record Score(Double candidateRecall50, Double recall3, Double precision3, Double ndcg3, Double mrr3,
                        boolean expectedEmpty, boolean returnedEmpty, boolean insufficient,
                        int relevantCount, int returnedCount, int candidateCount,
                        Map<String,List<Long>> violations, double latencyMs) {}
}
