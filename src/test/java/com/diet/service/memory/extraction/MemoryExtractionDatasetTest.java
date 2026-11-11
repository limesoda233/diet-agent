package com.diet.service.memory.extraction;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class MemoryExtractionDatasetTest {
    record Case(String id,String message,List<MemoryCandidate> expected) { }

    static List<Case> cases() throws Exception {
        try (var input=MemoryExtractionDatasetTest.class.getResourceAsStream("/evaluation/memory/dev-v1.json")) {
            assertThat(input).isNotNull();
            return Arrays.asList(new ObjectMapper().readValue(input,Case[].class));
        }
    }
    @Test void developmentEvidenceAndLabelsMeetTheProductionContract() throws Exception {
        Set<String> ids=new HashSet<>();
        List<Case> cases=cases();
        assertThat(cases).hasSizeGreaterThanOrEqualTo(14);
        for (Case item:cases) {
            assertThat(ids.add(item.id())).as("unique case %s",item.id()).isTrue();
            item.expected().forEach(candidate -> assertThat(CandidateValidation.validate(item.message(),candidate)).isEqualTo(candidate));
        }
        assertThat(cases.stream().flatMap(c -> c.expected().stream()).map(MemoryCandidate::type).distinct())
                .containsExactlyInAnyOrder("TASTE","CUISINE","ALLERGEN","AVOID_INGREDIENT","EQUIPMENT","MAX_MINUTES");
    }
}
