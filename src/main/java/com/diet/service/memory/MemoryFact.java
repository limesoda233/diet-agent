package com.diet.service.memory;

/** Validated model proposal. This is not itself an instruction to persist. */
public record MemoryFact(String type, String value, String stance, String scope,
                         String operation, String evidence) { }
