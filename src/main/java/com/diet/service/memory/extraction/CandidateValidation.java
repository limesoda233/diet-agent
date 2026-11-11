package com.diet.service.memory.extraction;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

/** Validation shared by the baseline and optional classifier. It never writes personal facts. */
final class CandidateValidation {
    private static final Set<String> TYPES=Set.of("ALLERGEN","AVOID_INGREDIENT","TASTE","CUISINE","EQUIPMENT","MAX_MINUTES");
    private static final Pattern SUBJECT = Pattern.compile("我(?:妈妈|爸爸|妈|爸|朋友|家人|父母|妻子|丈夫|儿子|女儿)|妈妈|爸爸|朋友|家人|父母|她|他|本人|自己|我(?!们)");
    private static final Pattern TEMPORARY = Pattern.compile("今天|今晚|这顿|本次|这次|暂时");
    private static final Pattern LONG_TERM = Pattern.compile("一直|一向|平时|长期|以后|通常|每天");
    private CandidateValidation() { }

    static void input(String message) {
        if (message == null || message.isBlank() || message.length() > 16_000) throw invalid();
    }

    static MemoryCandidate validate(String message,MemoryCandidate c) {
        input(message);
        if (c == null) throw invalid();
        String type=label(c.type(),TYPES), subject=label(c.subject(),Set.of("SELF","OTHER","UNKNOWN"));
        String stance=label(c.stance(),Set.of("LIKE","DISLIKE","NEUTRAL"));
        String scope=label(c.scope(),Set.of("LONG_TERM","TEMPORARY","UNKNOWN"));
        String operation=label(c.operation(),Set.of("ADD","REMOVE"));
        String value=c.value() == null ? "" : c.value().trim(), evidence=c.evidence();
        if (value.isBlank() || value.length() > 128 || value.chars().anyMatch(Character::isISOControl)
                || evidence == null || evidence.isBlank() || evidence.length() > 2000 || !message.contains(evidence)
                || !evidence.contains(value)) throw invalid();
        if (type.equals("MAX_MINUTES") && (!value.matches("[1-9][0-9]{0,3}") || Integer.parseInt(value) > 1440)) throw invalid();
        // These narrow guards only reduce certainty. The model still handles linguistic meaning.
        String local=localClause(evidence,value);
        if (operation.equals("ADD")) {
            if (evidence.matches("(?s).*(?:如果|假如|假设|要是|比如).*")) { subject="UNKNOWN"; scope="UNKNOWN"; stance="NEUTRAL"; }
            else {
                Matcher subjects=SUBJECT.matcher(evidence.substring(0,evidence.indexOf(value)));
                String last=null;
                while (subjects.find()) last=subjects.group();
                if (last != null && !Set.of("我","本人","自己").contains(last)) subject="OTHER";
                if (lastPosition(TEMPORARY,local) > lastPosition(LONG_TERM,local)) scope="TEMPORARY";
                if (type.equals("ALLERGEN") && (local.matches("(?s).*(?:不对|没有对|并非对|不是对).*"+Pattern.quote(value)+".*过敏.*")
                        || local.matches("(?s).*"+Pattern.quote(value)+".*(?:不过敏|没有过敏|并不过敏).*"))) {
                    subject="UNKNOWN"; scope="UNKNOWN"; stance="NEUTRAL";
                }
            }
        }
        return new MemoryCandidate(type,value,subject,stance,scope,operation,evidence);
    }
    private static int lastPosition(Pattern pattern,String text) {
        Matcher matcher=pattern.matcher(text); int position=-1;
        while (matcher.find()) position=matcher.start();
        return position;
    }
    private static String localClause(String evidence,String value) {
        int at=evidence.indexOf(value),start=at,end=at+value.length();
        while (start > 0 && "，,。；;！!？?\n\r".indexOf(evidence.charAt(start-1)) < 0) start--;
        while (end < evidence.length() && "，,。；;！!？?\n\r".indexOf(evidence.charAt(end)) < 0) end++;
        return evidence.substring(start,end);
    }
    private static String label(String value,Set<String> allowed) {
        if (value == null) throw invalid();
        String normalized=value.trim().toUpperCase(Locale.ROOT);
        if (!allowed.contains(normalized)) throw invalid();
        return normalized;
    }
    private static MemoryExtractionException invalid() { return new MemoryExtractionException("记忆候选字段或原文证据无效"); }
}
