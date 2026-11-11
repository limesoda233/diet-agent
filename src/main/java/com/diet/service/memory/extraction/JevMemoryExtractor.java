package com.diet.service.memory.extraction;

import com.diet.service.jev.*;
import java.time.Duration;
import java.util.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

public class JevMemoryExtractor implements MemoryExtractor {
    private final MemoryExtractor baseline;
    private final JevClient client;
    private final Map<String,Set<String>> dictionary;
    private final double threshold;
    private final Duration timeout;

    public JevMemoryExtractor(MemoryExtractor baseline,JevClient client,Map<String,Set<String>> dictionary,double threshold,Duration timeout) {
        if (!Double.isFinite(threshold) || threshold < 0 || threshold > 1 || timeout == null || timeout.isNegative() || timeout.isZero())
            throw new IllegalArgumentException("Invalid memory Jev settings");
        this.baseline=baseline; this.client=client; this.dictionary=Map.copyOf(dictionary); this.threshold=threshold; this.timeout=timeout;
    }
    @Override public List<MemoryCandidate> extract(String message) {
        CandidateValidation.input(message);
        List<MemoryCandidate> candidates=baseline.extract(message);
        if (candidates == null || candidates.size() > 32) throw new MemoryExtractionException("记忆基线未返回有效候选");
        List<MemoryCandidate> validated=candidates.stream().map(c -> CandidateValidation.validate(message,c)).toList();
        try {
            return Mono.fromCallable(() -> validated.stream().map(c -> classify(message,c)).toList())
                    .subscribeOn(Schedulers.boundedElastic()).timeout(timeout).block();
        } catch (RuntimeException error) {
            // Jev is optional. A successful validated baseline remains the result on classifier failures.
            return validated;
        }
    }

    private MemoryCandidate classify(String message,MemoryCandidate candidate) {
        if (!candidate.operation().equals("ADD") || !Set.of("TASTE","CUISINE").contains(candidate.type())
                || !dictionary.getOrDefault(candidate.type(),Set.of()).contains(candidate.value())) return candidate;
        try {
            var state=Map.of("message",message,"type",candidate.type(),"value",candidate.value(),"evidence",candidate.evidence());
            var questions=Map.<String,Object>of(
                    "subject",question("仅判断evidence中value所属主体。用完整message消解指代；不把他人当用户。",
                            Map.of("SELF","本人明确事实","OTHER","他人事实","UNKNOWN","主体不明、引用、假设或条件")),
                    "stance",question("仅判断evidence中对value的态度，不以否定词强行推断喜好；没有态度用NONE。",
                            Map.of("LIKE","明确喜欢","DISLIKE","明确不喜欢","NEUTRAL","明确中立","NONE","未表达偏好或仅是假设/提问")),
                    "scope",question("仅判断evidence中value的范围，结合message理解；一次请求不推断为长期偏好。",
                            Map.of("LONG_TERM","明确长期或通常偏好","TEMPORARY","今天、本次、暂时偏好","UNKNOWN","不明、假设或条件")));
            JevResponse response=client.systemOne(state,questions);
            String subject=answer(response,"subject",Set.of("SELF","OTHER","UNKNOWN"));
            String stance=answer(response,"stance",Set.of("LIKE","DISLIKE","NEUTRAL","NONE"));
            String scope=answer(response,"scope",Set.of("LONG_TERM","TEMPORARY","UNKNOWN"));
            if (stance.equals("NONE")) { subject="UNKNOWN"; stance="NEUTRAL"; scope="UNKNOWN"; }
            return CandidateValidation.validate(message,new MemoryCandidate(candidate.type(),candidate.value(),subject,stance,scope,
                    candidate.operation(),candidate.evidence()));
        } catch (RuntimeException error) { return candidate; }
    }
    private Map<String,Object> question(String instructions,Map<String,String> criteria) {
        return Map.of("type","choice","instructions",instructions,"criteria",criteria);
    }
    private String answer(JevResponse response,String id,Set<String> allowed) {
        JevAnswer answer=response == null || response.answers() == null ? null : response.answers().get(id);
        if (answer == null || !"choice".equals(answer.type()) || !allowed.contains(answer.choice()) || answer.confidence() == null
                || !Double.isFinite(answer.confidence()) || answer.confidence() < threshold || answer.confidence() > 1)
            throw new MemoryExtractionException("Jev记忆分类不完整或低置信度");
        return answer.choice();
    }
}
