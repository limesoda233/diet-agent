package com.diet.service.memory.extraction;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class DeepSeekMemoryExtractorTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test void keepsEveryFactIncludingOpenIngredientsAllergensAndTemporaryPreferences() throws Exception {
        String message = "我一直不吃刺芹，我对花生过敏，今天想吃清淡，家里有空气炸锅，以后做饭最多20分钟";
        List<MemoryCandidate> expected = List.of(
                candidate("AVOID_INGREDIENT", "刺芹", "SELF", "DISLIKE", "LONG_TERM", "我一直不吃刺芹"),
                candidate("ALLERGEN", "花生", "SELF", "NEUTRAL", "LONG_TERM", "我对花生过敏"),
                candidate("TASTE", "清淡", "SELF", "LIKE", "TEMPORARY", "今天想吃清淡"),
                candidate("EQUIPMENT", "空气炸锅", "SELF", "NEUTRAL", "LONG_TERM", "家里有空气炸锅"),
                candidate("MAX_MINUTES", "20", "SELF", "NEUTRAL", "LONG_TERM", "以后做饭最多20分钟"));
        assertThat(extractor(json.writeValueAsString(expected)).extract(message)).containsExactlyElementsOf(expected);
    }

    @Test void preservesOtherHypotheticalAndNegativeStanceWithoutPromotingThemToSelfFacts() throws Exception {
        List<MemoryCandidate> expected = List.of(
                candidate("CUISINE", "川菜", "OTHER", "LIKE", "LONG_TERM", "我妈一直喜欢川菜"),
                candidate("TASTE", "辣", "UNKNOWN", "NEUTRAL", "UNKNOWN", "假如我喜欢辣"),
                candidate("TASTE", "甜", "SELF", "DISLIKE", "LONG_TERM", "我一向不喜欢甜"));
        assertThat(extractor(json.writeValueAsString(expected)).extract("我妈一直喜欢川菜，假如我喜欢辣该吃什么？我一向不喜欢甜"))
                .containsExactlyElementsOf(expected);
    }

    @Test void retainsExplicitRemovalAndNormalizesLabelsOnly() {
        assertThat(extractor("""
                [{"type":" taste ","value":" 辣 ","subject":" self ","stance":"neutral",
                "scope":"long_term","operation":" remove ","evidence":"删除我喜欢辣的记忆"}]
                """).extract("删除我喜欢辣的记忆")).containsExactly(
                new MemoryCandidate("TASTE","辣","SELF","NEUTRAL","LONG_TERM","REMOVE","删除我喜欢辣的记忆"));
    }

    @ParameterizedTest @ValueSource(strings = {
            "{}", "null", "[] []", "```json\n[]\n```",
            "[{\"type\":\"TASTE\"}]",
            "[{\"type\":\"TASTE\",\"type\":\"CUISINE\"}]"
    }) void malformedResponsesFailInsteadOfPretendingThereAreNoFacts(String response) {
        assertThatThrownBy(() -> extractor(response).extract("我喜欢辣")).isInstanceOf(MemoryExtractionException.class);
    }

    @Test void rejectsInventedEvidenceValueAndLabels() throws Exception {
        for (MemoryCandidate invalid : List.of(
                candidate("TASTE","辣","SELF","LIKE","LONG_TERM","我喜欢辣"),
                candidate("TASTE","甜","SELF","LIKE","LONG_TERM","我喜欢清淡"),
                candidate("PERSONALITY","清淡","SELF","LIKE","LONG_TERM","我喜欢清淡"),
                candidate("TASTE","清淡","SELF","LIKE","ALWAYS","我喜欢清淡"))) {
            var extractor = extractor(json.writeValueAsString(List.of(invalid)));
            assertThatThrownBy(() -> extractor.extract("我喜欢清淡")).isInstanceOf(MemoryExtractionException.class);
        }
    }

    @Test void rejectsOversizedInputsAndOutputs() {
        assertThatThrownBy(() -> extractor("[]").extract("我".repeat(16001))).isInstanceOf(MemoryExtractionException.class);
        assertThatThrownBy(() -> extractor(" ".repeat(100001)).extract("我喜欢辣")).isInstanceOf(MemoryExtractionException.class);
    }

    @Test void emptyFactsAreValidButEmptyModelAndFailuresAreNot() {
        assertThat(extractor("[]").extract("你好")).isEmpty();
        assertThatThrownBy(() -> new DeepSeekMemoryExtractor((s,u) -> Mono.empty(),json,Duration.ofSeconds(1)).extract("你好"))
                .isInstanceOf(MemoryExtractionException.class);
        assertThatThrownBy(() -> new DeepSeekMemoryExtractor((s,u) -> Mono.error(new IllegalStateException("offline")),json,Duration.ofSeconds(1)).extract("你好"))
                .isInstanceOf(MemoryExtractionException.class);
    }

    @Test void modelTimeoutIsBounded() {
        var extractor = new DeepSeekMemoryExtractor((s,u) -> Mono.never(),json,Duration.ofMillis(30));
        assertThatThrownBy(() -> extractor.extract("我喜欢辣")).isInstanceOf(MemoryExtractionException.class);
    }

    @Test void obviousHypotheticalAndOtherEvidenceCannotBecomePersonalLongTermFactsEvenWhenModelMislabels() throws Exception {
        var hypothetical=candidate("TASTE","辣","SELF","LIKE","LONG_TERM","假如我喜欢辣");
        assertThat(extractor(json.writeValueAsString(List.of(hypothetical))).extract("假如我喜欢辣该怎么做"))
                .containsExactly(candidate("TASTE","辣","UNKNOWN","NEUTRAL","UNKNOWN","假如我喜欢辣"));
        var mother=candidate("TASTE","辣","SELF","LIKE","LONG_TERM","我妈一直喜欢辣");
        assertThat(extractor(json.writeValueAsString(List.of(mother))).extract("我妈一直喜欢辣"))
                .containsExactly(candidate("TASTE","辣","OTHER","LIKE","LONG_TERM","我妈一直喜欢辣"));
    }

    @Test void temporaryAndDeniedAllergyEvidenceCannotBecomeLongTermAssertions() throws Exception {
        var temporary=candidate("TASTE","辣","SELF","LIKE","LONG_TERM","今天我想吃辣");
        assertThat(extractor(json.writeValueAsString(List.of(temporary))).extract("今天我想吃辣"))
                .containsExactly(candidate("TASTE","辣","SELF","LIKE","TEMPORARY","今天我想吃辣"));
        var denied=candidate("ALLERGEN","花生","SELF","NEUTRAL","LONG_TERM","我不对花生过敏");
        assertThat(extractor(json.writeValueAsString(List.of(denied))).extract("我不对花生过敏"))
                .containsExactly(candidate("ALLERGEN","花生","UNKNOWN","NEUTRAL","UNKNOWN","我不对花生过敏"));
    }

    @Test void clauseGuardUsesLocalFactsInsteadOfGlobalSubjectOrNegation() throws Exception {
        String message="我妈今天喜欢川菜，我一直不喜欢辣";
        var own=candidate("TASTE","辣","SELF","DISLIKE","LONG_TERM","我一直不喜欢辣");
        assertThat(extractor(json.writeValueAsString(List.of(own))).extract(message)).containsExactly(own);
    }

    private DeepSeekMemoryExtractor extractor(String response) {
        return new DeepSeekMemoryExtractor((s,u) -> Mono.just(response),json,Duration.ofSeconds(1));
    }
    private MemoryCandidate candidate(String type,String value,String subject,String stance,String scope,String evidence) {
        return new MemoryCandidate(type,value,subject,stance,scope,"ADD",evidence);
    }
}
