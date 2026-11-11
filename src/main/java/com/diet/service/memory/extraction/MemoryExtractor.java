package com.diet.service.memory.extraction;

import java.util.List;

@FunctionalInterface
public interface MemoryExtractor {
    List<MemoryCandidate> extract(String message);
}
