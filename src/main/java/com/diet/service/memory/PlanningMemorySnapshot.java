package com.diet.service.memory;

import java.util.List;

/** Server-only snapshot; a single profile lock makes epoch and item versions consistent. */
public record PlanningMemorySnapshot(long epoch,List<MemoryItem> items) {
    public PlanningMemorySnapshot {items=List.copyOf(items);}
}
