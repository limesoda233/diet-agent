package com.diet.evaluation.retrieval;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class RetrievalReportTest {
    @TempDir Path output;
    @Test
    void reportsEffectiveDenominatorsAndDoesNotMixNoMatchWithRecall() throws Exception {
        var meals=RetrievalMetricsTest.meals();
        var q=RetrievalMetricsTest.query(Map.of(1L,2,2L,0,3L,0),List.of(),List.of(),List.of());
        var empty=RetrievalMetricsTest.query(Map.of(1L,0,2L,0,3L,0),List.of(),List.of(),List.of());
        var rows=List.of(
            new RetrievalReport.Row(q,List.of(1L),List.of(1L),List.of(1.0),RetrievalMetrics.evaluate(q,meals,List.of(1L),List.of(1L),2),List.of(),List.of(List.of(1L)),List.of(2.0),false),
            new RetrievalReport.Row(empty,List.of(),List.of(),List.of(),RetrievalMetrics.evaluate(empty,meals,List.of(),List.of(),8),List.of(),List.of(List.of()),List.of(8.0),false));
        var summary=RetrievalReport.summarize(rows);
        assertThat(summary.relevantQueries()).isEqualTo(1);
        assertThat(summary.noMatchQueries()).isEqualTo(1);
        assertThat(summary.recall3()).isEqualTo(1.0);
        assertThat(summary.noMatchAccuracy()).isEqualTo(1.0);
        assertThat(summary.p95LatencyMs()).isEqualTo(8.0);
        RetrievalReport.write(output,Map.of("strategy","fixture","corpusSha256","abc"),rows);
        var json=new ObjectMapper().readTree(output.resolve("report.json").toFile());
        assertThat(json.path("annotationCounts").path("draft").asInt()).isEqualTo(2);
        assertThat(json.path("rows").get(1).path("score").path("recall3").isNull()).isTrue();
        assertThat(Files.readString(output.resolve("report.md"))).contains("draft","fixture","有效相关查询","fixture");
        assertThat(json.path("bySplit").has("dev")).isTrue();
        assertThat(json.path("overall").path("corpusLimitedQueries").asInt()).isEqualTo(1);
        assertThat(json.path("overall").path("shortReturnQueries").asInt()).isEqualTo(2);
    }
}
