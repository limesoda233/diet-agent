package com.diet.evaluation.retrieval;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

final class RetrievalReport {
    private RetrievalReport() {}
    record Row(RetrievalDataset.Query query, List<Long> candidates, List<Long> top,
               List<Double> rankScores, RetrievalMetrics.Score score, List<String> capabilityGaps,
               List<List<Long>> observedRankings, List<Double> latencySamplesMs, boolean rankingVaried) {}
    record ViolationSummary(long results, long affectedQueries, long resultDenominator, long queryDenominator) {}
    record Summary(int queries, long relevantQueries, long noMatchQueries,
                   Double candidateRecall50, Double recall3, Double precision3, Double ndcg3, Double mrr3,
                   Double noMatchAccuracy, Double emptyRate, Double insufficientRate,
                   double p50LatencyMs, double p95LatencyMs, long rankingVariedQueries,
                   long corpusLimitedQueries, long shortReturnQueries,
                   Map<String,ViolationSummary> violations) {}

    static Summary summarize(List<Row> rows) {
        long relevant=rows.stream().filter(r->!r.score().expectedEmpty()).count();
        long noMatch=rows.size()-relevant;
        Map<String,ViolationSummary> violations=new LinkedHashMap<>();
        for(String type:List.of("candidateSourceScope","sourceScope","excludedMealId","excludedIngredient","missingRequiredIngredient")) {
            long count=rows.stream().mapToLong(r->r.score().violations().get(type).size()).sum();
            long affected=rows.stream().filter(r->!r.score().violations().get(type).isEmpty()).count();
            long denominator=rows.stream().mapToLong(r->type.equals("candidateSourceScope")?r.score().candidateCount():r.score().returnedCount()).sum();
            violations.put(type,new ViolationSummary(count,affected,denominator,rows.size()));
        }
        List<Double> times=rows.stream().flatMap(r->r.latencySamplesMs().stream()).sorted().toList();
        return new Summary(rows.size(),relevant,noMatch,average(rows,s->s.candidateRecall50()),average(rows,s->s.recall3()),
            average(rows,s->s.precision3()),average(rows,s->s.ndcg3()),average(rows,s->s.mrr3()),
            ratio(rows.stream().filter(r->r.score().expectedEmpty()&&r.score().returnedEmpty()).count(),noMatch),
            ratio(rows.stream().filter(r->!r.score().expectedEmpty()&&r.score().returnedEmpty()).count(),relevant),
            ratio(rows.stream().filter(r->r.score().insufficient()).count(),relevant),percentile(times,0.5),percentile(times,0.95),
            rows.stream().filter(Row::rankingVaried).count(),
            rows.stream().filter(r->r.score().relevantCount()>0 && r.score().relevantCount()<3).count(),
            rows.stream().filter(r->r.score().returnedCount()<3).count(),violations);
    }
    private static Double average(List<Row> rows,Function<RetrievalMetrics.Score,Double> metric) {
        var values=rows.stream().map(Row::score).map(metric).filter(Objects::nonNull).toList();
        return values.isEmpty()?null:values.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
    }
    private static Double ratio(long numerator,long denominator) { return denominator==0?null:numerator/(double)denominator; }
    private static double percentile(List<Double> sorted,double p) { return sorted.isEmpty()?0:sorted.get((int)Math.ceil(sorted.size()*p)-1); }
    private static Map<String,Summary> grouped(List<Row> rows,Function<Row,String> key) {
        Map<String,List<Row>> groups=rows.stream().collect(Collectors.groupingBy(key,TreeMap::new,Collectors.toList()));
        Map<String,Summary> result=new LinkedHashMap<>(); groups.forEach((k,v)->result.put(k,summarize(v))); return result;
    }
    static void write(Path output,Map<String,Object> metadata,List<Row> rows) throws IOException {
        Files.createDirectories(output);
        var bySplit=grouped(rows,r->r.query().split());
        var bySlice=grouped(rows,r->r.query().split()+" / "+r.query().slice());
        var byStatus=grouped(rows,r->r.query().annotationStatus());
        var counts=rows.stream().collect(Collectors.groupingBy(r->r.query().annotationStatus(),TreeMap::new,Collectors.counting()));
        Map<String,Object> json=new LinkedHashMap<>();
        json.put("metadata",metadata); json.put("annotationCounts",counts); json.put("overall",summarize(rows));
        json.put("bySplit",bySplit); json.put("bySlice",bySlice); json.put("byAnnotationStatus",byStatus); json.put("rows",rows);
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(output.resolve("report.json").toFile(),json);
        StringBuilder md=new StringBuilder("# 餐食检索基线报告\n\n");
        md.append("标注状态：").append(counts).append("。draft 是助手初稿，尚未经用户复核；成绩仅适用于这份固定语料。\n\n");
        metadata.forEach((k,v)->md.append("- ").append(k).append(": ").append(v).append('\n'));
        md.append("\n质量指标使用每条查询第一次测量的排名；延迟分位数使用全部重复测量，口径为本地检索与排序，不含 LLM。排名波动保存在 JSON。\n\n");
        md.append("相关性均值分母为有效相关查询；无匹配正确率分母为无相关项查询。P@3 始终以 3 为分母。\n\n");
        md.append("| 集合 / 切片 | 查询 | 有效相关查询 | 无相关项 | Recall@50 | Recall@3 | P@3 | nDCG@3 | MRR@3 | 无匹配正确率 | P95 ms |\n| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |\n");
        bySplit.forEach((k,s)->tableRow(md,k,s)); bySlice.forEach((k,s)->tableRow(md,k,s));
        var overall=summarize(rows);
        md.append("\n相关餐食本来只有 1～2 道的查询：").append(overall.corpusLimitedQueries())
                .append(" / ").append(overall.relevantQueries()).append("；返回不足三条（含无匹配）的查询：")
                .append(overall.shortReturnQueries()).append(" / ").append(overall.queries())
                .append("。insufficientRate 另衡量返回量低于 min(3, 相关总数) 的比例。\n");
        md.append("\n## 明确约束违规\n\n| 类型 | 违规结果 | 返回结果分母 | 受影响查询 | 查询分母 |\n| --- | ---: | ---: | ---: | ---: |\n");
        summarize(rows).violations().forEach((k,v)->md.append("| ").append(k).append(" | ").append(v.results()).append(" | ").append(v.resultDenominator()).append(" | ").append(v.affectedQueries()).append(" | ").append(v.queryDenominator()).append(" |\n"));
        md.append("\n## 能力缺口及失败样例\n\n当前基线不消费原始语义、必需/禁用食材，下面的限制由评测记录，未通过后置过滤隐藏。配方为假设，不构成过敏原保障。\n\n");
        for(var row:rows) if(row.score().expectedEmpty()&&!row.score().returnedEmpty() || !row.score().expectedEmpty() && row.score().recall3()<1 || row.score().violations().values().stream().anyMatch(v->!v.isEmpty())) {
            md.append("- **").append(row.query().id()).append("**：").append(row.query().utterance()).append("；Top3=").append(row.top()).append("；理由：").append(row.query().rationale()).append("；能力缺口=").append(row.capabilityGaps()).append('\n');
        }
        Files.writeString(output.resolve("report.md"),md.toString());
    }
    private static void tableRow(StringBuilder md,String key,Summary s) {
        md.append("| ").append(key).append(" | ").append(s.queries()).append(" | ").append(s.relevantQueries()).append(" | ").append(s.noMatchQueries());
        for(Double value:Arrays.asList(s.candidateRecall50(),s.recall3(),s.precision3(),s.ndcg3(),s.mrr3(),s.noMatchAccuracy(),s.p95LatencyMs())) md.append(" | ").append(value==null?"—":String.format(Locale.ROOT,"%.4f",value));
        md.append(" |\n");
    }
}
