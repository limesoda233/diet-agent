package com.diet.service.retrieval;

import java.util.*;

public final class ReciprocalRankFusion {
    private ReciprocalRankFusion() {}
    public static Map<Long,Double> fuse(List<Long> first,List<Long> second) {
        var scores=new HashMap<Long,Double>();
        for(var ranking:List.of(first,second)) {
            var seen=new HashSet<Long>();
            for(int i=0;i<ranking.size();i++) {
                if(!seen.add(ranking.get(i))) throw new IllegalArgumentException("召回通路包含重复 ID");
                scores.merge(ranking.get(i),1.0/(60+i+1),Double::sum);
            }
        }
        return scores;
    }
}
