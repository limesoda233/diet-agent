package com.diet.service.memory.extraction;

import com.diet.service.jev.JevClient;
import com.diet.service.jev.JevProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.model.OpenAIChatModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Explicit opt-in only: the normal Maven test suite never calls a remote model. */
@EnabledIfSystemProperty(named="diet.memory.live-eval",matches="true")
class MemoryExtractionLiveEvaluationTest {
    @Test void evaluatesFrozenDevelopmentCasesWithoutChangingTheirLabels() throws Exception {
        String apiKey=System.getenv("DEEPSEEK_API_KEY");
        assertThat(apiKey).as("DEEPSEEK_API_KEY is required for explicit remote evaluation").isNotBlank();
        var model=OpenAIChatModel.builder().apiKey(apiKey)
                .baseUrl(environment("DEEPSEEK_BASE_URL","https://api.deepseek.com"))
                .modelName(environment("DEEPSEEK_LIGHT_MODEL","deepseek-chat")).build();
        var json=new ObjectMapper();
        var beans=new StaticListableBeanFactory();
        MemoryExtractor extractor=new MemoryExtractionConfiguration().memoryExtractor(model,json,
                beans.getBeanProvider(JevClient.class),beans.getBeanProvider(JevProperties.class),"12s",false,.85,"3s");
        var rows=new ArrayList<Map<String,Object>>();
        var latencies=new ArrayList<Long>();
        int totalExpected=0,matched=0,predictedEligible=0,dangerous=0,fieldCorrect=0,fieldTotal=0,failures=0;
        for (var item:MemoryExtractionDatasetTest.cases()) {
            long started=System.nanoTime();
            List<MemoryCandidate> actual=List.of();
            String error="";
            try { actual=extractor.extract(item.message()); }
            catch (RuntimeException failure) { error=failure.getClass().getSimpleName(); failures++; }
            long elapsed=(System.nanoTime()-started)/1_000_000;
            latencies.add(elapsed);
            totalExpected+=item.expected().size();
            for (var expected:item.expected()) {
                if (actual.stream().anyMatch(c -> semanticKey(c).equals(semanticKey(expected)))) matched++;
                var predicted=actual.stream().filter(c -> c.type().equals(expected.type()) && c.value().equals(expected.value())).findFirst();
                if (predicted.isPresent()) {
                    MemoryCandidate c=predicted.get(); fieldTotal+=4;
                    if (c.subject().equals(expected.subject())) fieldCorrect++;
                    if (c.stance().equals(expected.stance())) fieldCorrect++;
                    if (c.scope().equals(expected.scope())) fieldCorrect++;
                    if (c.operation().equals(expected.operation())) fieldCorrect++;
                }
            }
            for (var candidate:actual) if (eligible(candidate)) {
                predictedEligible++;
                if (item.expected().stream().noneMatch(e -> eligible(e) && e.type().equals(candidate.type())
                        && e.value().equals(candidate.value()) && e.stance().equals(candidate.stance()))) dangerous++;
            }
            rows.add(Map.of("id",item.id(),"message",item.message(),"expected",item.expected(),"actual",actual,"error",error,"elapsedMs",elapsed));
        }
        Collections.sort(latencies);
        var report=new LinkedHashMap<String,Object>();
        report.put("dataset","dev-v1"); report.put("mode","explicit-remote-deepseek"); report.put("jevEnabled",false);
        report.put("caseCount",rows.size()); report.put("failureCount",failures);
        report.put("expectedCandidateCount",totalExpected); report.put("matchedCandidateCount",matched);
        report.put("candidateRecall",ratio(matched,totalExpected)); report.put("eligiblePredictionCount",predictedEligible);
        report.put("dangerousWriteCount",dangerous); report.put("dangerousWriteRate",ratio(dangerous,predictedEligible));
        report.put("matchedFieldCount",fieldTotal); report.put("fieldAccuracy",ratio(fieldCorrect,fieldTotal));
        report.put("latencyP50Ms",percentile(latencies,.50)); report.put("latencyP95Ms",percentile(latencies,.95)); report.put("cases",rows);
        Path path=Path.of("target","memory-extraction-live-report.json");
        Files.createDirectories(path.toAbsolutePath().getParent());
        json.writerWithDefaultPrettyPrinter().writeValue(path.toFile(),report);
        assertThat(failures).as("Remote failures are separately reported in %s",path).isZero();
        assertThat(dangerous).as("Unsafe personal long-term assertions are reported in %s",path).isZero();
    }
    private static List<String> semanticKey(MemoryCandidate c) { return List.of(c.type(),c.value(),c.subject(),c.stance(),c.scope(),c.operation()); }
    private static boolean eligible(MemoryCandidate c) { return c.subject().equals("SELF") && c.scope().equals("LONG_TERM") && c.operation().equals("ADD"); }
    private static double ratio(int numerator,int denominator) { return denominator == 0 ? 0 : (double)numerator/denominator; }
    private static long percentile(List<Long> values,double p) { return values.get(Math.max(0,(int)Math.ceil(values.size()*p)-1)); }
    private static String environment(String name,String fallback) { String value=System.getenv(name); return value == null || value.isBlank() ? fallback : value; }
}
