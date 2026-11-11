package com.diet.service.memory.extraction;

import com.diet.service.jev.*;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class JevMemoryExtractorTest {
    private final MemoryCandidate taste = new MemoryCandidate("TASTE","辣","SELF","DISLIKE","LONG_TERM","ADD","我一直不喜欢辣");

    @Test void incompleteAndLowConfidenceAnswersKeepTheValidatedBaseline() {
        for (Map<String,JevAnswer> answers : List.of(
                Map.of("subject",choice("SELF",.99)),
                answers("SELF","LIKE","LONG_TERM",.1),
                answers("SELF","LIKE","LONG_TERM",Double.NaN),
                answers("SELF","INVALID","LONG_TERM",.99))) {
            assertThat(extractor(List.of(taste),(state,questions) -> response(answers)).extract("我一直不喜欢辣"))
                    .containsExactly(taste);
        }
    }

    @Test void failureOrTimeoutInOptionalJevKeepsTheBaseline() {
        assertThat(extractor(List.of(taste),(s,q) -> { throw new JevClientException("offline"); })
                .extract("我一直不喜欢辣")).containsExactly(taste);
        var slow = new JevMemoryExtractor(message -> List.of(taste),(s,q) -> {
            try { Thread.sleep(300); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return response(answers("SELF","LIKE","LONG_TERM",.99));
        },dictionary(),.8,Duration.ofMillis(20));
        assertThat(slow.extract("我一直不喜欢辣")).containsExactly(taste);
    }

    @Test void classifiesEachCandidateWithItsOwnEvidenceAndFullMessageForCoreference() {
        String message="我妈一直喜欢川菜，尤其喜欢辣，我一直不喜欢辣";
        var mother = new MemoryCandidate("CUISINE","川菜","OTHER","LIKE","LONG_TERM","ADD","我妈一直喜欢川菜");
        var referred = new MemoryCandidate("TASTE","辣","OTHER","LIKE","LONG_TERM","ADD","尤其喜欢辣");
        var states = new ArrayList<Map<?,?>>();
        var questionsSeen = new ArrayList<Map<String,Object>>();
        var result = extractor(List.of(mother,referred,taste),(state,questions) -> {
            states.add((Map<?,?>)state); questionsSeen.add(questions);
            boolean self=((Map<?,?>)state).get("evidence").equals(taste.evidence());
            return response(answers(self ? "SELF" : "OTHER",self ? "DISLIKE" : "LIKE","LONG_TERM",.99));
        }).extract(message);
        assertThat(result).containsExactly(mother,referred,taste);
        assertThat(states).hasSize(3).allSatisfy(s -> assertThat(s.get("message")).isEqualTo(message));
        assertThat(questionsSeen).allSatisfy(q -> {
            assertThat(q.keySet()).containsExactlyInAnyOrder("subject","stance","scope");
            q.values().forEach(v -> assertThat(((Map<?,?>)v).get("type")).isEqualTo("choice"));
        });
    }

    @Test void noneAndUnknownDemoteAmbiguousPreferencesWithoutGuessingADislike() {
        var result=extractor(List.of(taste),(s,q) -> response(answers("UNKNOWN","NONE","UNKNOWN",.99)))
                .extract("我一直不喜欢辣");
        assertThat(result).containsExactly(new MemoryCandidate("TASTE","辣","UNKNOWN","NEUTRAL","UNKNOWN","ADD",taste.evidence()));
    }

    @Test void openFactsAndExplicitRemovalsNeverBecomeDictionaryPreferenceQuestions() {
        var allergen=new MemoryCandidate("ALLERGEN","刺芹","SELF","NEUTRAL","LONG_TERM","ADD","我对刺芹过敏");
        var removal=new MemoryCandidate("TASTE","辣","SELF","NEUTRAL","LONG_TERM","REMOVE","删除辣的偏好");
        assertThat(extractor(List.of(allergen,removal),(s,q) -> { throw new AssertionError("Must use open extraction"); })
                .extract("我对刺芹过敏，删除辣的偏好")).containsExactly(allergen,removal);
    }

    @Test void invalidBaselineEvidenceIsRejectedBeforeJevCanAcceptIt() {
        assertThatThrownBy(() -> extractor(List.of(taste),(s,q) -> response(answers("SELF","LIKE","LONG_TERM",.99)))
                .extract("你好")).isInstanceOf(MemoryExtractionException.class);
    }

    private JevMemoryExtractor extractor(List<MemoryCandidate> baseline,JevClient client) {
        return new JevMemoryExtractor(message -> baseline,client,dictionary(),.8,Duration.ofSeconds(1));
    }
    private Map<String,Set<String>> dictionary() { return Map.of("TASTE",Set.of("辣"),"CUISINE",Set.of("川菜")); }
    private JevAnswer choice(String value,double confidence) { return new JevAnswer("choice",value,null,null,confidence,Map.of()); }
    private Map<String,JevAnswer> answers(String subject,String stance,String scope,double confidence) {
        return Map.of("subject",choice(subject,confidence),"stance",choice(stance,confidence),"scope",choice(scope,confidence));
    }
    private JevResponse response(Map<String,JevAnswer> answers) { return new JevResponse("test",answers,new JevUsage(1,1)); }
}
