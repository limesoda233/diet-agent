package com.diet.service.memory;

import java.util.List;

public record MemoryTurn(List<MemoryFact> facts,List<MemoryItem> items,List<String> notices,boolean requiresClarification) {
    public MemoryTurn { facts=List.copyOf(facts);items=List.copyOf(items);notices=List.copyOf(notices); }
    public boolean hasAllergy() {
        return facts.stream().anyMatch(f->"ALLERGEN".equals(f.type()) && "ADD".equals(f.operation()))
                || items.stream().anyMatch(i->"ALLERGEN".equals(i.type()) && !"DELETED".equals(i.status()));
    }
}
