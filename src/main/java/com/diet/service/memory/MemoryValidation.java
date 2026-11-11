package com.diet.service.memory;

import com.diet.service.memory.extraction.MemoryCandidate;
import java.util.*;

/** Final domain gate; no model can bypass these checks to write a memory. */
public final class MemoryValidation {
    private static final Set<String> TYPES=Set.of("ALLERGEN","AVOID_INGREDIENT","TASTE","CUISINE","EQUIPMENT","MAX_MINUTES");
    private MemoryValidation() { }
    public static void validateValue(String type,String value,String stance) {
        if(!TYPES.contains(type) || value==null || !value.equals(value.trim()) || value.isBlank() || value.length()>128
                || !value.matches("[\\p{L}\\p{N} ·_+/-]+") || !Set.of("LIKE","DISLIKE","NEUTRAL").contains(stance))
            throw new IllegalArgumentException("记忆类型、内容或态度无效");
        if("MAX_MINUTES".equals(type)) {
            try { int n=Integer.parseInt(value); if(n<1 || n>1440) throw new NumberFormatException(); }
            catch(NumberFormatException bad) { throw new IllegalArgumentException("烹饪时长必须为1到1440分钟"); }
        }
    }
    public static List<MemoryFact> facts(String message,List<MemoryCandidate> candidates) {
        if(candidates==null || candidates.size()>32) throw new IllegalArgumentException("提取结果无效");
        var result=new ArrayList<MemoryFact>();
        for(var c:candidates) {
            if(c==null) throw new IllegalArgumentException("空记忆候选");
            validateValue(c.type(),c.value(),c.stance());
            if(c.evidence()==null || c.evidence().isBlank() || c.evidence().length()>2000 || !message.contains(c.evidence())
                    || !Set.of("SELF","OTHER","UNKNOWN").contains(c.subject())
                    || !Set.of("LONG_TERM","TEMPORARY","UNKNOWN").contains(c.scope())
                    || !Set.of("ADD","REMOVE").contains(c.operation())) throw new IllegalArgumentException("记忆缺少有效原文、主体或范围");
            if(!"SELF".equals(c.subject()) || "UNKNOWN".equals(c.scope())) continue;
            // Hypothetical questions must not create assertions even if extraction mistakenly labels them SELF.
            if(c.evidence().matches("(?s).*(如果|假如|假设|要是|比如).*")) continue;
            result.add(new MemoryFact(c.type(),c.value(),c.stance(),c.scope(),c.operation(),c.evidence()));
        }
        return result.stream().distinct().toList();
    }
}
