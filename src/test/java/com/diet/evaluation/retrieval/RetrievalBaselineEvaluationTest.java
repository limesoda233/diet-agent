package com.diet.evaluation.retrieval;

import com.diet.model.MealItem;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;

@EnabledIfSystemProperty(named="retrieval.eval.mysql",matches="true")
class RetrievalBaselineEvaluationTest {
    @Test
    void evaluatesProductionRetrievalAndWritesAnAuditableReport() throws Exception {
        var data=RetrievalDataset.load();
        int repetitions=Integer.getInteger("retrieval.eval.repetitions",3);
        if(repetitions<1 || repetitions>20) throw new IllegalArgumentException("repetitions must be 1..20");
        Path output=Path.of("target/evaluation/retrieval");
        // A failed new run must not leave a previous report looking like its successful output.
        Files.createDirectories(output);
        Files.deleteIfExists(output.resolve("report.json"));
        Files.deleteIfExists(output.resolve("report.md"));
        List<RetrievalReport.Row> rows=new ArrayList<>();
        Map<String,Object> metadata=new LinkedHashMap<>();
        try(var strategy=MySqlRetrievalAdapter.fromEnvironment(data.meals())) {
            metadata.put("strategy",strategy.name());
            metadata.put("track","fixed-slots; raw utterance is not consumed by baseline");
            metadata.put("mysqlVersion",strategy.mysqlVersion());
            metadata.put("javaVersion",System.getProperty("java.version"));
            metadata.put("runAt",OffsetDateTime.now().toString());
            metadata.put("metricVersion","1.0");
            metadata.put("warmupsPerQuery",1); metadata.put("repetitionsPerQuery",repetitions);
            metadata.put("qualitySample","first measured ranking; all measured rankings saved");
            metadata.put("mybatisLocalCache","STATEMENT; repeated retrievals execute SQL");
            metadata.put("gitHead",git("rev-parse","HEAD"));
            metadata.put("gitDirty",!git("status","--porcelain").isBlank());
            try(var corpus=RetrievalDataset.resource("corpus.json"); var queries=RetrievalDataset.resource("queries.json")) {
                metadata.put("corpusSha256",hash(corpus.readAllBytes()));
                metadata.put("queriesSha256",hash(queries.readAllBytes()));
            }
            Map<String,String> sources=new LinkedHashMap<>();
            for(String file:List.of("src/main/resources/mapper/MealMapper.xml",
                    "src/main/java/com/diet/service/meal/MealService.java",
                    "src/main/java/com/diet/service/meal/MealSearchService.java",
                    "src/main/java/com/diet/service/meal/MealRankService.java")) sources.put(file,hash(Files.readAllBytes(Path.of(file))));
            metadata.put("productionSourceSha256",sources);
            metadata.put("mapperSha256",sources.get("src/main/resources/mapper/MealMapper.xml"));
            for(var query:data.queries()) {
                var input=RetrievalStrategy.Input.from(query);
                strategy.retrieve(input);
                RetrievalStrategy.Retrieved first=null;
                List<Double> times=new ArrayList<>();
                List<List<Long>> observed=new ArrayList<>();
                for(int i=0;i<repetitions;i++) {
                    long start=System.nanoTime();
                    var result=strategy.retrieve(input);
                    double elapsed=(System.nanoTime()-start)/1_000_000.0;
                    times.add(elapsed); observed.add(ids(result.top()));
                    // Validate every run, including repetitions whose ranking is not used for aggregate quality.
                    RetrievalMetrics.evaluate(query,data.meals(),ids(result.candidates()),ids(result.top()),elapsed);
                    if(first==null) first=result;
                }
                List<String> gaps=new ArrayList<>();
                gaps.add("rawQueryNotConsumed");
                if(!query.excludedIngredients().isEmpty()) gaps.add("excludedIngredientsUnsupported");
                if(!query.requiredIngredients().isEmpty()) gaps.add("requiredIngredientsUnsupported");
                var score=RetrievalMetrics.evaluate(query,data.meals(),ids(first.candidates()),ids(first.top()),times.get(0));
                rows.add(new RetrievalReport.Row(query,ids(first.candidates()),ids(first.top()),first.top().stream().map(MealItem::matchScore).toList(),score,gaps,observed,times,observed.stream().distinct().count()>1));
            }
        }
        RetrievalReport.write(output,metadata,rows);
        var report=new ObjectMapper().readTree(output.resolve("report.json").toFile());
        assertThat(report.path("rows").size()).isEqualTo(64);
        assertThat(report.path("metadata").path("mysqlVersion").asText()).isNotBlank();
        assertThat(report.path("bySplit").has("dev")).isTrue();
        assertThat(report.path("bySplit").has("test")).isTrue();
        System.out.println("Retrieval report: "+output.toAbsolutePath().resolve("report.md"));
    }
    private static List<Long> ids(List<MealItem> meals) { return meals.stream().map(MealItem::id).toList(); }
    private static String hash(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    private static String git(String... arguments) throws Exception {
        List<String> command=new ArrayList<>(List.of("git")); command.addAll(Arrays.asList(arguments));
        var process=new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        if(!process.waitFor(5,TimeUnit.SECONDS)) { process.destroyForcibly(); return "unavailable"; }
        return process.exitValue()==0?new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8).trim():"unavailable";
    }
}
