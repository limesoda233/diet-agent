package com.diet.evaluation.retrieval;

import com.diet.service.meal.constraint.IngredientCatalog;

import com.diet.service.meal.constraint.MealConstraints;

import com.diet.service.meal.constraint.ConstraintParser;

import com.diet.service.meal.knowledge.MealKnowledge;

import com.diet.model.MealItem;
import com.diet.service.retrieval.*;
import com.diet.service.trace.AgentTraceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import java.net.http.HttpClient;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

@EnabledIfSystemProperty(named="retrieval.eval.mysql", matches="true")
class HybridRetrievalEvaluationTest {
    @Test void comparesStructuredRetrievalAndOptionallyRealEmbeddingsOnTheFrozenCorpus() throws Exception {
        var dataset=RetrievalDataset.load(); var json=new ObjectMapper(); var catalog=new IngredientCatalog();
        Path output=Path.of("target/evaluation/hybrid-retrieval"); Files.createDirectories(output);
        Files.deleteIfExists(output.resolve("status.json"));
        for(String strategy:List.of("structured","hybrid")) for(String track:List.of("fixed","parsed"))
            for(String file:List.of("report.json","report.md")) Files.deleteIfExists(output.resolve(strategy+"-"+track).resolve(file));
        boolean live=Boolean.getBoolean("retrieval.eval.hybrid.live");
        var props=new RetrievalProperties(); props.setEnabled(true);
        var embeddingConfig=props.getEmbedding();
        embeddingConfig.setEndpoint(env("DIET_RETRIEVAL_EMBEDDING_ENDPOINT")); embeddingConfig.setModel(env("DIET_RETRIEVAL_EMBEDDING_MODEL"));
        embeddingConfig.setApiKey(env("DIET_RETRIEVAL_EMBEDDING_APIKEY"));
        embeddingConfig.setDimensions(Integer.parseInt(System.getenv().getOrDefault("DIET_RETRIEVAL_EMBEDDING_DIMENSIONS","0")));
        embeddingConfig.setQueryPrefix(env("DIET_RETRIEVAL_EMBEDDING_QUERYPREFIX")); embeddingConfig.setDocumentPrefix(env("DIET_RETRIEVAL_EMBEDDING_DOCUMENTPREFIX"));
        if(live) assertThat(embeddingConfig.configured()).as("真实向量评测需要显式模型配置，不使用假向量").isTrue();
        try(var baseline=MySqlRetrievalAdapter.fromEnvironment(dataset.meals())) {
            var jdbc=new JdbcTemplate(new SingleConnectionDataSource(baseline.evaluationConnection(),true));
            String migration=Files.readString(Path.of("src/main/resources/db/migrations/002_meal_retrieval.sql"));
            for(String sql:migration.replace("CREATE TABLE","CREATE TEMPORARY TABLE").split(";")) if(!sql.isBlank()) jdbc.execute(sql);
            var store=new JdbcMealRetrievalStore(jdbc,json,catalog,props);
            for(var meal:dataset.meals()) store.saveKnowledge(meal.id(),new MealKnowledge(meal.ingredients(),meal.description(),meal.preparation(),"SYNTHETIC_EVALUATION",meal.provenance().get("seedFile")));
            var embedding=new HttpEmbeddingClient(embeddingConfig,HttpClient.newHttpClient());
            VectorStore vector=mock(VectorStore.class);
            if(live) {
                props.getQdrant().setEndpoint(System.getenv().getOrDefault("RETRIEVAL_EVAL_QDRANT_URL","http://127.0.0.1:6333"));
                props.getQdrant().setCollection("diet_eval_live_"+UUID.randomUUID().toString().replace("-",""));
                vector=new QdrantVectorStore(props.getQdrant(),embeddingConfig.getDimensions(),embeddingConfig.getModel(),HttpClient.newHttpClient());
                new MealIndexService(store,embedding,vector,props).rebuild();
            }
            var service=new MealRetrievalService(store,embedding,vector,props,null,null,mock(AgentTraceService.class));
            for(var strategy:live?List.of(RetrievalProperties.Strategy.STRUCTURED,RetrievalProperties.Strategy.HYBRID):List.of(RetrievalProperties.Strategy.STRUCTURED)) {
                props.setStrategy(strategy);
                for(String track:List.of("fixed","parsed")) {
                    Path directory=output.resolve(strategy.name().toLowerCase(Locale.ROOT)+"-"+track); Files.createDirectories(directory);
                    Files.deleteIfExists(directory.resolve("report.json")); Files.deleteIfExists(directory.resolve("report.md"));
                    List<RetrievalReport.Row> rows=new ArrayList<>(); Map<String,String> parseErrors=new LinkedHashMap<>();
                    Map<String,Object> parsing=new LinkedHashMap<>(); int missingCount=0,extraCount=0;
                    for(var query:dataset.queries()) {
                        MealConstraints constraints=new MealConstraints(query.requiredIngredients(),query.excludedIngredients(),null,null);
                        if(track.equals("parsed")) {
                            try {
                                var expected=constraints;
                                constraints=new ConstraintParser(catalog).resolve(query.utterance(),MealConstraints.empty(),null);
                                var missing=new TreeSet<>(atoms(expected)); missing.removeAll(atoms(constraints));
                                var extra=new TreeSet<>(atoms(constraints)); extra.removeAll(atoms(expected));
                                missingCount+=missing.size(); extraCount+=extra.size();
                                parsing.put(query.id(),Map.of("expected",expected,"actual",constraints,"missing",missing,"extra",extra));
                            }
                            catch(IllegalArgumentException e) { parseErrors.put(query.id(),e.getMessage()); continue; }
                        }
                        var request=new MealRetrievalRequest(query.utterance(),query.sourceMode(),query.userId(),RetrievalDataset.slots(query.slots()),query.excludeMealIds(),constraints);
                        long started=System.nanoTime(); var result=service.retrieve(request); double elapsed=(System.nanoTime()-started)/1_000_000.0;
                        assertThat(result.diagnostics().degraded()).as("不能把降级结果计为真实混合语义成绩").isFalse();
                        var candidateIds=result.candidates().stream().map(MealItem::id).toList();
                        var top=result.ranked().stream().limit(3).toList(); var topIds=top.stream().map(MealItem::id).toList();
                        var score=RetrievalMetrics.evaluate(query,dataset.meals(),candidateIds,topIds,elapsed);
                        if(track.equals("fixed")) assertThat(score.violations().values()).allSatisfy(values->assertThat(values).isEmpty());
                        rows.add(new RetrievalReport.Row(query,candidateIds,topIds,top.stream().map(MealItem::matchScore).toList(),score,
                                strategy==RetrievalProperties.Strategy.STRUCTURED?List.of("semanticRetrievalDisabled"):List.of(),List.of(topIds),List.of(elapsed),false));
                    }
                    var metadata=new LinkedHashMap<String,Object>();
                    metadata.put("strategy",strategy.name()); metadata.put("track",track); metadata.put("runAt",OffsetDateTime.now().toString());
                    metadata.put("metricVersion","1.0"); metadata.put("corpusSha256",hash("corpus.json")); metadata.put("queriesSha256",hash("queries.json"));
                    metadata.put("sourceSha256",sourceHash());
                    metadata.put("embeddingModel",embeddingConfig.getModel()); metadata.put("embeddingDimensions",embeddingConfig.getDimensions());
                    metadata.put("templateVersion",MealDocument.TEMPLATE_VERSION); metadata.put("collection",live?props.getQdrant().getCollection():"unused");
                    metadata.put("mysqlVersion",baseline.evaluationConnection().getMetaData().getDatabaseProductVersion()); metadata.put("javaVersion",System.getProperty("java.version"));
                    metadata.put("rrfK",60); metadata.put("rrfWeights",List.of(1,1)); metadata.put("perChannelLimit",50);
                    metadata.put("warmupsPerQuery",0); metadata.put("repetitionsPerQuery",1); metadata.put("attemptedQueries",dataset.queries().size());
                    metadata.put("parseErrorCount",parseErrors.size()); metadata.put("parseErrors",parseErrors);
                    metadata.put("parsedConstraints",parsing); metadata.put("missingConstraintCount",missingCount); metadata.put("extraConstraintCount",extraCount);
                    metadata.put("qualityDenominatorNote","parsed 仅对成功解析请求计算检索质量；另报解析错误，未冒充完整端到端表现");
                    metadata.put("capabilityDescription",strategy==RetrievalProperties.Strategy.STRUCTURED?"增强结构化策略使用同版食材与约束，未使用语义向量。":"使用真实云 embedding 和 Qdrant；未接受降级结果。");
                    metadata.put("latencyDescription","单次完整检索；hybrid 包括 embedding、Qdrant 与 MySQL，不包含推荐 LLM。未预热，不能与旧基线延迟直接比较。");
                    RetrievalReport.write(directory,metadata,rows);
                    if(track.equals("fixed")) assertThat(rows).hasSize(64);
                }
            }
        }
        Files.writeString(output.resolve("status.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("liveHybrid",live?"executed":"not_requested: requires explicit live flag and configured embedding; no synthetic quality score","annotationStatus","draft")));
    }
    private static String env(String key) { return System.getenv().getOrDefault(key,""); }
    private static Set<String> atoms(MealConstraints constraints) {
        var result=new TreeSet<String>();
        constraints.requiredIngredients().forEach(x->result.add("requiredIngredients:"+x));
        constraints.excludedIngredients().forEach(x->result.add("excludedIngredients:"+x));
        constraints.requiredSlots().forEach((key,values)->values.forEach(x->result.add("requiredSlots:"+key+":"+x)));
        constraints.excludedSlots().forEach((key,values)->values.forEach(x->result.add("excludedSlots:"+key+":"+x)));
        return result;
    }
    private static String sourceHash() throws Exception {
        var digest=MessageDigest.getInstance("SHA-256");
        for(String directory:List.of("src/main/java","src/test/java/com/diet/evaluation/retrieval","src/main/resources/diet/retrieval","src/main/resources/db/migrations"))
            try(var files=Files.walk(Path.of(directory))) {
                for(var file:files.filter(Files::isRegularFile).sorted().toList()) {
                    digest.update(file.toString().replace('\\','/').getBytes(java.nio.charset.StandardCharsets.UTF_8)); digest.update((byte)0); digest.update(Files.readAllBytes(file));
                }
            }
        return HexFormat.of().formatHex(digest.digest());
    }
    private static String hash(String resource) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(Path.of("src/test/resources/evaluation/retrieval",resource)))); }
}
